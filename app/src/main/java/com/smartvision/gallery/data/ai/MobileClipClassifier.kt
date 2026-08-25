package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.smartvision.gallery.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MobileCLIP-S2 zero-shot 端侧分类器.
 *
 * 流水线:
 *  1. Bitmap → 224×224 RGB (TFLite 模型期望输入形状 [1,224,224,3] NHWC)
 *  2. [0,1] 归一化 (除以 255f), mean=0 std=1
 *  3. TFLite image encoder → 512-dim 嵌入
 *  4. L2 normalize
 *  5. 与预存 prompt 嵌入 (assets/clip_prompts.bin) 计算余弦相似度
 *  6. argmax → top1 类
 *
 * ## 模型来源
 *  - TFLite: assets/mobileclip_s2_image.tflite (68 MB float16)
 *    来源 youthfedpycharm/memoria-mobile-vision-assets (HF), 已验证
 *    cosine ≥ 0.99997 vs PyTorch 参考模型
 *  - Prompt 嵌入: assets/clip_prompts.bin (40 KB)
 *    由 open_clip MobileCLIP-S2 datacompdr 在 Python 端预计算,
 *    与 TFLite 模型来自同一 PyTorch checkpoint, 嵌入空间保证匹配
 *
 * ## 类索引
 *  0..5    顶级域 (real, anime, game_screenshot, movie_screenshot,
 *          digital_painting, meme)
 *  10..23  子领域 (person, portrait, night, sunset, snow, water, food,
 *          indoor, plant, building, document, sky, baby, animal)
 *  AiTagger 调用 [classifySubDomain] 取 10..23 中 top1,
 *  [classifyDomain] 取 0..5 中 top1.
 */
class MobileClipClassifier(context: Context) {

    /** 子领域结果 (英文 category + 中文 + 置信度). */
    data class SubDomainResult(
        val category: String,   // EN: "person" / "sunset" ...
        val categoryZh: String,  // 中文: "人像" / "夕阳"
        val confidence: Float,
        val similarity: Float,
    )

    /** 顶级域结果 (domain string + 置信度). */
    data class DomainResult(
        val domain: String,      // "real" / "anime" / "game_screenshot" ...
        val confidence: Float,
        val similarity: Float,
    )

    private val appContext = context.applicationContext

    private val interpreter by lazy {
        AiModelHub.get(appContext).mobileclip()
    }

    /** 预存 prompt 嵌入 (n_classes × embed_dim, L2 normalized). */
    private val promptTable: PromptTable? by lazy { loadPromptTable() }

    private val runCount = java.util.concurrent.atomic.AtomicLong(0L)

    fun isReady(): Boolean = interpreter != null && promptTable != null

    /**
     * 子领域分类 — 对子领域 (class id 10..23) 取 argmax 相似度.
     * 嵌入由 [encodeImage] 共享, 多次比对开销可忽略.
     */
    fun classifySubDomain(bitmap: Bitmap): SubDomainResult? {
        val table = promptTable ?: return null
        val emb = encodeImage(bitmap) ?: return null
        val sims = cosineAgainst(emb, table)
        // 仅在 SUB coding range (10..23) 内取 argmax
        var bestIdx = -1
        var bestSim = -2f
        for (i in table.classIds.indices) {
            val cid = table.classIds[i]
            if (cid in 10..23 && sims[i] > bestSim) {
                bestSim = sims[i]
                bestIdx = i
            }
        }
        if (bestIdx < 0) return null
        val cid = table.classIds[bestIdx]
        val subIdx = cid - 10
        val en = SUB_DOMAINS_EN[subIdx]
        val zh = SUB_DOMAINS_ZH[subIdx]
        // Similarity ∈ [-1, 1] → 等 softmax-like top1 confidence
        val conf = ((bestSim + 1f) / 2f).coerceIn(0f, 1f)
        val rc = runCount.incrementAndGet()
        if (rc % 10L == 0L) {
            AppLog.i(TAG, "sub #$rc top1=$zh(sim=${"%.3f".format(bestSim)}) conf=${"%.3f".format(conf)} idx=$bestIdx")
        }
        return SubDomainResult(en, zh, conf, bestSim)
    }

    /**
     * 顶级域分类 — 仅在 TOP range (0..5) 取 argmax.
     */
    fun classifyDomain(bitmap: Bitmap): DomainResult? {
        val table = promptTable ?: return null
        val emb = encodeImage(bitmap) ?: return null
        val sims = cosineAgainst(emb, table)
        var bestIdx = -1
        var bestSim = -2f
        for (i in table.classIds.indices) {
            val cid = table.classIds[i]
            if (cid in 0..5 && sims[i] > bestSim) {
                bestSim = sims[i]
                bestIdx = i
            }
        }
        if (bestIdx < 0) return null
        val cid = table.classIds[bestIdx]
        val domain = TOP_DOMAINS[cid]
        val conf = ((bestSim + 1f) / 2f).coerceIn(0f, 1f)
        return DomainResult(domain, conf, bestSim)
    }

