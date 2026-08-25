# Hide-Persistence Bug — Split `media_flags` Table

> **For agentic workers:** This spec is the input to the implementation plan. It describes what to build, not how to step through tasks.

## Context

Hidden items (`is_hidden = 1`) reappear in the timeline, both:
- Spontaneously, ~minutes after hiding (the next incremental scan)
- After force-killing the app and relaunching

Three fix attempts have already failed:
1. Add `upsertAll` with custom OR-based flag merge
2. Add `cleanupDuplicates` + improved OR-merge + `replaceAllTransactional`
3. Move OR-merge from `associateBy` to explicit loop

All three assumed `INSERT OR REPLACE` could be coerced into preserving flags. The KSP-generated code is correct. The Room codegen does what it should. The issue is **architectural**: any code path that scans MediaStore and writes back through `@Insert(OnConflictStrategy.REPLACE)` will eventually reset `is_hidden`, regardless of how clever the merge is. Reasons:

- `INSERT OR REPLACE` is `DELETE + INSERT` under the hood. The DELETE fires row-level triggers and **clears any row-level state**. Index/trigger interactions and FK constraints in Room's generated SQL amplify this.
- Even with perfect merge, the scan path runs on `Dispatchers.IO`. A `setHidden(...)` from the main thread on the *same* URI can land in the small window between `findByUris(...)` and `upsertAllRaw(...)`. Then the scan's REPLACE overwrites the user's just-set value.
- `cleanupDuplicates` + `replaceAllTransactional` add complexity but don't change the fundamental race.

Per `superpowers:systematic-debugging` Phase 4.5 — "If 3+ Fixes Failed: Question Architecture" — we shift from in-place upsert with merge to an architecturally separate flag store.

## Approach: Split `media_flags` Table

Concept: User-driven flags (`is_hidden`, `is_favorite`, `is_trash`, `vault_id`) live in a separate `media_flags` table. The scanner writes **only** to `media`. It has zero SQL knowledge of `media_flags` and therefore zero ability to clobber user state. All read paths use `LEFT JOIN media_flags`, taking the flag-table value as truth.

```
media table (written by scanner)        media_flags table (written by user actions)
┌──────────────────────────┐           ┌──────────────────────────────┐
│ uri (unique idx)         │           │ uri (PK) REFERENCES media    │
│ display_name, dates, …   │           │ is_hidden      INTEGER       │
│ (no is_hidden column)    │           │ is_favorite    INTEGER       │
└──────────────────────────┘           │ is_trash       INTEGER       │
                                       │ vault_id       TEXT          │
                                       │ updated_at     INTEGER       │
                                       └──────────────────────────────┘
```

A row in `media_flags` exists iff the user has ever touched that flag. If absent → flags default to false. This is the v1 entity: we **remove** `is_favorite`, `is_hidden`, `is_trash`, `vault_id` columns from `MediaEntity`. Reading them becomes a JOIN.

## Schema

### New table `media_flags`

```sql
CREATE TABLE media_flags (
  uri        TEXT    PRIMARY KEY NOT NULL,
  is_hidden  INTEGER NOT NULL DEFAULT 0,
  is_favorite INTEGER NOT NULL DEFAULT 0,
  is_trash   INTEGER NOT NULL DEFAULT 0,
  vault_id   TEXT,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (uri) REFERENCES media(uri) ON DELETE CASCADE
);
CREATE INDEX idx_media_flags_hidden ON media_flags(is_hidden);
CREATE INDEX idx_media_flags_trash  ON media_flags(is_trash);
```

`uri` is PRIMARY KEY and a foreign key to `media(uri) ON DELETE CASCADE`. When the scanner removes a stale row, the flag row goes with it — no orphaned flags.

### Migration MIGRATION_4_5

⚠️ **3 gotchas that were carefully researched and confirmed:**

