package com.smartvision.gallery.data.model

import android.net.Uri
import android.os.Parcelable
import androidx.compose.runtime.Stable
import com.smartvision.gallery.decoder.format.MediaFormat
import kotlinx.parcelize.Parcelize

/**
 * Canonical description of a media item that the rest of the app speaks.
 *
 * `uri` is the only stable identifier — `id` is a convenience used by MediaStore ordering.
 * `format` is filled in at scan time and updated lazily for files where sniffing was deferred.
 */
@Parcelize
@Stable
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val format: MediaFormat,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateTakenMs: Long,
    val dateModifiedMs: Long,
    val durationMs: Long? = null,
    val bucketName: String? = null,
    val bucketPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val isInTrash: Boolean = false,
    val vaultId: String? = null,
    val aiTags: List<String> = emptyList(),
    val ocrText: String? = null,
    val hashSha1: String? = null,
    val isLivePhoto: Boolean = false,
    val aiDomain: String? = null,
    val aiSubDomain: String? = null,
    val aiCopyright: String? = null,
    val aiFaceCount: Int? = 0,
    val aiFaceArea: Float? = 0f,
    val aiScore: Float? = 0f,
    val aiVersion: Int? = 0,
    // v29: 二次元分类栏目 — 仅 domain==anime 写入, 含 Danbooru top-20 tags 序列化 JSON.
    val aiDanbooruTags: String? = null,
) : Parcelable {
    val isVideo: Boolean get() = format.isVideo
    val isAnimated: Boolean get() = format.isAnimated
    val isPhoto: Boolean get() = !isVideo

    val aspectRatio: Float
        get() = if (height > 0 && width > 0) width.toFloat() / height else 1f

    /**
     * Heuristic: is this item a screen capture? We use bucket name + path + file
     * name to avoid pulling the device's "Screenshots" album into curated
     * carousels (Memories, "本周精选"). The full library still shows them, but
     * they get a "Screenshots" bucket and never sit at the top of a curated row.
     *
     * Catches: "Screenshots", "屏幕截图", "截屏", "Screenshot",
     * "Screenshot_20240101_120000.png", and dev/SDK screencap leftovers like
     * "/s.png" that get indexed when adb dumps a raw screencap at the sdcard
     * root.
     */
    val isScreenshot: Boolean
        get() {
            val name = bucketName.orEmpty().lowercase()
            val path = bucketPath.orEmpty().lowercase()
            val file = displayName.lowercase()
            val bucketSaysYes = name.contains("screenshot") ||
                name.contains("截屏") || name.contains("屏幕截图") ||
                name.contains("螢幕擷取") || name.contains("screen shot") ||
                name.contains("截圖") || name.contains("录屏") || name.contains("錄屏")
            val pathSaysYes = path.contains("/screenshot") || path.contains("/screenshots")
            // Filename patterns: "Screenshot_*.png", "screenshot_*.jpg",
            // "screencap*.png" (some ROMs use this), "screen.png", "s.png"
            // (adb test artifacts).
            val fileSaysYes = file.startsWith("screenshot_") ||
                file.startsWith("screencap") ||
                file.startsWith("screenrecording") ||
                file.startsWith("screen_record") ||
                file == "s.png" || file == "screen.png" ||
                file == "screenrecord.mp4" || file == "screen_record.mp4"
            return bucketSaysYes || pathSaysYes || fileSaysYes
        }
}