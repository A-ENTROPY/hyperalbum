package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.smartvision.gallery.util.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.FloatBuffer

/**
 * DeepDanbooru v3 ONNX 多标签 tagger — 完全重写 (v38).
 *
 * 模型: KichangKim/DeepDanbooru v3-20211112-sgd-e28, ResNet-152 变体.
 * 输入: 512×512 RGB float32 [0,1] (仅 /255, 无 ImageNet mean/std).
 * 输出: 9176 维 sigmoid 概率 [0,1].
 * 标签从 deepdanbooru_tags.txt 读取 (9176 行, 含 rating + general + character + meta).
 *
 * ## 设计原则 (v38)
 *  1. **DeepDanbooru 是二次元分类唯一权威** — 所有 anime 判定路径基于本类输出.
 *  2. **用 rating 标签判定动漫域** — DeepDanbooru 内置 rating:safe/rating:questionable/
 *     rating:explicit 标签, sigmoid 值直接反映图像是否为动漫风格.
 *     比 WD tagger 的 "comic" style 标签更稳定可靠.
 *  3. **letterbox 预处理** — 保持宽高比, 边缘像素填充 (非 center crop),
 *     与 DeepDanbooru 训练时的预处理一致.
 *  4. **直接信任 sigmoid 输出** — 模型最后层自带 sigmoid, 输出 [0,1].
 *
 * ## 动漫判定规则
 *   animeScore = rating:safe + rating:questionable + rating:explicit 的 sigmoid 之和
 *   animeScore ≥ ANIME_THRESHOLD → 动漫
 *   rating:general sigmoid ≥ REAL_THRESHOLD → 真人照
 */
class DanbooruTagger(ctx: Context? = null) {

    data class TaggedTag(val name: String, val score: Float)

    data class DanbooruResult(
        val animeScore: Float,
        val ratingSafe: Float,
        val ratingQuestionable: Float,
        val ratingExplicit: Float,
        val characterTag: String?,
        val topTags: List<TaggedTag>,
        val rating: Rating?,
    ) {
        enum class Rating { GENERAL, SENSITIVE, QUESTIONABLE, EXPLICIT }

        fun isAnimeStyle(): Boolean = animeScore >= ANIME_THRESHOLD
    }

    private val appContext: Context? = ctx?.applicationContext
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val labels: List<String> by lazy { loadLabels() }
    private val runCount = java.util.concurrent.atomic.AtomicLong(0L)

    fun isReady(): Boolean = labels.isNotEmpty()

