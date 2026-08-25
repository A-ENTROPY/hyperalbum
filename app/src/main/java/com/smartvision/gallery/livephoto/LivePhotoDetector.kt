package com.smartvision.gallery.livephoto

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One Live Photo = a still image paired with a short motion video, taken with
 * iOS's Live Photo feature.
 *
 * The detector finds pairs by walking the MediaStore.Video collection for files
 * whose base name (minus the extension) matches the base name of the still. iOS
 * keeps the same file stem for the .HEIC and .MOV files (`IMG_1234.HEIC` +
 * `IMG_1234.MOV`), and many Android cameras copy that convention over.
 */
data class LivePhoto(
    val photoUri: Uri,
    val videoUri: Uri,
    val durationMs: Long
)

object LivePhotoDetector {

    private const val TAG = "LivePhotoDetector"

    /**
     * Best-effort lookup for a Live Photo pair by [photoUri]. Returns `null` if no
     * matching motion video is found in the device's MediaStore.
     */
    suspend fun findLivePhoto(context: Context, photoUri: Uri): LivePhoto? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val photoName = queryDisplayName(resolver, photoUri) ?: return@withContext null
        val stem = photoName.substringBeforeLast('.', photoName)
        if (stem.isBlank()) return@withContext null

        val videoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATA
        )
        val candidates = mutableListOf<VideoCandidate>()
        try {
            resolver.query(videoCollection, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val pathCol = c.getColumnIndex(MediaStore.Video.Media.DATA)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val candStem = name.substringBeforeLast('.', name)
                    if (candStem == stem) {
                        candidates += VideoCandidate(
                            uri = ContentUris.withAppendedId(videoCollection, c.getLong(idCol)),
                            durationMs = c.getLong(durCol),
                            path = if (pathCol >= 0) c.getString(pathCol) else null
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "Failed to query videos for $photoUri", t)
            return@withContext null
        }

        // Heuristic: pick the shortest video that is ≤ 5s. Real Live Photos are 1-3s.
        // If we have multiple candidates (e.g. a regular video with the same name),
        // shortest wins.
        val best = candidates
            .filter { it.durationMs in 500L..5_000L }
            .minByOrNull { it.durationMs }
            ?: candidates.minByOrNull { it.durationMs }
        if (best == null) {
            AppLog.d(TAG, "No candidate for $stem (${candidates.size} total)")
            return@withContext null
        }
        AppLog.d(TAG, "Found Live Photo for $stem at ${best.uri} (${best.durationMs}ms)")
        LivePhoto(photoUri = photoUri, videoUri = best.uri, durationMs = best.durationMs)
    }

    private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "queryDisplayName failed for $uri", t)
            null
        }
    }

    private data class VideoCandidate(
        val uri: Uri,
        val durationMs: Long,
        val path: String?
    )
}