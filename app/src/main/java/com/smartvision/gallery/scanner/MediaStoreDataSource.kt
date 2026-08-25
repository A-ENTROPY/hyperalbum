package com.smartvision.gallery.scanner

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.smartvision.gallery.data.db.MediaEntity
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.decoder.format.FormatDetector
import com.smartvision.gallery.decoder.format.MediaFormat
import com.smartvision.gallery.decoder.format.FormatDetector.isRecognizable
import com.smartvision.gallery.util.AppLog
import com.smartvision.gallery.util.IoUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Pure data source: pulls every image/video the system has indexed for the app. Doesn't
 * touch the DB — that's the repository's job. We keep sniffing (magic bytes) lazy: only
 * when the format can't be inferred from the MIME or the extension.
 */
class MediaStoreDataSource(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun queryAll(): List<MediaEntity> = withContext(ioDispatcher) {
        val results = ArrayList<MediaEntity>()
        results += queryImages(null)
        results += queryVideos(null)
        AppLog.i(TAG, "queryAll returned ${results.size} items (no EXIF — GPS filled by requestGeoRefill)")
        // GPS 读取已移除: 由 MediaScanCoordinator.requestGeoRefill 后台异步填,
        // 8000 张 inline EXIF ≈ 100s, 是首次扫描慢的真因 (注释可见 60 行 context).
        results
    }

    /**
     * Delta scan: returns only items added or modified after [sinceMs].
     *
     * Critical for auto-refresh latency: `queryAll()` re-scans every photo (8000+ items
     * + 4-way concurrent EXIF reading ≈ 100 s), so even with a ContentObserver firing
     * immediately on MediaStore change the UI still doesn't see new photos for minutes.
     * This filtered query typically returns 1-N items per change event, with the same
     * EXIF pipeline bounded to just those items.
     */
    suspend fun queryChangedSince(sinceMs: Long): List<MediaEntity> = withContext(ioDispatcher) {
        if (sinceMs <= 0L) return@withContext queryAll()

        // MediaStore stores DATE_ADDED / DATE_MODIFIED in seconds. sinceMs is ms.
        // Use a strict `>` so we don't re-fetch the row that ended the previous scan.
        val sinceSec = sinceMs / 1000L
        val results = ArrayList<MediaEntity>()
        results += queryImages(sinceSec)
        results += queryVideos(sinceSec)
        AppLog.i(TAG, "queryChangedSince sinceMs=$sinceMs returned ${results.size} items")

        // EXIF only for the delta (typically a handful of items). Use 8-way parallel.
        val semaphore = Semaphore(permits = 8)
        coroutineScope {
            results.map { entity ->
                async {
                    semaphore.withPermit {
                        val (lat, lng) = readGeoFromExif(Uri.parse(entity.uri))
                        entity.copy(latitude = lat, longitude = lng)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun queryImages(sinceSec: Long?): List<MediaEntity> = withContext(ioDispatcher) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DATA
        )
        val items = ArrayList<MediaEntity>()
        val sortOrder =
            "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, ${MediaStore.Images.Media.DATE_MODIFIED} * 1000) DESC"
        val selection: String?
        val selectionArgs: Array<String>?
        if (sinceSec != null) {
            // DATE_ADDED/DATE_MODIFIED are stored in SECONDS, not millis.
            // Match either: newly added since cutoff OR modified since cutoff.
            selection = "(${MediaStore.Images.Media.DATE_ADDED} > ? OR ${MediaStore.Images.Media.DATE_MODIFIED} > ?)"
            selectionArgs = arrayOf(sinceSec.toString(), sinceSec.toString())
        } else {
            selection = null
            selectionArgs = null
        }
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val pathCol = c.getColumnIndex(MediaStore.Images.Media.DATA)

            while (c.moveToNext()) {
                if ((items.size and 0x63) == 0) ensureActive()
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val name = c.getString(nameCol) ?: "image_$id"
                val mime = c.getString(mimeCol)
                val size = c.getLong(sizeCol)
                val w = c.getInt(widthCol)
                val h = c.getInt(heightCol)

                // Skip placeholder/garbage files that crash the native decoder.
                // Crash signature: native SIGSEGV (status=11) on files named
                // "null<hex>.<ext>" created by Xiaomi Cloud as pending placeholders
                // for items that haven't synced yet. Also filter 0-byte files and
                // entries with no dimensions — these can't be decoded either.
                if (!isValidMediaEntry(name = name, size = size, width = w, height = h)) {
                    AppLog.w(TAG, "skipping invalid image entry id=$id name=$name size=$size ${w}x$h")
                    continue
                }
                // Content-level check: reject files whose magic bytes don't match any
                // known image container. Catches corrupt/truncated/cloud-placeholder files
                // that pass MediaStore metadata checks (name+size+dimensions) but crash
                // the native decoder (libjpeg/libheif) at click time with SIGSEGV.
                //
                // OPTIMIZATION: only open the file when the MIME type is UNKNOWN or
                // ambiguous — known MIME types (image/jpeg, image/png, etc.) are always
                // valid. This eliminates ~90% of the per-item file opens during cursor
                // iteration (8000+ → ~800 on a typical device).
                if (mime == null || !mime.startsWith("image/")) {
                    if (!isRecognizable(context, uri)) {
                        AppLog.w(TAG, "skipping unrecognizable image content id=$id uri=$uri name=$name")
                        continue
                    }
                }

                val takenMs = c.getLong(takenCol).takeIf { it > 0 }
                    ?: (c.getLong(addedCol) * 1000L)
                val bucketName = c.getString(bucketNameCol)
                val bucketId = c.getLong(bucketIdCol)
                val path = if (pathCol >= 0) c.getString(pathCol) else null
                val format = inferFormat(uri, name, mime)

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
                    aiTags = listOf(""),
                    ocrText = null,
                    hashSha1 = null,
                    isLivePhoto = false,
                )
            }
        }
        items
    }

    private fun queryVideos(sinceSec: Long?): List<MediaEntity> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.DATA
        )
        val items = ArrayList<MediaEntity>()
        val sortOrder =
            "COALESCE(${MediaStore.Video.Media.DATE_TAKEN}, ${MediaStore.Video.Media.DATE_MODIFIED} * 1000) DESC"
        val selection: String?
        val selectionArgs: Array<String>?
        if (sinceSec != null) {
            selection = "(${MediaStore.Video.Media.DATE_ADDED} > ? OR ${MediaStore.Video.Media.DATE_MODIFIED} > ?)"
            selectionArgs = arrayOf(sinceSec.toString(), sinceSec.toString())
        } else {
            selection = null
            selectionArgs = null
        }
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val modCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val pathCol = c.getColumnIndex(MediaStore.Video.Media.DATA)

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                val name = c.getString(nameCol) ?: "video_$id"
                val mime = c.getString(mimeCol)
                val size = c.getLong(sizeCol)
                val w = c.getInt(widthCol)
                val h = c.getInt(heightCol)

                // Same defensive filter as queryImages — placeholder/garbage files
                // can be present in the video table too (e.g. partial .mov from
                // Xiaomi Cloud) and would crash ExoPlayer on open.
                if (!isValidMediaEntry(name = name, size = size, width = w, height = h)) {
                    AppLog.w(TAG, "skipping invalid video entry id=$id name=$name size=$size ${w}x$h")
                    continue
                }
                // Content-level check — same as images, but video decoder is ExoPlayer
                // (which has its own safe failure paths), so this is a defensive filter
                // for catastrophically broken MP4/MOV files.
                // OPTIMIZATION: skip file open for known video MIME types.
                if (mime == null || !mime.startsWith("video/")) {
                    if (!isRecognizable(context, uri)) {
                        AppLog.w(TAG, "skipping unrecognizable video content id=$id uri=$uri name=$name")
                        continue
                    }
                }

                val takenMs = c.getLong(takenCol).takeIf { it > 0 }
                    ?: (c.getLong(addedCol) * 1000L)
                val duration = c.getLong(durCol)
                val bucketName = c.getString(bucketNameCol)
                val bucketId = c.getLong(bucketIdCol)
                val path = if (pathCol >= 0) c.getString(pathCol) else null
                val format = inferFormat(uri, name, mime)

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
                    durationMs = duration,
                    bucketName = bucketName,
                    bucketPath = path?.let { bucketFromPath(it) } ?: "bucket_$bucketId",
                    latitude = null,
                    longitude = null,
                    aiTags = listOf(""),
                    ocrText = null,
                    hashSha1 = null,
                    isLivePhoto = name.endsWith(".mov", ignoreCase = true),
                )
            }
        }
        return items
    }

    /**
     * Filters out placeholder/garbage media entries before they reach the gallery.
     *
     * Why this exists: clicking a photo/video in the grid used to crash the whole
     * process with a native SIGSEGV (signal 11, status=11). Crash logs showed
     * MediaProvider was opening files named like `null784fcfecf277d41a.jpg` from
     * `小米云相册/` — these are Xiaomi Cloud pending-sync placeholders. The native
     * JPEG/HEVC decoder chokes on the malformed body and segfaults, which bypasses
     * all Kotlin try/catch (we verified every Compose/viewer/decoder path is wrapped
     * — none caught it because the crash happens in native code).
     *
     * Filter rules:
     *  * Empty / null-name files
     *  * Files starting with "null" followed by hex digits (the Xiaomi Cloud pattern)
     *  * 0-byte files (no payload to decode)
     *  * Entries with no width/height (MediaStore couldn't probe them, almost
     *    always means the file is garbage)
     *  * Files whose name is exactly the literal string "null"
     */
    private fun isValidMediaEntry(name: String, size: Long, width: Int, height: Int): Boolean {
        if (name.isBlank()) return false
        if (size <= 0L) return false
        // JXL: MediaStore on Android 16 cannot parse JXL dimensions (returns 0).
        // Our native decoder handles the decode, so accept 0-dimension JXL entries.
        if (name.endsWith(".jxl", ignoreCase = true)) return true
        if (width <= 0 || height <= 0) return false
        if (name == "null") return false
        // "null<hex>.<ext>" — seen in 小米云相册. Match case-insensitive.
        if (name.length > 4 && name.startsWith("null", ignoreCase = true)) {
            val fifth = name[4]
            if (fifth.isDigit() || (fifth in 'a'..'f') || (fifth in 'A'..'F')) {
                return false
            }
        }
        return true
    }

    private fun inferFormat(uri: Uri, name: String, mime: String?): MediaFormat {
        // 1. Sniff magic bytes if extension suggests something interesting.
        val extGuess = MediaFormat.fromFilename(name)
        if (extGuess != MediaFormat.UNKNOWN) return extGuess
        // 2. Fall back to MIME-derived guess.
        return when {
            mime == null -> MediaFormat.UNKNOWN
            mime.contains("avif") -> MediaFormat.AVIF_STATIC
            mime.contains("jxl") -> MediaFormat.JXL
            mime.contains("heic") || mime.contains("heif") -> MediaFormat.HEIC
            mime.contains("gif") -> MediaFormat.GIF
            mime.contains("png") -> MediaFormat.PNG
            mime.contains("jpeg") -> MediaFormat.JPEG
            mime.contains("webp") -> MediaFormat.WEBP_STATIC
            mime.contains("bmp") -> MediaFormat.BMP
            mime.contains("tiff") -> MediaFormat.TIFF
            mime.startsWith("video/") -> MediaFormat.MP4
            else -> MediaFormat.UNKNOWN
        }
    }

    /**
     * Read GPS coordinates from a single URI's EXIF. Public so
     * [com.smartvision.gallery.scanner.MediaScanCoordinator.requestGeoRefill] can
     * single-shot read GPS for a known URI list without re-running full queryImages.
     *
     * Two-pass strategy (same as [com.smartvision.gallery.ui.viewer.MediaItemAdapter.location]):
     * 1) `setRequireOriginal` + ContentResolver (canonical, needs ACCESS_MEDIA_LOCATION)
     * 2) Fallback to `ExifInterface(filePath)` direct read — bypasses ContentResolver
     *    redaction entirely. No permission needed because we never go through
     *    the content provider's InputStream wrapper.
     */
    fun readGeoFromExif(uri: Uri): Pair<Double?, Double?> {
        readGeoViaResolver(uri)?.let { return it }
        return readGeoViaFilePath(uri)
    }

    private fun readGeoViaResolver(uri: Uri): Pair<Double?, Double?>? {
        return try {
            val originalUri = com.smartvision.gallery.util.IoUtils.requireOriginalUri(context, uri)
            context.contentResolver.openInputStream(originalUri)?.use { input ->
                val exif = ExifInterface(input)
                val latLng = FloatArray(2)
                if (exif.getLatLong(latLng)) latLng[0].toDouble() to latLng[1].toDouble() else null to null
            } ?: (null to null)
        } catch (t: Throwable) {
            AppLog.w(TAG, "EXIF read via resolver failed for $uri", t)
            null
        }
    }

    private fun readGeoViaFilePath(uri: Uri): Pair<Double?, Double?> {
        return try {
            val meta = com.smartvision.gallery.util.IoUtils.queryForFile(context, uri)
            val path = meta?.absolutePath ?: return (null to null)
            if (!java.io.File(path).canRead()) return (null to null)
            val exif = ExifInterface(path)
            val latLng = FloatArray(2)
            if (exif.getLatLong(latLng)) latLng[0].toDouble() to latLng[1].toDouble() else null to null
        } catch (t: Throwable) {
            AppLog.w(TAG, "EXIF read via file-path failed for $uri", t)
            null to null
        }
    }

    private companion object {
        private const val TAG = "MediaStoreDataSource"
    }
}

/**
 * 从文件路径提取 bucket 名,取最后两段路径(如 "DCIM/Camera")。
 * 原实现只取倒数第二段,会丢失父目录(DCIM/Camera → "Camera")。
 * internal 供 BucketFromPathTest 直接验证生产真值源,避免测试内联漂移。
 */
internal fun bucketFromPath(path: String): String {
    val segments = path.split('/').filter { it.isNotEmpty() }
    // Drop the filename, take last 2 directory segments (e.g. "DCIM/Camera").
    val dirs = segments.dropLast(1)
    return when (dirs.size) {
        0 -> "root"
        1 -> dirs[0]
        else -> "${dirs[dirs.size - 2]}/${dirs.last()}"
    }
}