    fun detect(bitmap: Bitmap): DanbooruResult? {
        val allLabels = labels
        if (allLabels.isEmpty()) {
            AppLog.w(TAG, "detect() called but labels empty")
            return null
        }
        val sess = try {
            OrtSessionPool.borrow()
        } catch (t: Throwable) {
            AppLog.w(TAG, "detect() borrow failed: ${t.message}")
            return null
        }
        val t0 = System.nanoTime()
        return try {
            // 1) RGBA → RGB (alpha=0 → 白底)
            val rgbBitmap: Bitmap = if (bitmap.hasAlpha()) {
                val w = bitmap.width
                val h = bitmap.height
                val rgb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(rgb).run {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                rgb
            } else bitmap

            // 2) Letterbox: 保持宽高比, 缩放短边到 512, 边缘像素填充到 512×512
            val input = letterboxAndNormalize(rgbBitmap)

            val shape = longArrayOf(1L, INPUT_SIZE.toLong(), INPUT_SIZE.toLong(), 3L)
            val tensor = OnnxTensor.createTensor(env, input, shape)
            try {
                val output = sess.run(mapOf(INPUT_NAME to tensor))
                val onnxValue = output[0]
                val raw: FloatArray = when (onnxValue) {
                    is OnnxTensor -> {
                        val fb = onnxValue.floatBuffer
                        val arr = FloatArray(fb.remaining())
                        fb.get(arr)
                        arr
                    }
                    else -> {
                        AppLog.w(TAG, "unexpected output type=${onnxValue.javaClass.name}")
                        output.close()
                        return null
                    }
                }
                output.close()
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
                val result = parseResult(raw, allLabels)
                val dc = runCount.incrementAndGet()
                if (dc % 10L == 0L) {
                    AppLog.i(
                        TAG,
                        "detect #$dc elapsed=${elapsedMs}ms " +
                            "anime=${result.isAnimeStyle()} " +
                            "animeScore=${"%.3f".format(result.animeScore)} " +
                            "safe=${"%.3f".format(result.ratingSafe)} " +
                            "ques=${"%.3f".format(result.ratingQuestionable)} " +
                            "expl=${"%.3f".format(result.ratingExplicit)} " +
                            "char=${result.characterTag} tags=${result.topTags.size}"
                    )
                }
                result
            } finally {
                tensor.close()
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "DanbooruTagger inference failed", t)
            null
        } finally {
            OrtSessionPool.release(sess)
        }
    }

    /**
     * Letterbox 预处理: 保持宽高比缩放, 边缘像素填充到正方形.
     * DeepDanbooru 训练时使用 'edge' 模式填充 (复制边缘像素).
     */
    private fun letterboxAndNormalize(bitmap: Bitmap): FloatBuffer {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val targetSize = INPUT_SIZE

        // 计算缩放比例 (保持宽高比)
        val scale = minOf(targetSize.toFloat() / srcW, targetSize.toFloat() / srcH)
        val newW = (srcW * scale).toInt()
        val newH = (srcH * scale).toInt()

        // 缩放
        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

        // 创建正方形画布 + 边缘像素填充
        val canvas = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val c = Canvas(canvas)
        // 先画缩放后的图 (居中)
        val dx = (targetSize - newW) / 2
        val dy = (targetSize - newH) / 2
        c.drawBitmap(scaled, dx.toFloat(), dy.toFloat(), null)
        // 填充边缘: 用最近的边缘像素拉伸到边界
        // 上边
        if (dy > 0) {
            val edgeRow = Bitmap.createBitmap(scaled, 0, 0, newW, 1)
            c.drawBitmap(Bitmap.createScaledBitmap(edgeRow, newW, dy, true), dx.toFloat(), 0f, null)
            edgeRow.recycle()
        }
        // 下边
        if (dy > 0) {
            val edgeRow = Bitmap.createBitmap(scaled, 0, newH - 1, newW, 1)
            c.drawBitmap(Bitmap.createScaledBitmap(edgeRow, newW, dy, true), dx.toFloat(), (dy + newH).toFloat(), null)
            edgeRow.recycle()
        }
        // 左边
        if (dx > 0) {
            val edgeCol = Bitmap.createBitmap(scaled, 0, 0, 1, newH)
            c.drawBitmap(Bitmap.createScaledBitmap(edgeCol, dx, newH, true), 0f, dy.toFloat(), null)
            edgeCol.recycle()
        }
        // 右边
        if (dx > 0) {
            val edgeCol = Bitmap.createBitmap(scaled, newW - 1, 0, 1, newH)
            c.drawBitmap(Bitmap.createScaledBitmap(edgeCol, dx, newH, true), (dx + newW).toFloat(), dy.toFloat(), null)
            edgeCol.recycle()
        }
        scaled.recycle()

        // 3) RGB float32 + /255 → [0,1]
        val input = FloatBuffer.allocate(INPUT_SIZE * INPUT_SIZE * 3)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        canvas.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        canvas.recycle()
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF).toFloat() * INV_255
            val g = ((p shr 8) and 0xFF).toFloat() * INV_255
            val b = (p and 0xFF).toFloat() * INV_255
            input.put(r); input.put(g); input.put(b)
        }
        input.rewind()
        return input
    }

    private fun parseResult(logits: FloatArray, labels: List<String>): DanbooruResult {
        // 1) Rating 标签 — KichangKim DeepDanbooru v3 ONNX 模型最后层已经过 sigmoid
        // 激活 (官方 TF 模型用 sigmoid activation 作为最后一层, ONNX 导出保留),
        // logits 已是 [0,1] 概率. 不要再 sigmoid 一次 (v44 修复 v38 起的 double-sigmoid bug).
        //
        // 之前的 v38-v43 代码: 手动 sigmoid 把真实 [0,1] 概率压成 [0.5, 0.731] 区间,
        // 失去区分度 — 实测 anime 与 real photo 都 safe≈0.730 ques≈0.500 expl≈0.500,
        // 所有照片 animeScore≈1.73 触发 anime 误判, 整个 AI 分类系统错乱.
        var ratingSafeRaw = 0f
        var ratingQuestionableRaw = 0f
        var ratingExplicitRaw = 0f
        var ratingIdxSafe = -1
        var ratingIdxQuestionable = -1
        var ratingIdxExplicit = -1
        for (i in labels.indices) {
            if (i >= logits.size) break
            when (labels[i]) {
                "rating:safe" -> { ratingSafeRaw = logits[i]; ratingIdxSafe = i }
                "rating:questionable" -> { ratingQuestionableRaw = logits[i]; ratingIdxQuestionable = i }
                "rating:explicit" -> { ratingExplicitRaw = logits[i]; ratingIdxExplicit = i }
            }
        }
        // 直接用 logits 作为概率 (已 sigmoid).
        val ratingSafe = ratingSafeRaw
        val ratingQuestionable = ratingQuestionableRaw
        val ratingExplicit = ratingExplicitRaw

        // 3) Top-N tags (不含 rating 标签) — 直接用 logits (已 sigmoid) 排序输出.
        // animeScore 改为 max(anime-discriminator tag prob) — 比 rating 求和更准确:
        // rating:safe 对真人/动漫都 ≈0.95, 求和无区分度; 而 1girl/solo/comic 等
        // anime tag 在动漫图上 ≥0.95, 真人/风景照 ≤0.05.
        val ratingNames = setOf("rating:safe", "rating:questionable", "rating:explicit", "content_rating")
        val topTags = labels.indices
            .filter { it < logits.size && it !in setOf(ratingIdxSafe, ratingIdxQuestionable, ratingIdxExplicit) && !ratingNames.contains(labels[it]) }
            .sortedByDescending { logits[it] }
            .take(TOP_TAG_COUNT)
            .map { TaggedTag(labels[it], logits[it]) }

        // 4) animeScore = max(anime-discriminator tag prob) — 用 [AnimeBuckets.ANIME_DISCRIMINATOR_TAGS]
        // 中任一标签的最大概率. 真人/风景照这些 tag 普遍 < 0.05, 动漫照 ≥ 0.5 常见,
        // 阈值 0.5 在 [AiTagger.ANIME_DOMAIN_THRESHOLD] 处生效.
        var animeScore = 0f
        val animeDiscriminator = AnimeBuckets.ANIME_DISCRIMINATOR_TAGS
        // 二分: 用 labels.indexOf 拿每个 discriminator 的 idx, O(N) 一次.
        // 动漫 discriminator 集只有 14 个 tag, labels 9176, 完全 O(1) per lookup.
        for (tag in animeDiscriminator) {
            val idx = labels.indexOf(tag)
            if (idx in 0 until logits.size) {
                val p = logits[idx]
                if (p > animeScore) animeScore = p
            }
        }

        // 5) Character tag — 从 topTags 中提取, 仅动漫图 (animeScore ≥ 阈值).
        // 非动漫图 (真人/风景/截图) 的 topTags 里没有真角色名, 启发式提取会
        // 误把 phone_screen/checkered_floor/white_background 等当角色 → 污染
        // 角色桶. 加 isAnimeStyle 守门 (实测非动漫图这些伪标签 animeScore≈0.001).
        val characterTag = if (animeScore >= ANIME_THRESHOLD) extractCharacterTag(topTags) else null

        // 6) Rating class — 取最高 rating 类
        val rating = when {
            ratingExplicit >= ratingQuestionable && ratingExplicit >= ratingSafe -> DanbooruResult.Rating.EXPLICIT
            ratingQuestionable >= ratingSafe -> DanbooruResult.Rating.QUESTIONABLE
            else -> DanbooruResult.Rating.GENERAL
        }

        return DanbooruResult(
            animeScore = animeScore,
            ratingSafe = ratingSafe,
            ratingQuestionable = ratingQuestionable,
            ratingExplicit = ratingExplicit,
            characterTag = characterTag,
            topTags = topTags,
            rating = rating,
        )
    }

    private fun loadLabels(): List<String> = try {
        val c = appContext ?: return emptyList()
        c.assets.open(LABELS_ASSET).use { stream ->
            BufferedReader(InputStreamReader(stream)).useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "Failed to load labels from $LABELS_ASSET", t)
        emptyList()
    }

    companion object {
        private const val TAG = "DanbooruTagger"
        private const val INPUT_SIZE = 512
        private const val LABELS_ASSET = "deepdanbooru_tags.txt"
        private const val INPUT_NAME = "input_1"
        private const val INV_255 = 1f / 255f

        // v44 阈值 — animeScore = max(anime-discriminator tag prob).
        // anime-discriminator tag 集见 AnimeBuckets.ANIME_DISCRIMINATOR_TAGS.
        // 真人/风景照这些 tag 普遍 < 0.05; 动漫照 ≥ 0.5 常见.
        // 阈值 0.30 是为了保守, anime 域判定最终由 AiTagger.ANIME_DOMAIN_THRESHOLD
        // (0.5) 决定, 这里 isAnimeStyle() 仅用于 v34 历史的内部判断.
        private const val ANIME_THRESHOLD = 0.30f
        private const val TOP_TAG_COUNT = 50

        // 含 `_` 但不是角色标签的通用复合标签 — 用于角色标签提取时的黑名单过滤
        val CHARACTER_EXCLUDE: Set<String> = setOf(
            "thick_eyebrows", "animal_ear_fluff", "swept_bangs", "flexible_arms",
            "out_of_frame", "upper_body", "lower_body", "full_body", "profile_picture",
            "digital_media", "polished_media", "letterboxed", "wide_shot", "close_up",
            "greyscale", "monochrome", "from_side", "from_behind", "from_above", "from_below",
            "looking_at_viewer", "looking_away", "looking_down", "looking_up",
            "extra_ears", "extra_limbs", "extra_eyes", "extra_wings", "extra_tail",
            "no_humans", "only_watermark", "multiple_girls", "multiple_boys",
            "wide_image", "tall_image", "bad_image", "bad_anatomy", "bad_hands",
            "facing_viewer", "facing_away", "on_side", "on_back", "on_stomach",
            "on_ground", "on_table", "on_bed", "on_floor", "on_grass", "on_water",
            "on_rock", "on_wood", "on_paper",
        )

        /**
         * 从 topTags 中提取角色标签.
         * 规则: 取第一个含 `_` 且不在 [CHARACTER_EXCLUDE] 黑名单中, 且不以
         * 身体部位后缀结尾的标签. DeepDanbooru 9176 标签无 category 列,
         * 角色标签通过命名模式识别 (如 hatsune_miku, rem_(re:zero)).
         *
         * 后缀过滤: 排除 _hair/_eyes/_tail/_ears/_wings/_horn/_fur/_skin/_paws
         * 等描述性标签 (词汇表中 ~295 个), 避免 long_hair/blue_eyes 等高频通用
         * 标签排在角色名前.
         */
        fun extractCharacterTag(topTags: List<TaggedTag>): String? {
            for (tag in topTags) {
                if (!tag.name.contains('_')) continue
                if (tag.name in CHARACTER_EXCLUDE) continue
                if (CHARACTER_SUFFIX_EXCLUDE.any { tag.name.endsWith(it) }) continue
                return tag.name
            }
            return null
        }

        // 身体部位描述后缀 — 含 `_` 但不是角色名的标签, 按后缀批量过滤
        private val CHARACTER_SUFFIX_EXCLUDE: Set<String> = setOf(
            "_hair", "_eyes", "_tail", "_ears", "_wings", "_horn", "_fur", "_skin",
            "_paws", "_claws", "_hooves", "_markings", "_legs", "_arms", "_hands",
            "_feet", "_head", "_face", "_neck", "_chest", "_back",
            "_mouth", "_nose", "_teeth", "_tongue", "_cheek", "_chin", "_belly",
            "_navel", "_stomach", "_thigh", "_thighs", "_waist", "_shoulder",
            "_forehead", "_elbow", "_knee", "_ankle", "_wrist", "_hips",
        )
    }
}
