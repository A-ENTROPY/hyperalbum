package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.smartvision.gallery.util.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Image classification 后端 — MobileNet V2 1.0_224 quant (ImageNet 1001 类).
 *
 * ## 准确度两层补救
 *  1. **真实 synset 映射**: ImageNet 1000 类按 synset 名称 (sunset/snow 这种类根本不存在)
 *     手工校准 — 错误修复前 `928` 被误标成 "夕阳", 但实际是 `ice_cream`. 现在基于
 *     TF 官方 `imagenet_class_index.json` 重写整张 IMAGENET_TO_SCENE 表.
 *  2. **颜色 heuristic 后置 fallback**: ImageNet 1000 是物体分类, 场景照 (夕阳/雪景/纯天空)
 *     没有对应 synset. parseTop1 找不到映射时, 用 Bitmap 平均 HSV + 蓝白比例退回 sunset/snow/sky.
 *     这是 Places365 应做但目前没模型时唯一可用的兜底.
 */
class VisionClassifier(context: Context) {

    data class VisionResult(
        val category: String,
        val categoryZh: String,
        val confidence: Float
    )

    private val labelsZh = arrayOf(
        "人像", "肖像", "夜景", "夕阳", "雪景", "水面", "食物", "室内",
        "植物", "建筑", "文档", "天空", "宝宝", "动物"
    )

