package com.smartvision.gallery

import android.app.Application
import android.content.Context
import android.os.StrictMode
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.smartvision.gallery.cache.CacheCoordinator
import com.smartvision.gallery.data.ai.AiModelHub
import com.smartvision.gallery.data.ai.AiTagger
import com.smartvision.gallery.data.ai.DomainRouter
import com.smartvision.gallery.data.ai.DanbooruTagger
import com.smartvision.gallery.data.ai.MlKitFaceAnalyzer
import com.smartvision.gallery.data.ai.VisionClassifier
import com.smartvision.gallery.data.db.FlagBackupManager
import com.smartvision.gallery.data.db.SmartVisionDatabase
import com.smartvision.gallery.data.prefs.AiPreferences
import com.smartvision.gallery.data.repo.MediaRepository
import com.smartvision.gallery.decoder.MediaLoader
import com.smartvision.gallery.decoder.bridge.NativeBridge
import com.smartvision.gallery.privacy.VaultMigrator
import com.smartvision.gallery.scanner.AiTaggingWorker
import com.smartvision.gallery.scanner.MediaScanCoordinator
import com.smartvision.gallery.scanner.MediaStoreObserver
import com.smartvision.gallery.ui.components.AppImageLoaderFactory
import com.smartvision.gallery.ui.viewer.JxlFullResPrecacher
import com.smartvision.gallery.ui.viewer.AvifRawPrecacher
import com.smartvision.gallery.util.AppForegroundTracker
import com.smartvision.gallery.util.AppLog
import com.smartvision.gallery.util.AppPrefs
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration as OsmConfig
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * LiquidGallery root [Application].
 *
 * Responsibilities:
 *  1. Initialise the logger + native bridge as early as possible (cold-start budget is 1.5s).
 *  2. Lazily wire the singleton graph (db, repo, media loader, scan coordinator, cache).
 *  3. Trim background work when the process moves into the background.
 */
class SmartVisionApp : Application(), ImageLoaderFactory, Configuration.Provider {

    /**
     * Coil's [ImageLoaderFactory] hook. When [Application] implements this,
     * `AsyncImage` / `ZoomableAsyncImage` / `rememberAsyncImagePainter`
     * resolve to the loader returned here when there is no
     * `LocalImageLoader` in scope — e.g. inside the standalone
     * `PhotoViewerActivity`, which is NOT a child of `AppRoot`'s
     * `CompositionLocalProvider`.
     *
     * Why this matters: in the gallery grid the bug was invisible because
     * `AppRoot` exposes `LocalImageLoader provides imageLoader` and every
     * screen under [com.smartvision.gallery.ui.NavHost] inherits it. But
     * opening a photo/ GIF inside `PhotoViewerActivity` falls back to
     * Coil's default global loader, which has neither `VideoFrameDecoder`
     * (no video keyframes) nor `GifDecoder` (GIFs frozen on first frame).
     *
     * Funnelling the loader through [AppImageLoaderFactory] ensures the
     * SAME component set backs every image render in the app.
     */
    override fun newImageLoader(): ImageLoader = AppImageLoaderFactory.create(this)

    /** Application-scoped coroutine scope. Survives configuration changes, dies with the process. */
    val appScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    val prefs: AppPrefs by lazy { AppPrefs(this) }

    val database: SmartVisionDatabase by lazy {
        SmartVisionDatabase.create(this)
    }

    val flagBackupManager: FlagBackupManager by lazy {
        FlagBackupManager(this)
    }

    val mediaRepository: MediaRepository by lazy {
        MediaRepository(
            context = this,
            mediaDao = database.mediaDao(),
            mediaFlagDao = database.mediaFlagDao(),
            albumDao = database.albumDao(),
            flagBackup = flagBackupManager
        )
    }

    val cacheCoordinator: CacheCoordinator by lazy {
        CacheCoordinator(this)
    }

    val mediaLoader: MediaLoader by lazy {
        MediaLoader(this, cacheCoordinator)
    }

    val jxlFullResPrecacher: JxlFullResPrecacher by lazy {
        JxlFullResPrecacher(this)
    }

    val avifRawPrecacher: AvifRawPrecacher by lazy {
        AvifRawPrecacher(this)
    }

    val scanCoordinator: MediaScanCoordinator by lazy {
        MediaScanCoordinator(this, mediaRepository, prefs)
    }

    val mediaStoreObserver: MediaStoreObserver by lazy {
        MediaStoreObserver(this, scanCoordinator)
    }

