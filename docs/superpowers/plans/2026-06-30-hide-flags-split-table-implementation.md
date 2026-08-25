# Hide-Persistence Bug — Split `media_flags` Table Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `is_hidden`, `is_favorite`, `is_trash`, `vault_id` from `media` table to a separate `media_flags` table that the scanner never touches, permanently fixing the hide-persistence bug after 3 failed fix attempts.

**Architecture:** A new `MediaFlagEntity` with FK→`media(uri) ON DELETE CASCADE`. Scanner writes only to `media` via SQLite UPSERT (`INSERT … ON CONFLICT(uri) DO UPDATE SET`). All reads use `LEFT JOIN media_flags` with `COALESCE` for NULL-safe defaults. `MediaFlagDao` provides atomic single-statement setters via `INSERT ON CONFLICT(uri) DO UPDATE SET <col> = excluded.<col>` — no read-merge-write race. Room migration recreates `media` without flag columns, backfills flags from old rows, then drops old table.

**Tech Stack:** Android Room, Kotlin coroutines, SQLite UPSERT, FK cascade

---

## File Map

| File | Action | Purpose |
|---|---|---|
| `data/db/Entities.kt` | Modify | Drop 4 flag columns from `MediaEntity`. Remove `is_trash`/`is_hidden` indices. |
| `data/db/MediaFlagEntity.kt` | **Create** | New entity with FK→`media(uri) ON DELETE CASCADE` |
| `data/db/MediaItemWithFlags.kt` | **Create** | `@Embedded` join class with convenience `val` accessors |
| `data/db/MediaFlagDao.kt` | **Create** | Atomic single-statement setters via UPSERT |
| `data/db/Daos.kt` | Modify | Rewrite all `observe*` queries to `LEFT JOIN media_flags`. Remove dead code (`cleanupDuplicates`, `replaceAllTransactional`, flag-write methods, etc.). Add `upsertMedia` (UPSERT) + new `upsertAll`. `search()` gets COALESCE filters. `purgeTrash` uses subquery. |
| `data/db/SmartVisionDatabaseMigrations.kt` | Modify | Add `MIGRATION_4_5` with the 9-step SQL |
| `data/db/SmartVisionDatabase.kt` | Modify | Version 5, add `MediaFlagEntity`, add `mediaFlagDao()`, add `MIGRATION_4_5` |
| `data/repo/MediaRepository.kt` | Modify | Accept `MediaFlagDao`. Mutations → `MediaFlagDao`. `toModel` takes `MediaItemWithFlags`. |
| `scanner/MediaStoreDataSource.kt` | Modify | Drop `isFavorite = false, isHidden = false, isTrash = false` from `MediaEntity` constructor calls |
| `SmartVisionApp.kt` | Modify | Remove `cleanupDuplicates()` startup call. Wire `mediaFlagDao` into `MediaRepository`. |

**No changes needed (verified):**
- `privacy/EncryptedPrivacyVault.kt` — already calls `repository.setHidden/reserveVaultId/clearVaultId` → repo delegates to `MediaFlagDao`
- `privacy/VaultMigrator.kt` — already calls `repository.reserveVaultId` → same delegation
- `data/model/MediaItem.kt` — flags are in the domain model, mapping handled by repository
- `scanner/MediaScanCoordinator.kt` — just calls `repository.upsertAll/replaceAll`
- All UI files — they consume `MediaItem` domain objects, never raw entities

---

