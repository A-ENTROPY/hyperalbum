package com.smartvision.gallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at,
               mf.ai_domain   AS flag_ai_domain,
               mf.ai_sub_domain AS flag_ai_sub_domain,
               mf.ai_copyright AS flag_ai_copyright,
               mf.ai_face_count AS flag_ai_face_count,
               mf.ai_face_area  AS flag_ai_face_area,
               mf.ai_score      AS flag_ai_score,
               mf.ai_version    AS flag_ai_version,
               mf.ai_danbooru_tags AS flag_ai_danbooru_tags
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
        ORDER BY m.date_taken_ms DESC
    """)
    fun observeTimeline(): Flow<List<MediaItemWithFlags>>

    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at,
               mf.ai_domain   AS flag_ai_domain,
               mf.ai_sub_domain AS flag_ai_sub_domain,
               mf.ai_copyright AS flag_ai_copyright,
               mf.ai_face_count AS flag_ai_face_count,
               mf.ai_face_area  AS flag_ai_face_area,
               mf.ai_score      AS flag_ai_score,
               mf.ai_version    AS flag_ai_version,
               mf.ai_danbooru_tags AS flag_ai_danbooru_tags
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
          AND m.format = :format
        ORDER BY m.date_taken_ms DESC
    """)
    fun observeByFormat(format: String): Flow<List<MediaItemWithFlags>>

    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at,
               mf.ai_domain   AS flag_ai_domain,
               mf.ai_sub_domain AS flag_ai_sub_domain,
               mf.ai_copyright AS flag_ai_copyright,
               mf.ai_face_count AS flag_ai_face_count,
               mf.ai_face_area  AS flag_ai_face_area,
               mf.ai_score      AS flag_ai_score,
               mf.ai_version    AS flag_ai_version,
               mf.ai_danbooru_tags AS flag_ai_danbooru_tags
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
          AND m.bucket_path = :path
        ORDER BY m.date_taken_ms DESC
    """)
    fun observeByBucket(path: String): Flow<List<MediaItemWithFlags>>

    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at,
               mf.ai_domain   AS flag_ai_domain,
               mf.ai_sub_domain AS flag_ai_sub_domain,
               mf.ai_copyright AS flag_ai_copyright,
               mf.ai_face_count AS flag_ai_face_count,
               mf.ai_face_area  AS flag_ai_face_area,
               mf.ai_score      AS flag_ai_score,
               mf.ai_version    AS flag_ai_version,
               mf.ai_danbooru_tags AS flag_ai_danbooru_tags
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
          AND mf.is_favorite = 1
        ORDER BY m.date_taken_ms DESC
    """)
    fun observeFavorites(): Flow<List<MediaItemWithFlags>>

    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at,
               mf.ai_domain   AS flag_ai_domain,
               mf.ai_sub_domain AS flag_ai_sub_domain,
               mf.ai_copyright AS flag_ai_copyright,
               mf.ai_face_count AS flag_ai_face_count,
               mf.ai_face_area  AS flag_ai_face_area,
               mf.ai_score      AS flag_ai_score,
               mf.ai_version    AS flag_ai_version,
               mf.ai_danbooru_tags AS flag_ai_danbooru_tags
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE mf.is_hidden = 1
        ORDER BY m.date_taken_ms DESC
    """)
    fun observeHidden(): Flow<List<MediaItemWithFlags>>

    /**
     * 未处理照片: ai_version IS NULL OR ai_version = 0 且非隐藏/非回收站.
     * 让"未处理"桶能展示 worker 还没打完标的剩余量, 全部进 ai 识别后
     * 此查询应返回空.
     */
    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at,
               mf.ai_domain   AS flag_ai_domain,
               mf.ai_sub_domain AS flag_ai_sub_domain,
               mf.ai_copyright AS flag_ai_copyright,
               mf.ai_face_count AS flag_ai_face_count,
               mf.ai_face_area  AS flag_ai_face_area,
               mf.ai_score      AS flag_ai_score,
               mf.ai_version    AS flag_ai_version,
               mf.ai_danbooru_tags AS flag_ai_danbooru_tags
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE (mf.ai_version IS NULL OR mf.ai_version = 0)
          AND (mf.is_hidden IS NULL OR mf.is_hidden = 0)
          AND (mf.is_trash  IS NULL OR mf.is_trash  = 0)
        ORDER BY m.date_taken_ms DESC
    """)
    fun observeUntagged(): Flow<List<MediaItemWithFlags>>

    /**
     * INNER JOIN (not LEFT JOIN) on purpose: when a user hard-deletes via
     * the system MediaStore confirm dialog, the underlying file goes away
     * AND our `media` row is dropped via [deleteBatch]. If, for any reason,
     * `media_flags.is_trash=1` ends up orphaned (no matching `media` row)
     * — e.g. an aborted clear-all where MediaStore confirmed but our DB
     * write rolled back — `LEFT JOIN` would have surfaced `m.* = NULL`
     * rows and downstream `MediaRepository.toModel` would throw on
     * `item.media.mediaId`. The grid would silently go empty while
     * [MediaFlagDao.observeTrashCount] (a flat `SELECT COUNT(*)`) would
     * still report the stale count, which is exactly the "badge 9 but
     * trash page empty" drift this INNER JOIN prevents.
     */
    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at,
               mf.ai_domain   AS flag_ai_domain,
               mf.ai_sub_domain AS flag_ai_sub_domain,
               mf.ai_copyright AS flag_ai_copyright,
               mf.ai_face_count AS flag_ai_face_count,
               mf.ai_face_area  AS flag_ai_face_area,
               mf.ai_score      AS flag_ai_score,
               mf.ai_version    AS flag_ai_version,
               mf.ai_danbooru_tags AS flag_ai_danbooru_tags
        FROM media m
        INNER JOIN media_flags mf ON m.uri = mf.uri
        WHERE mf.is_trash = 1
        ORDER BY m.date_modified_ms DESC
    """)
    fun observeTrash(): Flow<List<MediaItemWithFlags>>

    @Query("SELECT * FROM media WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): MediaEntity?

    @Transaction
    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at,
               mf.ai_domain   AS flag_ai_domain,
               mf.ai_sub_domain AS flag_ai_sub_domain,
               mf.ai_copyright AS flag_ai_copyright,
               mf.ai_face_count AS flag_ai_face_count,
               mf.ai_face_area  AS flag_ai_face_area,
               mf.ai_score      AS flag_ai_score,
               mf.ai_version    AS flag_ai_version,
               mf.ai_danbooru_tags AS flag_ai_danbooru_tags
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
          AND (m.display_name LIKE '%' || :q || '%' OR m.ocr_text LIKE '%' || :q || '%')
        ORDER BY m.date_taken_ms DESC
        LIMIT :limit
    """)
    fun search(q: String, limit: Int = 200): Flow<List<MediaItemWithFlags>>

    @Query("""
        SELECT COUNT(*) FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
    """)
    fun observeCount(): Flow<Int>

    @Query("""
        SELECT DISTINCT m.format FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
    """)
    fun observeUsedFormats(): Flow<List<String>>

    /**
     * 按 format 聚合 — 每个格式一行：item_count、latest_date_ms、cover_uri。
     * 用 SQLite 内核的 GROUP BY + 相关子查询取 cover，5k+ items 在 SQLite 里跑
     * 比把列表拉到 Kotlin 后做 `formats.map { fmt -> items.filter { ... } }`
     * 快一个数量级（O(n × formats) 在 main/Default thread → SQLite 在 daemon
     * thread + 索引覆盖：`format` 索引覆盖 GROUP BY，`date_taken_ms` 索引覆盖
     * 排序，单次 GROUP BY + 单次 MAX(date_taken_ms) 即可决定 cover_uri）。
     */
    @Query("""
        SELECT m.format                AS format,
               COUNT(*)                AS item_count,
               MAX(m.date_taken_ms)    AS latest_date_ms,
               (
                   SELECT m2.uri
                   FROM media m2
                   LEFT JOIN media_flags mf2 ON m2.uri = mf2.uri
                   WHERE m2.format = m.format
                     AND COALESCE(mf2.is_trash, 0)  = 0
                     AND COALESCE(mf2.is_hidden, 0) = 0
                   ORDER BY m2.date_taken_ms DESC
                   LIMIT 1
               ) AS cover_uri
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
        GROUP BY m.format
        ORDER BY m.format
    """)
    fun observeFormatAlbums(): Flow<List<FormatAlbumRow>>

    /**
     * 按 bucket_path 聚合 — 每个目录一行。用法同 [observeFormatAlbums]。
     * bucket_path 可空（理论上 MediaStore 总给值，但兜底 `NULL` 由 SQLite
     * 合并成一组，Kotlin 端用 "unknown" 兜底，保持与旧逻辑一致）。
     */
    @Query("""
        SELECT m.bucket_path          AS bucket_path,
               MAX(m.bucket_name)     AS bucket_name,
               COUNT(*)               AS item_count,
               MAX(m.date_taken_ms)   AS latest_date_ms,
               (
                   SELECT m2.uri
                   FROM media m2
                   LEFT JOIN media_flags mf2 ON m2.uri = mf2.uri
                   WHERE m2.bucket_path IS m.bucket_path
                     AND COALESCE(mf2.is_trash, 0)  = 0
                     AND COALESCE(mf2.is_hidden, 0) = 0
                   ORDER BY m2.date_taken_ms DESC
                   LIMIT 1
               ) AS cover_uri
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE COALESCE(mf.is_trash, 0) = 0 AND COALESCE(mf.is_hidden, 0) = 0
        GROUP BY m.bucket_path
        ORDER BY latest_date_ms DESC
    """)
    fun observeBucketAlbums(): Flow<List<BucketAlbumRow>>

    /**
     * 收藏专辑聚合 — 走 `media_flags.is_favorite = 1` 而非 COALESCE：
     * 收藏是 explicit flag，没有 flag 行的 item 永远不可能成为收藏。
     */
    @Query("""
        SELECT COUNT(*)             AS item_count,
               MAX(m.date_taken_ms)  AS latest_date_ms,
               (
                   SELECT m2.uri
                   FROM media m2
                   INNER JOIN media_flags mf2 ON m2.uri = mf2.uri
                   WHERE mf2.is_favorite = 1
                     AND COALESCE(mf2.is_trash, 0)  = 0
                     AND COALESCE(mf2.is_hidden, 0) = 0
                   ORDER BY m2.date_taken_ms DESC
                   LIMIT 1
               ) AS cover_uri
        FROM media m
        INNER JOIN media_flags mf ON m.uri = mf.uri
        WHERE mf.is_favorite = 1
          AND COALESCE(mf.is_trash, 0)  = 0
          AND COALESCE(mf.is_hidden, 0) = 0
    """)
    fun observeFavoritesMeta(): Flow<FavoritesMetaRow?>

    // --- Scanner writes (UPSERT — never touches media_flags) ---

    @Query("""
        INSERT INTO media (media_id, uri, display_name, mime_type, format, size_bytes,
                           width, height, date_taken_ms, date_modified_ms, duration_ms,
                           bucket_name, bucket_path, latitude, longitude, ai_tags,
                           ocr_text, hash_sha1, is_live_photo)
        VALUES (:mediaId, :uri, :displayName, :mimeType, :format, :sizeBytes,
                :width, :height, :dateTakenMs, :dateModifiedMs, :durationMs,
                :bucketName, :bucketPath, :latitude, :longitude, :aiTags,
                :ocrText, :hashSha1, :isLivePhoto)
        ON CONFLICT(uri) DO UPDATE SET
            media_id         = excluded.media_id,
            display_name     = excluded.display_name,
            mime_type        = excluded.mime_type,
            format           = excluded.format,
            size_bytes       = excluded.size_bytes,
            width            = excluded.width,
            height           = excluded.height,
            date_taken_ms    = excluded.date_taken_ms,
            date_modified_ms = excluded.date_modified_ms,
            duration_ms      = excluded.duration_ms,
            bucket_name      = excluded.bucket_name,
            bucket_path      = excluded.bucket_path,
            latitude         = excluded.latitude,
            longitude        = excluded.longitude,
            ai_tags          = excluded.ai_tags,
            ocr_text         = excluded.ocr_text,
            hash_sha1        = excluded.hash_sha1,
            is_live_photo    = excluded.is_live_photo
    """)
    suspend fun upsertMedia(
        mediaId: Long, uri: String, displayName: String, mimeType: String?,
        format: String, sizeBytes: Long, width: Int, height: Int,
        dateTakenMs: Long, dateModifiedMs: Long, durationMs: Long?,
        bucketName: String?, bucketPath: String?, latitude: Double?, longitude: Double?,
        aiTags: List<String>, ocrText: String?, hashSha1: String?, isLivePhoto: Boolean
    )

    /**
     * Batch upsert. Every chunk gets its own transaction so SQLite can
     * checkpoint WAL and release the write lock between chunks, instead
     * of holding it for all 7332 rows (~13 min).  Each row is a true
     * atomic ON CONFLICT(uri) DO UPDATE — no DELETE+INSERT dance.
     */
    open suspend fun upsertAll(entities: List<MediaEntity>) {
        for (chunk in entities.chunked(CHUNK_SIZE)) {
            upsertChunk(chunk)
        }
    }

    @Transaction
    open suspend fun upsertChunk(chunk: List<MediaEntity>) {
        for (entity in chunk) {
            upsertMedia(
                mediaId = entity.mediaId,
                uri = entity.uri,
                displayName = entity.displayName,
                mimeType = entity.mimeType,
                format = entity.format,
                sizeBytes = entity.sizeBytes,
                width = entity.width,
                height = entity.height,
                dateTakenMs = entity.dateTakenMs,
                dateModifiedMs = entity.dateModifiedMs,
                durationMs = entity.durationMs,
                bucketName = entity.bucketName,
                bucketPath = entity.bucketPath,
                latitude = entity.latitude,
                longitude = entity.longitude,
                aiTags = entity.aiTags,
                ocrText = entity.ocrText,
                hashSha1 = entity.hashSha1,
                isLivePhoto = entity.isLivePhoto,
            )
        }
    }

    companion object {
        private const val CHUNK_SIZE = 500
    }

    // --- Geo refill (ACCESS_MEDIA_LOCATION granted 后回填历史为 null 的 lat/lng) ---

    /**
     * Returns URI strings of all rows that have a NULL latitude. Used by the
     * geo-refill pass that runs after ACCESS_MEDIA_LOCATION is first granted —
     * we don't want to re-scan all 8000 photos, just refill rows we couldn't
     * populate during initial scan because the permission hadn't been granted yet.
     *
     * Bound by [limit] to avoid pulling tens of thousands of rows in one go; caller
     * can paginate by adjusting [offset] if needed. SQLite DEFAULT 500 is a sane
     * upper bound for a single launch cycle.
     */
    @Query("SELECT uri FROM media WHERE latitude IS NULL LIMIT :limit OFFSET :offset")
    suspend fun findUrisMissingLocation(limit: Int = 500, offset: Int = 0): List<String>

    /**
     * Update only latitude/longitude for a single URI. Used by the geo-refill pass to
     * avoid the cost of `upsertMedia` (which re-validates everything).
     *
     * If both [latitude] and [longitude] are null, the row is left unchanged — we never
     * want to overwrite a real GPS with null due to a transient read failure.
     */
    @Query("""
        UPDATE media
        SET latitude = :latitude, longitude = :longitude
        WHERE uri = :uri
          AND :latitude IS NOT NULL
          AND :longitude IS NOT NULL
    """)
    suspend fun updateGeo(uri: String, latitude: Double?, longitude: Double?)

    // --- Deletes ---

    @Query("DELETE FROM media WHERE uri = :uri")
    suspend fun delete(uri: String)

    /**
     * Batch hard-delete from `media` AND `media_flags`. Wraps both deletes in
     * a single Room transaction so the InvalidationTracker fires exactly once
     * at commit AND observers see a consistent state.
     *
     * Why both tables: the 回收站 badge reads `COUNT(*) FROM media_flags
     * WHERE is_trash = 1` directly (see [MediaFlagDao.observeTrashCount]),
     * not via a JOIN with `media`. If we only drop the `media` rows, the
     * orphaned `media_flags` rows keep reporting the pre-delete count and
     * the badge stays at "9 项" forever. Deleting flags here is safe because
     * the URIs have already been confirmed-deleted from MediaStore via the
     * system delete dialog before this runs — there's no future scanner pass
     * that could re-create them.
     */
    @Transaction
    open suspend fun deleteBatch(uris: List<String>) {
        for (uri in uris) {
            delete(uri)
        }
        deleteFlagsByUris(uris)
    }

    /**
     * Badge count source-of-truth for the "回收站" entry. Mirrors
     * [observeTrash] exactly (INNER JOIN + same predicate) so the grid
     * and the badge can NEVER drift apart — see the comment on
     * [observeTrash] for the orphan-flag backstory.
     */
    @Query("""
        SELECT COUNT(*)
        FROM media m
        INNER JOIN media_flags mf ON m.uri = mf.uri
        WHERE mf.is_trash = 1
    """)
    fun observeTrashCount(): Flow<Int>

    /**
     * Companion to [observeTrashCount] for the "隐私空间" badge. Same
     * INNER-JOIN rationale — see [observeTrash].
     */
    @Query("""
        SELECT COUNT(*)
        FROM media m
        INNER JOIN media_flags mf ON m.uri = mf.uri
        WHERE mf.is_hidden = 1
    """)
    fun observeHiddenCount(): Flow<Int>

    // MIGRATION_5_6 移除了 FK CASCADE, media 行删除不再级联清 media_flags.
    // 必须先删 flags 再删 media, 否则孤儿 flag 行会让 trash/hidden count
    // badge 永久漂移 (LEFT JOIN 仍然关联到幽灵行).
    @Query("""
        DELETE FROM media_flags WHERE uri IN (
            SELECT uri FROM media_flags WHERE is_trash = 1 AND updated_at < :olderThanMs
        )
    """)
    suspend fun purgeTrashFlags(olderThanMs: Long): Int

    @Query("""
        DELETE FROM media WHERE uri IN (
            SELECT uri FROM media_flags WHERE is_trash = 1 AND updated_at < :olderThanMs
        )
    """)
    suspend fun purgeTrash(olderThanMs: Long): Int

    @Transaction
    open suspend fun purgeOldTrash(olderThanMs: Long): Int {
        purgeTrashFlags(olderThanMs)
        return purgeTrash(olderThanMs)
    }

    @Query("DELETE FROM media")
    suspend fun deleteAll(): Int

    // --- Bulk queries for selective replace ---

    @Query("SELECT uri FROM media")
    suspend fun getAllUris(): List<String>

    @Query("DELETE FROM media_flags WHERE uri IN (:uris)")
    suspend fun deleteFlagsByUrisRaw(uris: Collection<String>)

    @Query("DELETE FROM media WHERE uri IN (:uris)")
    suspend fun deleteMediaByUrisRaw(uris: Collection<String>)

    // SQLite 单条语句绑定变量上限 999. 批量删除可能超过 (多选全清 1200+
    // 张), 必须按块执行避免 SQLiteException "too many SQL variables".
    // CHUNK_SIZE=500 复用上层已有的 batch 尺寸, 安全低于 999.
    @Transaction
    open suspend fun deleteFlagsByUris(uris: Collection<String>) {
        for (chunk in uris.chunked(CHUNK_SIZE)) deleteFlagsByUrisRaw(chunk)
    }

    @Transaction
    open suspend fun deleteMediaByUris(uris: Collection<String>) {
        for (chunk in uris.chunked(CHUNK_SIZE)) deleteMediaByUrisRaw(chunk)
    }

    // --- Full replace: only DELETE genuinely removed URIs so FK CASCADE never
    //     touches rows that still exist.  UPSERT handles the rest in-place. ---

    @Transaction
    open suspend fun replaceAll(items: List<MediaEntity>) {
        val existingUris = getAllUris()
        val newUriSet = items.map { it.uri }.toSet()
        val removedUris = existingUris - newUriSet
        if (removedUris.isNotEmpty()) {
            deleteFlagsByUris(removedUris)
            deleteMediaByUris(removedUris)
        }
        upsertAll(items)
    }
}

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums ORDER BY latest_date_ms DESC")
    fun observeAll(): Flow<List<AlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AlbumEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AlbumEntity>)

    @Query("DELETE FROM albums")
    suspend fun clear()
}

@Dao
interface UserAlbumDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun add(item: UserAlbumItemEntity)

    @Query("DELETE FROM user_album_items WHERE album_id = :albumId AND media_uri = :mediaUri")
    suspend fun remove(albumId: String, mediaUri: String)

    @Query("SELECT media_uri FROM user_album_items WHERE album_id = :albumId ORDER BY added_ms DESC")
    fun observeMembers(albumId: String): Flow<List<String>>

    @Query("SELECT DISTINCT album_id FROM user_album_items")
    fun observeAlbums(): Flow<List<String>>
}
