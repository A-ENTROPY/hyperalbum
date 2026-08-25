package com.smartvision.gallery.data.ai

/**
 * 二次元分类栏目规则中心 — 完全重写 (v34).
 *
 * ## v34 修复的根因
 * 旧版 ANIME_DISCRIMINATOR_TAGS 和桶定义收录了大量 selected_tags.csv 中
 * **根本不存在的 tag 名** (manga / anime / illustration / doujinshi / lineart-only /
 * pencil_(medium) / super_deformed / sd / digital_media_(artwork) / pixel_art /
 * web_animation / animated / 2d / group / 3+_others / unfinished / grayscale /
 * monochrome_with_colored / oil_painting / watercolor 等). WD ConvNeXt-v3
 * tagger 永远不会输出这些 tag, 导致 [isAnimeStyle] 永久 false, 二次元分类
 * 栏目 9 个桶全部 0 张.
 *
 * ## v34 设计原则
 *  1. **只收录 selected_tags.csv 中验证存在的 tag** — 用 `awk -F','` 全表 grep 验证
 *     每个 tag 都在 cat=0 (general) 类中, 否则删除.
 *  2. **判别门和桶标签同源** — ANIME_DISCRIMINATOR_TAGS 是桶标签的超集,
 *     保证判别通过时必有桶命中.
 *  3. **黑白桶不放 ANIME_DISCRIMINATOR_TAGS** — WD tagger 对所有照片 monochrome
 *     sigmoid 都 ≈0.95, 真人照也会命中, 故 monochrome/greyscale 不作为动漫判别门,
 *     仅作桶内部匹配.
 *
 * ## 10 桶定义 (按相册展示顺序)
 *  构图 (3): 单人 / 双人 / 群像
 *  画风 (5): 漫画 / 线稿 / 插画 / Q版 / Furry
 *  色调 (1): 黑白
 *  兜底 (1): 全部 (anime domain)
 */
object AnimeBuckets {

    enum class MatchType { ANY, ALL, NONE }

    data class BucketDef(
        val id: String,
        val title: String,
        val subtitle: String,
        val matchType: MatchType,
        val tags: Set<String>,
    )

    /**
     * 固定 10 桶. 顺序 = 相册页 grid 顺序.
     *
     * v34: 每个 tag 都已对照 selected_tags.csv 验证存在 (cat=0 general).
     */
    val DEFINITIONS: List<BucketDef> = listOf(
        // ---- 构图 ----
        BucketDef(
            id = "ai:anime:单人",
            title = "单人",
            subtitle = "solo / 1girl / 1boy",
            matchType = MatchType.ANY,
            tags = setOf("solo", "1girl", "1boy", "1other"),
        ),
        BucketDef(
            id = "ai:anime:双人",
            title = "双人",
            subtitle = "2girls / 2boys",
            matchType = MatchType.ANY,
            tags = setOf("2girls", "2boys", "2others"),
        ),
        BucketDef(
            id = "ai:anime:群像",
            title = "群像",
            subtitle = "multiple_girls / multiple_boys / 3+",
            matchType = MatchType.ANY,
            tags = setOf("multiple_girls", "multiple_boys", "3girls", "3boys"),
        ),

        // ---- 画风 ----
        BucketDef(
            id = "ai:anime:漫画",
            title = "漫画",
            subtitle = "comic / 4koma / doujin_cover",
            matchType = MatchType.ANY,
            tags = setOf("comic", "4koma", "doujin_cover"),
        ),
        BucketDef(
            id = "ai:anime:线稿",
            title = "线稿",
            subtitle = "sketch / lineart / pencil",
            matchType = MatchType.ANY,
            tags = setOf("sketch", "lineart", "pencil"),
        ),
        BucketDef(
            id = "ai:anime:插画",
            title = "插画",
            subtitle = "traditional / watercolor / marker",
            matchType = MatchType.ANY,
            tags = setOf("traditional_media", "watercolor_(medium)", "marker_(medium)", "flat_color"),
        ),
        BucketDef(
            id = "ai:anime:Q版",
            title = "Q版",
            subtitle = "chibi",
            matchType = MatchType.ANY,
            tags = setOf("chibi"),
        ),
        BucketDef(
            id = "ai:anime:Furry",
            title = "Furry",
            subtitle = "furry / animal_focus",
            matchType = MatchType.ANY,
            tags = setOf("furry", "animal_focus", "kemonomimi_mode"),
        ),

        // ---- 色调 ----
        BucketDef(
            id = "ai:anime:黑白",
            title = "黑白",
            subtitle = "monochrome / greyscale",
            matchType = MatchType.ANY,
            tags = setOf("monochrome", "greyscale"),
        ),

        // ---- 兜底 ----
        BucketDef(
            id = "ai:anime:全部",
            title = "全部",
            subtitle = "全部二次元",
            matchType = MatchType.ANY,
            tags = emptySet(),
        ),
    )