### Task 1: Create `MediaFlagEntity` + `MediaItemWithFlags`

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/data/db/MediaFlagEntity.kt`
- Create: `app/src/main/java/com/smartvision/gallery/data/db/MediaItemWithFlags.kt`
- (No build yet — referenced by later tasks)

- [ ] **Step 1: Create `MediaFlagEntity.kt`**

```kotlin
package com.smartvision.gallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_flags",
    foreignKeys = [ForeignKey(
        entity = MediaEntity::class,
        parentColumns = ["uri"],
        childColumns = ["uri"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [
        Index(value = ["is_hidden"]),
        Index(value = ["is_trash"]),
    ]
)
data class MediaFlagEntity(
    @PrimaryKey @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean = false,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_trash") val isTrash: Boolean = false,
    @ColumnInfo(name = "vault_id") val vaultId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
)
```

- [ ] **Step 2: Modify `Entities.kt` — remove flag columns and indices from `MediaEntity`**

In `app/src/main/java/com/smartvision/gallery/data/db/Entities.kt`:

**Remove** these 4 lines from the `MediaEntity` data class body:
```kotlin
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean,
    @ColumnInfo(name = "is_trash") val isTrash: Boolean,
    @ColumnInfo(name = "vaultId") val vaultId: String? = null,
```

**Remove** these 2 index entries from the `@Entity` annotation:
```kotlin
        Index(value = ["is_trash"]),
        Index(value = ["is_hidden"])
```

Final `MediaEntity`:
```kotlin
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["date_taken_ms"]),
        Index(value = ["bucket_path"]),
        Index(value = ["format"]),
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "format") val format: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "width") val width: Int,
    @ColumnInfo(name = "height") val height: Int,
    @ColumnInfo(name = "date_taken_ms") val dateTakenMs: Long,
    @ColumnInfo(name = "date_modified_ms") val dateModifiedMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    @ColumnInfo(name = "bucket_name") val bucketName: String?,
    @ColumnInfo(name = "bucket_path") val bucketPath: String?,
    @ColumnInfo(name = "latitude") val latitude: Double?,
    @ColumnInfo(name = "longitude") val longitude: Double?,
    @ColumnInfo(name = "ai_tags") val aiTags: List<String>,
    @ColumnInfo(name = "ocr_text") val ocrText: String?,
    @ColumnInfo(name = "hash_sha1") val hashSha1: String?,
    @ColumnInfo(name = "is_live_photo") val isLivePhoto: Boolean = false,
)
```

- [ ] **Step 3: Create `MediaItemWithFlags.kt`**

```kotlin
package com.smartvision.gallery.data.db

import androidx.room.Embedded

/**
 * Joined result of [MediaEntity] LEFT JOIN [MediaFlagEntity].
 *
 * Every [observe*] DAO method returns this instead of bare [MediaEntity].
 * Access flag values through the convenience properties — they handle the
 * NULL (no flags row) case with sensible defaults.
 */
data class MediaItemWithFlags(
    @Embedded val media: MediaEntity,
    @Embedded(prefix = "flag_") val flags: MediaFlagEntity? = null,
) {
    val isHidden: Boolean get() = flags?.isHidden ?: false
    val isFavorite: Boolean get() = flags?.isFavorite ?: false
    val isInTrash: Boolean get() = flags?.isTrash ?: false
    val vaultId: String? get() = flags?.vaultId
}
```

---

### Task 2: Create `MediaFlagDao`

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/data/db/MediaFlagDao.kt`

- [ ] **Step 1: Create `MediaFlagDao.kt`**

```kotlin
package com.smartvision.gallery.data.db

import androidx.room.Dao
import androidx.room.Query

/**
 * DAO for [MediaFlagEntity]. Every setter is a single-statement atomic UPSERT
 * that never reads first — no read-merge-write race.
 */
@Dao
interface MediaFlagDao {

    @Query("SELECT * FROM media_flags WHERE uri = :uri")
    suspend fun findByUri(uri: String): MediaFlagEntity?

    @Query("SELECT * FROM media_flags WHERE vault_id IS NOT NULL")
    suspend fun findVaultEntries(): List<MediaFlagEntity>

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

    @Query("SELECT COUNT(*) FROM media_flags WHERE is_hidden = 1")
    suspend fun countHidden(): Int

    @Query("SELECT COUNT(*) FROM media_flags WHERE is_trash = 1")
    suspend fun countTrash(): Int
}
```

