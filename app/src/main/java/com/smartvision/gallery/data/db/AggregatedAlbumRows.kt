package com.smartvision.gallery.data.db

/**
 * 聚合行 — 由 SQL 在数据库内核做 group by + count + max，避免把 5k+ MediaItem
 * 拉到 Kotlin 端重新 O(n×formats) 过滤。
 *
 * 每个 Row 携带的字段刚好够 Repository 拼 Album 对象用。Room 用列名匹配字段名，
 * 经过 `@Query(... AS foo)` 后 SQLite 给出的列名就是 foo，无需 @ColumnInfo。
 */
data class FormatAlbumRow(
    val format: String,
    val item_count: Int,
    val latest_date_ms: Long,
    /** 该 format 下最新一条的 uri 用作 cover。可能为 null（只在 format 全部被 trash 时出现，理论上不会发生）。 */
    val cover_uri: String?,
)

data class BucketAlbumRow(
    val bucket_path: String?,
    val bucket_name: String?,
    val item_count: Int,
    val latest_date_ms: Long,
    val cover_uri: String?,
)

data class FavoritesMetaRow(
    val item_count: Int,
    val latest_date_ms: Long,
    val cover_uri: String?,
)
