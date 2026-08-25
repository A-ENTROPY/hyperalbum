package com.smartvision.gallery.data.ai

import android.content.Context
import com.smartvision.gallery.util.AppLog
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * 单例懒加载三套端侧 ML 后端:
 *  * mobilenet_v2_1.0_224_quant.tflite — MobileNet V1 ImageNet 1001 类,
 *    VisionClassifier 用 ImageNet→14 映射合并到自定义场景
 *  * mobileclip_s2_int8.tflite         — MobileCLIP-S2 FP16 图像 encoder
 *    (社区转换, 68MB), DomainRouter 用预计算 prompt embedding 做零样本域分类
 *  * wd_convnext_tagger_v3.onnx        — WD-ConvNeXt-v3 Danbooru 多标签 (10861 类)
 *    用 ONNX Runtime 跑, 不走 TFLite
 */
class AiModelHub private constructor(appContext: Context) {

    private val appContextRef: Context = appContext.applicationContext

    /** Keep FileChannels alive so MappedByteBuffers backed by them stay valid. */
    private val keptAlive = mutableListOf<Any>()

    val isAvailable: Boolean by lazy {
        isMobilenetAvailable || isDanbooruAvailable || isMobileclipAvailable
    }

    val isMobilenetAvailable: Boolean by lazy { checkAsset(appContextRef, ASSET_MOBILENET) }
    // v32: FP16 是唯一可用模型. 之前尝试 INT8 量化版 (wd_convnext_tagger_v3-int8.onnx,
    // 95MB), 但 onnxruntime Android 1.17 无 ConvInteger reference 实现, 加载即崩
    // "Could not find an implementation for ConvInteger(10)". QLinearOps 重量化或
    // 升级 ORT 都要数小时, 性价比低. 退回 FP16 + 4-session 并发拿 2-3x 加速.
    val isDanbooruAvailable: Boolean by lazy { checkAsset(appContextRef, ASSET_DANBOORU) }
    val isMobileclipAvailable: Boolean by lazy { checkAsset(appContextRef, ASSET_MOBILECLIP) }
    val isPlacesAvailable: Boolean by lazy { checkAsset(appContextRef, ASSET_PLACES365) }

    fun mobilenet(): Interpreter? = loadOrGetTflite(ASSET_MOBILENET, ::getMobilenet, ::setMobilenet)
    fun mobileclip(): Interpreter? = loadMobileclip()
    fun places365(): Interpreter? = loadOrGetTflite(ASSET_PLACES365, ::getPlaces365, ::setPlaces365)

    /**
     * ONNX 模型不走 Interpreter, 直接返回 cacheDir 上的 File.
     * DanbooruTagger 用 ONNX Runtime 加载并 run.
     */
    fun danbooruFile(): File? = loadOrGetFile(ASSET_DANBOORU, ::getDanbooruFile, ::setDanbooruFile)

    @Volatile private var _mobilenet: Interpreter? = null
    @Volatile private var _mobileclip: Interpreter? = null
    @Volatile private var _places365: Interpreter? = null
    @Volatile private var _danbooruFile: File? = null

    private fun getMobilenet(): Interpreter? = _mobilenet
    private fun setMobilenet(i: Interpreter?) { _mobilenet = i }
    private fun getMobileclip(): Interpreter? = _mobileclip
    private fun setMobileclip(i: Interpreter?) { _mobileclip = i }
    private fun getPlaces365(): Interpreter? = _places365
    private fun setPlaces365(i: Interpreter?) { _places365 = i }
    private fun getDanbooruFile(): File? = _danbooruFile
    private fun setDanbooruFile(f: File?) { _danbooruFile = f }

    private fun loadOrGetTflite(
        asset: String,
        getter: () -> Interpreter?,
        setter: (Interpreter) -> Unit,
        useXNNPACK: Boolean = true,
    ): Interpreter? {
        getter()?.let { return it }
        return synchronized(lock) {
            getter()?.let { return@synchronized it }
            val buf = loadAssetFile(appContextRef, asset) ?: return@synchronized null
            try {
                Interpreter(buf, Interpreter.Options().apply {
                    setNumThreads(1)
                    setUseXNNPACK(useXNNPACK)
                }).also(setter)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Failed to load $asset", t)
                null
            }
        }
    }