---

### Task 3: Rewrite `Daos.kt` — `MediaDao` LEFT JOIN + UPSERT

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/Daos.kt` (full rewrite)

- [ ] **Step 1: Replace entire `Daos.kt` content**

Remove: `MediaFlagRow`, `MediaRowWithId`, all OR-merge upsert, `cleanupDuplicates`, `replaceAllTransactional`, `updateFlagsById`, `getAllRowsWithId`, `deleteByRowId`, `findByUris`, `upsertRaw`, `upsertAllRaw`, `setFavorite`, `setHidden`, `setTrash`, `reserveVaultId`, `clearVaultId`.

Add: `upsertMedia` (single-row UPSERT), new `upsertAll` (loop calling `upsertMedia`), new `replaceAll` (no flag-merge). Rewrite all `observe*` queries with LEFT JOIN. Rewrite `search` with COALESCE. Rewrite `purgeTrash` with subquery.

```kotlin
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
               mf.updated_at AS flag_updated_at
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
               mf.updated_at AS flag_updated_at
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
               mf.updated_at AS flag_updated_at
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
               mf.updated_at AS flag_updated_at
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
               mf.updated_at AS flag_updated_at
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
        WHERE mf.is_hidden = 1
        ORDER BY m.date_taken_ms DESC
    """)
    fun observeHidden(): Flow<List<MediaItemWithFlags>>

    @Query("""
        SELECT m.*,
               mf.uri        AS flag_uri,
               mf.is_hidden  AS flag_is_hidden,
               mf.is_favorite AS flag_is_favorite,
               mf.is_trash   AS flag_is_trash,
               mf.vault_id   AS flag_vault_id,
               mf.updated_at AS flag_updated_at
        FROM media m
        LEFT JOIN media_flags mf ON m.uri = mf.uri
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
               mf.updated_at AS flag_updated_at
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

    @Transaction
    open suspend fun upsertAll(entities: List<MediaEntity>) {
        for (entity in entities) {
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

    // --- Deletes ---

    @Query("DELETE FROM media WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("""
        DELETE FROM media WHERE uri IN (
            SELECT uri FROM media_flags WHERE is_trash = 1 AND updated_at < :olderThanMs
        )
    """)
    suspend fun purgeTrash(olderThanMs: Long): Int

    @Query("DELETE FROM media")
    suspend fun deleteAll(): Int

    // --- Full replace (no flag-merge — FK cascade handles cleanup) ---

    @Transaction
    open suspend fun replaceAll(items: List<MediaEntity>) {
        deleteAll()
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
```

---

### Task 4: Add `MIGRATION_4_5`

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/SmartVisionDatabaseMigrations.kt`

- [ ] **Step 1: Append `MIGRATION_4_5`**

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Step 1: Rename old media table.
        db.execSQL("ALTER TABLE media RENAME TO media_old")

        // Step 2: Drop indices that referenced removed columns.
        db.execSQL("DROP INDEX IF EXISTS index_media_is_hidden")
        db.execSQL("DROP INDEX IF EXISTS index_media_is_trash")

        // Step 3: Create new media table WITHOUT flag columns.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media (
              rowId            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
              media_id         INTEGER NOT NULL,
              uri              TEXT    NOT NULL,
              display_name     TEXT    NOT NULL,
              mime_type        TEXT,
              format           TEXT    NOT NULL,
              size_bytes       INTEGER NOT NULL,
              width            INTEGER NOT NULL,
              height           INTEGER NOT NULL,
              date_taken_ms    INTEGER NOT NULL,
              date_modified_ms INTEGER NOT NULL,
              duration_ms      INTEGER,
              bucket_name      TEXT,
              bucket_path      TEXT,
              latitude         REAL,
              longitude        REAL,
              ai_tags          TEXT    NOT NULL,
              ocr_text         TEXT,
              hash_sha1        TEXT,
              is_live_photo    INTEGER NOT NULL DEFAULT 0
            )
        """)

        // Step 4: Copy data from media_old (omit rowId — auto-generated).
        db.execSQL("""
            INSERT INTO media (media_id, uri, display_name, mime_type, format, size_bytes,
                               width, height, date_taken_ms, date_modified_ms, duration_ms,
                               bucket_name, bucket_path, latitude, longitude, ai_tags,
                               ocr_text, hash_sha1, is_live_photo)
            SELECT media_id, uri, display_name, mime_type, format, size_bytes,
                   width, height, date_taken_ms, date_modified_ms, duration_ms,
                   bucket_name, bucket_path, latitude, longitude, ai_tags,
                   ocr_text, hash_sha1, is_live_photo
            FROM media_old
        """)

        // Step 5: Create media_flags table. FK references the NEW media table.
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_flags (
              uri        TEXT    NOT NULL PRIMARY KEY,
              is_hidden  INTEGER NOT NULL DEFAULT 0,
              is_favorite INTEGER NOT NULL DEFAULT 0,
              is_trash   INTEGER NOT NULL DEFAULT 0,
              vault_id   TEXT,
              updated_at INTEGER NOT NULL,
              FOREIGN KEY (uri) REFERENCES media(uri) ON DELETE CASCADE
            )
        """)

        // Step 6: Backfill flags from media_old. media_flags now exists.
        db.execSQL("""
            INSERT OR IGNORE INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at)
            SELECT uri, is_hidden, is_favorite, is_trash, vaultId,
                   CAST(strftime('%s','now') AS INTEGER) * 1000
            FROM media_old
            WHERE is_hidden = 1 OR is_favorite = 1 OR is_trash = 1 OR vaultId IS NOT NULL
        """)

        // Step 7: Indexes on media_flags.
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_is_hidden ON media_flags(is_hidden)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_is_trash  ON media_flags(is_trash)")

        // Step 8: Drop media_old. media_flags FK points to new media, not media_old.
        db.execSQL("DROP TABLE media_old")

        // Step 9: Indexes on new media.
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_uri           ON media(uri)")
        db.execSQL("CREATE        INDEX IF NOT EXISTS index_media_date_taken_ms ON media(date_taken_ms)")
        db.execSQL("CREATE        INDEX IF NOT EXISTS index_media_bucket_path   ON media(bucket_path)")
        db.execSQL("CREATE        INDEX IF NOT EXISTS index_media_format        ON media(format)")
    }
}
```

- [ ] **Step 2: Update imports at top of file**

Replace `import androidx.room.migration.Migration` (already exists) — no import changes needed since we use the same types.

---

### Task 5: Wire into `SmartVisionDatabase`

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/SmartVisionDatabase.kt`

- [ ] **Step 1: Update `@Database` annotation**

Add `MediaFlagEntity::class` to the entities array. Bump version to 5.

```kotlin
@Database(
    entities = [
        MediaEntity::class,
        MediaFlagEntity::class,
        AlbumEntity::class,
        UserAlbumItemEntity::class
    ],
    version = 5,
    exportSchema = false
)
```

- [ ] **Step 2: Add `mediaFlagDao()` abstract method**

```kotlin
abstract fun mediaFlagDao(): MediaFlagDao
```

- [ ] **Step 3: Add `MIGRATION_4_5` to the migration list**

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
```

Full final file:

```kotlin
package com.smartvision.gallery.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        MediaEntity::class,
        MediaFlagEntity::class,
        AlbumEntity::class,
        UserAlbumItemEntity::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class SmartVisionDatabase : RoomDatabase() {

    abstract fun mediaDao(): MediaDao
    abstract fun mediaFlagDao(): MediaFlagDao
    abstract fun albumDao(): AlbumDao
    abstract fun userAlbumDao(): UserAlbumDao

    companion object {
        fun create(context: Context): SmartVisionDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                SmartVisionDatabase::class.java,
                "smartvision.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
```

---

### Task 6: Rewrite `MediaRepository`

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/repo/MediaRepository.kt`

- [ ] **Step 1: Full rewrite of `MediaRepository.kt`**

Changes:
- Constructor accepts `mediaFlagDao: MediaFlagDao`
- All `observe*` methods map from `Flow<List<MediaItemWithFlags>>` → `Flow<List<MediaItem>>`
- Mutations delegate to `mediaFlagDao` instead of `mediaDao`
- `toModel` takes `MediaItemWithFlags` and reads flags from convenience properties
- `replaceAll` calls `mediaDao.replaceAll()`
- All other public API unchanged (callers don't need to know)

```kotlin
package com.smartvision.gallery.data.repo

import android.content.Context
import android.net.Uri
import com.smartvision.gallery.data.db.AlbumDao
import com.smartvision.gallery.data.db.AlbumEntity
import com.smartvision.gallery.data.db.MediaDao
import com.smartvision.gallery.data.db.MediaEntity
import com.smartvision.gallery.data.db.MediaFlagDao
import com.smartvision.gallery.data.db.MediaItemWithFlags
import com.smartvision.gallery.data.model.Album
import com.smartvision.gallery.data.model.AlbumKind
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.decoder.format.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MediaRepository(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val mediaFlagDao: MediaFlagDao,
    private val albumDao: AlbumDao,
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

    fun observeTrash(): Flow<List<MediaItem>> =
        mediaDao.observeTrash().map { it.map(::toModel) }

    fun observeSearch(q: String): Flow<List<MediaItem>> =
        mediaDao.search(q).map { it.map(::toModel) }

    fun observeUsedFormats(): Flow<List<MediaFormat>> =
        mediaDao.observeUsedFormats().map { names ->
            names.mapNotNull { runCatching { MediaFormat.valueOf(it) }.getOrNull() }
        }

    // -- Albums --------------------------------------------------------------------

    fun observeSmartAlbums(): Flow<List<Album>> {
        return timelineCache
            .combine(observeUsedFormats()) { items, formats ->
            val groups = items.groupBy { it.bucketPath }
            val bucketAlbums = groups.map { (bucket, list) ->
                val key = bucket ?: "unknown"
                Album(
                    id = "bucket:$key",
                    name = list.firstOrNull()?.bucketName ?: key,
                    kind = AlbumKind.BUCKET,
                    coverUri = list.firstOrNull()?.uri,
                    itemCount = list.size,
                    latestDateMs = list.maxOf { it.dateTakenMs },
                    bucketPath = bucket
                )
            }
            val formatAlbums = formats.map { fmt ->
                Album(
                    id = "format:${fmt.name}",
                    name = "所有 ${fmt.displayName} 图片",
                    kind = AlbumKind.FORMAT_FILTER,
                    coverUri = null,
                    itemCount = items.count { it.format == fmt },
                    latestDateMs = items.filter { it.format == fmt }.maxOfOrNull { it.dateTakenMs } ?: 0L,
                    formatFilter = fmt
                )
            }
            val fav = Album(
                id = "favorites",
                name = "收藏",
                kind = AlbumKind.FAVORITES,
                coverUri = null,
                itemCount = items.count { it.isFavorite },
                latestDateMs = items.filter { it.isFavorite }.maxOfOrNull { it.dateTakenMs } ?: 0L
            )
            val hidden = Album(
                id = "hidden",
                name = "隐私空间",
                kind = AlbumKind.HIDDEN_VAULT,
                coverUri = null,
                itemCount = items.count { it.isHidden },
                latestDateMs = items.filter { it.isHidden }.maxOfOrNull { it.dateTakenMs } ?: 0L
            )
            val trash = Album(
                id = "trash",
                name = "回收站",
                kind = AlbumKind.TRASH,
                coverUri = null,
                itemCount = items.count { it.isInTrash },
                latestDateMs = items.filter { it.isInTrash }.maxOfOrNull { it.dateModifiedMs } ?: 0L
            )
            listOf(fav, trash, hidden) + formatAlbums + bucketAlbums
        }
            .distinctUntilChanged()
    }

    // -- Mutations ----------------------------------------------------------------

    suspend fun upsertAll(items: List<MediaEntity>) = mediaDao.upsertAll(items)
    suspend fun replaceAll(items: List<MediaEntity>) = mediaDao.replaceAll(items)

    suspend fun setFavorite(uri: Uri, fav: Boolean) =
        mediaFlagDao.setFavorite(uri.toString(), fav, System.currentTimeMillis())
    suspend fun setHidden(uri: Uri, hidden: Boolean) =
        mediaFlagDao.setHidden(uri.toString(), hidden, System.currentTimeMillis())
    suspend fun setTrash(uri: Uri, trash: Boolean) =
        mediaFlagDao.setTrash(uri.toString(), trash, System.currentTimeMillis())
    suspend fun reserveVaultId(uri: Uri, vaultId: String) =
        mediaFlagDao.setVaultId(uri.toString(), vaultId, System.currentTimeMillis())
    suspend fun clearVaultId(uri: Uri) =
        mediaFlagDao.setVaultId(uri.toString(), null, System.currentTimeMillis())
    suspend fun delete(uri: Uri) = mediaDao.delete(uri.toString())
    suspend fun purgeOldTrash(olderThanMs: Long) = mediaDao.purgeTrash(olderThanMs)

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
    )
}
```

---

### Task 7: Update `SmartVisionApp.kt`

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/SmartVisionApp.kt`

- [ ] **Step 1: Wire `mediaFlagDao` into `MediaRepository` constructor**

Change the `mediaRepository` lazy block from:
```kotlin
val mediaRepository: MediaRepository by lazy {
    MediaRepository(
        context = this,
        mediaDao = database.mediaDao(),
        albumDao = database.albumDao()
    )
}
```
to:
```kotlin
val mediaRepository: MediaRepository by lazy {
    MediaRepository(
        context = this,
        mediaDao = database.mediaDao(),
        mediaFlagDao = database.mediaFlagDao(),
        albumDao = database.albumDao()
    )
}
```

- [ ] **Step 2: Remove `cleanupDuplicates()` startup call**

Delete these lines entirely (the entire `// 3.5` block):
```kotlin
        // 3.5 One-time cleanup of duplicate DB rows from old buggy upsert.
        // Must run BEFORE scan to prevent re-adding duplicates.
        appScope.launch(Dispatchers.IO) {
            try {
                val deleted = database.mediaDao().cleanupDuplicates()
                if (deleted > 0) AppLog.i(TAG, "Dedup: removed $deleted duplicate rows")
            } catch (t: Throwable) {
                AppLog.w(TAG, "Dedup failed (non-fatal)", t)
            }
        }
```

---

### Task 8: Clean `MediaStoreDataSource` flag defaults

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/scanner/MediaStoreDataSource.kt`

- [ ] **Step 1: Remove flag fields from `queryImages()` `MediaEntity` constructor (lines 144-146)**

Remove these 3 lines from the image entity construction:
```kotlin
                    isFavorite = false,
                    isHidden = false,
                    isTrash = false,
```

The `MediaEntity` constructor no longer has these parameters. Remove them entirely. The block goes from:
```kotlin
                items += MediaEntity(
                    mediaId = id,
                    uri = uri.toString(),
                    // ... all fields ...
                    isFavorite = false,   // ← remove
                    isHidden = false,     // ← remove
                    isTrash = false,      // ← remove
                    aiTags = emptyList(),
                    ocrText = null,
                    hashSha1 = null,
                    isLivePhoto = false,
                )
```
to:
```kotlin
                items += MediaEntity(
                    mediaId = id,
                    uri = uri.toString(),
                    displayName = name,
                    mimeType = mime,
                    format = format.name,
                    sizeBytes = size,
                    width = w,
                    height = h,
                    dateTakenMs = takenMs,
                    dateModifiedMs = c.getLong(modCol) * 1000L,
                    durationMs = null,
                    bucketName = bucketName,
                    bucketPath = path?.let { bucketFromPath(it) } ?: "bucket_$bucketId",
                    latitude = null,
                    longitude = null,
                    aiTags = emptyList(),
                    ocrText = null,
                    hashSha1 = null,
                    isLivePhoto = false,
                )
```

- [ ] **Step 2: Remove flag fields from `queryVideos()` `MediaEntity` constructor (lines 244-246)**

Same change in the video section — remove `isFavorite = false, isHidden = false, isTrash = false`.

---

### Task 9: Build and verify

**Files:**
- No file changes — run the build and check for compilation errors.

- [ ] **Step 1: Build the project**

Run:
```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
./gradlew :app:assembleDebug 2>&1
```

If build fails, fix errors and retry. Expected outcomes:
- `MediaItemWithFlags` and `MediaFlagEntity` compile as new files
- `MediaFlagDao` compiles against `MediaFlagEntity`
- `Daos.kt` compiles with LEFT JOIN queries and UPSERT
- `SmartVisionDatabase.kt` finds all entities and migrations
- `MediaRepository.kt` compiles with `MediaFlagDao` parameter and new `toModel`
- `SmartVisionApp.kt` compiles with `mediaFlagDao` and no `cleanupDuplicates`
- `MediaStoreDataSource.kt` compiles without the removed flag fields
- `MediaScanCoordinator.kt` continues to compile (no changes needed)
- `EncryptedPrivacyVault.kt` continues to compile (repository API unchanged)
- `VaultMigrator.kt` continues to compile

- [ ] **Step 2: Common build errors and fixes**

If Room complains about schema mismatch (MIGRATION_4_5 produces a different schema than Room expects from annotations):
- The migration creates `media` without `is_hidden`/`is_favorite`/`is_trash`/`vaultId` columns ✓ (matches new `MediaEntity`)
- The migration creates `media_flags` with FK → `media(uri)` ✓ (matches `MediaFlagEntity`)
- The migration creates indices matching both entities ✓

If Room generates a `_Impl` file that references removed columns, check:
- Did all `observe*` queries in `Daos.kt` get the full column projection? Yes — `m.*` captures all `media` columns, the `flag_*` aliases map to `MediaItemWithFlags` prefix.
- Are all `MediaEntity` column names matched in `upsertMedia`'s INSERT column list? Yes — all non-flag columns are listed explicitly.

- [ ] **Step 3: Hand off to user for functional testing**

Build succeeds. APK at `app/build/outputs/apk/debug/app-debug.apk`. User installs and tests:

## Spec Coverage Verification

| Spec Requirement | Covered By |
|---|---|
| `MediaFlagEntity` with FK + indices | Task 1 |
| `MediaItemWithFlags` join class | Task 1 |
| `MediaFlagDao` atomic setters | Task 2 |
| LEFT JOIN all observe* queries | Task 3 |
| COALESCE for NULL-safe flag defaults | Task 3 |
| UPSERT `upsertMedia` (no DELETE/INSERT) | Task 3 |
| Remove dead code (cleanupDuplicates, etc.) | Task 3 |
| `replaceAll` without flag-merge | Task 3 |
| `purgeTrash` with subquery | Task 3 |
| `MIGRATION_4_5` 9-step SQL | Task 4 |
| Database wiring (version 5, entities, migration) | Task 5 |
| Repository rewrite (MediaFlagDao delegation) | Task 6 |
| `SmartVisionApp` dedup removal + wiring | Task 7 |
| `MediaStoreDataSource` flag default cleanup | Task 8 |
| Build verification | Task 9 |
| Privacy vault (unchanged — repository API same) | Verified separately |
| VaultMigrator (unchanged — repository API same) | Verified separately |
