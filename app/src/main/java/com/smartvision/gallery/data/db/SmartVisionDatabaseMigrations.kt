package com.smartvision.gallery.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // v1→v2 无实际 schema 变化 (仅内部重构). 保留空迁移以便
        // Room 验证 path: 从 v1 直接升级时仍走此步, schema 校验通过.
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media ADD COLUMN vaultId TEXT")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media ADD COLUMN is_live_photo INTEGER NOT NULL DEFAULT 0")
    }
}

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
        """.trimIndent())

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
        """.trimIndent())

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
        """.trimIndent())

        // Step 6: Backfill flags from media_old. media_flags now exists.
        db.execSQL("""
            INSERT OR IGNORE INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at)
            SELECT uri, is_hidden, is_favorite, is_trash, vaultId,
                   CAST(strftime('%s','now') AS INTEGER) * 1000
            FROM media_old
            WHERE is_hidden = 1 OR is_favorite = 1 OR is_trash = 1 OR vaultId IS NOT NULL
        """.trimIndent())

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

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Drop FK CASCADE on media_flags → media. The FK was causing flags to be
        // wiped whenever any media row was deleted (including routine scans that
        // re-insert stale rows, vacuum, etc.). Recreate the table without FK.
        db.execSQL("ALTER TABLE media_flags RENAME TO media_flags_old")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_flags (
              uri        TEXT    NOT NULL PRIMARY KEY,
              is_hidden  INTEGER NOT NULL DEFAULT 0,
              is_favorite INTEGER NOT NULL DEFAULT 0,
              is_trash   INTEGER NOT NULL DEFAULT 0,
              vault_id   TEXT,
              updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT OR IGNORE INTO media_flags (uri, is_hidden, is_favorite, is_trash, vault_id, updated_at)
            SELECT uri, is_hidden, is_favorite, is_trash, vault_id, updated_at
            FROM media_flags_old
        """.trimIndent())
        db.execSQL("DROP TABLE media_flags_old")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_is_hidden ON media_flags(is_hidden)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_is_trash  ON media_flags(is_trash)")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_domain TEXT")
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_sub_domain TEXT")
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_copyright TEXT")
        // Numeric AI columns are nullable in MediaFlagEntity (Int?/Float?) — leaving
        // them nullable on disk keeps Room's post-migration schema check happy.
        // Forcing NOT NULL here breaks the migration with
        // "notNull=true in DB but expected false from entity".
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_face_count INTEGER DEFAULT 0")
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_face_area REAL DEFAULT 0")
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_score REAL DEFAULT 0")
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_version INTEGER DEFAULT 0")
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_tagged_at INTEGER DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_ai_score ON media_flags(ai_score)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_ai_domain ON media_flags(ai_domain)")
    }
}

/**
 * Migration 7 → 8: rebuild media_flags so the five AI numeric columns are
 * NULLABLE on disk. They were originally added as `NOT NULL DEFAULT 0` in
 * MIGRATION_6_7 because the plan originally had them primitive-valued; the
 * linter later normalized them to `Int?/Float?` so Room's schema validator
 * now expects them nullable. SQLite can't ALTER a column's NOT NULL
 * constraint in place, so we use the standard 12-step table-rebuild.
 *
 * Safe because media_flags is purely derivative of media — even if the
 * rebuild dropped rows, the next media scan repopulates them.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_flags RENAME TO media_flags_old")
        db.execSQL("""
            CREATE TABLE media_flags (
                uri              TEXT NOT NULL PRIMARY KEY,
                is_favorite      INTEGER NOT NULL,
                is_hidden        INTEGER NOT NULL,
                is_trash         INTEGER NOT NULL,
                vault_id         TEXT,
                updated_at       INTEGER NOT NULL,
                ai_domain        TEXT,
                ai_sub_domain    TEXT,
                ai_copyright     TEXT,
                ai_face_count    INTEGER DEFAULT 0,
                ai_face_area     REAL    DEFAULT 0,
                ai_score         REAL    DEFAULT 0,
                ai_version       INTEGER DEFAULT 0,
                ai_tagged_at     INTEGER DEFAULT 0
            )
        """)
        db.execSQL("""
            INSERT INTO media_flags (
                uri, is_favorite, is_hidden, is_trash, vault_id, updated_at,
                ai_domain, ai_sub_domain, ai_copyright,
                ai_face_count, ai_face_area, ai_score, ai_version, ai_tagged_at
            )
            SELECT
                uri, is_favorite, is_hidden, is_trash, vault_id, updated_at,
                ai_domain, ai_sub_domain, ai_copyright,
                ai_face_count, ai_face_area, ai_score, ai_version, ai_tagged_at
            FROM media_flags_old
        """)
        db.execSQL("DROP TABLE media_flags_old")
        // Recreate indexes the validator expects.
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_ai_score   ON media_flags(ai_score)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_ai_domain  ON media_flags(ai_domain)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_is_hidden  ON media_flags(is_hidden)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_is_trash   ON media_flags(is_trash)")
    }
}

/**
 * Migration 8 → 9: 加 ai_danbooru_tags 字段, 用于二次元分类栏目.
 * 仅 ALTER ADD COLUMN — SQLite 支持原地添加 nullable 列, 无需 rebuild table.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_flags ADD COLUMN ai_danbooru_tags TEXT DEFAULT NULL")
    }
}

/**
 * v9→v10: 补 is_favorite 索引. 收藏页/相册按 is_favorite 过滤时
 * 全表扫描 media_flags; 8000+ 行时 LIMIT/EQ 变慢. 与其他 flag 索引一致.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_media_flags_is_favorite ON media_flags(is_favorite)")
    }
}