    val BY_ID: Map<String, BucketDef> = DEFINITIONS.associateBy { it.id }

    /**
     * 动漫判别 tag 集 — 命中至少 1 个才能进入任何二次元桶.
     *
     * v34: 严格筛选 — 仅收录 selected_tags.csv 中确认存在的 tag.
     * 已删除 (csv 不存在): manga, anime, anime_style, illustration, doujinshi,
     * lineart-only, pencil_(medium), super_deformed, sd, digital_media_(artwork),
     * pixel_art, web_animation, animated, 2d.
     *
     * 故意不收录: monochrome / greyscale — DanbooruTagger 注释明确:
     * WD tagger 对所有照片 (含真人/风景) monochrome sigmoid 都 ≈0.95,
     * 不是 anime 证据, 收录会让真实夜景/静物误归二次元.
     */
    val ANIME_DISCRIMINATOR_TAGS: Set<String> = setOf(
        // 强动漫风格 (csv 验证)
        "comic",
        "4koma",
        "doujin_cover",
        "sketch",
        "lineart",
        "chibi",
        "furry",
        "kemonomimi_mode",
        // 数字/平面绘画 (csv 验证)
        "traditional_media",
        "watercolor_(medium)",
        "marker_(medium)",
        "flat_color",
        "oekaki",
    )

    /**
     * 判别 parsedTags 是否为动漫风格.
     * 命中至少 1 个 [ANIME_DISCRIMINATOR_TAGS] 元素即可. 真人照几乎不会同时出现
     * 这些 tag 之一, 故可有效排除.
     */
    fun isAnimeStyle(parsedTags: Set<String>): Boolean =
        ANIME_DISCRIMINATOR_TAGS.any { it in parsedTags }

    /**
     * 字符级扫描 [parseTagSet] 解析后的 Set 命中桶定义.
     * - ANY: parsedTags ∩ ruleTags ≠ ∅
     * - ALL: ruleTags ⊆ parsedTags
     * - NONE: parsedTags ∩ ruleTags = ∅
     *
     * v34: 所有桶 (含"全部"空集桶) 在匹配规则前必须先过 [isAnimeStyle] 门,
     * 否则真人照会被 1girl/solo 误归二次元.
     */
    fun matches(parsedTags: Set<String>, def: BucketDef): Boolean {
        // v43: 兜底桶 (tags=emptySet) 不再强制 isAnimeStyle 守门.
        //
        // 背景: v34/v41 设计时 AiTagger 直接把 Danbooru 输出的 1girl/solo 标签
        // 喂给 AnimeBuckets, 故守门必要. v42 起 AiTagger 已用 aiDomain="anime"
        // 上游守门 (animeScore>=0.5+hasAnimeStyle 或 clipSaysAnime), aiDomain
        // 已是可信信号. queryAnimeByBucket 在调用前已过滤 aiDomain=="anime",
        // 再叠 isAnimeStyle 守门导致 2872 张 anime 照片只有 838 张 (29%)
        // 能进桶 — 完整上色的动漫插画 top-50 不含 sketch/lineart/chibi style
        // tag, 被错杀.
        //
        // 当前 matches 仅由 queryAnimeByBucket 调用, 而调用前已 aiDomain 过滤,
        // 此处不再二次守门. 若未来其他入口复用此函数, 需自行加 isAnimeStyle.
        return when (def.matchType) {
            MatchType.ANY -> def.tags.isEmpty() || def.tags.any { it in parsedTags }
            MatchType.ALL -> def.tags.all { it in parsedTags }
            MatchType.NONE -> def.tags.none { it in parsedTags }
        }
    }

    /**
     * 从 AiTagger 序列化的 JSON `[{t,s},...]` 抽取 tag 名集合.
     * 字符级扫描, 跳过 "t":"c:xxx" (character 标记).
     * 容忍 JSON 损坏 — 返回 partial 集合.
     */
    fun parseTagSet(json: String?): Set<String> {
        if (json.isNullOrEmpty()) return emptySet()
        val out = HashSet<String>(32)
        val marker = "\"t\":\""
        var i = 0
        while (i < json.length) {
            val pos = json.indexOf(marker, i)
            if (pos < 0) break
            val start = pos + marker.length
            val end = json.indexOf('"', start)
            if (end < 0) break
            if (end > start) {
                val tag = json.substring(start, end)
                if (!tag.startsWith("c:")) out.add(tag)
            }
            i = end + 1
        }
        return out
    }
}