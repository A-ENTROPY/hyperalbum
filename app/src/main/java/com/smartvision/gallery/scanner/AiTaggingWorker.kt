package com.smartvision.gallery.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.smartvision.gallery.data.ai.AiModelHub
import com.smartvision.gallery.data.ai.AiTagger
import com.smartvision.gallery.data.ai.OrtSessionPool
import com.smartvision.gallery.data.db.MediaFlagDao
import com.smartvision.gallery.data.prefs.AiPreferences
import com.smartvision.gallery.data.repo.MediaRepository
import com.smartvision.gallery.util.AppForegroundTracker
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

/**
 * 后台 AI 标签批 worker.
 *
 * 性能优化 (相对早期串行实现):
 *  - 串行 for-loop 改为 [PARALLELISM] 路 async + Semaphore 节流,
 *    IO decode / TFLite inference / DB update 并行;
 *  - decode 阶段使用 [BitmapFactory.Options.inSampleSize] 直接缩到
 *    ~224 短边附近, 跳过全尺寸 ARGB_8888 (4K JPEG ≈24MB) 内存峰值;
 *  - 三模型共享同一张 224 短边 Bitmap, 各自再缩到模型期望尺寸;
 *  - DB 进度用 AtomicLong 累加, 避免协程间同步 lock.
 */