    private val interpreter by lazy { AiModelHub.get(context).mobilenet() }
    private val visionRunCount: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0L)

    fun isReady(): Boolean = interpreter != null

    fun classify(bitmap: Bitmap): VisionResult? {
        val raw = classifyRaw(bitmap) ?: return null
        return parseTop1WithFallback(bitmap, raw.probs)
    }

    data class RawTop1(val idx: Int, val conf: Float, val probs: FloatArray)

    fun classifyRaw(bitmap: Bitmap): RawTop1? {
        val itp = interpreter ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val input = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3)
                .order(ByteOrder.nativeOrder())
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            for (p in pixels) {
                input.put(((p shr 16) and 0xFF).toByte())
                input.put(((p shr 8) and 0xFF).toByte())
                input.put((p and 0xFF).toByte())
            }
            input.rewind()
            val outCount = itp.getOutputTensor(0).numElements()
            val outBytes = ByteArray(outCount)
            val output = arrayOf(outBytes)
            synchronized(itp) { itp.run(input, output) }
            val probs = FloatArray(outCount) { (output[0][it].toInt() and 0xFF) / 255f }
            val top1Idx = probs.indices.maxByOrNull { probs[it] } ?: 0
            RawTop1(top1Idx, probs[top1Idx], probs)
        } catch (t: Throwable) {
            AppLog.e(TAG, "VisionClassifier inference failed", t)
            null
        }
    }

    /** 颜色探测结果 — sceneIdx=-1 表示未分类, conf 表示信号强度 (0..1) */
    private data class ColorProbe(val sceneIdx: Int, val conf: Float)

    /**
     * top-1 → 14 场景类解析流程 (ImageNet 优先, 颜色仅在彻底失配时介入):
     *  (1) top-20 索引依次查 IMAGENET_TO_SCENE, 命中直接返回 (物体识别永远优先)
     *  (2) 全无命中 → [parseFallbackColor] 强模式 (224×224 全采样) 跑出 scene →
     *      若颜色信号强, 采纳颜色结果 (补 5 个 ImageNet 缺类: sunset/snow/sky/water/night)
     *  (3) 还判断不出 → 颜色弱模式 (32×32 downsample) 兜底
     *  (4) 才落 "其他"
     *
     * 关键修正: 旧版"ImageNet conf<0.5 → 颜色劫持"过于激进 — ice_cream conf=0.40
     * 会被错判为 sunset, pizza conf=0.35 会被错判为夜景. 现改为 ImageNet 完全
     * 无映射时才启用颜色, 保证物体识别永远胜出.
     */
    fun parseTop1WithFallback(bitmap: Bitmap, probs: FloatArray): VisionResult {
        val topN = probs.indices
            .filter { it != 0 }
            .sortedByDescending { probs[it] }
            .take(20)
        val top1Conf = probs.getOrElse(topN.firstOrNull() ?: 0) { 0f }

        // Stage 1: ImageNet top-20 lookup — 物体识别永远优先
        // 先查 IMAGENET_TO_SCENE: ice_cream(928)/pizza(963)/alp(970) 即使 conf=0.3
        // 也能正确归到 FOOD/SUNSET. 只有当 ImageNet 完全没命中物体时, 才退到颜色.
        for (idx in topN) {
            val sceneIdx = IMAGENET_TO_SCENE[idx] ?: -1
            if (sceneIdx in LABELS_EN.indices) {
                // 临时诊断: 记录每张被 ImageNet 命中的物体内 ID 与置信度,
                // 用于定位 v23 仍 27% "文档" 来源 (是否 binder/notebook 等假阳).
                val vc = visionRunCount.incrementAndGet()
                if (vc % 1L == 0L && vc <= 300L) {
                    val hint = IMAGENET_NAME_HINT[idx] ?: "?"
                    AppLog.i(TAG, "match #$vc idx=$idx=$hint conf=${"%.2f".format(probs[idx])} → ${LABELS_EN[sceneIdx]}")
                }
                return VisionResult(
                    category = LABELS_EN[sceneIdx],
                    categoryZh = labelsZh[sceneIdx],
                    confidence = probs[idx]
                )
            }
        }

        // Stage 2: 颜色强探测 (224×224 全采样, 仅当 ImageNet 完全没命中物体时介入)
        // 用于补 ImageNet 缺的 5 个场景 (sunset/snow/sky/water/night).
        val colorStrong = parseFallbackColor(bitmap, strongMode = true)
        if (colorStrong != null && colorStrong.sceneIdx in LABELS_EN.indices && colorStrong.conf >= 0.55f) {
            val vc = visionRunCount.incrementAndGet()
            if (vc % 20L == 0L) {
                AppLog.i(TAG, "color-strong #$vc imnetTop1Conf=${"%.2f".format(top1Conf)} → ${LABELS_EN[colorStrong.sceneIdx]}(conf=${"%.2f".format(colorStrong.conf)})")
            }
            return VisionResult(
                category = LABELS_EN[colorStrong.sceneIdx],
                categoryZh = labelsZh[colorStrong.sceneIdx],
                confidence = colorStrong.conf
            )
        }

        // Stage 3: 颜色弱兜底 (32×32 downsample)
        val colorWeak = parseFallbackColor(bitmap, strongMode = false)
        if (colorWeak != null && colorWeak.sceneIdx in LABELS_EN.indices) {
            return VisionResult(
                category = LABELS_EN[colorWeak.sceneIdx],
                categoryZh = labelsZh[colorWeak.sceneIdx],
                confidence = colorWeak.conf * 0.85f // 弱模式再降权
            )
        }

        // Stage 4: 其他
        val vc = visionRunCount.incrementAndGet()
        if (vc % 50L == 0L) {
            val top5 = topN.take(5).joinToString(",") { idx ->
                "$idx=${"%.2f".format(probs[idx])}/${IMAGENET_NAME_HINT[idx] ?: "?"}"
            }
            AppLog.w(TAG, "Vision other-fallback #$vc top5=[$top5]")
        }
        return VisionResult(category = "other", categoryZh = "其他", confidence = top1Conf)
    }

    /**
     * 颜色 heuristic — 全 Bitmap 采样:
     *  * 平均 R+G 高且 B 低, 红黄主导 → sunset
     *  * 平均 R+G+B 都高 (>200) 且饱和度低 → snow
     *  * 平均 B 占优 + 蓝色像素 > 30% → water (避免与 sky 混淆)
     *  * 全部都很暗 (V<60) → night
     *  * B 占优且显著低于整图 → sky
     *
     *  strongMode=true  → 全分辨率采样, 阈值略严格 (因为有 50x 像素, 噪声更低)
     *                     用于 Stage 1 拦截弱置信的 ImageNet
     *  strongMode=false → 32×32 downsample, 阈值较宽松, 仅作最后兜底
     */
    private fun parseFallbackColor(bitmap: Bitmap, strongMode: Boolean): ColorProbe? {
        val sampleSize = if (strongMode) {
            // 224×224 全采样 — bitmap 在 pipeline 入口已 downsample 到 224 短边
            // (INFERENCE_SHORT_EDGE = 224), 这里若原图已 >= 224 直接用, 否则缩放
            minOf(bitmap.width, bitmap.height).coerceAtLeast(32)
        } else 32
        val small = if (bitmap.width >= sampleSize && bitmap.height >= sampleSize) {
            Bitmap.createScaledBitmap(bitmap, sampleSize, sampleSize, true)
        } else bitmap
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var n = 0L
        var bluePx = 0L
        var brightLowSat = 0L
        var darkPx = 0L
        var warmPx = 0L
        for (p in pixels) {
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            sumR += r; sumG += g; sumB += b; n++
            // 蓝色面积: B > R 且 B > G 且 B > 80
            if (b > r + 15 && b > g + 5 && b > 80) bluePx++
            // 亮且低饱和 = 雪
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            if (maxC >= 200 && (maxC - minC) < 30) brightLowSat++
            // 暗 = 夜景
            val v = (r + g + b) / 3
            if (v < 50) darkPx++
            // 暖色主导 = 夕阳
            if (r > b + 50 && r > 100) warmPx++
        }
        if (small !== bitmap) small.recycle()
        val meanR = (sumR / n).toInt()
        val meanG = (sumG / n).toInt()
        val meanB = (sumB / n).toInt()
        val blueRatio = bluePx.toFloat() / n.toFloat()
        val brightLowSatRatio = brightLowSat.toFloat() / n.toFloat()
        val darkRatio = darkPx.toFloat() / n.toFloat()
        val warmRatio = warmPx.toFloat() / n.toFloat()
        val vc = visionRunCount.incrementAndGet()
        if (vc % 50L == 0L) {
            AppLog.i(TAG, "color${if (strongMode) "-strong" else "-weak"} #$vc rgb=($meanR,$meanG,$meanB) blue=${"%.2f".format(blueRatio)} snow=${"%.2f".format(brightLowSatRatio)} dark=${"%.2f".format(darkRatio)} warm=${"%.2f".format(warmRatio)}")
        }
        // 强模式阈值 (224×224 全采样, 噪声低, 要求更严格的比例)
        // 弱模式阈值 (32×32, 信号被平均模糊, 要求更宽松)
        return when {
            // 暗 = 夜景
            darkRatio >= if (strongMode) 0.55f else 0.50f ->
                ColorProbe(SCENE_NIGHT, 0.70f)
            // 亮且低饱和 = 雪
            brightLowSatRatio >= if (strongMode) 0.55f else 0.60f ->
                ColorProbe(SCENE_SNOW, 0.65f)
            // 暖色为主 → sunset (括号必须: if 表达式优先级问题)
            warmRatio >= (if (strongMode) 0.30f else 0.25f) &&
                meanR >= 140 && meanG >= 100 && meanB <= 130 && meanR > meanB + 40 ->
                ColorProbe(SCENE_SUNSET, 0.60f)
            // 蓝色主导 → water (vs sky 需要更多蓝色)
            blueRatio >= if (strongMode) 0.45f else 0.30f ->
                ColorProbe(SCENE_WATER, 0.55f)
            // 主调偏蓝且不暗 → sky
            meanB > meanR + 25 && meanB > meanG + 25 && meanB > 100 ->
                ColorProbe(SCENE_SKY, 0.50f)
            else -> null
        }
    }

    companion object {
        private const val TAG = "VisionClassifier"
        private const val INPUT_SIZE = 224

        // 14 类顺序 — DomainRouter/AiTagger 都依赖这索引
        private val LABELS_EN = arrayOf(
            "person", "portrait", "night", "sunset", "snow", "water", "food", "indoor",
            "plant", "building", "document", "sky", "baby", "animal"
        )
        private const val SCENE_PERSON = 0
        private const val SCENE_PORTRAIT = 1
        private const val SCENE_NIGHT = 2
        private const val SCENE_SUNSET = 3
        private const val SCENE_SNOW = 4
        private const val SCENE_WATER = 5
        private const val SCENE_FOOD = 6
        private const val SCENE_INDOOR = 7
        private const val SCENE_PLANT = 8
        private const val SCENE_BUILDING = 9
        private const val SCENE_DOCUMENT = 10
        private const val SCENE_SKY = 11
        private const val SCENE_BABY = 12
        private const val SCENE_ANIMAL = 13

        /**
         * 384 个真 ImageNet 1000 synset → 14 场景类映射.
         * 全部基于 TF 官方 imagenet_class_index.json (storage.googleapis.com/.../imagenet_class_index.json)
         * 手动按 synset 英文名匹配. 覆盖要点:
         *  - IMAGE 无 sunset/snow scene 类 — 这两类靠 [parseFallbackColor] 颜色兜底
         *  - SCENE_WATER 真有 lakeside(975)/seashore(978)/coral_reef(973)/diver/船
         *  - SCENE_FOOD 真有 50+ 类 — 924~969, 987, 988
         *  - SCENE_BUILDING 真有 castle(483)/church(497)/palace(698)/monastery(663)/mosque(668)/bridge(821,839)
         *  - SCENE_ANIMAL 真有鱼鸟犬猫熊等大段 (1-100, 151-268, 281-300)
         */
        private val IMAGENET_NAME_HINT: Map<Int, String> = mapOf(
            // PERSON (少见; MLKit face 会优先)
            400 to "academic_gown", 451 to "bolo_tie", 457 to "bow_tie", 570 to "gasmask",
            578 to "gown", 834 to "suit", 906 to "Windsor_tie", 643 to "mask",
            691 to "oxygen_mask", 796 to "ski_mask", 871 to "trimaran",
            // PORTRAIT
            982 to "groom",
            // NIGHT (灯/蜡烛)
            470 to "candle", 619 to "lampshade", 818 to "spotlight", 846 to "table_lamp", 862 to "torch",
            // SUNSET (靠颜色) — 970-980 段都是自然地形
            970 to "alp", 972 to "cliff", 975 to "lakeside", 977 to "sandbar", 980 to "volcano",
            // SNOW (基本靠颜色; snowmobile/snow_leopard 弱证据)
            289 to "snow_leopard", 802 to "snowmobile", 803 to "snowplow",
            // WATER — 真有 water 场景
            20 to "water_ouzel", 58 to "water_snake", 65 to "sea_snake", 108 to "sea_anemone",
            109 to "brain_coral", 115 to "sea_slug", 150 to "sea_lion",
            328 to "sea_urchin", 329 to "sea_cucumber", 346 to "water_buffalo",
            460 to "breakwater", 536 to "dock", 833 to "submarine", 871 to "trimaran",
            914 to "yawl", 973 to "coral_reef", 975 to "lakeside", 978 to "seashore", 983 to "scuba_diver",
            // FOOD — 真有 50+ 类食物
            924 to "guacamole", 925 to "consomme", 926 to "hot_pot", 927 to "trifle",
            928 to "ice_cream", 929 to "ice_lolly", 930 to "French_loaf", 931 to "bagel",
            932 to "pretzel", 933 to "cheeseburger", 934 to "hotdog", 935 to "mashed_potato",
            936 to "head_cabbage", 937 to "broccoli", 938 to "cauliflower", 939 to "zucchini",
            940 to "spaghetti_squash", 941 to "acorn_squash", 942 to "butternut_squash", 943 to "cucumber",
            944 to "artichoke", 945 to "bell_pepper", 946 to "cardoon", 947 to "mushroom",
            948 to "Granny_Smith", 949 to "strawberry", 950 to "orange", 951 to "lemon",
            952 to "fig", 953 to "pineapple", 954 to "banana", 955 to "jackfruit",
            956 to "custard_apple", 957 to "pomegranate", 958 to "hay", 959 to "carbonara",
            960 to "chocolate_sauce", 961 to "dough", 962 to "meat_loaf", 963 to "pizza",
            964 to "potpie", 965 to "burrito", 966 to "red_wine", 967 to "espresso",
            968 to "cup", 969 to "eggnog", 987 to "corn", 988 to "acorn",
            // INDOOR
            435 to "bathtub", 475 to "car_mirror", 493 to "chiffonier", 495 to "china_cabinet",
            532 to "dining_table", 564 to "four-poster", 624 to "library", 761 to "rocking_chair",
            762 to "restaurant", 794 to "shower_curtain", 819 to "stage", 831 to "studio_couch",
            854 to "theater_curtain", 892 to "wall_clock", 894 to "wardrobe",
            896 to "washbasin", 897 to "washer", 999 to "toilet_tissue",
            // PLANT
            984 to "rapeseed", 985 to "daisy", 986 to "yellow_lady_slipper", 988 to "acorn",
            // BUILDING
            425 to "barn", 442 to "bell_cote", 449 to "boathouse", 476 to "carousel",
            483 to "castle", 497 to "church", 580 to "greenhouse", 660 to "mobile_home",
            663 to "monastery", 668 to "mosque", 698 to "palace", 821 to "steel_arch_bridge",
            839 to "suspension_bridge", 915 to "yurt", 856 to "thresher", 857 to "throne",
            // DOCUMENT — 仅保留真正文档/书阅读相关 (排除 menu/plate/bow/canoe/
            // beach_wagon/carton/cassette_player/web_site/mailbox/paper_towel —
            // 它们是物体或容器, 非"文档")
            446 to "binder", 549 to "envelope", 681 to "notebook",
            917 to "comic_book", 921 to "book_jacket",
            // SKY (飞行)
            404 to "airliner", 417 to "balloon", 701 to "parachute", 809 to "sunglass",
            895 to "warplane",
            // BABY
            516 to "cradle", 520 to "crib"
        )

        private val IMAGENET_TO_SCENE: Map<Int, Int> = buildMap {
            // Person
            putIds("400,451,457,570,578,834,906,643,691,796,871".split(",").map { it.toInt() }, SCENE_PERSON)
            // Portrait
            put(982, SCENE_PORTRAIT)
            // Night — 仅保留强信号: candle/lampshade/spotlight/table_lamp/torch 暗示夜景
            // 移除弱映射 (这些是物体, 不一定夜景)
            putIds("470,619,818,846,862".split(",").map { it.toInt() }, SCENE_NIGHT)
            // Sunset — 仅留 lakeside(975) 给水域夕阳. 移除 alp(970)/cliff(972)/
            // sandbar(977)/volcano(980) — 这些是模糊地形, 与 Places365 SCENE_SUNSET
            // 重叠且容易和 BUILDING/PLANT 冲突.
            putIds(listOf(975), SCENE_SUNSET)
            // Snow — 移除 snow_leopard(289)/snowmobile(802)/snowplow(803),
            // 这些是动物/车辆物体, 不是雪景. Places365 的 SCENE_SNOW 才是权威.
            // 颜色 heuristic 仍可补 sunset/snow/sky/water/night 4 个 ImageNet 缺类.
            // (完全移除 SCENE_SNOW 映射)
            // Water
            putIds(listOf(20, 58, 65, 108, 109, 115, 150, 328, 329, 346, 460, 536, 833, 871, 914, 973, 975, 978, 983), SCENE_WATER)
            // Food
            putIds((924..969).toList(), SCENE_FOOD)
            putIds(listOf(987, 988), SCENE_FOOD)
            // Indoor
            putIds(listOf(435, 475, 493, 495, 532, 564, 624, 761, 762, 794, 819, 831, 854, 892, 894, 896, 897, 999), SCENE_INDOOR)
            // Plant
            putIds(listOf(984, 985, 986, 988), SCENE_PLANT)
            // Building
            putIds(listOf(425, 442, 449, 476, 483, 497, 580, 660, 663, 668, 698, 821, 839, 915, 856, 857), SCENE_BUILDING)
            // Document
            putIds(listOf(446, 549, 681, 917, 921), SCENE_DOCUMENT)
            // Sky — 移除 airliner(404)/balloon(417)/parachute(701)/sunglass(809)/
            // warplane(895), 这些是飞行物体不是天空场景. Places365 sky 字段是权威,
            // 颜色 heuristic 补 fallback.
            // (完全移除 SCENE_SKY 映射)
            // Baby
            putIds(listOf(516, 520), SCENE_BABY)
            // Animal — 鱼/鸟/爬行/两栖/小哺乳 (1-100)
            for (i in 1..100) put(i, SCENE_ANIMAL)
            // Dog breeds 151-268
            for (i in 151..268) put(i, SCENE_ANIMAL)
            // Cat breeds 281-285
            for (i in 281..285) put(i, SCENE_ANIMAL)
            // Bears/large cats 286-300
            for (i in 286..300) put(i, SCENE_ANIMAL)
            // Bats/rabbits/mice 301-319
            for (i in 301..319) put(i, SCENE_ANIMAL)
            // Misc 320-347
            for (i in 320..347) put(i, SCENE_ANIMAL)
            // Large mammals 348-400
            for (i in 348..400) put(i, SCENE_ANIMAL)
        }

        private fun MutableMap<Int, Int>.putIds(keys: Iterable<Int>, value: Int) {
            for (k in keys) put(k, value)
        }
    }
}