1. **RENAME auto-rewrites FK**: SQLite `ALTER TABLE RENAME` automatically rewrites FK references in other tables to point to the new name ([SQLite docs](https://www.sqlite.org/lang_altertable.html)). If `media_flags` already references `media` and we rename `media → media_old`, the FK in `media_flags` silently changes to point to `media_old`. Then creating a new `media` table and dropping `media_old` fails because `media_flags` still FK-references `media_old`.

    **Fix**: Create `media_flags` AFTER the new `media` table exists, not before.

2. **`PRAGMA foreign_keys = OFF` is a no-op inside a transaction** ([SQLite docs](https://www.sqlite.org/pragma.html#pragma_foreign_keys)): Room wraps migrations in `beginTransaction()` / `endTransaction()`. Any `PRAGMA foreign_keys` change between those is silently ignored. Cannot rely on it.

    **Fix**: Order the SQL so FK is always satisfied — no OFF needed.

3. **`rowId` in raw INSERT needs explicit handling**: Room's `@Insert(autoGenerate=true)` uses `nullif(?, 0)` in its generated SQL so that `rowId=0` becomes SQL NULL, triggering auto-increment. Raw SQL in `@Query` does NOT get this transformation. If we include `rowId` in the INSERT with value `0`, SQLite tries to insert primary-key `0`, which conflicts on the second insert of a different URI (both have rowId=0).

    **Fix**: Omit `rowId` from the raw INSERT column list entirely. SQLite auto-generates it. On `ON CONFLICT(uri) DO UPDATE`, rowId stays untouched.

#### Correct Migration SQL

```sql
BEGIN EXCLUSIVE TRANSACTION;

-- Step 1: Rename the old media table.
ALTER TABLE media RENAME TO media_old;

-- Step 2: Drop indexes on media_old that referenced removed columns.
DROP INDEX IF EXISTS index_media_is_hidden;
DROP INDEX IF EXISTS index_media_is_trash;

-- Step 3: Create the new media table WITHOUT flag columns.
-- rowId omitted from INSERT → SQLite auto-generates fresh ids.
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
);

-- Step 4: Copy data from media_old.
INSERT INTO media (media_id, uri, display_name, mime_type, format, size_bytes,
                   width, height, date_taken_ms, date_modified_ms, duration_ms,
                   bucket_name, bucket_path, latitude, longitude, ai_tags,
                   ocr_text, hash_sha1, is_live_photo)
SELECT media_id, uri, display_name, mime_type, format, size_bytes,
       width, height, date_taken_ms, date_modified_ms, duration_ms,
       bucket_name, bucket_path, latitude, longitude, ai_tags,
       ocr_text, hash_sha1, is_live_photo
FROM media_old;

-- Step 5: Create media_flags table. FK references the NEW media table.
CREATE TABLE IF NOT EXISTS media_flags (
  uri        TEXT    NOT NULL PRIMARY KEY,
  is_hidden  INTEGER NOT NULL DEFAULT 0,
  is_favorite INTEGER NOT NULL DEFAULT 0,
  is_trash   INTEGER NOT NULL DEFAULT 0,
  vault_id   TEXT,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY (uri) REFERENCES media(uri) ON DELETE CASCADE
);

-- Step 6: Backfill flags from media_old. media_flags now exists.
INSERT OR IGNORE INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at)
SELECT uri, is_hidden, is_favorite, is_trash, vaultId,
       CAST(strftime('%s','now') AS INTEGER) * 1000
FROM media_old
WHERE is_hidden = 1 OR is_favorite = 1 OR is_trash = 1 OR vaultId IS NOT NULL;

-- Step 7: Indexes on media_flags.
CREATE INDEX IF NOT EXISTS index_media_flags_is_hidden ON media_flags(is_hidden);
CREATE INDEX IF NOT EXISTS index_media_flags_is_trash  ON media_flags(is_trash);

-- Step 8: Drop media_old. No table FK-references it — media_flags points to
-- the new media table, so this succeeds with FK enforcement on.
DROP TABLE media_old;

-- Step 9: Indexes on new media.
CREATE UNIQUE INDEX IF NOT EXISTS index_media_uri           ON media(uri);
CREATE        INDEX IF NOT EXISTS index_media_date_taken_ms ON media(date_taken_ms);
CREATE        INDEX IF NOT EXISTS index_media_bucket_path   ON media(bucket_path);
CREATE        INDEX IF NOT EXISTS index_media_format        ON media(format);

COMMIT;
```

Safety at each step (no `PRAGMA foreign_keys = OFF` needed):
| Step | Why safe |
|---|---|
| 1 RENAME | `media_flags` doesn't exist yet — nothing FK-references `media` |
| 3–4 create+copy | `media_old` and new `media` coexist; FK state unchanged |
| 5 CREATE media_flags | New `media` table already exists — FK constraint immediately satisfied |
| 6 Backfill | `media_flags` exists; `media_old` still readable |
| 8 DROP media_old | `media_flags` FK points to new `media`, not `media_old` — DROP succeeds |

This migration never requires `PRAGMA foreign_keys = OFF`. FK constraints are always satisfied:
- Before Step 1: no tables FK-reference `media`, so RENAME is clean.
- Steps 3–4: new `media` and `media_old` coexist; no FK changes.
- Step 5: `media_flags` created with FK pointing to new `media` — FK immediately satisfied.
- Step 8 (`DROP TABLE media_old`): `media_flags` FK points to new `media`, not `media_old` — DROP succeeds.

`fallbackToDestructiveMigration` is **not** used. The migration must succeed or the user loses their flag data.

### `MediaEntity` after migration

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
    // ... rest unchanged ...
    @ColumnInfo(name = "ai_tags") val aiTags: List<String>,
    @ColumnInfo(name = "ocr_text") val ocrText: String?,
    @ColumnInfo(name = "hash_sha1") val hashSha1: String?,
    @ColumnInfo(name = "is_live_photo") val isLivePhoto: Boolean = false,
)
```

The 4 columns `is_hidden`, `is_favorite`, `is_trash`, `vaultId` are **removed**.

### New entity `MediaFlagEntity`

```kotlin
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
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean,
    @ColumnInfo(name = "is_trash") val isTrash: Boolean,
    @ColumnInfo(name = "vault_id") val vaultId: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
```

## Data Model: `MediaItemWithFlags`

Reads must return flags alongside media. We introduce a non-entity class for the joined view:

```kotlin
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

All DAO read methods that today return `Flow<List<MediaEntity>>` are replaced with `Flow<List<MediaItemWithFlags>>`. The active SQL is `LEFT JOIN media_flags ON media.uri = media_flags.uri`.

### Sample read (timeline)

```sql
SELECT
  m.*,
  mf.uri        AS flag_uri,
  mf.is_hidden  AS flag_is_hidden,
  mf.is_favorite AS flag_is_favorite,
  mf.is_trash   AS flag_is_trash,
  mf.vault_id   AS flag_vault_id,
  mf.updated_at AS flag_updated_at
FROM media m
LEFT JOIN media_flags mf ON m.uri = mf.uri
WHERE COALESCE(mf.is_trash, 0) = 0
  AND COALESCE(mf.is_hidden, 0) = 0
ORDER BY m.date_taken_ms DESC
```

`COALESCE(..., 0) = 0` handles the LEFT JOIN null case where the user has never set a flag on this URI. The filter `is_hidden = 0` excludes hidden items from the timeline — this is what the user's complaint was about.

## Repository / DAO Changes

### New DAO `MediaFlagDao`

Critical correctness detail: **every setter must be a single-statement atomic write.** Read-merge-write patterns (read current flags, modify one, write back) race when two callers concurrently touch different flags of the same URI. The first thread's intended update is silently overwritten by the second thread's merge. Use SQLite `INSERT ON CONFLICT(uri) DO UPDATE SET <col> = excluded.<col>` for each setter — SQLite executes this as a single statement with internal locking; other columns are not re-written.

```kotlin
@Dao
interface MediaFlagDao {
    @Query("SELECT * FROM media_flags WHERE uri = :uri")
    suspend fun findByUri(uri: String): MediaFlagEntity?

    // For vault migration tooling — reads all rows with vault_id set.
    @Query("SELECT * FROM media_flags WHERE vault_id IS NOT NULL")
    suspend fun findVaultEntries(): List<MediaFlagEntity>

    /**
     * Atomic setHidden. The INSERT path creates the row with defaults for the
     * other three flags; the ON CONFLICT path only touches `is_hidden` and
     * `updated_at`. Never reads first, so no race.
     */
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

Repository methods are 2-line wrappers:

```kotlin
suspend fun setHidden(uri: Uri, hidden: Boolean) {
    mediaFlagDao.setHidden(uri.toString(), hidden, System.currentTimeMillis())
}

suspend fun setFavorite(uri: Uri, fav: Boolean) {
    mediaFlagDao.setFavorite(uri.toString(), fav, System.currentTimeMillis())
}

suspend fun setTrash(uri: Uri, trash: Boolean) {
    mediaFlagDao.setTrash(uri.toString(), trash, System.currentTimeMillis())
}

suspend fun reserveVaultId(uri: Uri, vaultId: String) {
    mediaFlagDao.setVaultId(uri.toString(), vaultId, System.currentTimeMillis())
}

suspend fun clearVaultId(uri: Uri) {
    mediaFlagDao.setVaultId(uri.toString(), null, System.currentTimeMillis())
}
```

No read-merge-write. No race. The vault migration code (`EncryptedPrivacyVault.migrateIfNeeded`) reads via `findVaultEntries()` to map old URI-hash vault IDs to new SecureRandom ones; calls to `setVaultId` use the atomic setter.

### `MediaDao` read methods change return type

Each `Flow<List<MediaEntity>>` becomes `Flow<List<MediaItemWithFlags>>`. Select SQL is updated with the LEFT JOIN. The `WHERE` clauses are updated to filter on `flag_*` columns:

| Old DAO method | New return | New filter |
|---|---|---|
| `observeTimeline()` | joined | `COALESCE(mf.is_trash,0)=0 AND COALESCE(mf.is_hidden,0)=0` |
| `observeHidden()` | joined | `mf.is_hidden = 1` |
| `observeFavorites()` | joined | `mf.is_favorite = 1` |
| `observeTrash()` | joined | `mf.is_trash = 1` |
| `observeByFormat()` | joined | + format |
| `observeByBucket()` | joined | + bucket |
| `observeByUris()` | joined | uris IN … |
| `search()` | joined | (filter on text) AND `COALESCE(mf.is_trash,0)=0 AND COALESCE(mf.is_hidden,0)=0` |

### `MediaDao` write methods migrate to `MediaFlagDao`

- `setHidden`, `setFavorite`, `setTrash`, `reserveVaultId`, `clearVaultId` → removed from `MediaDao`, replaced by `MediaFlagDao` versions
- `delete(uri)` removes only from `media`; the FK `ON DELETE CASCADE` cleans `media_flags` automatically

### `MediaDao.upsertAll` — must avoid INSERT OR REPLACE because of FK cascade

A subtle but critical detail: even with the FK defined on `media_flags`, the scanner's `INSERT OR REPLACE` on `media` is internally a `DELETE + INSERT`. The DELETE fires the FK cascade and silently drops the user's flag row. To prevent this, the scanner must use SQLite UPSERT (`INSERT ... ON CONFLICT(uri) DO UPDATE SET ...`), which is an atomic UPDATE in place — no DELETE, no cascade.

```kotlin
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
            // ... all other fields, just NOT rowId ...
        )
    }
}
```

This eliminates the OR-merge entirely. The scanner updates media metadata atomically; flags in `media_flags` are never touched.

### Removed code paths (cascading FK makes them obsolete)

The following are deleted because the architectural root cause is gone:
- `MediaFlagRow` (Daos.kt:12-18)
- `MediaRowWithId` (Daos.kt:20-28)
- `MediaDao.findByUris` (Daos.kt:54-55) — used only by `upsertAll` merge
- `MediaDao.upsertRaw` (single insert) — used only in cleanupDuplicates
- `MediaDao.upsertAllRaw` — replaced by `upsertAll` calling `upsertMedia`
- `MediaDao.cleanupDuplicates` (Daos.kt:152-175)
- `MediaDao.replaceAllTransactional` (Daos.kt:194-225)
- `MediaDao.updateFlagsById`, `getAllRowsWithId`, `deleteByRowId`
- `SmartVisionApp.kt` startup dedup call (lines 114-121)
- All references to `MediaDao.setHidden/setFavorite/setTrash/reserveVaultId/clearVaultId` → `MediaFlagDao` versions

`replaceAllTransactional` (used by `requestFullScan`) becomes:
```kotlin
@Transaction
open suspend fun replaceAll(items: List<MediaEntity>) {
    deleteAll()
    upsertAll(items)  // uses upsertMedia which is non-cascading
}
```

The `deleteAll()` removes `media` rows; FK cascade cleans `media_flags`. After insert, flags are gone. **This is intentional for `requestFullScan`** — a full scan user-initiated scan is a "wipe and rebuild". If the user wants to preserve flags across a full scan, they should not trigger one (the app already only does incremental scans automatically). The full-scan flag-loss trade-off is acceptable because the user is explicitly requesting a rebuild.

### `MediaItem` mapping

`MediaRepository.toModel(entity: MediaItemWithFlags): MediaItem` reuses existing mapping plus flag columns. `MediaItem.isHidden/isFavorite/isInTrash/vaultId` come from the joined `MediaFlagEntity`.

## Scanning Path

`MediaStoreDataSource.queryAll()` writes a `MediaEntity` without flag columns. Already does (`isFavorite = false, isHidden = false, isTrash = false` are hard-coded but those columns no longer exist on `MediaEntity`). Fix: drop those parameters from the constructor calls and update both `queryImages()` (line ~144-146) and `queryVideos()` (line ~244-246).

`MediaScanCoordinator.scheduleIncrementalScan()` calls `repository.upsertAll(items)`. No change. `requestFullScan()` calls `repository.replaceAll(items)`. No change — the replace still only touches `media`.

## Files Changed

| File | Change |
|---|---|
| `data/db/Entities.kt` | Drop 4 columns from MediaEntity. Add MediaFlagEntity. |
| `data/db/Daos.kt` | Replace upsertAll/cleanupDuplicates/replaceAllTransactional logic. Add MediaFlagDao (or co-located). Change all observe* queries to LEFT JOIN. Drop flag-write methods from MediaDao. Add `findByUri` to MediaDao for MediaFlagDao lookup. |
| `data/db/SmartVisionDatabase.kt` | Bump version to 5. Add MediaFlagEntity. Add MIGRATION_4_5. |
| `data/db/SmartVisionDatabaseMigrations.kt` | Add MIGRATION_4_5 — backfill flags, recreate media without flag columns. |
| `data/db/MediaItemWithFlags.kt` (new) | The @Embedded join class. |
| `data/repo/MediaRepository.kt` | All observe* methods return `Flow<List<MediaItem>>` (mapped from joined view). Mutations go through MediaFlagDao. `toModel` reads from joined view. |
| `scanner/MediaStoreDataSource.kt` | Drop `isFavorite/isHidden/isTrash` from the MediaEntity constructors in `queryImages` (line ~144-146) and `queryVideos` (line ~244-246). |
| `scanner/MediaScanCoordinator.kt` | No change — just calls repository. |
| `SmartVisionApp.kt` | Remove `cleanupDuplicates` startup call (lines 114-121). |
| `privacy/EncryptedPrivacyVault.kt` | Update to use MediaFlagDao for setHidden/reserveVaultId/clearVaultId. |
| `data/model/MediaItem.kt` | No shape change — but check that callers expecting flags as part of MediaItem still work via repository mapping. |

## Migration Safety

The migration handles 3 cases:

1. **Fresh install** (no DB): version 5 matches — no migration runs.
2. **Existing user (v4)**: MIGRATION_4_5 runs. Backfills flags. Recreates table. Foreign key cascade ensures no orphaned `media_flags`.
3. **User with existing hidden photos**: the backfill INSERT copies any `is_hidden = 1` rows from `media` into `media_flags`. Hidden status is preserved across the migration.

The migration must be tested manually before shipping:
- Install v4 APK with hidden items
- Upgrade to v5 — hidden items must still be hidden

## Verification Checklist

1. `./gradlew :app:assembleDebug` builds without errors after migration
2. Install fresh — timeline shows all photos
3. Hide a photo → it disappears from timeline
4. Force-kill app → relaunch → photo stays hidden
5. Wait 30s (incremental scan debounce) → photo stays hidden
6. Force a full scan via dev tool → photo stays hidden
7. Unhide a photo → it reappears in timeline
8. Favorite a photo → it appears in favorites
9. Move to trash → timeline hides it, trash shows it
10. Add a NEW photo via camera → scan picks it up, no flags leak through
11. Move hidden photo to vault → vaultId flag set in media_flags, photo accessible from vault
12. All existing tests pass; new test covers LEFT JOIN and flag preservation under concurrent scan

## Risks

| Risk | Mitigation |
|---|---|
| Migration corrupts DB | Migration runs in single `@Transaction`. backupDb in DEBUG builds before migration for safety. |
| LEFT JOIN performance regression vs single-table query | Indexes on `media_flags.is_hidden` and `is_trash` cover WHERE clauses. Query plan will use them. |
| Other modules read `MediaEntity.isHidden` directly | Code search confirms `MediaRepository.toModel` is the only path. Update if any direct access exists. |
| Live queries lose Room invalidation when only flags change | LEFT JOIN queries are on the `media` and `media_flags` tables — both tracked. Room invalidation tracker re-fires on either change. |
| Privacy vault encrypted paths | EncryptedPrivacyVault writes flags via `setHidden`/`reserveVaultId` — both now go through MediaFlagDao. Verify by reading that file. |
