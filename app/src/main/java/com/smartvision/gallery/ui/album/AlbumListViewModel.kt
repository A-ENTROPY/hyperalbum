package com.smartvision.gallery.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartvision.gallery.data.ai.AnimeBuckets
import com.smartvision.gallery.data.model.Album
import com.smartvision.gallery.data.model.AlbumKind
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.data.repo.MediaRepository
import com.smartvision.gallery.data.repo.MemoryCluster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AlbumListViewModel(private val repository: MediaRepository) : ViewModel() {

    /**
     * Raw streams are pushed off the main thread onto Dispatchers.Default.
     *
     * Why: observeSmartAlbums runs an O(n) groupBy + multiple O(n) filters +
     * maxOf scans over the full timeline on every emit. On large libraries
     * (5k+ items) that is 50-200ms of CPU — the entire frame budget for a
     * single 60Hz tick. Doing it on Default keeps the main thread free for
     * Compose composition / layout / draw while the heavy lifting runs in
     * parallel. stateIn's collector lands on Main as Compose requires.
     */
    private val rawAlbums: StateFlow<List<Album>> = repository.observeSmartAlbums()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Three pre-sliced subsets consumed directly by the page — the page used
     * to call `albums.filter { ... }` inside the composable on every
     * recomposition. Each filter is O(n) over the full album list. By
     * deriving once here, the page only re-snapshots when a subset actually
     * changes (distinctUntilChanged), and Compose does zero work for the
     * other two.
     */
    val pinnedAlbums: StateFlow<List<Album>> = rawAlbums
        .map { list ->
            list.filter {
                it.kind == AlbumKind.FAVORITES ||
                    it.kind == AlbumKind.HIDDEN_VAULT ||
                    it.kind == AlbumKind.TRASH
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val mediaAlbums: StateFlow<List<Album>> = rawAlbums
        .map { list ->
            list.filter {
                it.kind !in PINNED_KINDS
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Hero memories — top 8 photos with screenshots stripped. We
     * truncate to 8 EARLY so downstream listeners only ever see an 8-item
     * list; without that cap, every timeline emit reshuffles the whole
     * 5k-item list through `.filterNot().take(8)` even though the
     * resulting 8 are unchanged 99% of the time.
     *
     * The distinctUntilChanged after take(8) means the UI collector only
     * fires when at least one of those 8 actually changed — eliminating
     * the recompose storm that used to happen whenever a flag flip nudged
     * the timeline Flow.
     */
    val memoryPhotos: StateFlow<List<MediaItem>> = repository.observeTimeline()
        .map { items ->
            // Single-pass filter + truncate — avoids allocating intermediate
            // lists for filter+filter+take (which Coi/Kotlin chains otherwise
            // produce as 3 lists of full size before truncating).
            val out = ArrayList<MediaItem>(8)
            for (it in items) {
                if (it.isScreenshot) continue
                out.add(it)
                if (out.size == 8) break
            }
            out
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Pre-computed curated collections for the "精选集" rows.
     *
     * 之前 7 个 AI 卡片 (本周精选/人像/游戏画面/动漫插画 等) 已全部移至
     * [aiCategoryFolders] 15 分类行, 避免双份展示. 精选集现在只剩:
     *  * [memoryClusters]  — 回忆之旅 spacetime clusters
     *
     * ai:thisWeekReal / ai:portraits / ai:animeChars / ai:animeArt 等虚拟
     * albumId 仍保留在 [AlbumDetailPage] dispatch 表, 用户可从 AI 智能分类
     * 卡片跳转打开.
     */
    val memoryClusters: StateFlow<List<MemoryCluster>> = repository.queryMemoriesTimeline()
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val formatChallenge: StateFlow<List<MediaItem>> = repository.queryFormatChallenge()
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Format challenge CoverUri for the header card. */
    val formatChallengeCover: StateFlow<android.net.Uri?> = formatChallenge
        .map { it.firstOrNull()?.uri }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * 15 个 AI 分类文件夹, 每张图按 aiSubDomain 落到对应文件夹. 单次
     * O(n) 扫描拿 15 个桶, 一次 emit 触发一次 recompose (vs 15 个独立
     * query StateFlow 触发 15 次). 每个桶只取 1 张 cover, 总内存 O(15).
     */
    val aiCategoryFolders: StateFlow<List<CuratedCollection>> = repository.observeTimeline()
        .map { items ->
            // 用 buckets: LinkedHashMap 保持顺序, 单次扫描.
            val bucketCover = LinkedHashMap<String, android.net.Uri?>(AI_CATEGORIES.size)
            val bucketCount = HashMap<String, Int>(AI_CATEGORIES.size)
            AI_CATEGORIES.forEach { (sub, _, _, albumId) ->
                bucketCover[albumId] = null
                bucketCount[albumId] = 0
            }
            var untaggedCount = 0
            var untaggedCover: android.net.Uri? = null
            // 单次扫描填 cover + count. aiVersion=0 的照片进 "未处理" 桶
            // (用户能看到待打标的总量, 点击直接进入 timeline 即可).
            for (item in items) {
                if ((item.aiVersion ?: 0) <= 0) {
                    untaggedCount++
                    if (untaggedCover == null) untaggedCover = item.uri
                    continue
                }
                val sub = item.aiSubDomain ?: continue
                val albumId = SUB_TO_ALBUM_ID[sub] ?: continue
                if (bucketCount[albumId] == 0) {
                    bucketCover[albumId] = item.uri
                }
                bucketCount[albumId] = (bucketCount[albumId] ?: 0) + 1
            }
            // 按 AI_CATEGORIES 顺序产出, count=0 也保留 (空文件夹用户能看到)
            val tagged = AI_CATEGORIES.map { (sub, title, _, albumId) ->
                CuratedCollection(
                    title = title,
                    coverUri = bucketCover[albumId],
                    count = bucketCount[albumId] ?: 0,
                    albumId = albumId,
                )
            }
            // "未处理" 桶追加在最末 — 用户直观看到待打标量, AI worker 跑完后
            // 这条会自然减为 0. albumId="ai:untagged" → AlbumDetailPage 走
            // observeTimeline() 显示全部未分类照片.
            tagged + CuratedCollection(
                title = "未处理",
                coverUri = untaggedCover,
                count = untaggedCount,
                albumId = "ai:untagged",
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 二次元分类栏目 (v29 重设计): 10 固定桶 + 角色专辑 (top-5).
     *
     * 桶定义集中在 [AnimeBuckets] (data.ai) — 此处只消费, 不再各自定义.
     * 匹配用 parsed Set 等值检查, 不再做 substring 判断, 杜绝 "group" 误
     * 命中 "group_(of_people)" 之类子串假阳.
     *
     * 性能:
     *  - 单次扫描 items, 每张图 parseTagSet 一次 (O(该图 tag 数)).
     *  - 每张图对 10 个桶做 matches, 每桶 O(1)~O(小集合), 整体 O(n × 10).
     *  - character tag 抽取复用原始 JSON 找 "t":"c:xxx" 子串 (角色桶专属).
     */
    val animeCategoryFolders: StateFlow<List<CuratedCollection>> = repository.observeTimeline()
        .map { items ->
            val defs = AnimeBuckets.DEFINITIONS
            val bucketCover = LinkedHashMap<String, android.net.Uri?>(defs.size)
            val bucketCount = HashMap<String, Int>(defs.size)
            defs.forEach { def ->
                bucketCover[def.id] = null
                bucketCount[def.id] = 0
            }
            // 角色桶: character 名 → (cover, count)
            data class CharBucket(var cover: android.net.Uri?, var count: Int)
            val charBuckets = HashMap<String, CharBucket>()

            // v39: items 先按 aiScore 降序, 确保封面用高质量图.
            // queryAnimeByBucket 内也按 score 排序, 与外部封面选择一致.
            val sortedItems = items.sortedByDescending { it.aiScore ?: 0f }
            for (item in sortedItems) {
                if (item.aiDomain != "anime") continue
                if ((item.aiVersion ?: 0) <= 0) continue
                val json = item.aiDanbooruTags ?: ""
                if (json.isEmpty()) continue
                val tagSet = AnimeBuckets.parseTagSet(json)
                if (tagSet.isEmpty()) continue
                // v43: 移除 isAnimeStyle 守门 — aiDomain="anime" 已在 AiTagger
                // 上游由 DeepDanbooru animeScore>=0.5 单一决策, 此处不二次守门.

                for (def in defs) {
                    if (AnimeBuckets.matches(tagSet, def)) {
                        if (bucketCount[def.id] == 0) bucketCover[def.id] = item.uri
                        bucketCount[def.id] = (bucketCount[def.id] ?: 0) + 1
                    }
                }
                extractCharacterTagsFromJson(json).forEach { ch ->
                    val b = charBuckets.getOrPut(ch) { CharBucket(null, 0) }
                    if (b.cover == null) b.cover = item.uri
                    b.count++
                }
            }

            val fixedBuckets = defs.map { def ->
                CuratedCollection(
                    title = def.title,
                    coverUri = bucketCover[def.id],
                    count = bucketCount[def.id] ?: 0,
                    albumId = def.id,
                )
            }
            // 角色专辑: 按 count 取 top-5 (至少 2 张同角色才出现, 避免噪声)
            val charAlbums = charBuckets.entries
                .filter { it.value.count >= 2 }
                .sortedByDescending { it.value.count }
                .take(5)
                .map { (ch, b) ->
                    CuratedCollection(
                        title = "角色· $ch",
                        coverUri = b.cover,
                        count = b.count,
                        albumId = "ai:anime:角色:$ch",
                    )
                }
            fixedBuckets + charAlbums
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        private val PINNED_KINDS = setOf(
            AlbumKind.FAVORITES,
            AlbumKind.HIDDEN_VAULT,
            AlbumKind.TRASH,
        )

        /**
         * 15 个 AI 分类文件夹元数据. 顺序就是相册页展示顺序.
         * (subDomain, 显示标题, 副标题, albumId).
         * subDomain 必须与 AiTagger / VisionClassifier 写库值一致.
         */
        private data class CategoryDef(
            val subDomain: String,
            val title: String,
            val subtitle: String,
            val albumId: String,
        )

        private val AI_CATEGORIES: List<CategoryDef> = listOf(
            CategoryDef("动物", "动物", "毛孩子/萌宠/野生动物", "ai:sub:动物"),
            CategoryDef("建筑", "建筑", "城市/教堂/桥/古建", "ai:sub:建筑"),
            CategoryDef("植物", "植物", "花卉/树木/绿植", "ai:sub:植物"),
            CategoryDef("夕阳", "夕阳", "黄昏/日落/暖色天空", "ai:sub:夕阳"),
            CategoryDef("其他", "其他", "未明确归类", "ai:sub:其他"),
            CategoryDef("室内", "室内", "家居/餐厅/客厅", "ai:sub:室内"),
            CategoryDef("动漫插画", "动漫插画", "二次元插画/同人", "ai:sub:动漫插画"),
            CategoryDef("水面", "水面", "海/湖/河/水池", "ai:sub:水面"),
            CategoryDef("雪景", "雪景", "雪山/雪原/冰雪", "ai:sub:雪景"),
            CategoryDef("食物", "食物", "美食/甜点/饮品", "ai:sub:食物"),
            CategoryDef("文档", "文档", "票据/书页/屏幕截图", "ai:sub:文档"),
            CategoryDef("人像", "人像", "人物/自拍/合影", "ai:sub:人像"),
            CategoryDef("天空", "天空", "蓝天/云/星空", "ai:sub:天空"),
            CategoryDef("宝宝", "宝宝", "婴儿/儿童", "ai:sub:宝宝"),
            CategoryDef("游戏画面", "游戏画面", "游戏截图/界面", "ai:sub:游戏画面"),
        )

        private val SUB_TO_ALBUM_ID: Map<String, String> = AI_CATEGORIES
            .associate { it.subDomain to it.albumId }

        /**
         * 从 JSON 中抽取 character tag, 形如 {"t":"c:xxx","s":1.0}.
         * 用简单子串扫描 — Danbooru tags 不含双引号, 安全.
         * 返回去 c: 前缀的纯净 character 名.
         */
        private fun extractCharacterTagsFromJson(json: String): List<String> {
            val marker = "\"t\":\"c:"
            val out = ArrayList<String>(2)
            var idx = 0
            while (true) {
                val pos = json.indexOf(marker, idx)
                if (pos < 0) break
                val start = pos + marker.length
                val end = json.indexOf('"', start)
                if (end < 0) break
                if (end > start) out.add(json.substring(start, end))
                idx = end + 1
            }
            return out
        }

        fun factory(repository: MediaRepository) = viewModelFactory {
            initializer { AlbumListViewModel(repository) }
        }
    }
}

/**
 * Pre-computed view-model for one card in the "精选集" row. Carries
 * everything the card needs so the composable does zero per-frame
 * computation — just an `AsyncThumbnail` + two `Text`s.
 */
data class CuratedCollection(
    val title: String,
    val coverUri: android.net.Uri?,
    val count: Int,
    val albumId: String,
)
