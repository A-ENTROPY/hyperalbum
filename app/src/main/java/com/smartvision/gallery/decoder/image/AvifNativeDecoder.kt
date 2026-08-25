package com.smartvision.gallery.decoder.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.Decoder
import com.smartvision.gallery.decoder.bridge.NativeBridge
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AVIF (AV1 Image File Format) decoder.
 *
 * Three code paths, picked in order of preference:
 *
 *  1. **System ImageDecoder** — Android 12+ (API 31) ships a hardware-accelerated AVIF
 *     decoder exposed via [ImageDecoder]. This is the preferred path on supported
 *     devices — zero native code needed, GPU-accelerated where available.
 *
 *  2. **Native bridge (libavif)** — on Android < 12 the system has no AVIF decoder,
 *     so we route through [NativeBridge]. The V1.0 stub returns a placeholder;
 *     drop a libavif .so into `src/main/jniLibs/` and the bridge picks it up
 *     without any Kotlin changes.
 *
 *  3. **Graceful failure** — if neither path works we return null and the caller
 *     shows a "format unsupported" placeholder.
 */
class AvifNativeDecoder(private val context: Context) : Decoder {

    override val id = "avif"

    override suspend fun decodeThumbnail(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        decodeInternal(uri, targetWidthPx, targetHeightPx, fullResolution = false)
    }

    override suspend fun decodeFull(
        uri: Uri,
        maxWidthPx: Int?,
        maxHeightPx: Int?
    ): DecodedPayload? = withContext(Dispatchers.IO) {
        val bmp = decodeInternal(uri, maxWidthPx ?: 0, maxHeightPx ?: 0, fullResolution = true)
            ?: return@withContext null
        DecodedPayload.BitmapPayload(
            width = bmp.width,
            height = bmp.height,
            sourceUri = uri,
            decoderId = id,
            bitmap = bmp,
            colorDepth = if (Build.VERSION.SDK_INT >= 34) 10 else 8, // 10-bit HDR when supported
            isHdr = false
        )
    }

    private fun decodeInternal(
        uri: Uri,
        targetW: Int,
        targetH: Int,
        fullResolution: Boolean
    ): Bitmap? {
        // (1) System ImageDecoder — Android 12+ (API 31) supports AVIF natively.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = info.size.width
                    val h = info.size.height
                    if (!fullResolution) {
                        val sample = computeSample(w, h, targetW, targetH)
                        decoder.setTargetSampleSize(sample)
                    }
                    // Note: intentionally NOT calling setTargetColorSpace(SRGB) here.
                    // AVIF supports HDR and wide-gamut color spaces (Display P3, BT.2020).
                    // Forcing SRGB strips the HDR metadata and causes incorrect brightness
                    // mapping. The system ImageDecoder handles color space conversion correctly.
                }
                return bmp
            } catch (t: Throwable) {
                AppLog.w(TAG, "ImageDecoder AVIF decode failed for $uri — falling back to native", t)
            }
        }

        // (2) Native bridge — libavif path on devices without system support.
        if (NativeBridge.isReady) {
            try {
                if (fullResolution) {
                    val res = kotlinx.coroutines.runBlocking { NativeBridge.decodeAvifFull(uri) }
                    if (res != null && res.bitmap.width > 0) return res.bitmap
                } else {
                    val bmp = kotlinx.coroutines.runBlocking { NativeBridge.decodeAvifThumbnail(uri, targetW, targetH) }
                    if (bmp != null && bmp.width > 0) return bmp
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "Native AVIF decode failed for $uri", t)
            }
        }

        AppLog.w(TAG, "AVIF decode unavailable for $uri — needs libavif.so or Android 12+")
        return null
    }

    private fun computeSample(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        while (width / sample > reqW || height / sample > reqH) sample *= 2
        return sample
    }

    private companion object {
        private const val TAG = "AvifNativeDecoder"
    }
}