    /**
     * MobileCLIP: load via Interpreter(File) instead of MappedByteBuffer.
     * The float16 model crashes in TFLite native je_free when loaded via
     * memory-mapped buffer (SIGSEGV null pointer). File-based loading uses
     * a different code path that avoids the bug.
     *
     * v42: XNNPACK=true — 实测 disable XNNPACK 时 FP16 模型所有输出 NaN
     * (TFLite 纯 CPU FP16 路径在很多 ARM 设备上数值不稳定, 0/0 = NaN 传播).
     * 启用 XNNPACK 后 FP16 → FP32 转换走 XNNPACK 优化路径, 输出稳定.
     */
    private fun loadMobileclip(): Interpreter? {
        getMobileclip()?.let { return it }
        return synchronized(lock) {
            getMobileclip()?.let { return@synchronized it }
            val file = copyAssetToCache(appContextRef, ASSET_MOBILECLIP) ?: return@synchronized null
            try {
                Interpreter(file, Interpreter.Options().apply {
                    setNumThreads(2)
                    setUseXNNPACK(true)
                }).also(::setMobileclip)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Failed to load $ASSET_MOBILECLIP via File", t)
                null
            }
        }
    }

    private fun loadOrGetFile(
        asset: String,
        getter: () -> File?,
        setter: (File?) -> Unit,
    ): File? {
        getter()?.let { return it }
        return synchronized(lock) {
            getter()?.let { return@synchronized it }
            val f = copyAssetToCache(appContextRef, asset) ?: return@synchronized null
            setter(f)
            f
        }
    }

    private fun checkAsset(context: Context, name: String): Boolean = try {
        context.assets.openFd(name).use { fd -> fd.length >= MIN_MODEL_BYTES }
    } catch (t: Throwable) {
        AppLog.w(TAG, "Asset missing or tiny: $name", t)
        false
    }

    private fun loadAssetFile(context: Context, name: String): MappedByteBuffer? = try {
        val fd = context.assets.openFd(name)
        val input = fd.createInputStream()
        val tmp = File.createTempFile(name, ".tflite", context.cacheDir)
        tmp.outputStream().use { out -> input.copyTo(out) }
        val raf = java.io.RandomAccessFile(tmp, "r")
        val channel = raf.channel
        val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
        // Prevent GC of channel/raf — MappedByteBuffer can become invalid if
        // the underlying FileChannel is collected.
        synchronized(keptAlive) { keptAlive.add(raf); keptAlive.add(channel) }
        buf
    } catch (t: Throwable) {
        AppLog.w(TAG, "Cannot read $name", t)
        null
    }

    private fun copyAssetToCache(context: Context, name: String): File? = try {
        val out = File(context.cacheDir, name)
        if (!out.exists() || out.length() < MIN_MODEL_BYTES) {
            context.assets.open(name).use { input ->
                out.outputStream().use { o -> input.copyTo(o) }
            }
        }
        out
    } catch (t: Throwable) {
        AppLog.w(TAG, "Cannot copy $name to cache", t)
        null
    }

    companion object {
        private const val TAG = "AiModelHub"
        private const val ASSET_MOBILENET = "mobilenet_v2_1.0_224_quant.tflite"
        // v32: 退回 FP16 (INT8 ConvInteger onnxruntime Android 不支持, 见 danbooruFile 注释).
        // v38: 换 DeepDanbooru v3 — 9176 标签含 rating, ResNet-152, 512×512 输入.
        private const val ASSET_DANBOORU = "deepdanbooru.onnx"
        private const val ASSET_MOBILECLIP = "mobileclip_s2_image.tflite"
        const val ASSET_CLIP_PROMPTS = "clip_prompts.bin"
        private const val ASSET_PLACES365 = "places365_resnet50_int8.tflite"
        private const val MIN_MODEL_BYTES = 1024L

        @Volatile private var cached: AiModelHub? = null
        private val lock = Any()

        fun get(context: Context): AiModelHub = cached ?: synchronized(this) {
            cached ?: AiModelHub(context).also { cached = it }
        }
    }
}