    /** 复用 image encoder — 供 AiTagger 与 [classifySubDomain]/[classifyDomain] 共享. */
    fun encodeImage(bitmap: Bitmap): FloatArray? {
        val itp = interpreter ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, IMG_SIZE, IMG_SIZE, true)
            // NHWC float32 — MobileCLIP-S2 (open_clip datacompdr) preprocess.
            //
            // v42 重大修复: gen_prompts.py 用 image_mean=(0,0,0) image_std=(1,1,1)
            //   → 模型期望 [0,1] 原始像素, 不做 mean/std 归一化.
            // v41 改成 (x-0.5)/0.5 → [-1,1] 反而让 TFLite 输出全 NaN (nNaN=512/512),
            //   cascade 永远走不到 mobileclip stage. 改回 [0,1] 后 nNaN=0/512,
            //   sumSq≈1.0 (L2 归一化前), embedding 有效.
            //
            // 原理: open_clip datacompdr 在 DataCompDR 数据集训练时, image transform
            // 的 mean=(0,0,0) std=(1,1,1) 即"不做归一化". yauthfedpycharm/memoria-
            // mobile-vision-assets 的 tflite 是从同一个 PyTorch checkpoint 导出,
            // 所以 image encoder 内部 BatchNorm/Linear 已适配 [0,1] 输入范围.
            val input = ByteBuffer.allocateDirect(1 * IMG_SIZE * IMG_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(IMG_SIZE * IMG_SIZE)
            resized.getPixels(pixels, 0, IMG_SIZE, 0, 0, IMG_SIZE, IMG_SIZE)
            // NHWC interleaved: R, G, B per pixel, [0,1] 归一化
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF).toFloat() / 255f)
                input.putFloat(((p shr 8) and 0xFF).toFloat() / 255f)
                input.putFloat((p and 0xFF).toFloat() / 255f)
            }
            input.rewind()

            // 输出: 512-dim float32
            val output = ByteBuffer.allocateDirect(EMBED_DIM * 4)
                .order(ByteOrder.nativeOrder())
            synchronized(itp) { itp.run(input, output) }
            output.rewind()
            val fb = output.asFloatBuffer()
            val out = FloatArray(EMBED_DIM)
            fb.get(out)
            // L2 normalize
            var norm = 0f
            for (v in out) norm += v * v
            norm = kotlin.math.sqrt(norm).coerceAtLeast(1e-12f)
            for (i in out.indices) out[i] = out[i] / norm
            out
        } catch (t: Throwable) {
            AppLog.e(TAG, "encodeImage #${runCount.get()} failed: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    /** 与预存 prompt 嵌入逐行点积 (双方已 L2 归一化 → 即余弦). */
    private fun cosineAgainst(imageEmb: FloatArray, table: PromptTable): FloatArray {
        val n = table.classIds.size
        val sims = FloatArray(n)
        val mat = table.embeddings // shape: (n, embedDim), row-major
        for (i in 0 until n) {
            var s = 0f
            val base = i * EMBED_DIM
            for (k in 0 until EMBED_DIM) {
                s += imageEmb[k] * mat[base + k]
            }
            sims[i] = s
        }
        return sims
    }

    private fun loadPromptTable(): PromptTable? {
        return try {
            appContext.assets.open(AiModelHub.ASSET_CLIP_PROMPTS).use { stream ->
                val bytes = stream.readBytes()
                // Python struct.pack("<IIII", ...) writes LITTLE-ENDIAN.
                // Use ByteBuffer with LITTLE_ENDIAN to match.
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val version = buf.int
                require(version == 1) { "Unsupported clip_prompts.bin version=$version" }
                val nClasses = buf.int
                val nPrompts = buf.int
                val embedDim = buf.int
                require(embedDim == EMBED_DIM) { "embedDim=$embedDim, expected $EMBED_DIM" }
                val classIds = IntArray(nClasses)
                val embeddings = FloatArray(nClasses * embedDim)
                for (i in 0 until nClasses) {
                    classIds[i] = buf.int
                    for (k in 0 until embedDim) {
                        embeddings[i * embedDim + k] = buf.float
                    }
                }
                AppLog.i(TAG, "Loaded clip_prompts.bin: $nClasses classes, embed=$embedDim, prompts/class=$nPrompts")
                PromptTable(classIds, embeddings)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "Failed to load clip_prompts.bin", t)
            null
        }
    }

    private data class PromptTable(val classIds: IntArray, val embeddings: FloatArray)

    companion object {
        private const val TAG = "MobileClipClassifier"
        private const val IMG_SIZE = 224
        private const val EMBED_DIM = 512

        // 与 gen_prompts.py 中顺序一致
        private val TOP_DOMAINS = arrayOf(
            "real", "anime", "game_screenshot", "movie_screenshot",
            "digital_painting", "meme",
        )
        private val SUB_DOMAINS_EN = arrayOf(
            "person", "portrait", "night", "sunset", "snow", "water", "food", "indoor",
            "plant", "building", "document", "sky", "baby", "animal",
        )
        private val SUB_DOMAINS_ZH = arrayOf(
            "人像", "肖像", "夜景", "夕阳", "雪景", "水面", "食物", "室内",
            "植物", "建筑", "文档", "天空", "宝宝", "动物",
        )
    }
}