class AiTaggingWorker(
    appContext: Context,
    params: WorkerParameters,
    private val repository: MediaRepository,
    private val flagDao: MediaFlagDao,
    private val aiPrefs: AiPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            doWorkInternal()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            AppLog.w(TAG, "doWork cancelled by WM — re-enqueueing self")
            enqueueSelf()
            throw ce
        }
    }

    private suspend fun doWorkInternal(): Result = withContext(Dispatchers.IO) {
        AppLog.i(TAG, "doWork() entered, foreground=${AppForegroundTracker.isForeground}")
        if (!repository.isAiEnabled()) {
            AppLog.i(TAG, "AI disabled, skipping")
            return@withContext Result.success()
        }
        // v46: 从 AiPreferences 读取用户配置，替代硬编码常量
        val configBatchSize = aiPrefs.batchSize.first()
        val configCooldownMs = aiPrefs.cooldownMs.first()
        val configFgEnabled = aiPrefs.foregroundAiEnabled.first()
        AppLog.i(TAG, "config: batchSize=$configBatchSize cooldown=${configCooldownMs}ms fgEnabled=$configFgEnabled")
        // v47: 前台暂停 — 用户在查看相册/大图时绝不跑推理, CPU 全让给
        // viewer (JXL/AVIF 解码 + Telephoto 渲染). 用户开图慢/手机烫的根因
        // 是 AI worker 在应用前台仍连续跑满批 (25 张×2 并发, 每张 ~2.3s).
        // 后台化后才继续; 太久没后台则让 WM 重新调度, 保持链不断.
        // v46+ 用户可设置 foregroundAiEnabled=true 允许前台运行（牺牲流畅度换取速度）
        if (!configFgEnabled && isAppForeground()) {
            AppLog.i(TAG, "App in foreground — pausing batch until backgrounded")
            val waited = pauseWhileForeground()
            if (waited == null) {
                AppLog.i(TAG, "Still foreground after ${FOREGROUND_PAUSE_CAP_MS}ms — re-enqueueing")
                enqueueSelf()
                return@withContext Result.success()
            }
            AppLog.i(TAG, "App backgrounded after ${waited}ms — resuming batch")
        }
        val ctx = applicationContext
        val hub = AiModelHub.get(ctx)
        AppLog.i(TAG, "hub.isAvailable=${hub.isAvailable} mobilenet=${hub.isMobilenetAvailable} mobileclip=${hub.isMobileclipAvailable} danbooru=${hub.isDanbooruAvailable}")
        if (!hub.isAvailable) {
            AppLog.w(TAG, "Models unavailable, skipping batch")
            return@withContext Result.success()
        }
        val tagger = AiTagger(ctx)
        // v32: 预热 OrtSessionPool — 启动 N 个 session + 一次 stub 推理,
        // 避免第一批首张等 1s 阻塞 + 让 ONNX runtime 完成 shape inference.
        // 必须在 pending 查询前调用, 让后续 processOne 借 session 都是热路径.
        tagger.warmup()
        // v34: 单条 SQL 批量给短边<100px 的小图标打 fallback.
        // 之前每批 ~150/800 张是 56x56/96x96 之类系统图标, 浪费 8 路 decode
        // 槽位 + 触发 BitmapFactory 解码 + 触发 isProcessableImage 检查.
        // 一次 UPDATE 把它们都标 ai_version=33, 后续 findPendingAi 直接跳过.
        flagDao.bulkFallbackSmallImages(AiTagger.AI_VERSION, System.currentTimeMillis())
        // 循环处理直到 pending 清空或达到每批上限. 避免 BATCH_LIMIT=200 处理完就
        // 退出导致 7332 张永远只打 第一批. WorkManager 单 worker 10min timeout,
        // 故单次最多跑 BATCH_LIMIT 张, 剩余时返回 retry 让 WM 继续调起下一批.
        val pending = flagDao.findPendingAi(AiTagger.AI_VERSION, limit = configBatchSize)
        if (pending.isEmpty()) {
            AppLog.i(TAG, "No pending photos, AI tagging complete")
            aiPrefs.setProgress(0L, 0L)
            return@withContext Result.success()
        }
        val remaining = flagDao.countPendingAi(AiTagger.AI_VERSION) - pending.size
        AppLog.i(TAG, "Processing ${pending.size} photos (parallelism=$PARALLELISM, remaining after this batch=$remaining)")
        val total = pending.size.toLong()
        val processed = AtomicLong(0L)
        aiPrefs.setProgress(0L, total)

        val decodeSemaphore = Semaphore(DECODE_PARALLELISM)
        // v32: N 路 OrtSession 并发推理. OrtSessionPool 持有 N 个独立 session,
        // N 由 device memoryClass 决定 (≥1024MB→4, 512-1024MB→2, <512MB→1).
        // 单 session 推理 ~180ms INT8, N=4 并发 → 吞吐 ~6 张/秒 vs 之前 ~1.3 张/秒.
        // inflightSemaphore 限制同时在推理的协程数, decode 仍并行 (8 路).
        val poolCap = OrtSessionPool.capacity().coerceAtLeast(1)
        AppLog.i(TAG, "OrtSessionPool capacity=$poolCap (memClass-based or TotalRam-based)")
        val inflightSemaphore = Semaphore(poolCap)

        // v48: 批内前台中止信号. 批次后台启动后用户切前台, 余下照片保持
        // pending (不标已完成), 批收尾后 re-enqueue 等后台续跑.
        val foregroundBail = AtomicBoolean(false)
        coroutineScope {
            pending.map { flag ->
                async(Dispatchers.IO) {
                    decodeSemaphore.withPermit {
                        // v48: 拿到 slot 后查前台 — 用户开图/浏览期间绝不推理.
                        // v47 只在批次起点查一次: 批次后台启动后用户开图, 推理仍
                        // 与 viewer 解码抢 CPU (模型加载 2.25s + 25 张×2 并发).
                        // 前台则跳过本张 (保 pending), 让批尽快收尾 re-enqueue.
                        // 如果 foregroundAiEnabled=true 则允许前台运行.
                        if (!configFgEnabled && isAppForeground()) {
                            AppLog.i(TAG, "batch foreground gate tripped at #${processed.get()}/${pending.size}")
                            foregroundBail.set(true)
                            return@withPermit
                        }
                        // v35: withTimeout 兜底 — 单张 decode + inference 超过 60s
                        // 就 abort 释放 permit 槽, 防止部分坏图或模型 hang 永久堵死
                        // 8 路 decode + 4 路 inference.
                        try {
                            withTimeout(PROCESS_TIMEOUT_MS) {
                                processOne(ctx, tagger, flag, inflightSemaphore)
                            }
                            processed.incrementAndGet()
                            val done = processed.get()
                            if (done % 10L == 0L) aiPrefs.setProgress(done, total)
                        } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
                            AppLog.w(TAG, "processOne timeout for ${flag.uri}")
                            writeFallbackFields(flag.uri, "其他")
                        }
                    }
                }
            }.awaitAll()
        }
        aiPrefs.setProgress(processed.get(), total)
        // v48: 前台中止 → 立即 re-enqueue, 让 WM 在后台调度下一批继续.
        // 不能当"本批完成" — 剩余照片仍 pending.
        if (foregroundBail.get()) {
            AppLog.i(TAG, "Foreground during batch — bailed ${pending.size - processed.get()} photos, re-enqueueing")
            enqueueSelf()
            return@withContext Result.success()
        }
        // v34: 用 Result.success() + 立即 re-enqueue 同名 worker 替换 retry().
        // retry() 触发 EXPONENTIAL backoff (10s→20s→40s→80s→160s→320s→640s+),
        // 跑 5 批后每次间隔超过 5 分钟, 实际总吞吐减半. 直接 success() 让 WM 释放
        // 当前 job 槽位, 我们立即 enqueue 一个新 job — WM 会在 JobScheduler
        // 可用时 (通常 < 1s) 调度下一批.
        val stillPending = flagDao.countPendingAi(AiTagger.AI_VERSION)
        if (stillPending > 0) {
            AppLog.i(TAG, "Batch done, $stillPending pending remaining, cooling ${configCooldownMs / 1000}s")
            // v46: 批间冷却窗口 — 让 SoC 散热再跑下一批. 宁可慢, 不要持续满载.
            kotlinx.coroutines.delay(configCooldownMs)
            // v47: 批间同样尊重前台 — 用户切回应用就暂停, 后台才继续.
            // 如果 foregroundAiEnabled=true 则跳过前台检查.
            if (!configFgEnabled && isAppForeground()) {
                AppLog.i(TAG, "App foreground during cooldown — pausing")
                if (pauseWhileForeground() == null) {
                    AppLog.i(TAG, "Still foreground after pause cap — re-enqueueing")
                    enqueueSelf()
                    return@withContext Result.success()
                }
            }
            enqueueSelf()
            Result.success()
        } else {
            AppLog.i(TAG, "All AI tagging done")
            Result.success()
        }
    }

    private fun isAppForeground(): Boolean = AppForegroundTracker.isForeground

    /**
     * v47: 轮询等待应用进入后台. 返回等待毫秒数; 前台持续超过
     * [FOREGROUND_PAUSE_CAP_MS] 返回 null — 调用方 re-enqueue 让 WM
     * 稍后重试, 避免长时间占住 worker 槽位.
     */
    private suspend fun pauseWhileForeground(): Long? {
        var waited = 0L
        while (isAppForeground()) {
            if (waited >= FOREGROUND_PAUSE_CAP_MS) return null
            kotlinx.coroutines.delay(FOREGROUND_PAUSE_INTERVAL_MS)
            waited += FOREGROUND_PAUSE_INTERVAL_MS
        }
        return waited
    }

    /**
     * v34: WM 可能在 doWork() 进行中通过 JobScheduler.onStopJob 取消 (电池约束 /
     * 系统压力 / 应用进入 standby bucket). 此时 withContext 抛 CancellationException,
     * 我们的 doWork 提早退出, enqueueSelf 不会被调用, 链断. 这里在 finally 兜底
     * 再 enqueue 一次, 让下一批能继续 — CoroutineWorker.onStopped() 是 final
     * 不能 override, 只能在 doWork 的 finally 里挂.
     */

    private fun enqueueSelf() {
        try {
            val ctx = applicationContext
            // 去掉 setRequiresBatteryNotLow 约束 — v34 实测该约束导致 WM 在电池
            // "临界" 区间频繁 onStopJob + onStartJob, 1-2 分钟打断 worker, 链断.
            // AI 推理本身功耗可控, 用户主动跑批量时不需要电池保护.
            val req = androidx.work.OneTimeWorkRequestBuilder<AiTaggingWorker>()
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    30, java.util.concurrent.TimeUnit.SECONDS
                )
                .build()
            // APPEND policy: 当前 work 还在 RUNNING 状态, APPEND 把新请求
            // 链入队列尾部, 当前 doWork() 返回 Result.success() 后 WM 自动启动下一批.
            // 不能用 KEEP — KEEP 在 RUNNING 时直接丢弃新请求.
            // 也不能用 REPLACE — REPLACE 会立即取消当前 work, 中断在飞的 800 张.
            androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME,
                androidx.work.ExistingWorkPolicy.APPEND,
                req
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "re-enqueue self failed", t)
        }
    }

    private suspend fun processOne(
        ctx: Context,
        tagger: AiTagger,
        flag: com.smartvision.gallery.data.db.MediaFlagEntity,
        inflightSemaphore: Semaphore,
    ) {
        var bitmap: Bitmap? = null
        try {
            val uri = Uri.parse(flag.uri)
            val mimeType = ctx.contentResolver.getType(uri) ?: ""
            bitmap = decodeScaledBitmap(ctx, uri, mimeType, AiTagger.INFERENCE_SHORT_EDGE)
            if (bitmap == null) {
                // 解码失败 → fallback (aiVersion=AI_VERSION, subDomain="其他"),
                // 否则 aiVersion 永远 0 → 不进任何 AI 分类栏.
                writeFallbackFields(flag.uri, "其他")
                return
            }
            // v34: 图像质量过滤 — 超低分辨率图标/纯色图直接标 "其他",
            // 不喂给 ML 模型 (避免小图标让 Danbooru 抛异常 / 纯色图让
            // Places365 误判为"文档"或"室内").
            if (!isProcessableImage(bitmap)) {
                AppLog.i(TAG, "low-quality skip → 其他: ${flag.uri} ${bitmap.width}x${bitmap.height}")
                writeFallbackFields(flag.uri, "其他")
                return
            }
            inflightSemaphore.withPermit {
                val ai = tagger.tag(bitmap)
                if (ai == null) {
                    writeFallbackFields(flag.uri, "其他")
                    return@withPermit
                }
                flagDao.updateAiFields(
                    uri = flag.uri,
                    domain = ai.domain,
                    subDomain = ai.subDomain,
                    copyright = ai.copyright,
                    faceCount = ai.faceCount,
                    faceArea = ai.faceArea,
                    score = ai.score,
                    version = ai.version,
                    taggedAt = System.currentTimeMillis(),
                    danbooruTags = ai.danbooruTags,
                )
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "tag failed for ${flag.uri}", t)
            // 即便 catch 也写 fallback, 不留 aiVersion=0 黑洞.
            try {
                writeFallbackFields(flag.uri, "其他")
            } catch (inner: Throwable) {
                AppLog.w(TAG, "fallback write also failed for ${flag.uri}", inner)
            }
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * v34: 图像质量过滤. 返回 false 时跳过 ML 推理, 直接标 "其他".
     *
     *  * 短边 < 100px → icon / 缩略图 / 系统素材, ML 模型推理失真.
     *  * 颜色方差 < 阈值 → 纯色 (白底/纯黑壁纸/单色 logo), Places365
     *    会误判为"文档"或"室内", 污染分类.
     *
     * 9 点采样足够 (3x3 grid), 1ms 内完成.
     */
    private fun isProcessableImage(bitmap: Bitmap): Boolean {
        if (bitmap.width < 100 || bitmap.height < 100) return false
        if (isSolidColor(bitmap)) return false
        return true
    }

    private fun isSolidColor(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        val samplePoints = arrayOf(
            w / 4 to h / 4,
            w / 2 to h / 4,
            w * 3 / 4 to h / 4,
            w / 4 to h / 2,
            w / 2 to h / 2,
            w * 3 / 4 to h / 2,
            w / 4 to h * 3 / 4,
            w / 2 to h * 3 / 4,
            w * 3 / 4 to h * 3 / 4,
        )
        var sumR = 0; var sumG = 0; var sumB = 0
        val rs = IntArray(9); val gs = IntArray(9); val bs = IntArray(9)
        for (i in samplePoints.indices) {
            val (x, y) = samplePoints[i]
            val px = bitmap.getPixel(x, y)
            rs[i] = (px shr 16) and 0xFF
            gs[i] = (px shr 8) and 0xFF
            bs[i] = px and 0xFF
            sumR += rs[i]; sumG += gs[i]; sumB += bs[i]
        }
        val avgR = sumR / 9.0; val avgG = sumG / 9.0; val avgB = sumB / 9.0
        var varSum = 0.0
        for (i in 0 until 9) {
            varSum += (rs[i] - avgR).toDouble().let { it * it } +
                (gs[i] - avgG).toDouble().let { it * it } +
                (bs[i] - avgB).toDouble().let { it * it }
        }
        // 阈值: 9 点 RGB 平方差总和. 纯色 = 0, 浅蓝渐变 ≈ 200,
        // 普通照片 ≥ 2000. 1000 兼顾低光/夜景.
        return varSum < 1000.0
    }

    /**
     * 写 fallback: aiVersion=AI_VERSION (脱离 pending), subDomain="其他".
     */
    private suspend fun writeFallbackFields(uri: String, subDomain: String) {
        flagDao.updateAiFields(
            uri = uri,
            domain = "real",
            subDomain = subDomain,
            copyright = null,
            faceCount = 0,
            faceArea = 0f,
            score = 0f,
            version = AiTagger.AI_VERSION,
            taggedAt = System.currentTimeMillis(),
            danbooruTags = null,
        )
    }

    /**
     * 解码 mediastore uri 并 downsample 到 [targetShortEdge] 附近.
     * 视频走 MediaMetadataRetriever 取关键帧 (无 inSampleSize 支持, 直出原图后缩).
     */
    private fun decodeScaledBitmap(
        ctx: Context,
        uri: Uri,
        mimeType: String,
        targetShortEdge: Int,
    ): Bitmap? {
        return if (mimeType.startsWith("video/")) {
            android.media.MediaMetadataRetriever().use { r ->
                r.setDataSource(ctx, uri)
                val full = r.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
                downsampleBitmap(full, targetShortEdge).also { if (it !== full) full.recycle() }
            }
        } else {
            // 两步: 先用 inJustDecodeBounds 拿尺寸, 算 inSampleSize, 再 decode.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetShortEdge)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }
    }

    private fun computeInSampleSize(w: Int, h: Int, targetShortEdge: Int): Int {
        var sample = 1
        var shortEdge = minOf(w, h)
        while (shortEdge / 2 >= targetShortEdge * 2) {
            sample *= 2
            shortEdge /= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun downsampleBitmap(src: Bitmap, targetShortEdge: Int): Bitmap {
        val shortEdge = minOf(src.width, src.height)
        if (shortEdge <= targetShortEdge) return src
        val scale = targetShortEdge.toFloat() / shortEdge.toFloat()
        val newW = (src.width * scale).toInt().coerceAtLeast(1)
        val newH = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, true)
    }

    companion object {
        private const val TAG = "AiTaggingWorker"
        const val WORK_NAME = "ai_tagging_worker"
        // 单张 processOne 超时 — DeepDanbooru ~3.5s/张, 120s 兜底.
        private const val PROCESS_TIMEOUT_MS = 120_000L
        // v46: 25 张/批 + 30s 冷却 ≈ 1.5min burst + 0.5min 休息, 在 WM 10min 限制内.
        // 默认值，实际由 AiPreferences.batchSize 覆盖。
        private const val BATCH_LIMIT = 25
        // v46: 批间冷却窗口, 给 SoC 散热. 宁可慢, 不要烫.
        // 默认值，实际由 AiPreferences.cooldownMs 覆盖。
        private const val BATCH_COOLDOWN_MS = 30_000L
        // 8 路并行解码 + N 路 OrtSession 池并发推理 (v32).
        // v31: 1 inference + 8 decode, 推理 ~700ms 串行, 6405 张 ~80min.
        // v32: INT8 模型推理 ~180ms + OrtSessionPool N=4 并发,
        //      6405 张预计 ~18min (4-5x speedup).
        // 单张 wall time 取决于 inflight 队列: decode ~250ms + 推理 ~180ms ≈ 430ms.
        // N=4 时, 8 路 decode 全部完成才进 inflight 队列, inflight 4 路 ×180ms
        // = 720ms 处理后 4 张, 余 4 张再 720ms, 总 ~1.4s 8 张 ≈ 175ms/张 ≈ 5.7 张/秒.
        // v46: 8→2 路解码. 留 6 核给系统/UI, 消除满载发热.
        // 吞吐从 ~6 张/秒 → ~1.5 张/秒, 换来无感不烫.
        private const val DECODE_PARALLELISM = 2
        // v47: 前台暂停轮询间隔 + 上限. 前台时每 5s 检查一次; 连续 3min
        // 前台仍不放行则 re-enqueue, 让 WM 调度下一批 (间隔已由 backoff
        // 兜底), 不给 worker 占 10min 槽位.
        private const val FOREGROUND_PAUSE_INTERVAL_MS = 5_000L
        private const val FOREGROUND_PAUSE_CAP_MS = 180_000L
        @Suppress("unused")
        private const val PARALLELISM = DECODE_PARALLELISM
    }
}
