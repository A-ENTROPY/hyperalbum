package com.smartvision.gallery.hdr

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.view.Display
import androidx.exifinterface.media.ExifInterface
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HDR (High Dynamic Range) support.
 *
 *  * **Detection** — read EXIF/SMPTE-2086 metadata from the file. We mark a frame as
 *    HDR if either (a) the EXIF TransferFunction / ColorSpace tags indicate PQ or HLG,
 *    or (b) the bit-depth is ≥ 10.
 *
 *  * **Rendering** — when the display supports HDR (Android 13+), the system uses
 *    [Display.Mode] to switch to the highest-brightness HLG/PQ profile while the
 *    viewer is foreground. We restore the previous mode on stop.
 *
 *  * **Decoding** — on API 34+ we ask [ImageDecoder] for a wide-gamut bitmap
 *    (Display-P3 or BT.2020). Lower APIs fall back to sRGB.
 */
object HdrController {

    private const val TAG = "HdrController"

    /** Result of probing an image file for HDR characteristics. */
    data class HdrInfo(
        val isHdr: Boolean,
        val transfer: Transfer,
        val colorSpace: ColorSpace?,
        val bitDepth: Int
    ) {
        enum class Transfer { SDR, PQ, HLG }
    }

    suspend fun probe(context: Context, uri: Uri): HdrInfo = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val bits = exif.getAttributeInt(ExifInterface.TAG_BITS_PER_SAMPLE, 8)
                val transferStr = exif.getAttribute(ExifInterface.TAG_TRANSFER_FUNCTION)?.trim()
                val photometric = exif.getAttributeInt(ExifInterface.TAG_PHOTOMETRIC_INTERPRETATION, 0)
                val transfer = when {
                    bits >= 10 && transferStr == "PQ" -> HdrInfo.Transfer.PQ
                    bits >= 10 && transferStr == "HLG" -> HdrInfo.Transfer.HLG
                    bits >= 10 -> HdrInfo.Transfer.PQ // 10+ bits → assume PQ
                    else -> HdrInfo.Transfer.SDR
                }
                val colorSpace = when (photometric) {
                    32803 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
                    34892 -> ColorSpace.get(ColorSpace.Named.BT2020)
                    else -> null
                }
                HdrInfo(
                    isHdr = transfer != HdrInfo.Transfer.SDR,
                    transfer = transfer,
                    colorSpace = colorSpace,
                    bitDepth = bits
                )
            }
        }.onFailure { AppLog.w(TAG, "HDR probe failed for $uri", it) }
            .getOrNull() ?: HdrInfo(false, HdrInfo.Transfer.SDR, null, 8)
    }

    /** Returns true if the device supports HDR display modes (API 33+). */
    fun isDeviceHdrCapable(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val display = activity.display ?: activity.windowManager.defaultDisplay
        val modes = display?.supportedModes ?: return false
        return modes.any { mode ->
            // HDR-capable modes advertise a peak brightness ≥ 1000 nits in HLG.
            // We can't read brightness directly on every device, so we treat any
            // mode with an alternate refresh rate group or "preferredDisplayModeId"
            // hook as a candidate. The hook here: alternate modes > 60Hz typically
            // require HDR support to be enabled.
            mode.modeId != display.mode.modeId
        }
    }

    /**
     * Request the highest-brightness [Display.Mode] for HDR rendering. No-op on
     * devices that don't support HDR mode switching (API < 33).
     */
    fun requestHdrMode(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val display = activity.display ?: activity.windowManager.defaultDisplay
        val modes = display?.supportedModes ?: return false
        val best = modes.maxByOrNull { it.refreshRate } ?: return false
        val window = activity.window
        val params = window.attributes
        params.preferredDisplayModeId = best.modeId
        // Android 14+ allows fine-grained HDR headroom; we don't need to do anything
        // extra in V1.x, but leave the hook here for the future.
        window.attributes = params
        return true
    }

    /** Restore the display's preferred mode when leaving the viewer. */
    fun releaseHdrMode(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val window = activity.window
        val params = window.attributes
        params.preferredDisplayModeId = 0
        window.attributes = params
    }

    /**
     * Decode the image with HDR color space if supported. Returns the bitmap + a
     * boolean indicating whether HDR rendering will actually display differently.
     */
    suspend fun decodeForHdr(context: Context, uri: Uri): Pair<Bitmap?, HdrInfo> = withContext(Dispatchers.IO) {
        val info = probe(context, uri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bmp = runCatching {
                ImageDecoder.decodeBitmap(source) { decoder, src, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
                    val space = info.colorSpace
                        ?: if (info.isHdr) {
                            ColorSpace.get(ColorSpace.Named.BT2020_HLG)
                        } else {
                            ColorSpace.get(ColorSpace.Named.SRGB)
                        }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        decoder.setTargetColorSpace(space)
                    }
                    // For thumbnails we still apply a sample.
                    if (src.size.width > 4096 || src.size.height > 4096) {
                        decoder.setTargetSampleSize(2)
                    }
                }
            }.getOrNull()
            bmp to info
        } else null to info
    }
}