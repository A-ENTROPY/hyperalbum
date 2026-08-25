package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.smartvision.gallery.util.AppLog

/**
 * 协调器: 输入 bitmap → 生成 7 个 MediaItem AI 字段.
 *
 * 优化: 调用方应先 [preprocessForInference] 把源图缩到 224×224 短边
 * (或直接 [tagFromPath] 由 Tagger 内部 decode+downsample 一次复用三模型),
 * 避免每个模型分别 createScaledBitmap 浪费内存与 CPU.
 *
 * 不再使用 withContext(Dispatchers.Default) — TFLite/ONNX/MLKit 各自
 * 有内部线程池, 外层包装只是冗余上下文切换. 调用方应已在 IO/Default
 * 协程中调用.
 */
class AiTagger(private val context: Context) {

    data class AiTagResult(
        val domain: String,
        val subDomain: String,
        val copyright: String?,
        val faceCount: Int,
        val faceArea: Float,
        val score: Float,
        val version: Int,
        // v29: 二次元分类栏目 — domain=="anime" 时存 Danbooru top-20 tags JSON.
        // 格式: [{"t":"tag","s":0.99}]. null 表示非 anime 或无 tags.
        val danbooruTags: String? = null,
    )

    private val vision by lazy { VisionClassifier(context) }
    private val places by lazy { Places365Classifier(context) }
    private val clip by lazy { DomainRouter(context) }
    private val danbooru by lazy { DanbooruTagger(context) }
    private val faceAnalyzer by lazy { MlKitFaceAnalyzer(context) }
    private val mlKitLabeler by lazy { MlKitImageLabeler(context) }
    private val mobileclip by lazy { MobileClipClassifier(context) }
    private val upgradeCount: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0L)
    private val tagCount: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0L)
    private val clipHitCount: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0L)
    private val tagCountReal: java.util.concurrent.atomic.AtomicLong = java.util.concurrent.atomic.AtomicLong(0L)

    private val placesSceneLabels = mapOf(
        Places365Classifier.SCENE_PERSON to "人像",
        Places365Classifier.SCENE_PORTRAIT to "肖像",
        Places365Classifier.SCENE_NIGHT to "夜景",
        Places365Classifier.SCENE_SUNSET to "夕阳",
        Places365Classifier.SCENE_SNOW to "雪景",
        Places365Classifier.SCENE_WATER to "水面",
        Places365Classifier.SCENE_FOOD to "食物",
        Places365Classifier.SCENE_INDOOR to "室内",
        Places365Classifier.SCENE_PLANT to "植物",
        Places365Classifier.SCENE_BUILDING to "建筑",
        // SCENE_DOCUMENT 也从 Places365 路径中移除 — v22 仍 28/79=35% 误归"文档",
        // 源于 Places365 SCENE_DOCUMENT (archive/library/bookstore) 在阈值 ≥0.40 后
        // 仍把任何图书室/书店/办公室误判为文档, 且 cascade 中无 MLKit 文档桶兜底
        // (MLKit labeler 在该设备上 PipelineManager.start 失败 disable). 文档类
        // 仅由 ImageNet IMAGENET_TO_SCENE binder/notebook/comic_book/book_jacket 等
        // 物体识别提供, 已在 v22 收紧到 5 个高特异类.
        Places365Classifier.SCENE_SKY to "天空",
        // SCENE_BABY 从 Places365 路径中移除 — Places365 模型对 childs_room/nursery/
        // playroom 输出 conf>0.4 时仍假阳高 (v18 证实 40/195=20% 误归宝宝).
        // BABY 仅由 MLKit "baby"/"infant"/"toddler" 物体 label 提供.
        Places365Classifier.SCENE_ANIMAL to "动物"
    )

    /**
     * 兼容旧入口: 直接接收任意尺寸 Bitmap. 仍建议外部先 downsample.
     */
    suspend fun tag(bitmap: Bitmap): AiTagResult? = tagInternal(bitmap)

    /**
     * v32: 预热 — 启动 OrtSessionPool, 跑一次 stub 推理, 让 N 个 session
     * 全部 ready. 第一次 detect() 才 init 会让首张延迟 ≈ 1s (4 sessions × ~250ms)
     * 且阻塞单线程 — 提前在 worker 启动时 warmup, 不阻塞批处理.
     */
    suspend fun warmup() {
        val c = context.applicationContext
        val hub = AiModelHub.get(c)
        if (!hub.isDanbooruAvailable) {
            AppLog.w(TAG, "warmup: Danbooru model not vendored, skip pool init")
            return
        }
        val file = hub.danbooruFile() ?: run {
            AppLog.w(TAG, "warmup: danbooruFile()==null")
            return
        }
        try {
            OrtSessionPool.init(c, file.absolutePath)
            AppLog.i(TAG, "warmup: pool init OK capacity=${OrtSessionPool.capacity()}")
            // 跑一次 stub 推理让 ONNX runtime 完成 shape inference / 算子融合
            // v38: DeepDanbooru 512×512 输入.
            val stub = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            try {
                val r = danbooru.detect(stub)
                AppLog.i(TAG, "warmup: stub detect OK, anime=${r?.isAnimeStyle()} score=${"%.3f".format(r?.animeScore ?: 0f)} tags=${r?.topTags?.size}")
            } finally {
                stub.recycle()
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "warmup failed", t)
        }
    }

    /**
     * 内部不切 Dispatchers.Default — TFLite/ONNX/MLKit 自带线程池.
     */
    private suspend fun tagInternal(bitmap: Bitmap): AiTagResult? {
        val hub = AiModelHub.get(context)
        if (!hub.isAvailable) {
            AppLog.w(TAG, "No AI models vendored, falling back to heuristic")
            return null
        }
        // v32: 懒初始化 OrtSessionPool. 浏览路径 (PhotoView 等) 调 tag()
        // 不经过 AiTaggingWorker.warmup(), 必须在这里兜底.
        if (hub.isDanbooruAvailable && !OrtSessionPool.isInitialized()) {
            warmup()
        }
        val t0 = System.nanoTime()
        // v34: 每个模型独立 try/catch — 任一模型抛异常不能让整张图 aiVersion=0.
        // 默认值与原流程"模型返回 null"时等价, 失败模型 → 跳过该模型, 其他模型照常.
        val emptyFace = com.smartvision.gallery.data.ai.MlKitFaceAnalyzer.FaceResult(0, 0f)
        val face = try { faceAnalyzer.detect(bitmap) }
                   catch (t: Throwable) { AppLog.w(TAG, "faceAnalyzer failed", t); emptyFace }
        // v43: 二次元分类由 DeepDanbooru 单一决策, 移除所有判别门.
        //
        // 历史 (v40-v42):
        //   v40: Danbooru animeScore>=0.5 → 真人照被 Danbooru 高 rating:safe 误判
        //   v41: 加 ANIME_DISCRIMINATOR_TAGS 守门 (comic/sketch/chibi/furry 等) +
        //        OR DomainRouter=="anime" 兜底
        //   v42: MobileCLIP 归一化修复, cos sim 从 0.10 噪声水平恢复到 0.60
        //
        // v43 决策规则:
        //   isAnime = (dResult.animeScore >= 0.5)
        //
        // 0.5 是 DeepDanbooru KichangKim 仓库 + A1111 wd-tagger 公认的 rating
        // safe_cutoff 默认值 (picobyte/stable-diffusion-webui-wd14-tagger
        // /tagger/settings.py), 阈值越低越倾向把真人判 anime, 越高越漏判.
        // 0.5 是社区共识的"balanced"档. 移除 hasAnimeStyle 守门和 clipSaysAnime
        // 兜底: 让模型输出直接决定, 不再加任何启发式门控.
        val dResult = if (hub.isDanbooruAvailable)
            try { danbooru.detect(bitmap) } catch (t: Throwable) { AppLog.w(TAG, "Danbooru failed", t); null }
        else null
        val clipDomain: String = try { clip.route(bitmap) } catch (t: Throwable) { AppLog.w(TAG, "DomainRouter failed", t); "real" }
        val danbooruSaysAnime = dResult != null && dResult.animeScore >= ANIME_DOMAIN_THRESHOLD
        val isAnime = danbooruSaysAnime
        val domain: String = when {
            isAnime -> {
                val uc = upgradeCount.incrementAndGet()
                if (uc % 10L == 0L) {
                    // v43: 仅 DeepDanbooru 决策, 移除 via=CLIP/styleHits 字段.
                    AppLog.i(TAG, "anime #$uc via=Danbooru score=${"%.3f".format(dResult?.animeScore)} tags=${dResult?.topTags?.size} clipDomain=$clipDomain")
                }
                "anime"
            }
            else -> {
                // v43: 每 50 张打一次"为何非 anime"分布, 验证阈值生效.
                val realCount = tagCountReal.incrementAndGet()
                if (realCount % 50L == 0L) {
                    AppLog.i(TAG, "real #$realCount dsAnime=${dResult?.let { "%.2f".format(it.animeScore) }} clipDomain=$clipDomain → routed=$clipDomain")
                }
                clipDomain
            }
        }
        return try {
            tagCount.incrementAndGet()
            val tc = tagCount.get()
            if (tc % 30L == 0L) {
                AppLog.i(TAG, "tag #$tc clipHits=${clipHitCount.get()} clipReady=${mobileclip.isReady()}")
            }
            val subDomain: String
            val copyright: String?
            val top1Confidence: Float
            var stageWon = "none"
            when (domain) {
                "real" -> {
                    // AI_VERSION=27: MobileCLIP 恢复为主路径.
                    // v26 把 MobileCLIP 降级到最后兜底因为 cosine sim 仅 0.087 (噪声水平),
                    // 根因是 clip_prompts.bin 从错误的 PyTorch 导出生成 (open_clip MobileCLIP-S2
                    // 但 TFLite 模型来自另一次导出, 嵌入空间不匹配).
                    // v27: 用 open_clip MobileCLIP-S2 datacompdr 在 Python 端重新生成 prompt
                    // embeddings, 与 TFLite 模型 (youthfedpycharm/memoria-mobile-vision-assets
                    // 已验证 cosine ≥ 0.99997 vs PyTorch) 来自同一 PyTorch checkpoint, 嵌入
                    // 空间保证匹配. MobileCLIP 恢复为主分类器, Places365/MLKit/ImageNet 降为
                    // 兜底. 预期 cosine sim 恢复至 0.25-0.35+.
                    val p = try { places.classify(bitmap) } catch (t: Throwable) { AppLog.w(TAG, "Places365 failed", t); null }
                    val ml = try { mlKitLabeler.classify(bitmap) } catch (t: Throwable) { AppLog.w(TAG, "MLKitLabeler failed", t); null }

                    // Stage 1: 人脸优先级 (>= 2.5% area) — 硬信号, 优先于人像类
                    if (face.count >= 1 && face.areaRatio >= 0.025f) {
                        subDomain = "人像"
                        top1Confidence = ((face.count.coerceAtMost(5)) / 5f)
                        copyright = null
                        stageWon = "face"
                    } else {
                        // Stage 2: MobileCLIP 零样本子域分类 (主路径)
                        val clipSub = try { mobileclip.classifySubDomain(bitmap) }
                                      catch (t: Throwable) { AppLog.w(TAG, "MobileCLIP failed", t); null }
                        if (clipSub != null && clipSub.similarity >= CLIP_MIN_SIM) {
                            clipHitCount.incrementAndGet()
                            subDomain = clipSub.categoryZh
                            top1Confidence = clipSub.confidence
                            stageWon = "mobileclip"
                            copyright = null
                        } else if (ml != null && ml.confidence >= 0.55f) {
                            // Stage 3: MLKit 物体检测 (animal/food/document/baby)
                            subDomain = ml.subDomain
                            top1Confidence = ml.confidence
                            copyright = null
                            stageWon = "mlkit"
                        } else if (p != null && p.confidence >= 0.40f && p.sceneIdx in placesSceneLabels) {
                            // Stage 4: Places365 场景分类
                            subDomain = placesSceneLabels[p.sceneIdx] ?: "其他"
                            top1Confidence = p.confidence
                            copyright = null
                            stageWon = "places365"
                        } else {
                            // Stage 5: ImageNet 兜底
                            val v = try { vision.classify(bitmap) }
                                    catch (t: Throwable) { AppLog.w(TAG, "VisionClassifier failed", t); null }
                            if (v != null) {
                                subDomain = v.categoryZh
                                top1Confidence = v.confidence
                                copyright = null
                                stageWon = "imagenet"
                            } else {
                                subDomain = "其他"
                                top1Confidence = 0f
                                copyright = null
                                stageWon = "other"
                            }
                        }
                    }
                }
                "anime" -> {
                    subDomain = "动漫插画"
                    copyright = dResult?.characterTag
                    top1Confidence = ((dResult?.topTags?.size ?: 0).coerceAtMost(10)) / 10f
                    // danbooruTags 在下方统一序列化写入, 不论 domain 是 real/anime.
                }
                "game_screenshot" -> {
                    subDomain = "游戏画面"
                    copyright = null
                    top1Confidence = 0.7f
                }
                "digital_painting" -> {
                    subDomain = "数字插画"
                    copyright = null
                    top1Confidence = 0.7f
                }
                "meme" -> {
                    subDomain = "表情包"
                    copyright = null
                    top1Confidence = 0.7f
                }
                else -> {
                    subDomain = "其他"
                    copyright = null
                    top1Confidence = 0f
                }
            }
            // 统一序列化 Danbooru tags — 不管 domain 是 real/anime/digital_painting,
            // 只要 DanbooruTagger 跑过 (dResult != null) 就写, 让二次元分类栏目
            // 能基于 Danbooru 标签内容 (1girl/2girls/chibi/solo/monochrome 等)
            // 决定照片该进哪个桶, 而不是看 AiTagger 的 domain 分类.
            // 注意: 域内 "anime" 子域的 copyright/top1Confidence 走原路径 (上方
            // when 块), 但 danbooruTags 字段是 db 列, 跟 subDomain 无关.
            val danbooruTags: String? = dResult?.let {
                try { serializeDanbooruTags(it) }
                catch (t: Throwable) { AppLog.w(TAG, "serializeDanbooruTags failed", t); null }
            }
            val imageQuality = (face.count.coerceAtMost(3) / 3f)
            val confStr = "%.2f".format(top1Confidence)
            val clipInfo = if (stageWon == "mobileclip") confStr else "n/a"
            val score = computeScore(face.areaRatio, top1Confidence, imageQuality)
            // 每张图 stage 决策诊断 (v27)
            AppLog.d(TAG, "v27 cascade: domain=$domain sub=$subDomain stage=$stageWon clipSim=$clipInfo conf=$confStr")
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000L
            val tc2 = tagCount.get()
            if (tc2 % 10L == 0L) {
                AppLog.i(TAG, "tag #$tc2 elapsed=${elapsedMs}ms domain=$domain sub=$subDomain stage=$stageWon")
            }
            AiTagResult(domain, subDomain, copyright, face.count, face.areaRatio, score, AI_VERSION, danbooruTags)
        } catch (t: Throwable) {
            AppLog.e(TAG, "AiTagger.tag() failed", t)
            null
        }
    }

    fun computeScore(faceArea: Float, top1Confidence: Float, imageQuality: Float): Float =
        (faceArea.coerceIn(0f, 1f) * 0.4f) +
            (top1Confidence.coerceIn(0f, 1f) * 0.3f) +
            (imageQuality.coerceIn(0f, 1f) * 0.3f)

    /**
     * 序列化 Danbooru 结果为紧凑 JSON 字符串 [{t,s},...].
     *
     * v34: 适配新 DanbooruResult.topTags (List<TaggedTag>) — 不再依赖旧
     * allTags/allScores 双数组结构. characterTag 头部加 c: 前缀, 后续按
     * topTags 顺序序列化. 手写 JSON 避免 org.json 依赖.
     */
    private fun serializeDanbooruTags(d: DanbooruTagger.DanbooruResult): String {
        val sb = StringBuilder(512)
        sb.append('[')
        var first = true
        d.characterTag?.let { ch ->
            sb.append("""{"t":"c:""").append(escapeJson(ch)).append(""","s":1.0}""")
            first = false
        }
        for ((name, score) in d.topTags) {
            if (!first) sb.append(',')
            // 标签值必须用双引号包裹才是合法 JSON.
            sb.append("""{"t":"""").append(escapeJson(name)).append("""","s":""").append("%.3f".format(score)).append("}")
            first = false
        }
        sb.append(']')
        return sb.toString()
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    fun route(domain: String, @Suppress("UNUSED_PARAMETER") subDomain: String): String = domain

    companion object {
        private const val TAG = "AiTagger"
        // AI_VERSION=29: 二次元分类栏目 — 升 VERSION 强制全量重打标让
        // ai_danbooru_tags 字段填充. 现有 7332 张照片在 v28 时已打标过但
        // ai_danbooru_tags 列是 v29 新加的, 不重打该字段永远 NULL → 二次元
        // 分类除"彩色/全部"兜底桶外全部为空 (Phase 1 root cause).
        // v28: CLIP_MIN_SIM 0.15→0.10.
        // 历史: v27=MobileCLIP 主路径(嵌入空间修复), v26=Places365 主路径,
        // v25=MobileCLIP 主路径(嵌入空间失配),
        // v22=VisionClassifier 仅 food/animal/document,
        // v18=Places365 SCENE_BABY 阈值 0.40, v17=MLKit 全输出诊断 log,
        // v16=MLKit 动物桶恢复, v15=MLKit LABEL_MAP 收紧, v14=Places365 映射 +
        // Vision 颜色 heuristic, v13=Danbooru girlTop 升级移除.
        // AI_VERSION=30:
        // 1. serializeDanbooruTags 给 tag value 补了双引号 (旧版输出
        //    {"t":no_humans,"s":0.948} 是非法 JSON, parseTagSet 找不到).
        // 2. danbooruTags 改为所有 domain 都写 (旧版仅 anime 写, 287 v29
        //    "real" 照片 tags=NULL → 二次元分类栏目看不到).
        // bump 30 触发 6405 张全量重打标, 二次元分类栏目最终正常分布.
        // AI_VERSION=31: 放宽 DanbooruTagger 升级路径 (MIN_STYLE_HITS 2→1,
        // STRONG_STYLE_THRESHOLD 0.5→0.4), 让真动漫插画能升到 aiDomain=anime.
        // 配合 AnimeBuckets.isAnimeStyle() 守门, 真人照不会被引入二次元栏目.
        // AI_VERSION=32: OrtSession 池 (N=1..4) 并发推理.
        // 原计划: INT8 量化 + 4-way 并发拿 4-5x 加速. 实测 onnxruntime Android
        // 1.17 无 ConvInteger reference 实现, INT8 模型加载即崩
        // "Could not find an implementation for ConvInteger(10)". 退而求其次:
        // FP16 4-way 并发 (基于 /proc/meminfo 真实 RAM 决策, 不信 memoryClass —
        // OPPO ColorOS 把 memoryClass 压到 384MB 即便 16GB RAM 设备).
        // FP16 单 session ~700ms, N=4 → 1.4s/4张 = 350ms/张 (vs 750ms 串行) ≈ 2.1x.
        // 准确度: FP16 与 v31 完全一致 (同一模型), 动漫判别路径不变.
        // AI_VERSION=35: 修复 DanbooruTagger 预处理. 旧版做 BGR swap + raw [0,255]
        // 把图像推到训练分布外, style 标签 (comic/sketch/lineart/chibi/furry) sigmoid
        // 全部塌成 ≈0, 动漫判别永久 false. 改回 RGB + ImageNet normalize (mean=0.5,
        // std=0.5) + 重新跑 v34 写过的 800 张. 旧结果作废.
        // 1. 移除"三重锁" — 旧升级条件 hasAnimeStyle && styleHits>=2 && characterTag
        //    && !realPhotoHints 过严, styleHits 通常 1, characterTag 在 WD tagger
        //    上 50%+ null, 漏判绝大多数真动漫插画. 改为 dResult.isAnimeStyle()
        //    直接判断 (≥2 style 命中 或 单 style ≥0.5 强证据, AND 无真人黑名单).
        // 2. anime 域判定完全独立 — Danbooru 是唯一权威, DomainRouter
        //    (MobileCLIP/ImageNet/Places365) 仅决定其他域 (real/digital_painting/
        //    game_screenshot/meme).
        // 3. AnimeBuckets 完全重写 — ANIME_DISCRIMINATOR_TAGS 和桶定义只收
        //    selected_tags.csv 中实际存在的 tag, 删除 manga/anime/illustration/
        //    doujinshi/lineart-only 等 csv 不存在的 tag (WD tagger 永不输出,
        //    导致动漫判别永久失败). 配合 DanbooruTagger 新阈值 (STYLE=0.35
        //    STRONG=0.50 REAL=0.35 CHAR=0.20), 二次元分类栏目桶数应大幅上升.
        // 4. DanbooruTagger.parseResult 字段重命名 — animeStyleHits/realPhotoHits/
        //    hasStrongStyle/topTags 替代旧 allTags/allScores/styleHits/realPhotoHints.
        // bump 34 触发 6405 张全量重打标, ai_danbooru_tags 重新生成.
        // AI_VERSION=41: 加 AnimeBuckets 风格 tag 守门, 修真人照假阳.
        // v40 单纯 animeScore>=0.5 在真人/QR码上也命中 (DeepDanbooru 训练集纯动漫),
        // 把真人照打成 aiDomain="anime" → 进二次元桶污染. v41 加 ANIME_DISCRIMINATOR_TAGS
        // (comic/sketch/chibi/furry/lineart/traditional_media/watercolor/marker/
        // flat_color/oekaki/4koma/doujin_cover/kemonomimi_mode) 守门: Danbooru tags
        // 至少 1 个命中才算动漫.
        // AI_VERSION=42: 修 MobileCLIP-S2 归一化方向 — v41 把 encodeImage 改为
        // (x-0.5)/0.5 推到 [-1,1] 范围, 实测 TFLite 输出 512/512 全 NaN, DomainRouter
        // 永远兜底成 "real" 或抛错, cos sim 噪声水平. 根因: open_clip datacompdr
        // 训练时 image_mean=(0,0,0) image_std=(1,1,1) 即"不做归一化", 模型期望原始
        // [0,1] 像素. 改回 pixel/255f 后 nNaN=0/512, sumSq≈1.0, 嵌入有效, cos sim
        // 恢复到 0.20-0.35 区间. 同时 AiModelHub.loadMobileclip 重新启用 XNNPACK
        // (FP16 量化模型禁用 XNNPACK 时全部 NaN). bump 42 触发全量重打标.
        // AI_VERSION=44: 修复 DanbooruTagger double-sigmoid bug (v38 起的隐 bug).
        // KichangKim DeepDanbooru v3 ONNX 模型最后层已 sigmoid (官方 TF 用 sigmoid 激活),
        // 但 v38-v43 代码手动 sigmoid 一次, 把 [0,1] 概率压成 [0.5, 0.731] 区间 —
        // 实测 anime 与 real photo 都 safe≈0.730 ques≈0.500 expl≈0.500, 所有照片
        // animeScore≈1.73 触发 anime 误判, 整个 AI 分类错乱.
        //
        // v44 修复:
        //  1. DanbooruTagger.parseResult 移除手动 sigmoid (rating 值 + topTags 都改用
        //     logits 直接作为概率).
        //  2. animeScore 改为 max(anime-discriminator tag prob) — 比 rating 求和更
        //     准确: rating:safe 对真人/动漫都 ≈0.95 无区分度; 1girl/solo/comic 等
        //     anime tag 在动漫图 ≥0.95, 真人/风景 ≤0.05.
        //  3. AI_VERSION=44 触发全量重打标, 预期真实照片回归 real 域, 二次元照片
        //     进 anime 域.
        // AI_VERSION=44 (并发容量策略已在 44 发布): OrtSessionPool 容量策略改写
        // (不影响分类准确性, 仅性能). 之前 v32-v43 用 ActivityManager.memoryClass
        // (ColorOS 强制报 384MB) → 永远 capacity=1, 单 session ~800ms/张. 改读
        // /proc/meminfo TotalRam + MemAvailable, 按 budget = MemAvailable/2 计算.
        // OPPO 16GB 设备解锁 2-way 并发 (~400ms/张, 2x speedup). 上限 2 是因为
        // ColorOS per-process RSS cap 普遍 ~1.5GB, 3 session × 700MB 会 OOM.
        //
        // AI_VERSION=45: 角色标签提取. DanbooruTagger.parseResult 中 characterTag
        // 从硬编码 null 改为从 topTags 提取 (extractCharacterTag: 含 '_' + 不在
        // CHARACTER_EXCLUDE 黑名单 + 非身体部位后缀). 提取受 animeScore ≥ 0.30
        // 守门 — 非动漫图 (真人/风景/截图) 返回 null, 防止 phone_screen 等伪
        // 角色污染桶. serializeDanbooruTags 输出 c:xxx 条目, 填充 anime 角色桶.
        // bump 45 触发全量重打标让角色信息写入.
        const val AI_VERSION = 45
        private const val ANIME_DOMAIN_THRESHOLD = 0.5f
        // AI_VERSION=23: 从 placesSceneLabels 移除 SCENE_DOCUMENT — v22 28/79=35%
        // 仍误归"文档", 因为 Places365 SCENE_DOCUMENT (archive/library/bookstore)
        // 在 ≥0.40 阈值后仍把办公室/书架误判文档. 文档类改为只由 ImageNet
        // IMAGENET_TO_SCENE (binder/notebook/comic_book/book_jacket/envelope) 提供.
        // AI_VERSION=22: VisionClassifier IMAGENET_TO_SCENE DOCUMENT 桶收紧 — v21
        // 33/98=34% 被误归"文档". 删除 menu(922)/plate(923)/beach_wagon(436)/
        // canoe(472)/carton(478)/cassette_player(502)/mailbox(678)/paper_towel(700)/
        // web_site(916)/bow(458) 等物体类. 仅保留 binder(446)/envelope(549)/
        // notebook(681)/comic_book(917)/book_jacket(921) 真正文档/书.
        // AI_VERSION=21: SmartVisionApp.onCreate 中 main looper 预热 MLKit
        // labeler+face detector process() (1x1 stub) — v20 withContext(Main)
        // 仍崩 "addObserver must be called on main thread" 因为 MLKit 内部
        // PipelineManager.start 在 lazy first process 调用时才注册. Prewarm 在
        // 真正 main thread 上触发, 让 init 一次完成, 后续 worker 调用命中已注册 pipe.
        // AI_VERSION=20: MlKitImageLabeler + MlKitFaceAnalyzer 切到 Dispatchers.Main
        // 调用 detector.process() — v17/v18 MLKit 抛 "Method addObserver must be
        // called on the main thread" 致 labeler 永久 disable, 人脸/物体识别全停.
        // v19 移除 Places365 SCENE_BABY 但 MLKit 仍崩, 故 BABY/ANIMAL/FOOD/DOCUMENT/
        // PERSON 全靠 ImageNet + 颜色 heuristic 兜底. v20 修复 main thread 后 MLKit
        // 物体/人脸识别回归, 期望分布显著改善.
        // AI_VERSION=19: 从 placesSceneLabels 移除 SCENE_BABY — v18 证实 Places365
        // 对 childs_room 输出 conf>0.4 仍假阳高 (40/195=20%). BABY 仅由 MLKit
        // "baby"/"infant"/"toddler" 物体 label 提供, 与 Places365 场景解耦.
        // 同时 MlKitImageLabeler 改为连续 5 次失败才 disable (v17/v18 偶发
        // "MlKit Internal error" 后整轮都 fallback 到 Places365).
        // AI_VERSION=18: AiTagger cascade 给 Places365 加硬阈值 0.40 — v14+v16
        // 缺阈值时 conf=0.103 的 childs_room 被采纳为 SCENE_BABY, 508 张宝宝
        // 误判主因. 同时加 MLKit label 诊断 log (MlKitImageLabeler diagCount).
        // AI_VERSION=17: 临时加 MLKit label 全输出诊断 log (MlKitImageLabeler
        // diagCount % 5==0 && cnt<=200 时打 all=[labels]).
        // AI_VERSION=16: 恢复 MLKit 动物桶 mammal/animal/pet 泛类 — v15 全删导致
        // 动物桶只有 1 张, 真实宠物照漏判. 这三个泛类 MLKit 在风景照上置信
        // <0.3, 0.55 阈值已能筛掉噪声.
        // AI_VERSION=15: MLKit LABEL_MAP 大幅收紧 — 移除 baby/food/document 桶里
        // 的泛类目标签 (toy/doll/stroller/food/meal/drink/poster/print/stationery/
        // paper/page/folder/list/certificate/bill/contract/label/pastry/bakery 等).
        // 这些泛类对任何含物体的照片 MLKit 都给高置信 (>0.55), 导致 v14 大量
        // 风景/screenshot 误归 "宝宝" (508/1356=37% 假阳性). 只保留明确特化物件
        // 标签让 MLKit 在 cascade 中承担精确物体识别, 不再泛化保底.
        // AI_VERSION=14: 收紧 Places365 场景映射 + 降低人脸/MLKit 阈值 +
        // 限制 VisionClassifier 颜色 heuristic 仅在 ImageNet 完全失配时启用 +
        // 移除 ImageNet 中弱 sunset/snow/sky 映射 (与 Places365 冲突). 目标:
        // 71.8% 室内假阳性降至 ≤40%, 人像/夜景/食物/植物等关键类恢复到 ≥5%.
        // AI_VERSION=13: 移除 DanbooruTagger girlTop 升级路径 — WD tagger 对真人照
        // 1girl/solo sigmoid 也常 ≥0.99 (与真动漫分布重合), 此路径是 v12 误升级主因.
        // 升动漫仅靠 styleHits>=2 + strongSingle + NOT realPhotoHints.

        /**
         * 推理用统一短边尺寸. Vision/MobileCLIP 都吃 224×224,
         * Danbooru 需要 448×448 (内部再缩), MLKit Face 用原图大小
         * 但 224 短边已足够检出大脸 (>15% image area).
         * 调用方 decode 后用此常量做 inSampleSize 计算.
         *
         * 注意: MobileCLIP-S2 image encoder 自身吃 256×256, 但调用方先
         * downsample 到 224 短边足够保存语义且节省内存; MobileClipClassifier
         * 内部会再缩放到 256.
         */
        const val INFERENCE_SHORT_EDGE = 224

        /** MobileCLIP 余弦相似度最小阈值.
         *  v42: 归一化修复为 [0,1] 后, 实测匹配类对 cos sim 0.20-0.35+, 非匹配 0.05-0.15.
         *  v44: 还原合理阈值 0.15f — v41 临时降为 0.0 是 debug 留场, 导致 cascade
         *  Stage 2 永远胜出, 即便 MobileCLIP 输出噪声 argmax (sim≈0.05) 也采纳.
         *  0.15f 是匹配/非匹配分界点, 真匹配 sim≥0.15 命中, 噪声 sim<0.15 落兜底.
         */
        private const val CLIP_MIN_SIM = 0.15f
    }
}
