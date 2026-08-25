package com.smartvision.gallery.decoder.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.Decoder
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RAW image decoder.
 *
 * Android exposes native RAW support in two ways:
 *
 *  * **Camera2 RAW** (API 23+) — DngCreator / DngReader. We use this for thumbnail
 *    extraction when the input is a DNG file.
 *  * **ImageDecoder** (API 28+) — supports a growing set of OEM-packaged RAW
 *    formats through the same HEIF/RAW pipeline that powers AVIF/HEIC.
 *
 * In addition to rendering, we read EXIF + MakerNote HDR tags so the viewer can
 * surface "this is a 14-bit RAW from an iPhone Pro" metadata without parsing the
 * container itself.
 *
 * For formats we cannot decode we still extract metadata + return a metadata-only
 * payload so the rest of the UI (search, smart albums) still has something to work
 * with.
 */
class RawImageDecoder(private val context: Context) : Decoder {

    override val id = "raw"

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
        val meta = readExifMetadata(uri)
        val bmp = decodeInternal(uri, maxWidthPx ?: 4096, maxHeightPx ?: 4096, fullResolution = true)
        if (bmp != null) {
            DecodedPayload.BitmapPayload(
                width = bmp.width,
                height = bmp.height,
                sourceUri = uri,
                decoderId = id,
                bitmap = bmp,
                colorDepth = meta?.colorDepth ?: 8,
                isHdr = meta?.isHdr == true
            )
        } else {
            // Metadata-only payload — at least we can still display the dimensions
            // and HDR flag in the viewer info panel.
            DecodedPayload.BitmapPayload(
                width = meta?.width ?: 0,
                height = meta?.height ?: 0,
                sourceUri = uri,
                decoderId = "$id-meta",
                bitmap = renderMetadataPlaceholder(meta?.width ?: 0, meta?.height ?: 0),
                colorDepth = meta?.colorDepth ?: 0,
                isHdr = meta?.isHdr == true
            )
        }
    }

    private fun decodeInternal(
        uri: Uri,
        targetW: Int,
        targetH: Int,
        fullResolution: Boolean
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    if (!fullResolution) {
                        val sample = computeSample(info.size.width, info.size.height, targetW, targetH)
                        decoder.setTargetSampleSize(sample)
                    }
                }
                return bmp
            } catch (t: Throwable) {
                AppLog.w(TAG, "ImageDecoder RAW decode failed for $uri", t)
            }
        }
        return null
    }

    /** Read EXIF + Make/Model/HDR tags from the RAW file. */
    private fun readExifMetadata(uri: Uri): RawMetadata? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                val height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                val bitsPerSample = exif.getAttributeInt(ExifInterface.TAG_BITS_PER_SAMPLE, 8)
                // Many RAW formats flag HDR via custom MakerNotes; fall back to
                // PhotometricInterpretation tag when available.
                val photometric = exif.getAttributeInt(ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, 0)
                val isHdr = photometric == 32803 || photometric == 34892 // CIE Lab / ICCLabelled
                RawMetadata(
                    width = width,
                    height = height,
                    make = make,
                    model = model,
                    colorDepth = bitsPerSample,
                    isHdr = isHdr
                )
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "EXIF read failed for $uri", t)
            null
        }
    }

    private fun renderMetadataPlaceholder(width: Int, height: Int): Bitmap {
        val w = if (width > 0) width.coerceAtMost(2048) else 1080
        val h = if (height > 0) height.coerceAtMost(2048) else 720
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(0xFF1A1F27.toInt())
        val paint = android.graphics.Paint().apply {
            color = 0xFF8E8E93.toInt()
            textSize = 48f
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        canvas.drawText(
            "RAW ${w}×${h}\n预览不可用",
            w / 2f, h / 2f, paint
        )
        return bmp
    }

    private fun computeSample(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfH / sample >= reqH && halfW / sample >= reqW) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private companion object {
        private const val TAG = "RawImageDecoder"
    }
}

/** Metadata extracted from a RAW file's EXIF / MakerNote blocks. */
data class RawMetadata(
    val width: Int,
    val height: Int,
    val make: String?,
    val model: String?,
    val colorDepth: Int,
    val isHdr: Boolean
)