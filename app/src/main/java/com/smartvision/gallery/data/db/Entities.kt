package com.smartvision.gallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
/**
 * Room representation of a [com.smartvision.gallery.data.model.MediaItem].
 *
 * `uri` is stored as a String (Room doesn't natively support Uri in TypeConverters across
 * all our flows; storing the canonical MediaStore content URI as TEXT is sufficient).
 */
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

/** Album denormalised for fast UI rendering. */
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "latest_date_ms") val latestDateMs: Long,
    @ColumnInfo(name = "format_filter") val formatFilter: String?,
    @ColumnInfo(name = "bucket_path") val bucketPath: String?
)

/** Persisted user album (explicit pin/collection). */
@Entity(
    tableName = "user_album_items",
    indices = [Index(value = ["album_id", "media_uri"], unique = true)]
)
data class UserAlbumItemEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "album_id") val albumId: String,
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "added_ms") val addedMs: Long
)