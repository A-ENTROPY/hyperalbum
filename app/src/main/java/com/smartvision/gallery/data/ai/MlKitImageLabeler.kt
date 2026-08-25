package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.tasks.await

/**
 * ML Kit Image Labeling wrapper. 400+ label categories covering objects,
 * animals, food, documents, scenes — complementing Places365 (scene-only).
 *
 * Returns best-matching subDomain override for categories Places365 cannot
 * detect (动物/食物/文档), or null if no confident match.
 */
class MlKitImageLabeler(private val context: Context) {

    data class LabelResult(val subDomain: String, val confidence: Float, val label: String)

    /** 失败计数 — 连续 5 次才禁用, 避免单次启动 race 永久 disable */
    private var failCount = 0
    private val failLock = Any()

    private val labeler by lazy {
        // 默认阈值 0.5 对花朵/植物假阳性高, 但 0.6 漏掉很多物体 (动物/食物/文档).
        // 折中 0.55: 保持植物抑制同时允许更准的物体类 (dog/cat/pizza 等通常 ≥0.6
        // 即可信, 0.5 起步能扩到 60% 候选).
        val opts = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.55f)
            .build()
        ImageLabeling.getClient(opts)
    }

    private val diagCount = java.util.concurrent.atomic.AtomicLong(0L)

    suspend fun classify(bitmap: Bitmap): LabelResult? {
        // 失败计数 >= 5 才真正禁用, 避免启动 race / 单次 MlKit internal error
        // 永久 disable 所有后续图像 (v17/v18 出现 "Internal error has occurred
        // when executing ML Kit tasks" 一次崩溃后全图 fallback 到 Places365).
        if (synchronized(failLock) { failCount } >= 5) return null
        val input = InputImage.fromBitmap(bitmap, 0)
        // MLKit labeler.process() 内部 PipelineManager.start 调用 addObserver 必须
        // 在 main thread, 否则抛 IllegalStateException: Method addObserver must be
        // called on the main thread. AiTaggingWorker 协程跑在 Dispatchers.Default,
        // 必须切到 Dispatchers.Main 调用 labeler.
        val labels = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                labeler.process(input).await()
            }
        } catch (t: Throwable) {
            // 累计失败计数, 连续 5 次失败才永久 disable.
            val fc = synchronized(failLock) { failCount++; failCount }
            AppLog.w(TAG, "ML Kit labeling failed (count=$fc)", t)
            return null
        }
        // 成功后清零 (避免偶发失败累计误 disable)
        synchronized(failLock) { failCount = 0 }
        // Find highest-confidence label matching our mapping
        var best: LabelResult? = null
        for (lbl in labels) {
            val sub = LABEL_MAP[lbl.text.lowercase()]
            if (sub != null && (best == null || lbl.confidence > best.confidence)) {
                best = LabelResult(sub, lbl.confidence, lbl.text)
            }
        }
        // 临时诊断: 每 5 张把全部 labels 都打出来 (无论是否匹配 LABEL_MAP),
        // 用于定位 v16 "宝宝" 误判源 (是 baby 假阳还是 MLKit 输出别的标签).
        val cnt = diagCount.incrementAndGet()
        if (cnt % 5L == 0L && cnt <= 200L) {
            val all = labels.joinToString(",") { "${it.text}=${"%.2f".format(it.confidence)}" }
            AppLog.i(TAG, "mlkit #$cnt matched=${best?.label}(${best?.subDomain}=${if (best != null) "%.2f".format(best.confidence) else "null"}) all=[$all]")
        }
        return best
    }

    companion object {
        private const val TAG = "MlKitImageLabeler"
        private const val MIN_CONFIDENCE = 0.4f

        /**
         * ML Kit label text → subDomain mapping (lowercase keys).
         *
         * v14 收紧: 移除泛类目 (toy/doll/food/meal/drink/poster/print/stationery),
         * 这些会导致 ML Kit 对含有相关物体 (玩具/海报/打印机/任何食物) 的照片
         * 高置信命中, 触发 508 张误归 "宝宝" / 大量 "食物" 假阳性. 只保留
         * 含义明确的特化物件标签, 让 MLKit 在 cascade 中承担精确物体识别.
         */
        private val LABEL_MAP = buildMap<String, String> {
            // 动物 — 仅保留具体 species. 保留 mammal/animal/pet — 这三个
            // 虽为泛类但 MLKit 在风景照/screenshot 上置信度通常 <0.3,
            // 阈值 0.55 已能排除噪声, 无泛类反而漏判宠物/特异小动物.
            // 仅移除 insect/wildlife — 它们对昆虫标本/safari 远景假阳高.
            val animal = listOf(
                "dog", "cat", "bird", "horse", "cow", "sheep", "elephant", "bear",
                "rabbit", "fish", "shark", "whale", "dolphin", "turtle", "frog", "snake",
                "lizard", "butterfly", "spider",
                "puppy", "kitten", "duck", "goose", "pig", "goat", "deer", "monkey",
                "lion", "tiger", "zebra", "giraffe", "panda", "koala", "mouse", "rat",
                "parrot", "eagle", "owl", "swan", "crab", "snail", "bee", "ant",
                "dinosaur", "reptile", "mammal", "animal", "pet"
            )
            animal.forEach { put(it, "动物") }

            // 食物 — 仅保留具体菜名/食物体 (移除泛类 food/meal/drink/beverage/
            // cuisine/snack/alcohol/breakfast/lunch/dinner/bakery/pastry/bbq/grill/
            // roast/sauce/jam/honey/syrup/gravy/dip — 它们对风景照/screenshot 都高假阳).
            val food = listOf(
                "pizza", "cake", "bread", "pasta", "rice", "fruit", "vegetable",
                "salad", "soup", "sandwich", "hamburger", "fries", "cookie", "donut",
                "ice cream", "chocolate", "candy",
                "coffee", "tea", "juice", "wine", "beer", "milk",
                "egg", "cheese", "butter", "meat", "seafood",
                "sushi", "taco", "noodle", "dessert", "pancake", "waffle",
                "steak", "cocktail", "yogurt", "popcorn", "pie", "bacon", "sausage",
                "toast", "salsa", "bagel", "muffin", "cupcake",
                "casserole", "stew", "curry",
                "peanut butter", "jelly", "soda", "lemonade", "milkshake"
            )
            food.forEach { put(it, "食物") }

            // 文档 — 仅保留文本载体 (移除泛类 paper/print/stationery/page/folder/
            // file/label/certificate/bill/list/contract — 它们对任何纸质/卡片/告示
            // 类都误命中, 易把海报/通知单/菜单误归 "文档").
            val document = listOf(
                "book", "magazine", "newspaper", "document", "receipt",
                "envelope", "letter", "notebook", "diary", "menu", "map", "blueprint",
                "manuscript", "booklet", "pamphlet", "brochure", "flyer"
            )
            document.forEach { put(it, "文档") }

            // 宝宝 — 仅保留明确指示 baby 在场的标签 (移除 toy/doll/stroller/crib/
            // pacifier/rattle/bib/diaper — 这些是物体, 玩具照/娃娃/手推车 都被误
            // 触发为 508 张宝宝假阳性, 是 v14 最大误判源).
            val baby = listOf("baby", "infant", "toddler")
            baby.forEach { put(it, "宝宝") }
        }
    }
}