    val aiService: com.smartvision.gallery.ai.AiService by lazy {
        com.smartvision.gallery.ai.HeuristicClassifier(this)
    }

    val aiModelHub: AiModelHub by lazy { AiModelHub.get(this) }
    val aiVisionClassifier: VisionClassifier by lazy { VisionClassifier(this) }
    val aiDomainRouter: DomainRouter by lazy { DomainRouter(this) }
    val aiDanbooruTagger: DanbooruTagger by lazy { DanbooruTagger(this) }
    val aiFaceAnalyzer: MlKitFaceAnalyzer by lazy { MlKitFaceAnalyzer(this) }
    val aiTagger: AiTagger by lazy { AiTagger(this) }
    val aiPreferences: AiPreferences by lazy { AiPreferences(this) }

    /**
     * Liquid Glass tuning repository. Persists per-spec sliders
     * (tab bar / static card / lens / backdrop) to DataStore. Read by
     * [com.smartvision.gallery.ui.glass.GlassConfigViewModel] and
     * consumed by every glass surface via [LocalGlassConfig].
     */
    val glassConfigRepository: com.smartvision.gallery.data.glass.GlassConfigRepository by lazy {
        com.smartvision.gallery.data.glass.GlassConfigRepository(this)
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Logging first, so any subsequent failure can be surfaced.
        android.util.Log.i(TAG, "onCreate() entered — pid=${android.os.Process.myPid()}")
        AppLog.install(this)
        android.util.Log.i(TAG, "AppLog installed")
        AppLog.i(TAG, "SmartVisionApp.onCreate() boot complete")

        // 1b. Global crash handler — logs to applog.txt + logcat before the
        // process dies. Installed in ALL builds (not just DEBUG) so release
        // crashes are diagnosable from applog.txt instead of being invisible.
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            try {
                AppLog.e(TAG, "CRASH on thread ${thread.name}", ex)
                android.util.Log.e(TAG, "CRASH: ${ex.message}", ex)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "CRASH handler failed", t)
                android.util.Log.e(TAG, "CRASH on thread ${thread.name}: ${ex.message}", ex)
            }
        }

        // 2. Bring up the native decoder surface. libsmartvision_decoder.so resolves all
        //    AVIF/JXL symbol dependencies internally.
        try {
            NativeBridge.init(this)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Native bridge failed to initialise", t)
        }

        // 3. osmdroid global config — must be set before any MapView is created.
        OsmConfig.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        // 2b. MLKit pre-warm: 在 main thread 预热 MLKit labeler/face detector 内部
        // PipelineManager, 触发其 Lifecycle.addObserver 注册在 main thread 而非
        // worker 线程 (避免 AiTaggingWorker 调用时 "Method addObserver must be
        // called on the main thread" IllegalStateException). 关键是 process() 调用
        // 真正在主 looper 触发 model load.
        try {
            android.os.Handler(mainLooper).post {
                runCatching {
                    val opts = com.google.mlkit.vision.label.defaults.ImageLabelerOptions.Builder()
                        .setConfidenceThreshold(0.55f).build()
                    val labeler = com.google.mlkit.vision.label.ImageLabeling.getClient(opts)
                    val faceOpts = com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                        .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .build()
                    val detector = com.google.mlkit.vision.face.FaceDetection.getClient(faceOpts)
                    // 触发 PipelineManager.start 一次 (1x1 transparent stub), 要 PipelineManager 内部 addObserver 在主 thread 跑.
                    val stub = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    stub.eraseColor(android.graphics.Color.WHITE)
                    val img = com.google.mlkit.vision.common.InputImage.fromBitmap(stub, 0)
                    labeler.process(img)
                        .addOnFailureListener { e -> android.util.Log.w(TAG, "MLKit labeler prewarm fail: $e") }
                        .addOnCompleteListener { stub.recycle() }
                    detector.process(img)
                        .addOnFailureListener { e -> android.util.Log.w(TAG, "MLKit detector prewarm fail: $e") }
                    android.util.Log.i(TAG, "MLKit prewarm dispatched on main")
                }.onFailure { android.util.Log.w(TAG, "MLKit prewarm outer fail", it) }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "MLKit prewarm handler post failed", t)
        }

        // 5. Background-mode tracking → trim cache and pause scans.
        // v48: 顺带维护 AppForegroundTracker 标志位 — AiTaggingWorker 读它
        // 决定是否暂停推理。这个 observer 已实测可靠触发 (冷启动 onStart 必到),
        // 标志位语义确定性, 规避 worker 线程读 currentState 的派发竞态.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                AppForegroundTracker.markBackground()
                AppLog.i(TAG, "ProcessLifecycleOwner.onStop → tracker=false")
                cacheCoordinator.onAppBackground()
                // 退后台 → 重启 AI 标签 (前台暂停的续跑)
                scanCoordinator.scheduleIncrementalScan()
            }
            override fun onStart(owner: LifecycleOwner) {
                AppForegroundTracker.markForeground()
                AppLog.i(TAG, "ProcessLifecycleOwner.onStart → tracker=true")
                cacheCoordinator.onAppForeground()
                // v46: 用户在前台 → 取消 AI worker, 避免推理抢 CPU 卡 UI + 发热.
                // 退后台由 onStop 重新排程. (worker re-enqueue 链条会重排程,
                // 真正的暂停靠 AiTaggingWorker 前台门 + v48 批内检查兜底.)
                try {
                    androidx.work.WorkManager.getInstance(this@SmartVisionApp)
                        .cancelUniqueWork(com.smartvision.gallery.scanner.AiTaggingWorker.WORK_NAME)
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "cancel AI tagging on foreground failed", t)
                }
            }
        })

        // 6. Background incremental scan. Non-blocking.
        android.util.Log.i(TAG, "About to launch scanCoordinator.scheduleIncrementalScan()")
        appScope.launch {
            try {
                android.util.Log.i(TAG, "appScope.launch entered, calling scheduleIncrementalScan()")
                scanCoordinator.scheduleIncrementalScan()
                android.util.Log.i(TAG, "scheduleIncrementalScan() returned normally")
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "scheduleIncrementalScan() threw", t)
                AppLog.e(TAG, "scheduleIncrementalScan() threw", t)
            }
        }
        android.util.Log.i(TAG, "After appScope.launch for scan")

        // 7a. Subscribe to MediaStore changes — fire incremental scan whenever
        mediaStoreObserver.register()

        // 7b. Restore flags from SharedPreferences backup (safety net).
        appScope.launch {
            try {
                val restored = flagBackupManager.restoreIfMissing(database.mediaFlagDao())
                if (restored > 0) {
                    AppLog.i(TAG, "Restored $restored flags from backup")
                }
                AppLog.i(TAG, flagBackupManager.getBackupStats())
            } catch (t: Throwable) {
                AppLog.e(TAG, "Flag restore from backup failed", t)
            }
        }

        // 7c. Migrate legacy vault rows from uri-hash naming to SecureRandom vaultId.
        appScope.launch {
            try {
                val vaultDir = File(cacheDir, ".vault").apply { mkdirs() }
                VaultMigrator(this@SmartVisionApp, vaultDir, mediaRepository)
                    .migrateIfNeeded()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Vault migration aborted", t)
            }
        }

        // 7d. GC old decoded vault plaintexts (>7 days).
        appScope.launch(Dispatchers.IO) {
            val decodedDir = File(cacheDir, "vault-decoded")
            decodedDir.listFiles()?.forEach { file ->
                val ageMs = System.currentTimeMillis() - file.lastModified()
                if (ageMs > TimeUnit.DAYS.toMillis(7)) {
                    file.delete()
                    AppLog.i(TAG, "Cleaned up old decoded file: ${file.name}")
                }
            }
        }

        // 5. Install the heuristic AI service. Swap with TFLite-backed implementation
        //    when a packaged model is available.
        com.smartvision.gallery.ai.AiServiceLocator.set(aiService)

        if (BuildConfig.DEBUG) {
            installStrictMode()
        }
    }

    /** WorkManager Configuration — provides our [WorkerFactory] so [AiTaggingWorker]
     *  can receive its repository + DAO dependencies at construction time. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .setWorkerFactory(aiWorkerFactory)
            .build()

    private val aiWorkerFactory: WorkerFactory
        get() = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? {
                return when (workerClassName) {
                    AiTaggingWorker::class.java.name ->
                        AiTaggingWorker(
                            appContext,
                            workerParameters,
                            mediaRepository,
                            database.mediaFlagDao(),
                            aiPreferences,
                        )
                    else -> null
                }
            }
        }

    private fun installStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
    }

    companion object {
        private const val TAG = "SmartVisionApp"
        fun from(context: Context): SmartVisionApp =
            context.applicationContext as SmartVisionApp
    }
}