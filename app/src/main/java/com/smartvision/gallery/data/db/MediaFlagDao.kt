package com.smartvision.gallery.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * DAO for [MediaFlagEntity]. Every setter is a single-statement atomic UPSERT
 * that never reads first — no read-merge-write race.
 *
 * Batch setters (`setHiddenBatch` / `setTrashBatch`) wrap N single-row UPSERTs
 * in a single `@Transaction`, so Room's InvalidationTracker fires exactly ONCE
 * at commit. Without the transaction wrapper, N rapid individual UPSERTs
 * trigger N separate invalidations, which — under Main-dispatcher pressure —
 * can cause Compose's `collectAsStateWithLifecycle` to coalesce the final
 * emission into a no-op snapshot, leaving the gallery grid showing the stale
 * pre-batch list until the next process restart.
 */
@Dao
interface MediaFlagDao {

    @Query("SELECT * FROM media_flags")
    suspend fun findAll(): List<MediaFlagEntity>

    @Query("SELECT * FROM media_flags WHERE uri = :uri")
    suspend fun findByUri(uri: String): MediaFlagEntity?

    @Query("SELECT * FROM media_flags WHERE vault_id IS NOT NULL")
    suspend fun findVaultEntries(): List<MediaFlagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaFlagEntity)

    /**
     * 为 media 表存在但 media_flags 不存在的 uri 批量插入空 flag 行
     * (default ai_version=0 → 视为 pending → AiTaggingWorker 接管).
     *
     * 仅扫描后调用一次, 避免空 flag 表导致 AI worker 0 行可处理.
     * 用 LEFT JOIN 找缺失行, 单条批量 INSERT, O(media) 但只插入缺失的.
     */
    @Query("""
        INSERT INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at,
                                 ai_domain, ai_sub_domain, ai_copyright,
                                 ai_face_count, ai_face_area, ai_score, ai_version, ai_tagged_at)
        SELECT m.uri, 0, 0, 0, NULL, 0, NULL, NULL, NULL, 0, 0, 0, 0, 0
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE mf.uri IS NULL
    """)
    suspend fun ensureFlagsForAllMedia(): Long

    @Query("""
        INSERT INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at)
        VALUES (:uri, :isHidden, 0, 0, NULL, :updatedAt)
        ON CONFLICT(uri) DO UPDATE SET
            is_hidden  = excluded.is_hidden,
            updated_at = excluded.updated_at
    """)
    suspend fun setHidden(uri: String, isHidden: Boolean, updatedAt: Long)

    @Query("""
        INSERT INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at)
        VALUES (:uri, 0, :isFavorite, 0, NULL, :updatedAt)
        ON CONFLICT(uri) DO UPDATE SET
            is_favorite = excluded.is_favorite,
            updated_at  = excluded.updated_at
    """)
    suspend fun setFavorite(uri: String, isFavorite: Boolean, updatedAt: Long)

    @Query("""
        INSERT INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at)
        VALUES (:uri, 0, 0, :isTrash, NULL, :updatedAt)
        ON CONFLICT(uri) DO UPDATE SET
            is_trash   = excluded.is_trash,
            updated_at = excluded.updated_at
    """)
    suspend fun setTrash(uri: String, isTrash: Boolean, updatedAt: Long)

    @Query("""
        INSERT INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at)
        VALUES (:uri, 0, 0, 0, :vaultId, :updatedAt)
        ON CONFLICT(uri) DO UPDATE SET
            vault_id   = excluded.vault_id,
            updated_at = excluded.updated_at
    """)
    suspend fun setVaultId(uri: String, vaultId: String?, updatedAt: Long)

    @Query("DELETE FROM media_flags WHERE uri = :uri")
    suspend fun delete(uri: String)

    /**
     * Batch hide / unhide. All UPSERTs run in one transaction so the
     * InvalidationTracker fires once, guaranteeing the gallery Flow emits
     * exactly one new list and the UI recomposes with the post-batch state.
     */
    @Transaction
    open suspend fun setHiddenBatch(uris: List<String>, isHidden: Boolean, updatedAt: Long) {
        for (uri in uris) {
            setHidden(uri, isHidden, updatedAt)
        }
    }

    @Transaction
    open suspend fun setTrashBatch(uris: List<String>, isTrash: Boolean, updatedAt: Long) {
        for (uri in uris) {
            setTrash(uri, isTrash, updatedAt)
        }
    }

    @Query("SELECT COUNT(*) FROM media_flags WHERE is_hidden = 1")
    suspend fun countHidden(): Int

    @Query("SELECT COUNT(*) FROM media_flags WHERE is_trash = 1")
    suspend fun countTrash(): Int

    @Query("SELECT COUNT(*) FROM media_flags")
    suspend fun countAll(): Int

    // --- Live counts (for sidebar / pinned album badges) ---

    /**
     * Live count of items currently in trash. Source of truth for the
     * "回收站" badge — must NOT be derived from `media` (timeline) because
     * `observeTimeline` excludes trashed rows, which silently produced a
     * zero count even when the trash held real items.
     */
    @Query("SELECT COUNT(*) FROM media_flags WHERE is_trash = 1")
    fun observeTrashCount(): Flow<Int>

    /**
     * Live count of items currently in the privacy vault. Same rationale as
     * [observeTrashCount] — `observeTimeline` excludes hidden rows.
     */
    @Query("SELECT COUNT(*) FROM media_flags WHERE is_hidden = 1")
    fun observeHiddenCount(): Flow<Int>

    // --- AI classification fields ---

    @Query("""
        UPDATE media_flags
        SET ai_domain = :domain,
            ai_sub_domain = :subDomain,
            ai_copyright = :copyright,
            ai_face_count = :faceCount,
            ai_face_area = :faceArea,
            ai_score = :score,
            ai_version = :version,
            ai_tagged_at = :taggedAt,
            ai_danbooru_tags = :danbooruTags
        WHERE uri = :uri
    """)
    suspend fun updateAiFields(
        uri: String,
        domain: String?,
        subDomain: String?,
        copyright: String?,
        faceCount: Int,
        faceArea: Float,
        score: Float,
        version: Int,
        taggedAt: Long,
        danbooruTags: String? = null
    )

    @Query("""
        SELECT mf.* FROM media_flags mf
        INNER JOIN media m ON m.uri = mf.uri
        WHERE mf.ai_version < :version AND mf.is_trash = 0
          AND m.width >= 100 AND m.height >= 100
        LIMIT :limit
    """)
    suspend fun findPendingAi(version: Int, limit: Int): List<MediaFlagEntity>

    @Query("""
        SELECT COUNT(*) FROM media_flags mf
        INNER JOIN media m ON m.uri = mf.uri
        WHERE mf.ai_version < :version AND mf.is_trash = 0
          AND m.width >= 100 AND m.height >= 100
    """)
    suspend fun countPendingAi(version: Int): Int

    /**
     * v34: 单条 SQL 批量给短边 < 100px 的小图标/缩略图打 fallback,
     * 避免它们在 worker 里逐张 decode + isProcessableImage 浪费 IO 槽位.
     * 直接 UPDATE 不返回行数 — 调用方不需要知道多少条, 反正都被标了.
     */
    @Query("""
        UPDATE media_flags
        SET ai_domain = 'real',
            ai_sub_domain = '其他',
            ai_copyright = NULL,
            ai_face_count = 0,
            ai_face_area = 0.0,
            ai_score = 0.0,
            ai_version = :version,
            ai_tagged_at = :taggedAt,
            ai_danbooru_tags = NULL
        WHERE ai_version < :version AND is_trash = 0
          AND uri IN (SELECT uri FROM media WHERE width < 100 OR height < 100)
    """)
    suspend fun bulkFallbackSmallImages(version: Int, taggedAt: Long)

    @Query("""
        SELECT COUNT(*) FROM media_flags
        WHERE ai_version >= :version AND is_trash = 0
    """)
    suspend fun countDoneAi(version: Int): Int
}
