package com.smartvision.gallery.data.ai

import android.content.Context
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.smartvision.gallery.util.AppLog
import java.io.FileInputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WD ConvNeXt-v3 ONNX OrtSession 池.
 *
 * OrtSession.run() 不是线程安全的 — 共享单 session 会 SIGSEGV.
 * 池持有 N 个独立 session, 每次推理 borrow 1 个, 用完归还.
 *
 * 懒加载: 第一次 borrow() 才创建所有 session, 避免 app 启动阻塞.
 *
 * ## 容量策略 (v45)
 *  1. 硬底线 ≥ 4GB TotalRAM 才开多 session (否则单 session 都吃紧).
 *  2. 内存预算: 单 session DeepDanbooru FP16 ≈ 700MB native heap (实测).
 *     多 session 总和按 device FreeRAM/2 取 min — 给系统/UI/其他线程留余量.
 *  3. 硬上限 2 — OPPO ColorOS per-process RSS cap 普遍 ~1.5GB, 即便 16GB RAM
 *     设备也不能撑 3 session × 700MB = 2.1GB.
 *  4. 退化兜底: 若 totalRam 读取失败 (沙箱/权限), 单 session 安全模式.
 *
 * 历史 (v32-v44):
 *  v32: 4-way 并发基于 device memoryClass (ColorOS 报告 384MB → 强制 1 session).
 *  v38: 同上, 加 614MB FP16 注释.
 *  v45: 改读 /proc/meminfo TotalRam + Free 动态计算, 真实 OPPO 16GB 设备解锁 2-way.
 */
object OrtSessionPool {
    private const val TAG = "OrtSessionPool"
    private const val MAX_CAPACITY = 1
    private const val BORROW_TIMEOUT_S = 30L

    // 单 OrtSession (DeepDanbooru FP16 614MB) ≈ 700MB native heap.
    private const val PER_SESSION_MB = 700L

    private val pool = ArrayBlockingQueue<OrtSession>(MAX_CAPACITY)
    private val initialized = AtomicBoolean(false)
    @Volatile private var capacity: Int = 1
    @Volatile private var modelPath: String? = null

    /** 初始化 (幂等). capacity 由设备 TotalRam 计算. */
    @Synchronized
    fun init(context: Context, modelPath: String) {
        if (initialized.get()) return
        this.modelPath = modelPath
        this.capacity = computeCapacity(context)
        AppLog.i(TAG, "init: capacity=$capacity model=${modelPath.substringAfterLast('/')}")
        val env = OrtEnvironment.getEnvironment()
        repeat(capacity) { idx ->
            val opts = OrtSession.SessionOptions().apply {
                // 全方位图优化: ALL_OPT = 算子融合 + 常量折叠 + 布局优化 NHWC.
                // 语义保持的图优化, 不改变浮点输出 — 不影响 domain 路由.
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // v46: 降线程 — 保温度. 2 intra + 1 inter = 3 线程峰值.
                // 每张 ~700ms 比 ~500ms 也慢不了多少, 但少烧 5 线程. 宁慢不烫.
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                // 固定输入 shape (512×512) 预分配内存, 减少每次 Run 分配开销
                setMemoryPatternOptimization(true)
            }
            val session = env.createSession(modelPath, opts)
            pool.offer(session)
            AppLog.i(TAG, "session #$idx created")
        }
        initialized.set(true)
    }

    /** 借出 session (阻塞直到有可用). 限时 30s 避免永久卡死. */
    fun borrow(): OrtSession {
        check(initialized.get()) { "OrtSessionPool not initialized — call init() first" }
        return pool.poll(BORROW_TIMEOUT_S, TimeUnit.SECONDS)
            ?: error("OrtSessionPool: no session available after ${BORROW_TIMEOUT_S}s")
    }

    /** 归还 session. */
    fun release(session: OrtSession) {
        if (!pool.offer(session)) {
            AppLog.w(TAG, "release: pool full, closing session")
            session.close()
        }
    }

    /** 当前容量 (用于诊断 / 测试 / AiTaggingWorker 决定并发度). */
    fun capacity(): Int = capacity

    /** 是否已初始化. AiTagger 首次 detect() 懒调 init 用. */
    fun isInitialized(): Boolean = initialized.get()

    /** 测试用: 重置状态. */
    @Synchronized
    internal fun resetForTesting() {
        pool.forEach { it.close() }
        pool.clear()
        initialized.set(false)
        modelPath = null
        capacity = 1
    }

    /**
     * 计算 OrtSession 池容量.
     *
     * 算法:
     *  1. 读 /proc/meminfo: TotalRam + MemAvailable (扣除 cache/buffer 后可用).
     *  2. TotalRam < 4GB → 单 session (内存吃紧).
     *  3. MemAvailable / 2 = 当前可分配预算, 除以单 session 占用 PER_SESSION_MB.
     *  4. 上限 2 — ColorOS per-process cap 普遍 1.5GB, 3 session × 700MB 会 OOM.
     *  5. 下限 1 — 总能保底.
     */
    private fun computeCapacity(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memClassMb = am.memoryClass
        val totalRamMb = readTotalRamMb()
        val availMb = readMemAvailableMb()
        AppLog.i(TAG, "device memoryClass=${memClassMb}MB totalRam=${totalRamMb}MB available=${availMb}MB")

        if (totalRamMb <= 0) {
            AppLog.w(TAG, "TotalRam unreadable, falling back to capacity=1 (safe)")
            return 1
        }
        if (totalRamMb < 4L * 1024L) {
            // <4GB RAM 设备: 单 session 都要 ~700MB, 不够开第 2 个
            AppLog.i(TAG, "TotalRam < 4GB, capacity=1")
            return 1
        }
        // 4GB+ 设备: 按 MemAvailable/2 预算, 但上限 2
        val budgetMb = if (availMb > 0) availMb / 2 else totalRamMb / 4
        val fromBudget = (budgetMb / PER_SESSION_MB).toInt().coerceAtLeast(1)
        val cap = minOf(fromBudget, MAX_CAPACITY)
        AppLog.i(TAG, "TotalRam=${totalRamMb}MB → capacity=$cap (budget=${budgetMb}MB, perSession=${PER_SESSION_MB}MB)")
        return cap
    }

    private fun readTotalRamMb(): Long {
        return try {
            val memInfo = FileInputStream("/proc/meminfo").bufferedReader().use { it.readText() }
            val match = Regex("MemTotal:\\s+(\\d+)\\s+kB").find(memInfo)
            match?.groupValues?.get(1)?.toLong()?.div(1024) ?: 0L
        } catch (t: Throwable) {
            AppLog.w(TAG, "readTotalRamMb failed: ${t.message}")
            0L
        }
    }

    private fun readMemAvailableMb(): Long {
        return try {
            val memInfo = FileInputStream("/proc/meminfo").bufferedReader().use { it.readText() }
            val match = Regex("MemAvailable:\\s+(\\d+)\\s+kB").find(memInfo)
            match?.groupValues?.get(1)?.toLong()?.div(1024) ?: 0L
        } catch (t: Throwable) {
            AppLog.w(TAG, "readMemAvailableMb failed: ${t.message}")
            0L
        }
    }
}