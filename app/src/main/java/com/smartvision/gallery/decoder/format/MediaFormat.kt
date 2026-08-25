package com.smartvision.gallery.decoder.format

import androidx.annotation.ColorRes
import com.smartvision.gallery.R

/**
 * All media formats the app understands. The [canonicalExtensions] list is the source of
 * truth for "what does the system think this file is" before we run magic-byte detection.
 *
 *  * [isNative] = supported by `android.media.ImageDecoder` or `BitmapFactory` without our help.
 *  * [needsNativeBridge] = requires a JNI call into libsmartvision_decoder.
 *  * [isAnimated] = multi-frame. The viewer takes a different rendering path for these.
 *  * [isNextGen] = AVIF / JXL / WebP2 etc. Drives the "format badge" UI and the "save as
 *    next-gen" export option.
 */
enum class MediaFormat(
    val displayName: String,
    val canonicalExtensions: Set<String>,
    @ColorRes val badgeColorRes: Int,
    val isVideo: Boolean = false,
    val isAnimated: Boolean = false,
    val isNextGen: Boolean = false,
    val isNative: Boolean = true,
    val needsNativeBridge: Boolean = false,
    val isLossless: Boolean = false
) {
    JPEG(
        displayName = "JPEG",
        canonicalExtensions = setOf("jpg", "jpeg", "jpe", "jfif"),
        badgeColorRes = R.color.format_tag_jpeg,
        isLossless = false
    ),
    PNG(
        displayName = "PNG",
        canonicalExtensions = setOf("png"),
        badgeColorRes = R.color.format_tag_png,
        isLossless = true
    ),
    GIF(
        displayName = "GIF",
        canonicalExtensions = setOf("gif"),
        badgeColorRes = R.color.format_tag_gif,
        isAnimated = true
    ),
    BMP(
        displayName = "BMP",
        canonicalExtensions = setOf("bmp", "dib"),
        badgeColorRes = R.color.format_tag_default
    ),
    WEBP_STATIC(
        displayName = "WebP",
        canonicalExtensions = setOf("webp"),
        badgeColorRes = R.color.format_tag_webp
    ),
    WEBP_ANIMATED(
        displayName = "WebP",
        canonicalExtensions = setOf("webp"),
        badgeColorRes = R.color.format_tag_webp,
        isAnimated = true
    ),
    HEIC(
        displayName = "HEIC",
        canonicalExtensions = setOf("heic", "heif", "hif"),
        badgeColorRes = R.color.format_tag_heic,
        isNextGen = true
    ),
    HEIC_SEQ(
        displayName = "HEIC seq",
        canonicalExtensions = setOf("heics", "heifs"),
        badgeColorRes = R.color.format_tag_heic,
        isAnimated = true,
        isNextGen = true
    ),
    AVIF_STATIC(
        displayName = "AVIF",
        canonicalExtensions = setOf("avif", "avifs"),
        badgeColorRes = R.color.format_tag_avif,
        isNextGen = true,
        // We can rely on Android 12+'s ImageDecoder; below that we fall back to native.
        needsNativeBridge = false
    ),
    AVIF_ANIMATED(
        displayName = "AVIF",
        canonicalExtensions = setOf("avif", "avifs"),
        badgeColorRes = R.color.format_tag_avif,
        isAnimated = true,
        isNextGen = true
    ),
    JXL(
        displayName = "JPEG XL",
        canonicalExtensions = setOf("jxl"),
        badgeColorRes = R.color.format_tag_jxl,
        isNextGen = true,
        // Always need the native bridge; no JVM decoder shipped with Android.
        isNative = false,
        needsNativeBridge = true
    ),
    TIFF(
        displayName = "TIFF",
        canonicalExtensions = setOf("tif", "tiff"),
        badgeColorRes = R.color.format_tag_default
    ),
    RAW(
        displayName = "RAW",
        canonicalExtensions = setOf("dng", "cr2", "cr3", "nef", "arw", "rw2", "orf", "raf"),
        badgeColorRes = R.color.format_tag_raw
    ),
    MP4(
        displayName = "MP4",
        canonicalExtensions = setOf("mp4", "m4v"),
        badgeColorRes = R.color.format_tag_video,
        isVideo = true
    ),
    MOV(
        displayName = "MOV",
        canonicalExtensions = setOf("mov"),
        badgeColorRes = R.color.format_tag_video,
        isVideo = true
    ),
    AVI(
        displayName = "AVI",
        canonicalExtensions = setOf("avi"),
        badgeColorRes = R.color.format_tag_video,
        isVideo = true
    ),
    MKV(
        displayName = "MKV",
        canonicalExtensions = setOf("mkv"),
        badgeColorRes = R.color.format_tag_video,
        isVideo = true
    ),
    WEBM(
        displayName = "WebM",
        canonicalExtensions = setOf("webm"),
        badgeColorRes = R.color.format_tag_video,
        isVideo = true
    ),
    UNKNOWN(
        displayName = "?",
        canonicalExtensions = emptySet(),
        badgeColorRes = R.color.format_tag_default
    );

    companion object {
        /** Filename-extension based guess. We always re-validate via [FormatDetector]. */
        fun fromFilename(name: String?): MediaFormat {
            if (name.isNullOrBlank()) return UNKNOWN
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return UNKNOWN
            return entries.firstOrNull { it.canonicalExtensions.contains(ext) } ?: UNKNOWN
        }
    }
}