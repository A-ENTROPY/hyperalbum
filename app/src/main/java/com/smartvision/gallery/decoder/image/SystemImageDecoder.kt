package com.smartvision.gallery.decoder.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.Decoder
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default decoder backed by the system. Uses [ImageDecoder] on API 28+ (preferred for
 * HDR + animated images + precise resize), and falls back to [BitmapFactory] below that.
 */
class SystemImageDecoder(private val context: Context) : Decoder {

    override val id = "system"

    override suspend fun decodeThumbnail(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val sample = computeInSampleSize(w, h, targetWidthPx, targetHeightPx)
                    decoder.setTargetSampleSize(sample)
                }
            } else {
                legacyDecode(uri, targetWidthPx, targetHeightPx)
            }
        }.onFailure { AppLog.w(TAG, "Thumbnail decode failed for $uri", it) }
            .getOrNull()
    }

    override suspend fun decodeFull(
        uri: Uri,
        maxWidthPx: Int?,
        maxHeightPx: Int?
    ): DecodedPayload? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    if (maxWidthPx != null && maxHeightPx != null) {
                        val sample = computeInSampleSize(info.size.width, info.size.height, maxWidthPx, maxHeightPx)
                        decoder.setTargetSampleSize(sample)
                    }
                    // Note: intentionally NOT calling setTargetColorSpace(SRGB).
                    // The system ImageDecoder handles color space conversion correctly.
                    // Forcing SRGB strips HDR metadata and causes incorrect brightness.
                }
                DecodedPayload.BitmapPayload(
                    width = bitmap.width,
                    height = bitmap.height,
                    sourceUri = uri,
                    decoderId = id,
                    bitmap = bitmap
                )
            } else {
                val bitmap = legacyDecode(uri, maxWidthPx ?: Int.MAX_VALUE, maxHeightPx ?: Int.MAX_VALUE)
                    ?: return@withContext null
                DecodedPayload.BitmapPayload(
                    width = bitmap.width,
                    height = bitmap.height,
                    sourceUri = uri,
                    decoderId = id,
                    bitmap = bitmap
                )
            }
        }.onFailure { AppLog.w(TAG, "Full decode failed for $uri", it) }
            .getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun legacyDecode(uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, targetW, targetH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun computeInSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var inSampleSize = 1
        while (width / inSampleSize > reqW || height / inSampleSize > reqH) inSampleSize *= 2
        return inSampleSize
    }

    private companion object {
        private const val TAG = "SystemImageDecoder"
    }
}