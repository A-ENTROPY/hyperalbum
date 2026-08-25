package com.smartvision.gallery.ui.viewer

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.decoder.format.MediaFormat

/**
 * Lightweight adapter from a bare [Uri] to the metadata operations the viewer
 * needs (favorite toggle, delete, share). Avoids round-tripping through
 * [com.smartvision.gallery.data.MediaRepository] for one-shot operations.
 *
 * Why this exists: the standalone [PhotoViewerActivity] receives only URIs from
 * its Intent extras. Operations like Share / Delete / Favorite need at least a
 * MIME type and display name; rather than re-construct a full [MediaItem] (which
 * needs bucket + dateTaken + size + many more columns) we query the smallest
 * possible column set per call.
 */
object MediaItemAdapter {

    fun mimeType(context: Context, uri: Uri): String? =
        context.contentResolver.getType(uri)

    fun isVideo(context: Context, uri: Uri): Boolean =
        (mimeType(context, uri) ?: "").startsWith("video/")

    fun isImage(context: Context, uri: Uri): Boolean =
        (mimeType(context, uri) ?: "").startsWith("image/")

    /**
     * Query MediaStore.Images for the display name + favorite flag of [uri].
     * Returns null if [uri] is not a MediaStore image URI.
     */
    fun queryImage(context: Context, uri: Uri): ImageMeta? {
        if (!uri.toString().startsWith("content://media/")) return null
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    "_favorite"
                ),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val favIdx = c.getColumnIndex("_favorite")
                ImageMeta(
                    displayName = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else "",
                    mimeType = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else "",
                    isFavorite = favIdx >= 0 && c.getInt(favIdx) == 1
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Toggle favorite flag in MediaStore. Returns the new state, or null on
     * failure. Caller should show a snackbar with the failure message.
     *
     * **Deprecated for the viewer path** — on Android 11+ this fails for
     * non-owned media with SecurityException swallowed as null. Use
     * [favoriteWriteRequestIntent] + caller-side launcher pattern instead.
     * Retained for legacy / owned-media code paths.
     */
    fun toggleFavorite(context: Context, uri: Uri): Boolean? {
        val current = queryImage(context, uri)?.isFavorite ?: return null
        val newValue = if (current) 0 else 1
        val values = android.content.ContentValues().apply { put("_favorite", newValue) }
        return try {
            val n = context.contentResolver.update(uri, values, null, null)
            if (n > 0) !current else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Result of a favorite toggle attempt. The caller drives the system
     * consent dialog when [FavoriteToggleResult.NeedsWriteRequest] is
     * returned; on consent, retry the in-process toggle.
     */
    sealed interface FavoriteToggleResult {
        /** Toggle succeeded in-process; new state is [newFavorite]. */
        data class Success(val newFavorite: Boolean) : FavoriteToggleResult
        /** Caller MUST launch the [intentSender] system dialog, then retry. */
        data class NeedsWriteRequest(
            val intentSender: android.content.IntentSender,
            val targetFavorite: Boolean,
        ) : FavoriteToggleResult
        /** Hard failure (URI not MediaStore, query failed, etc.). */
        data object Failed : FavoriteToggleResult
    }

    /**
     * Attempt favorite toggle. On Android R+ scoped storage,non-owned media
     * requires `MediaStore.createWriteRequest` consent — same flow as
     * [deleteRequestIntent]. The caller is responsible for launching the
     * returned [android.content.IntentSender] and retrying the toggle on
     * user approval.
     *
     * Order:
     *  1. Query current favorite state.
     *  2. Try `contentResolver.update` directly (works for owned media +
     *     pre-R devices).
     *  3. On SecurityException, build a `createWriteRequest` IntentSender
     *     targeting the same URI. The caller launches it; after the user
     *     grants consent, the caller retries this method — the second call
     *     succeeds because the write grant is now cached for the session.
     */
    fun toggleFavoriteWithConsent(context: Context, uri: Uri): FavoriteToggleResult {
        val current = queryImage(context, uri)?.isFavorite
            ?: return FavoriteToggleResult.Failed
        val target = !current
        val newValue = if (target) 1 else 0
        val values = android.content.ContentValues().apply { put("_favorite", newValue) }
        return try {
            val n = context.contentResolver.update(uri, values, null, null)
            if (n > 0) FavoriteToggleResult.Success(target)
            else FavoriteToggleResult.Failed
        } catch (security: SecurityException) {
            // API 30+ non-owned media — request one-time write consent.
            val sender = favoriteWriteRequestIntent(context, listOf(uri))
                ?: return FavoriteToggleResult.Failed
            FavoriteToggleResult.NeedsWriteRequest(sender, target)
        } catch (e: Exception) {
            // DataLossException, RecoverableSecurityException (API 29) 等
            // 统一处理为失败 — R 以下无 createWriteRequest，无授权路径可走。
            FavoriteToggleResult.Failed
        }
    }

    /**
     * Apply the new favorite value after the user has granted write consent
     * via the [FavoriteToggleResult.NeedsWriteRequest.intentSender] dialog.
     * Returns the new state on success, null on failure.
     */
    fun applyFavorite(context: Context, uri: Uri, target: Boolean): Boolean? {
        val newValue = if (target) 1 else 0
        val values = android.content.ContentValues().apply { put("_favorite", newValue) }
        return try {
            val n = context.contentResolver.update(uri, values, null, null)
            if (n > 0) target else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Build a system confirmation dialog intent for granting write access
     * to [uris] on Android R+ (API 30+). Returns null on older API levels
     * or on failure.
     *
     * Same pattern as [deleteRequestIntent] but uses `createWriteRequest`
     * — the user-visible dialog asks "Allow <app> to modify N item(s)?".
     * The grant is one-shot but persists for the lifetime of the resumed
     * session, so the caller can immediately retry the in-process update
     * after the user approves.
     */
    fun favoriteWriteRequestIntent(context: Context, uris: List<Uri>): android.content.IntentSender? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        if (uris.isEmpty()) return null
        return runCatching {
            android.provider.MediaStore.createWriteRequest(context.contentResolver, uris)
                .intentSender
        }.getOrNull()
    }

    /**
     * Delete via MediaStore. Returns true on success.
     *
     * On Android 10+ (API 30+) deleting a media file NOT created by your
     * app throws `RecoverableSecurityException` (or `SecurityException` on
     * R+) unless the user grants one-shot consent via the system dialog.
     * Callers MUST use [deleteRequestIntent] on those API levels instead.
     * This synchronous path is only safe for legacy (API < 30) devices or
     * for media files owned by the app itself.
     */
    fun delete(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: Exception) {
        false
    }

    /**
     * Build a system confirmation dialog intent for deleting [uris] on
     * Android R+ (API 30+). Returns null on older API levels (callers
     * should fall back to [delete] which works without consent there).
     *
     * Why this exists: `ContentResolver.delete()` throws
     * `RecoverableSecurityException` for files not owned by your app on
     * scoped storage. `MediaStore.createDeleteRequest` is the official
     * path — it batches all URIs into a single system confirmation
     * dialog and handles all permission + physical deletion for you.
     */
    fun deleteRequestIntent(context: Context, uris: List<Uri>): android.content.IntentSender? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        if (uris.isEmpty()) return null
        return runCatching {
            android.provider.MediaStore.createDeleteRequest(context.contentResolver, uris)
                .intentSender
        }.getOrNull()
    }

    /**
     * Read EXIF GPS coordinates from a URI. Returns `doubleArrayOf(lat, lng)` or
     * null if the URI has no GPS tag / cannot be opened.
     *
     * Two reads in sequence — the file-path one is the real fallback:
     *
     * 1. **ContentResolver + `setRequireOriginal`** — requires `ACCESS_MEDIA_LOCATION`
     *    on Android 10+. On some OEM ROMs (Oplus) this permission is silently denied
     *    even when the user "grants" it from system settings, so this path returns
     *    redacted EXIF (no GPS) and we get a false negative.
     *
     * 2. **`ExifInterface(path)` direct file read** — bypasses MediaStore's InputStream
     *    redaction entirely. Requires the absolute file path, which we get from
     *    `IoUtils.queryForFile` (the `DATA` column is unrestricted on scoped-storage
     *    images). Works without `ACCESS_MEDIA_LOCATION` because we never touch the
     *    content provider's redacting wrapper.
     *
     * The ContentResolver path is kept because it's the canonical method on stock
     * Android and might be the only option on devices where the photo lives outside
     * our app's visible file tree.
     */
    fun location(context: Context, uri: Uri): DoubleArray? {
        // Path 1: MediaStore-required (canonical on stock Android).
        readGeoViaContentResolver(context, uri)?.let { return it }
        // Path 2: file-path fallback (works on ROMs where MediaLocation permission
        // doesn't actually take effect).
        return readGeoViaFilePath(context, uri)
    }

    private fun readGeoViaContentResolver(context: Context, uri: Uri): DoubleArray? = try {
        val originalUri = com.smartvision.gallery.util.IoUtils.requireOriginalUri(context, uri)
        context.contentResolver.openInputStream(originalUri)?.use { input ->
            val exif = ExifInterface(input)
            exifToLatLng(exif)
        }
    } catch (_: Throwable) { null }

    private fun readGeoViaFilePath(context: Context, uri: Uri): DoubleArray? {
        return try {
            val meta = com.smartvision.gallery.util.IoUtils.queryForFile(context, uri)
            val path = meta?.absolutePath ?: return null
            if (!java.io.File(path).canRead()) return null
            exifToLatLng(ExifInterface(path))
        } catch (_: Throwable) { null }
    }

    private fun exifToLatLng(exif: ExifInterface): DoubleArray? {
        val ll = FloatArray(2)
        return if (exif.getLatLong(ll)) doubleArrayOf(ll[0].toDouble(), ll[1].toDouble()) else null
    }

    /**
     * Construct a minimal [MediaItem] from a content URI by querying MediaStore.
     * Only fills the fields needed for vault/share/export operations.
     * Returns null if the URI cannot be resolved.
     */
    fun buildMediaItem(context: Context, uri: Uri): MediaItem? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.MIME_TYPE,
                    MediaStore.Images.Media.SIZE,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_MODIFIED,
                    MediaStore.Images.Media.DATA,
                ),
                null, null, null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val nameIdx = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
                val sizeIdx = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                val wIdx = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val hIdx = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val takenIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val modifiedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val dataIdx = c.getColumnIndex(MediaStore.Images.Media.DATA)

                val displayName = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                val mimeType = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else ""
                val sizeBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                val width = if (wIdx >= 0) c.getInt(wIdx) else 0
                val height = if (hIdx >= 0) c.getInt(hIdx) else 0
                val dateTakenMs = if (takenIdx >= 0) c.getLong(takenIdx) else 0L
                val dateModifiedMs = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else 0L
                val dataPath = if (dataIdx >= 0) c.getString(dataIdx) else null
                val bucketPath = dataPath?.substringBeforeLast("/", "")

                MediaItem(
                    id = 0L,
                    uri = uri,
                    displayName = displayName,
                    mimeType = mimeType,
                    format = MediaFormat.fromFilename(displayName),
                    sizeBytes = sizeBytes,
                    width = width,
                    height = height,
                    dateTakenMs = dateTakenMs,
                    dateModifiedMs = dateModifiedMs,
                    bucketPath = bucketPath,
                    isFavorite = false,
                    isHidden = false,
                    isInTrash = false,
                    isLivePhoto = false,
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    data class ImageMeta(
        val displayName: String,
        val mimeType: String,
        val isFavorite: Boolean,
    )
}