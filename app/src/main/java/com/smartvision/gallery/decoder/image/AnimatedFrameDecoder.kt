package com.smartvision.gallery.decoder.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.Decoder
import com.smartvision.gallery.util.AppLog

/**
 * Decoder for animated formats that are still happily handled by [ImageDecoder] —
 * namely animated WebP and GIF on API 28+. For each frame we extract a bitmap; the viewer
 * drives playback at its own cadence.
 */
class AnimatedFrameDecoder(private val context: Context) : Decoder {

    override val id = "system-animated"

    override suspend fun decodeThumbnail(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val sample = computeSample(info.size.width, info.size.height, targetWidthPx, targetHeightPx)
                decoder.setTargetSampleSize(sample)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "Animated thumbnail decode failed for $uri", t)
            null
        }
    }

    override suspend fun decodeFull(
        uri: Uri,
        maxWidthPx: Int?,
        maxHeightPx: Int?
    ): DecodedPayload? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val drawable = ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val sample = computeSample(info.size.width, info.size.height, maxWidthPx ?: 4096, maxHeightPx ?: 4096)
                decoder.setTargetSampleSize(sample)
            }
            val bitmap = renderDrawableToBitmap(drawable) ?: return null
            DecodedPayload.GifFramePayload(
                width = bitmap.width,
                height = bitmap.height,
                sourceUri = uri,
                decoderId = id,
                firstFrame = bitmap,
                totalFrames = (drawable as? android.graphics.drawable.Animatable)?.let { 0 } ?: 1
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "Animated full decode failed for $uri", t)
            null
        }
    }

    override suspend fun frameStream(uri: Uri): List<Bitmap> {
        // ImageDecoder only exposes the static frame here. Real frame streaming for
        // animated AVIF/WebP is done in [com.smartvision.gallery.decoder.bridge.NativeBridge]
        // if/when added; until then the viewer plays the drawable directly.
        val thumb = decodeThumbnail(uri, 1080, 1080) ?: return emptyList()
        return listOf(thumb)
    }

    private fun computeSample(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        return sample
    }

    private fun renderDrawableToBitmap(drawable: android.graphics.drawable.Drawable): Bitmap? {
        if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return null
        val bmp = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }

    private companion object {
        private const val TAG = "AnimatedFrameDecoder"
    }
}