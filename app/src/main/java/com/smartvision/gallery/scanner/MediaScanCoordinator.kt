package com.smartvision.gallery.scanner

import android.content.Context
import android.net.Uri
import com.smartvision.gallery.data.repo.MediaRepository
import com.smartvision.gallery.util.AppLog
import com.smartvision.gallery.util.AppPrefs
import com.smartvision.gallery.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Top-level scan orchestrator. Decides between:
 *  * Cold full scan (first launch).
 *  * Incremental scan (subsequent launches).
 *  * Forced scan via [requestFullScan].
 */
class MediaScanCoordinator(
    private val context: Context,
    private val repository: MediaRepository,
    private val prefs: AppPrefs? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    /**
     * Schedule an incremental scan — debounced to one per app launch.
     *
     * Uses the [AppPrefs.lastScanTimeMs] watermark to do a delta query when possible.
     * The full `queryAll()` path is reserved for first launch (watermark == 0) and is
     * otherwise bypassed because re-querying 8000+ rows + parallel EXIF reading takes
     * minutes, which is what made auto-refresh feel broken from a user perspective.
     */
    fun scheduleIncrementalScan() {
        android.util.Log.i(TAG, "scheduleIncrementalScan() called, activeJob?=${activeJob?.isActive}")
        if (activeJob?.isActive == true) return
        // Gate on storage permission BEFORE querying. On first launch the scan
        // fires from SmartVisionApp.onCreate before the permission dialog is
        // answered — without this check queryAll() returns 0 items and we'd
        // still write the watermark, poisoning every future delta scan (delta
        // only finds items newer than the watermark → forever empty gallery
        // → TimelinePage spins forever). PermissionViewModel re-kicks the
        // scan on the Denied→Granted transition.
        if (!PermissionHelper.hasStoragePermission(context)) {
            AppLog.w(TAG, "scheduleIncrementalScan: storage permission not granted — deferring (no watermark write)")
            return
        }
        android.util.Log.i(TAG, "scheduleIncrementalScan() launching new activeJob")
        activeJob = scope.launch {
            android.util.Log.i(TAG, "MediaScanCoordinator scope.launch entered")
            // Give the UI a moment to settle before we touch MediaStore.
            delay(SCAN_DEBOUNCE_MS)
            val startMs = System.currentTimeMillis()
            AppLog.i(TAG, "Starting incremental scan")
            val flagCountBefore = repository.countFlags()
            AppLog.i(TAG, "Flags before scan: $flagCountBefore")

            val source = MediaStoreDataSource(context)
            val sinceMs = prefs?.getLastScanTimeMs() ?: 0L
            // Empty DB forces a FULL scan regardless of watermark. A poisoned
            // watermark (written by a 0-item scan from before the permission
            // gate, or a stale SharedPreferences from an older build) would
            // otherwise make the delta query return 0 forever → gallery stuck
            // empty with a permanent spinner. Delta is only meaningful when
            // the DB already has rows to increment.
            val items = if (sinceMs > 0L && flagCountBefore > 0) {
                AppLog.i(TAG, "Delta scan since=$sinceMs")
                source.queryChangedSince(sinceMs)
            } else {
                AppLog.i(TAG, "Full scan (no watermark or empty DB)")
                source.queryAll()
            }
            if (items.isEmpty()) {
                AppLog.i(TAG, "No new/changed items — skipping DB upsert")
            } else {
                AppLog.i(TAG, "Upserting ${items.size} items")
                repository.upsertAll(items)
            }
            val flagCountAfter = repository.countFlags()
            AppLog.i(TAG, "Flags after scan: $flagCountAfter (delta=${flagCountAfter - flagCountBefore})")
            // Watermark 必须用 query 完成时间而非 scan 启动时间: 若用 startMs,
            // 扫描耗时期间新增/修改的文件 DATE_MODIFIED > sinceSec 会被下一个
            // delta 查询跳过 (strict >), 该文件永远不进 DB 直到手动全量刷新.
            // +1000ms 缓冲: DATE_MODIFIED 只有秒级精度, 严格 `>` 会跳过与
            // watermark 同一秒内新增的文件. 加一秒永不漏, 代价是偶尔重复
            // UPSERT 几个文件 (幂等无害).
            val queryDoneAtMs = System.currentTimeMillis() + WATERMARK_GRACE_MS
            AppLog.w(TAG, "Scan started at $startMs, query done ~$queryDoneAtMs, watermark=$queryDoneAtMs")
            prefs?.setLastScanTimeMs(queryDoneAtMs)
            AppLog.i(TAG, "Incremental scan done in ${System.currentTimeMillis() - startMs} ms, ${items.size} items")

            // Hand off to the AI tagging worker. Even when no new items were
            // discovered this run, the worker can still find pending AI work from
            // a previous AI_VERSION bump (KEEP policy dedupes a redundant enqueue).
            val aiEnabled = repository.isAiEnabled()
            android.util.Log.i(TAG, "After scan: flagCountAfter=$flagCountAfter aiEnabled=$aiEnabled")
            if (flagCountAfter > 0 && aiEnabled) {
                android.util.Log.i(TAG, "Calling enqueueAiTagging()")
                enqueueAiTagging()
            }
            // 异步后台读 EXIF GPS — 不阻塞用户. 仅在本次 scan 实际写入了新行
            // 时触发 (delta=0 跳过避免反复空跑).
            if (flagCountAfter > 0 && items.isNotEmpty()) {
                android.util.Log.i(TAG, "Scheduling geo refill")
                requestGeoRefill()
            }
        }
    }

    private fun enqueueAiTagging() {
        android.util.Log.i(TAG, "enqueueAiTagging() entered")
        try {
            val ctx = context.applicationContext
            val req = androidx.work.OneTimeWorkRequestBuilder<AiTaggingWorker>()
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    30, java.util.concurrent.TimeUnit.SECONDS
                )
                .build()
            // v46: KEEP 替代 REPLACE — 广播不中断已在跑的 worker, 避免重启烧 CPU.
            androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
                AiTaggingWorker.WORK_NAME,
                androidx.work.ExistingWorkPolicy.KEEP,
                req
            )
            android.util.Log.i(TAG, "enqueueAiTagging OK")
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "enqueueAiTagging failed", t)
            AppLog.w(TAG, "enqueueAiTagging failed", t)
        }
    }

    fun requestFullScan() {
        activeJob?.cancel()
        activeJob = scope.launch {
            AppLog.i(TAG, "Starting full scan")
            val source = MediaStoreDataSource(context)
            val items = source.queryAll()
            repository.replaceAll(items)
            prefs?.setLastScanTimeMs(System.currentTimeMillis())
            AppLog.i(TAG, "Full scan done: ${items.size} items")
        }
    }

    /**
     * Hook for MediaStore change broadcasts — refresh only what changed.
     *
     * Before this fix, every MediaStore notification kicked off a full queryAll() →
     * 8000-row EXIF storm → minutes of unresponsiveness. Now we re-check the
     * watermark and run the much smaller delta path.
     */
    fun onMediaChanged() {
        scheduleIncrementalScan()
    }

    /**
     * Refill GPS coordinates for rows whose `latitude IS NULL`.
     *
     * Why this exists: when ACCESS_MEDIA_LOCATION was not granted at first scan,
     * `readGeoFromExif` returned null for every photo (MediaStore redacts GPS unless
     * setRequireOriginal is used). After the user grants the permission, the DB is
     * full of rows with latitude=null/longitude=null. Running a full `queryAll()`
     * just to re-read EXIF would take minutes on 8000+ photos.
     *
     * This pass queries the DB for the URIs that need refill, reads EXIF GPS (now
     * with setRequireOriginal working), and updates each row individually. Bounded
     * to a single launch cycle by `GEO_REFILL_BATCH`; multi-batch would require
     * a Worker.
     */
    fun requestGeoRefill() {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            val startMs = System.currentTimeMillis()
            AppLog.i(TAG, "Starting geo refill")
            val source = MediaStoreDataSource(context)
            val missing = repository.findUrisMissingLocation(limit = GEO_REFILL_BATCH)
            if (missing.isEmpty()) {
                AppLog.i(TAG, "No rows need geo refill")
                return@launch
            }
            AppLog.i(TAG, "Geo refill: ${missing.size} rows")

            val semaphore = Semaphore(permits = 4)
            coroutineScope {
                missing.map { uriStr ->
                    async {
                        semaphore.withPermit {
                            try {
                                val (lat, lng) = source.readGeoFromExif(Uri.parse(uriStr))
                                if (lat != null && lng != null) {
                                    repository.updateGeo(uriStr, lat, lng)
                                }
                            } catch (t: Throwable) {
                                AppLog.w(TAG, "Geo refill failed for $uriStr", t)
                            }
                        }
                    }
                }.awaitAll()
            }
            AppLog.i(TAG, "Geo refill done in ${System.currentTimeMillis() - startMs} ms")
        }
    }

    companion object {
        private const val TAG = "MediaScanCoordinator"
        private const val SCAN_DEBOUNCE_MS = 1500L
        private const val GEO_REFILL_BATCH = 500
        // DATE_MODIFIED 秒级精度缓冲, 防止 strict > 跳过同一秒内新增文件
        private const val WATERMARK_GRACE_MS = 1000L
    }
}