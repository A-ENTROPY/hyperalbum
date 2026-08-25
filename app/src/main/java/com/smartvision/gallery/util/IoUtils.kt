package com.smartvision.gallery.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Small helpers for cheap file I/O — the codebase intentionally avoids extension functions
 * that hide IO exceptions or that allocate where we can stream instead.
 */
object IoUtils {

    /**
     * Wrap a MediaStore URI to require the original (unredacted) stream.
     *
     * On Android 10+ (API 29+), `contentResolver.openInputStream(uri)` returns an
     * InputStream with location metadata redacted (GPS zeros out). To retrieve the
     * raw EXIF GPS for a photo, **each URI** must be wrapped with this call, which
     * requires `ACCESS_MEDIA_LOCATION` to be granted. Without the permission, the
     * wrapped URI throws [SecurityException] when opened — callers must either
     * gate this with [com.smartvision.gallery.util.PermissionHelper.hasMediaLocationPermission]
     * or wrap it in a try/catch.
     *
     * < Android 10: this is a no-op pass-through (no redaction was ever applied).
     */
    fun requireOriginalUri(context: Context, uri: Uri): Uri {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return uri
        return try {
            MediaStore.setRequireOriginal(uri)
        } catch (e: SecurityException) {
            AppLog.w("IoUtils", "setRequireOriginal denied (no ACCESS_MEDIA_LOCATION): $uri")
            uri
        }
    }

    /** Read up to [length] bytes from a content uri. Returns an empty buffer on failure. */
    fun readHead(context: Context, uri: Uri, length: Int = 64): ByteBuffer {
        val resolver: ContentResolver = context.contentResolver
        return try {
            val stream: InputStream = resolver.openInputStream(uri) ?: return ByteBuffer.allocate(0)
            stream.use { input ->
                val bytes = ByteArray(length)
                val read = input.read(bytes)
                if (read <= 0) ByteBuffer.allocate(0)
                else ByteBuffer.wrap(bytes, 0, read)
            }
        } catch (t: Throwable) {
            AppLog.w("IoUtils", "Failed to read head of $uri", t)
            ByteBuffer.allocate(0)
        }
    }

    fun mimeOf(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        resolver.getType(uri)?.let { return it }
        val ext = uri.lastPathSegment?.substringAfterLast('.', "") ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase())
    }

    fun queryForFile(context: Context, uri: Uri): MediaStoreFileMeta? {
        // Best-effort lookup of MediaStore.VIDEO / IMAGE columns by URI.
        val resolver = context.contentResolver
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT
        )
        return try {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                MediaStoreFileMeta(
                    displayName = c.getString(1) ?: "",
                    sizeBytes = c.getLong(2),
                    mimeType = c.getString(3),
                    dateAddedSec = c.getLong(4),
                    dateModifiedSec = c.getLong(5),
                    absolutePath = c.getString(6),
                    width = c.getInt(7),
                    height = c.getInt(8)
                )
            }
        } catch (t: Throwable) {
            AppLog.w("IoUtils", "queryForFile failed for $uri", t)
            null
        }
    }
}

data class MediaStoreFileMeta(
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val absolutePath: String?,
    val width: Int,
    val height: Int
)