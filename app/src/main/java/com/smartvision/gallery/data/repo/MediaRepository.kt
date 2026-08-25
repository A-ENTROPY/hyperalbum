package com.smartvision.gallery.data.repo

import android.content.Context
import android.net.Uri
import com.smartvision.gallery.data.db.AlbumDao
import com.smartvision.gallery.data.db.AlbumEntity
import com.smartvision.gallery.data.db.BucketAlbumRow
import com.smartvision.gallery.data.db.FavoritesMetaRow
import com.smartvision.gallery.data.db.FlagBackupManager
import com.smartvision.gallery.data.db.FormatAlbumRow
import com.smartvision.gallery.data.db.MediaDao
import com.smartvision.gallery.data.db.MediaEntity
import com.smartvision.gallery.data.db.MediaFlagDao
import com.smartvision.gallery.data.db.MediaItemWithFlags
import com.smartvision.gallery.data.model.Album
import com.smartvision.gallery.data.model.AlbumKind
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.data.model.TrashEntry
import com.smartvision.gallery.decoder.format.MediaFormat
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MediaRepository(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val mediaFlagDao: MediaFlagDao,
    private val albumDao: AlbumDao,
    private val flagBackup: FlagBackupManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val timelineCache = MutableStateFlow<List<MediaItem>>(emptyList())

    init {
        scope.launch {
            mediaDao.observeTimeline()
                .map { it.map(::toModel) }
                .distinctUntilChanged()
                .collect { timelineCache.value = it }
        }
    }

    // -- Read streams --------------------------------------------------------------

    fun observeTimeline(): Flow<List<MediaItem>> = timelineCache

    fun observeByFormat(format: MediaFormat): Flow<List<MediaItem>> =
        mediaDao.observeByFormat(format.name).map { it.map(::toModel) }

    fun observeBucket(bucketPath: String): Flow<List<MediaItem>> =
        mediaDao.observeByBucket(bucketPath).map { it.map(::toModel) }

    fun observeFavorites(): Flow<List<MediaItem>> =
        mediaDao.observeFavorites().map { it.map(::toModel) }

    fun observeHidden(): Flow<List<MediaItem>> =
        mediaDao.observeHidden().map { it.map(::toModel) }

    fun observeUntagged(): Flow<List<MediaItem>> =
        mediaDao.observeUntagged().map { it.map(::toModel) }

    fun observeTrash(): Flow<List<MediaItem>> =
        mediaDao.observeTrash().map { it.map(::toModel) }

    /**
     * Trash page stream: pairs each [MediaItem] with the timestamp it was trashed,
     * so the UI can render "还剩 X 天" badges and trigger 30-day auto-purge.
     */
    fun observeTrashEntries(): Flow<List<TrashEntry>> =
        mediaDao.observeTrash().map { rows ->
            rows.map { row ->
                TrashEntry(
                    item = toModel(row),
                    trashedAtMs = row.flagUpdatedAt
                )
            }
        }

    fun observeSearch(q: String): Flow<List<MediaItem>> =
        mediaDao.search(q).map { it.map(::toModel) }

    fun observeUsedFormats(): Flow<List<MediaFormat>> =
        mediaDao.observeUsedFormats().map { names ->
            names.mapNotNull { runCatching { MediaFormat.valueOf(it) }.getOrNull() }
        }

    /**
     * Live count of items currently in trash. Source of truth for the
     * "回收站" badge — see [MediaFlagDao.observeTrashCount] for rationale.
     */
    fun observeTrashCount(): Flow<Int> = mediaDao.observeTrashCount()

    fun observeHiddenCount(): Flow<Int> = mediaFlagDao.observeHiddenCount()

    // -- Albums --------------------------------------------------------------------

    fun observeSmartAlbums(): Flow<List<Album>> {
        // SQL 上推版：每个聚合流由 SQLite 内核用 GROUP BY + 索引扫描完成，
        // 输出只 N 行（N = format 数 + bucket 数 + 3 个固定专辑），而不是
        // 5k+ MediaItem。再由 Kotlin 端把四路 Flow 合并成 List<Album>。
        //
        // 收益对比（5k items，8 formats，~30 buckets）：
        //  旧：timeline 5k emit → items.groupBy(5k) + formats.map×items.filter(×5k)
        //     + items.count(favorite) + items.filter(fav).maxOf → ~5×5k 比较在 Default
        //     dispatcher 单线程上跑 50-200ms。
        //  新：四路 SQL 直接到 daemon thread，每路 O(n) 但在 C 引擎 + 覆盖索引上跑，
        //     总结果 N≈40 行，Kotlin 端只构造 40 个 Album 对象。
        return combine(
            mediaDao.observeFormatAlbums(),
            mediaDao.observeBucketAlbums(),
            mediaDao.observeFavoritesMeta(),
            mediaDao.observeTrashCount(),
            mediaDao.observeHiddenCount(),
        ) { formatRows, bucketRows, favMeta, trashCount, hiddenCount ->
            val formatAlbums = formatRows.map { row ->
                val fmt = runCatching { MediaFormat.valueOf(row.format) }
                    .getOrDefault(MediaFormat.UNKNOWN)
                Album(
                    id = "format:${fmt.name}",
                    name = "所有 ${fmt.displayName} 图片",
                    kind = AlbumKind.FORMAT_FILTER,
                    coverUri = row.cover_uri?.let(Uri::parse),
                    itemCount = row.item_count,
                    latestDateMs = row.latest_date_ms,
                    formatFilter = fmt,
                )
            }
            val bucketAlbums = bucketRows.map { row ->
                val key = row.bucket_path ?: "unknown"
                Album(
                    id = "bucket:$key",
                    name = row.bucket_name ?: key,
                    kind = AlbumKind.BUCKET,
                    coverUri = row.cover_uri?.let(Uri::parse),
                    itemCount = row.item_count,
                    latestDateMs = row.latest_date_ms,
                    bucketPath = row.bucket_path,
                )
            }
            val fav = Album(
                id = "favorites",
                name = "收藏",
                kind = AlbumKind.FAVORITES,
                coverUri = favMeta?.cover_uri?.let(Uri::parse),
                itemCount = favMeta?.item_count ?: 0,
                latestDateMs = favMeta?.latest_date_ms ?: 0L
            )
            val hidden = Album(
                id = "hidden",
                name = "隐私空间",
                kind = AlbumKind.HIDDEN_VAULT,
                coverUri = null,
                itemCount = hiddenCount,
                latestDateMs = 0L
            )
            val trash = Album(
                id = "trash",
                name = "回收站",
                kind = AlbumKind.TRASH,
                coverUri = null,
                itemCount = trashCount,
                latestDateMs = 0L
            )
            listOf(fav, trash, hidden) + formatAlbums + bucketAlbums
        }
            .distinctUntilChanged()
    }

    // -- Mutations ----------------------------------------------------------------

    suspend fun upsertAll(items: List<MediaEntity>) {
        AppLog.i(TAG, "upsertAll start items=${items.size}")
        mediaDao.upsertAll(items)
        // 为新增 media 行补空 flag 行 (ai_version=0 → 视为 pending → AI worker 接管).
        // 没这步会导致首次/清数据后扫描入库 7332 张但 findPendingAi 返回 0 行,
        // AI 标签完全不跑.
        val inserted = mediaFlagDao.ensureFlagsForAllMedia()
        if (inserted > 0) {
            AppLog.i(TAG, "ensureFlagsForAllMedia inserted $inserted new flag rows")
        }
        AppLog.i(TAG, "upsertAll done items=${items.size}")
    }
    suspend fun replaceAll(items: List<MediaEntity>) {
        AppLog.w(TAG, "replaceAll start items=${items.size} (DANGEROUS: may delete media rows!)")
        mediaDao.replaceAll(items)
        // 同 upsertAll: 为新增 media 行补 flag 行 (ai_version=0 → pending).
        // 不补则全量扫描 (requestFullScan) 后新照片永远不被 AI 标记,
        // findPendingAi 查 0 行, 也不出现在回收站.
        val inserted = mediaFlagDao.ensureFlagsForAllMedia()
        if (inserted > 0) {
            AppLog.w(TAG, "replaceAll ensureFlagsForAllMedia inserted $inserted flag rows")
        }
        AppLog.w(TAG, "replaceAll done items=${items.size}")
    }

    suspend fun setFavorite(uri: Uri, fav: Boolean) {
        val uriStr = uri.toString()
        AppLog.i(TAG, "setFavorite start uri=$uriStr fav=$fav")
        mediaFlagDao.setFavorite(uriStr, fav, System.currentTimeMillis())
        AppLog.i(TAG, "setFavorite db-write done uri=$uriStr")
        flagBackup.backupFavorite(uriStr, fav)
        AppLog.i(TAG, "setFavorite sp-backup done uri=$uriStr fav=$fav")
    }

    suspend fun setHidden(uri: Uri, hidden: Boolean) {
        val uriStr = uri.toString()
        AppLog.i(TAG, "setHidden start uri=$uriStr hidden=$hidden")
        mediaFlagDao.setHidden(uriStr, hidden, System.currentTimeMillis())
        AppLog.i(TAG, "setHidden db-write done uri=$uriStr")
        flagBackup.backupHidden(uriStr, hidden)
        AppLog.i(TAG, "setHidden sp-backup done uri=$uriStr hidden=$hidden")
    }

    suspend fun setHiddenBatch(uris: List<Uri>, hidden: Boolean) {
        if (uris.isEmpty()) return
        val uriStrs = uris.map { it.toString() }
        val ts = System.currentTimeMillis()
        AppLog.i(TAG, "setHiddenBatch start count=${uriStrs.size} hidden=$hidden")
        mediaFlagDao.setHiddenBatch(uriStrs, hidden, ts)
        AppLog.i(TAG, "setHiddenBatch db-write done count=${uriStrs.size}")
        uriStrs.forEach { flagBackup.backupHidden(it, hidden) }
        AppLog.i(TAG, "setHiddenBatch sp-backup done count=${uriStrs.size} hidden=$hidden")
    }

    suspend fun setTrash(uri: Uri, trash: Boolean) {
        val uriStr = uri.toString()
        AppLog.i(TAG, "setTrash start uri=$uriStr trash=$trash")
        mediaFlagDao.setTrash(uriStr, trash, System.currentTimeMillis())
        AppLog.i(TAG, "setTrash db-write done uri=$uriStr")
        flagBackup.backupTrash(uriStr, trash)
        AppLog.i(TAG, "setTrash sp-backup done uri=$uriStr trash=$trash")
    }

    suspend fun setTrashBatch(uris: List<Uri>, trash: Boolean) {
        if (uris.isEmpty()) return
        val uriStrs = uris.map { it.toString() }
        val ts = System.currentTimeMillis()
        AppLog.i(TAG, "setTrashBatch start count=${uriStrs.size} trash=$trash")
        mediaFlagDao.setTrashBatch(uriStrs, trash, ts)
        AppLog.i(TAG, "setTrashBatch db-write done count=${uriStrs.size}")
        uriStrs.forEach { flagBackup.backupTrash(it, trash) }
        AppLog.i(TAG, "setTrashBatch sp-backup done count=${uriStrs.size} trash=$trash")
    }

    suspend fun reserveVaultId(uri: Uri, vaultId: String) {
        val uriStr = uri.toString()
        AppLog.i(TAG, "reserveVaultId start uri=$uriStr vaultId=$vaultId")
        mediaFlagDao.setVaultId(uriStr, vaultId, System.currentTimeMillis())
        AppLog.i(TAG, "reserveVaultId db-write done uri=$uriStr")
        flagBackup.backupVaultId(uriStr, vaultId)
        AppLog.i(TAG, "reserveVaultId sp-backup done uri=$uriStr")
    }

    suspend fun clearVaultId(uri: Uri) {
        val uriStr = uri.toString()
        AppLog.i(TAG, "clearVaultId start uri=$uriStr")
        mediaFlagDao.setVaultId(uriStr, null, System.currentTimeMillis())
        AppLog.i(TAG, "clearVaultId db-write done uri=$uriStr")
        flagBackup.backupVaultId(uriStr, null)
        AppLog.i(TAG, "clearVaultId sp-backup done uri=$uriStr")
    }
    suspend fun delete(uri: Uri) {
        val uriStr = uri.toString()
        AppLog.w(TAG, "delete media uri=$uriStr")
        mediaDao.delete(uriStr)
        AppLog.w(TAG, "delete media done uri=$uriStr")
    }

    suspend fun deleteBatch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val uriStrs = uris.map { it.toString() }
        AppLog.w(TAG, "deleteBatch (DB-only) start count=${uriStrs.size}")
        // File deletion from MediaStore happens via the Activity layer
        // (MediaStore.createDeleteRequest requires an Activity context to
        // launch the system confirmation dialog). Callers that need the
        // real file gone MUST request system deletion BEFORE calling this
        // method. By the time we drop the DB rows the underlying file
        // should already be gone — this just stops us from re-surfacing
        // it on the next scan.
        //
        // MediaDao.deleteBatch drops rows from BOTH `media` and `media_flags`
        // in one transaction. Dropping the flag rows is what fixes the
        // 回收站 badge — it reads `media_flags WHERE is_trash = 1` directly,
        // so leaving orphans there keeps the count stuck.
        mediaDao.deleteBatch(uriStrs)
        // Also drop the SharedPreferences flag backup entries for the
        // same URIs. If we skip this, the next cold start's
        // `FlagBackupManager.restoreIfMissing` resurrects `media_flags`
        // rows for the just-deleted files — `media` has no row, but the
        // SP still says trash_<uri>=true and a fresh flag row is inserted
        // unconditionally on restore. The badge then creeps back up
        // after every process restart.
        flagBackup.clearAllFlagsFor(uriStrs)
        AppLog.w(TAG, "deleteBatch (DB-only) done count=${uriStrs.size}")
    }
    suspend fun purgeOldTrash(olderThanMs: Long): Int {
        AppLog.w(TAG, "purgeOldTrash olderThanMs=$olderThanMs (transactional: flags + media)")
        val n = mediaDao.purgeOldTrash(olderThanMs)
        AppLog.w(TAG, "purgeOldTrash deleted=$n")
        return n
    }
    suspend fun countFlags(): Int = mediaFlagDao.countAll()

    /**
     * Returns URI strings of rows that have a NULL latitude. Used by the geo-refill
     * pass that runs after ACCESS_MEDIA_LOCATION is first granted.
     *
     * @param limit max rows to return (one launch cycle, kept bounded)
     */
    suspend fun findUrisMissingLocation(limit: Int = 500): List<String> =
        mediaDao.findUrisMissingLocation(limit = limit, offset = 0)

    /**
     * Update only latitude/longitude for a single URI. If either is null, the row
     * is left untouched (we never overwrite a real GPS with null due to a transient
     * read failure).
     */
    suspend fun updateGeo(uri: String, latitude: Double?, longitude: Double?) {
        mediaDao.updateGeo(uri, latitude, longitude)
    }

    // -- Mapping -------------------------------------------------------------------

    private fun toModel(item: MediaItemWithFlags): MediaItem = MediaItem(
        id = item.media.mediaId,
        uri = Uri.parse(item.media.uri),
        displayName = item.media.displayName,
        mimeType = item.media.mimeType,
        format = runCatching { MediaFormat.valueOf(item.media.format) }.getOrDefault(MediaFormat.UNKNOWN),
        sizeBytes = item.media.sizeBytes,
        width = item.media.width,
        height = item.media.height,
        dateTakenMs = item.media.dateTakenMs,
        dateModifiedMs = item.media.dateModifiedMs,
        durationMs = item.media.durationMs,
        bucketName = item.media.bucketName,
        bucketPath = item.media.bucketPath,
        latitude = item.media.latitude,
        longitude = item.media.longitude,
        isFavorite = item.isFavorite,
        isHidden = item.isHidden,
        isInTrash = item.isInTrash,
        vaultId = item.vaultId,
        aiTags = item.media.aiTags,
        ocrText = item.media.ocrText,
        hashSha1 = item.media.hashSha1,
        isLivePhoto = item.media.isLivePhoto,
        // AI fields propagated from flag row. Must be read here (not just in queries)
        // so curated collections get their data when Room re-emits the timeline Flow
        // after each updateAiFields write.
        aiDomain = item.flags?.aiDomain,
        aiSubDomain = item.flags?.aiSubDomain,
        aiCopyright = item.flags?.aiCopyright,
        aiFaceCount = item.flags?.aiFaceCount,
        aiFaceArea = item.flags?.aiFaceArea,
        aiScore = item.flags?.aiScore,
        aiVersion = item.flags?.aiVersion,
        aiDanbooruTags = item.flags?.aiDanbooruTags,
    )

    // -- AI queries for curated collections ----------------------------------------

    fun queryCuratedThisWeek(limit: Int = 12) = observeTimeline()
        .map { items ->
            // 不限 7 天窗 — 历史照片打标后才进精选; 否则用户测试时永远 0 项.
            // 取 aiVersion>0 (已打标) 按 score 降序.
            items.asSequence()
                .filter { (it.aiVersion ?: 0) > 0 }
                .filter { (it.aiDomain ?: "real") in setOf("real", "anime") }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    fun queryPortraits(limit: Int = 12) = observeTimeline()
        .map { items ->
            // 放宽: MLKit 小脸 area 普遍 <0.05; 允许 faceCount>=1,
            // 或 vision sub_domain=="肖像"/"人像" 兜底 (ImageNet 头部 synset).
            items.asSequence()
                .filter { it.aiDomain == "real" }
                .filter {
                    ((it.aiFaceCount ?: 0) >= 1 && (it.aiFaceArea ?: 0f) >= 0.01f) ||
                        it.aiSubDomain in setOf("肖像", "人像")
                }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    fun queryAnimeCharacters(limit: Int = 12) = observeTimeline()
        .map { items ->
            // 不强制 copyright — anime 域照片按 score 排序.
            items.asSequence()
                .filter { it.aiDomain == "anime" }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    fun queryGameScreens(limit: Int = 12) = observeTimeline()
        .map { items ->
            items.asSequence()
                .filter { it.aiDomain == "game_screenshot" }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    fun queryMovieScreens(limit: Int = 12) = observeTimeline()
        .map { items ->
            // movie_screenshot 域已在 T#141 从 DomainRouter 移除, 此 query 已死.
            // 兜底改为: 取 sub_domain=="数字插画" 或 score top 历史照片, 让卡片有内容.
            items.asSequence()
                .filter { (it.aiVersion ?: 0) > 0 }
                .filter { it.aiSubDomain in setOf("数字插画", "游戏画面", "表情包") || it.aiDomain == "game_screenshot" }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    fun queryMemoriesTimeline(): Flow<List<MemoryCluster>> = observeTimeline()
        .map { items ->
            val ninetyDaysAgo = System.currentTimeMillis() - 90L * 24 * 60 * 60 * 1000
            items.asSequence()
                .filter { it.dateTakenMs in ninetyDaysAgo..Long.MAX_VALUE }
                .filter { (it.aiVersion ?: 0) > 0 }
                .groupBy { item ->
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = item.dateTakenMs }
                    val year = cal.get(java.util.Calendar.YEAR)
                    val month = cal.get(java.util.Calendar.MONTH) + 1
                    val bucket = "%d-%02d".format(year, month)
                    bucket to (items.firstNotNullOfOrNull { other ->
                        if (other.latitude != null && other.longitude != null &&
                            java.util.Calendar.getInstance().apply { timeInMillis = other.dateTakenMs }
                                .let { c -> c.get(java.util.Calendar.YEAR) == year && c.get(java.util.Calendar.MONTH) + 1 == month }
                        ) {
                            "%.1f,%.1f".format(other.latitude, other.longitude)
                        } else null
                    } ?: bucket)
                }
                .filter { (_, clusterItems) -> clusterItems.size >= 3 }
                .map { (key, clusterItems) ->
                    val (bucket, geo) = key
                    val hero = clusterItems.maxByOrNull { it.aiScore ?: 0f } ?: clusterItems.first()
                    MemoryCluster(
                        id = "memory:$bucket:$geo",
                        bucketLabel = bucket,
                        heroUri = hero.uri,
                        photoUris = clusterItems.map { it.uri },
                        count = clusterItems.size,
                        dateRangeStart = clusterItems.minOf { it.dateTakenMs },
                        dateRangeEnd = clusterItems.maxOf { it.dateTakenMs },
                        geoLabel = geo
                    )
                }
                .sortedByDescending { it.count }
                .take(5)
                .toList()
        }
        .distinctUntilChanged()

    fun queryFormatChallenge(limit: Int = 12) = observeTimeline()
        .map { items -> items.filter { it.format.isNextGen }.take(limit) }
        .distinctUntilChanged()

    /**
     * 二次元插画专列: aiDomain=="anime" 按 score 排序. 修复前
     * AlbumDetailPage 误用 queryFormatChallenge (HEIC/HEIF 过滤) 导致
     * "二次元插画" 永远只显示 6 张 (用户设备上 next-gen 格式恰好 6 张).
     */
    fun queryAnimeArt(limit: Int = 500) = observeTimeline()
        .map { items ->
            items.asSequence()
                .filter { it.aiDomain == "anime" }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    /**
     * 通用 14 类 (人像/肖像/夜景/夕阳/雪景/水面/食物/室内/植物/建筑/文档/天空/宝宝/动物)
     * 子域查询. 返回 aiSubDomain 命中且 aiVersion>0 的照片, 按 score 降序.
     * "其他" 单独走 queryOtherReal: 因为 IMAGENET_TO_SCENE 落空时会写 "其他",
     * 命中后用户能看到那些 "看不出来" 的照片, 避免永远埋没.
     */
    fun queryAiSubDomain(subDomain: String, limit: Int = 500) = observeTimeline()
        .map { items ->
            items.asSequence()
                .filter { (it.aiVersion ?: 0) > 0 }
                .filter { it.aiSubDomain == subDomain }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    /**
     * 二次元栏目按桶 ID 过滤 (v29): 用 [com.smartvision.gallery.data.ai.AnimeBuckets]
     * 的严格 Set 匹配, 取代原 substring 匹配.
     *
     *   bucketId == "ai:anime:全部" → 所有 anime 图 (受动漫判别门约束)
     *   bucketId == null → 所有有 Danbooru tag 的图 (兜底, 不推荐)
     *   其他 → 用 AnimeBuckets.BY_ID[bucketId].tags 做 ANY/ALL/NONE 检查.
     *
     * 角色专辑走不同路径 (queryAnimeByCharacter).
     *
     * v30.1: 入口处追加 [com.smartvision.gallery.data.ai.AnimeBuckets.isAnimeStyle]
     * 动漫判别门, 防止真人照被 Danbooru 误判的 1girl/solo 标签污染二次元桶.
     * 判别门在 matches() 内部也会检查, 这里前置是为了 null 桶/直接 hits 时也走同一过滤.
     */
    fun queryAnimeByBucket(bucketId: String?, limit: Int = 500) = observeTimeline()
        .map { items ->
            val def = bucketId?.let { com.smartvision.gallery.data.ai.AnimeBuckets.BY_ID[it] }
            items.asSequence()
                // v39: 强制 aiDomain=="anime" — DeepDanbooru 对二维码/真人照也输出
                // 标签 (训练集只有动漫图), 不加 domain 过滤会污染二次元桶.
                // v43: 删除 isAnimeStyle 守门 — aiDomain 已在 AiTagger 上游守门
                // (animeScore>=0.5+hasAnimeStyle 或 clipSaysAnime), 二次元桶无需
                // 再守 ANIME_DISCRIMINATOR_TAGS (comic/sketch/chibi/furry style
                // 标签). v42 实测 2872 张 aiDomain=anime 照片中只有 838 (29%)
                // 含 style 标签, 其余完整上色插画被错杀, "全部"桶只剩 838 张.
                .filter { it.aiDomain == "anime" && (it.aiVersion ?: 0) > 0 && !it.aiDanbooruTags.isNullOrEmpty() }
                .filter { item ->
                    if (def == null) return@filter true
                    val tagSet = com.smartvision.gallery.data.ai.AnimeBuckets.parseTagSet(item.aiDanbooruTags)
                    com.smartvision.gallery.data.ai.AnimeBuckets.matches(tagSet, def)
                }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    /**
     * 角色专辑: 按具体 characterTag 分组, characterTagName 为 "c:xxx" JSON 子串.
     * 直接 contains 子串匹配即可 (Danbooru tag 命名空间不含特殊符号).
     */
    fun queryAnimeByCharacter(characterTag: String, limit: Int = 500) = observeTimeline()
        .map { items ->
            items.asSequence()
                .filter { it.aiDomain == "anime" && (it.aiVersion ?: 0) > 0 }
                .filter { (it.aiDanbooruTags ?: "").contains("""c:$characterTag""") }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    fun queryOtherReal(limit: Int = 500) = observeTimeline()
        .map { items ->
            items.asSequence()
                .filter { (it.aiVersion ?: 0) > 0 }
                .filter { it.aiDomain == "real" && it.aiSubDomain == "其他" }
                .sortedByDescending { it.aiScore ?: 0f }
                .take(limit)
                .toList()
        }
        .distinctUntilChanged()

    /** Synchronous check consumed by background workers — reads the Settings
     *  "本地 AI 分类" toggle via [com.smartvision.gallery.data.prefs.AiPreferences]. */
    suspend fun isAiEnabled(): Boolean =
        SmartVisionApp.from(context).aiPreferences.aiEnabled.first()

    companion object {
        private const val TAG = "MediaRepo"
    }
}
