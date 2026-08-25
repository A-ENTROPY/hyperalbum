package com.smartvision.gallery.data.model

import android.net.Uri

/**
 * Loaded, ready-to-render decoded output from [com.smartvision.gallery.decoder.MediaLoader].
 *
 * Decoders may produce either a Bitmap (typical for JPEG/PNG/WebP/HEIC/AVIF/JXL) or a
 * software-rasterised video frame. We expose both as [BitmapPayload] / [VideoFramePayload]
 * so the UI layer doesn't need to care which decoder ran.
 */
sealed class DecodedPayload {
    abstract val width: Int
    abstract val height: Int
    abstract val sourceUri: Uri
    abstract val decoderId: String

    data class BitmapPayload(
        override val width: Int,
        override val height: Int,
        override val sourceUri: Uri,
        override val decoderId: String,
        val bitmap: android.graphics.Bitmap,
        val colorDepth: Int? = null,
        val isHdr: Boolean = false
    ) : DecodedPayload()

    data class VideoFramePayload(
        override val width: Int,
        override val height: Int,
        override val sourceUri: Uri,
        override val decoderId: String,
        val bitmap: android.graphics.Bitmap,
        val timeUs: Long,
        val durationMs: Long
    ) : DecodedPayload()

    data class GifFramePayload(
        override val width: Int,
        override val height: Int,
        override val sourceUri: Uri,
        override val decoderId: String,
        val firstFrame: android.graphics.Bitmap,
        val totalFrames: Int
    ) : DecodedPayload()
}