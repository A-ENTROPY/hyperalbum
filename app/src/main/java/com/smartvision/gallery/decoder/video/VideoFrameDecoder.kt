package com.smartvision.gallery.decoder.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.Decoder
import com.smartvision.gallery.util.AppLog

/**
 * Video thumbnail decoder. Uses [MediaMetadataRetriever] to extract a frame at
 * approximately 1 second in. We deliberately avoid [MediaCodec] here — `getFrameAtTime`
 * is fast enough for grid thumbnails and avoids the surface plumbing overhead.
 */
class VideoFrameDecoder(private val context: Context) : Decoder {

    override val id = "media-metadata-retriever"

    override suspend fun decodeThumbnail(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val full = retriever.getFrameAtTime(
                THUMB_TIME_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            scaleBitmap(full, targetWidthPx, targetHeightPx)
        } catch (t: Throwable) {
            AppLog.w(TAG, "Video thumbnail failed for $uri", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    override suspend fun decodeFull(
        uri: Uri,
        maxWidthPx: Int?,
        maxHeightPx: Int?
    ): DecodedPayload? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            val full = retriever.getFrameAtTime(
                THUMB_TIME_US,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: return null
            DecodedPayload.VideoFramePayload(
                width = full.width,
                height = full.height,
                sourceUri = uri,
                decoderId = id,
                bitmap = full,
                timeUs = THUMB_TIME_US,
                durationMs = durationMs
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "Video full frame failed for $uri", t)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleBitmap(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (src.width <= maxW && src.height <= maxH) return src
        val ratio = minOf(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
        val targetW = (src.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, targetW, targetH, true)
    }

    private companion object {
        private const val TAG = "VideoFrameDecoder"
        private const val THUMB_TIME_US = 1_000_000L // 1 second
    }
}