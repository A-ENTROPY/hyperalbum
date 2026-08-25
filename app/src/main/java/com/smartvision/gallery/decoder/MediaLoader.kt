package com.smartvision.gallery.decoder

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.cache.CacheCoordinator
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.bridge.NativeBridge
import com.smartvision.gallery.decoder.format.FormatDetector
import com.smartvision.gallery.decoder.format.MediaFormat
import com.smartvision.gallery.decoder.image.AnimatedFrameDecoder
import com.smartvision.gallery.decoder.image.AvifNativeDecoder
import com.smartvision.gallery.decoder.image.JxlNativeDecoder
import com.smartvision.gallery.decoder.image.RawImageDecoder
import com.smartvision.gallery.decoder.image.SystemImageDecoder
import com.smartvision.gallery.decoder.video.VideoFrameDecoder
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Public surface for "load me a bitmap from this Uri, I don't care how".
 *
 * Routing logic:
 *  1. FormatDetector sniffs the file.
 *  2. The detected [MediaFormat] maps to a decoder chain.
 *  3. Decoders are tried in priority order; first non-null wins.
 *  4. The bitmap goes through the [CacheCoordinator] before being returned.
 */
class MediaLoader(
    private val context: Context,
    private val cacheCoordinator: CacheCoordinator,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /** In-flight cache to dedupe concurrent decode requests for the same URI. */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Uri, DeferredDecode>()

    suspend fun loadThumbnail(
        item: com.smartvision.gallery.data.model.MediaItem,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? {
        val cached = cacheCoordinator.thumbnailCache.get(item.uri, targetWidthPx, targetHeightPx)
        if (cached != null) return cached

        val format = if (item.format == MediaFormat.UNKNOWN) {
            FormatDetector.detect(context, item.uri, item.displayName)
        } else item.format

        val decoderChain = decoderFor(format, useHardwareAccel())
        val bitmap = decoderChain.decodeThumbnail(item.uri, targetWidthPx, targetHeightPx)
        if (bitmap != null) {
            cacheCoordinator.thumbnailCache.put(item.uri, targetWidthPx, targetHeightPx, bitmap)
        }
        return bitmap
    }

    suspend fun loadFull(
        item: com.smartvision.gallery.data.model.MediaItem,
        maxWidthPx: Int? = null,
        maxHeightPx: Int? = null
    ): DecodedPayload? = withContext(decodeDispatcher) {
        val format = if (item.format == MediaFormat.UNKNOWN) {
            FormatDetector.detect(context, item.uri, item.displayName)
        } else item.format
        decoderFor(format, useHardwareAccel()).decodeFull(item.uri, maxWidthPx, maxHeightPx)
    }

    /**
     * Load a URI at full resolution without needing a [MediaItem].
     *
     * Used by the viewer's [ZoomableImage] composable for next-gen formats (AVIF/JXL)
     * so the zoomed-in view shows native pixels instead of an upscaled display-size decode.
     */
    suspend fun loadFullUri(
        uri: Uri,
        format: MediaFormat? = null,
        maxDimensionPx: Int = 4096
    ): DecodedPayload? = withContext(decodeDispatcher) {
        val fmt = format ?: FormatDetector.detect(context, uri, null)
        decoderFor(fmt, useHardwareAccel()).decodeFull(uri, maxDimensionPx, maxDimensionPx)
    }

    /**
     * Copy a URI's bytes into a cache file and report its pixel dimensions.
     *
     * Used by the viewer's sub-sampled (tiled) renderer. Telephoto's
     * [SubSamplingImageSource] file-based tiling decodes regions with
     * [BitmapRegionDecoder], which only works when it can map the raw file
     * stream — decoding directly from a content Uri and re-serving the same
     * bytes to BitmapRegionDecoder is unreliable across OEMs. A cache file is
     * the deterministic path.
     *
     * The Bitmap preview is a cheap (256 px) downscale shown instantly while
     * tiles stream in at full quality. Null when the file can't be copied.
     */
    suspend fun copyToCacheFile(
        uri: Uri,
        format: MediaFormat? = null
    ): CacheFileResult? = withContext(decodeDispatcher) {
        val fmt = format ?: FormatDetector.detect(context, uri, null)
        try {
            val name = "subsampling_${System.nanoTime()}.${fmt.canonicalExtensions.firstOrNull() ?: "img"}"
            val target = java.io.File(context.cacheDir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null

            val preview = decodePreview(target)
            if (fmt == MediaFormat.AVIF_STATIC &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                // Probe BitmapRegionDecoder against this AVIF. The tiling path
                // depends on it; if newInstance throws or decodeRegion returns
                // null, Skia can't region-decode AVIF here and the viewer falls
                // back to the 256px preview (= the "糊" report). This log
                // confirms or refutes that hypothesis before we pick a fix.
                try {
                    val brd = android.graphics.BitmapRegionDecoder.newInstance(target.absolutePath)
                    val rw = brd.width
                    val rh = brd.height
                    val rect = android.graphics.Rect(0, 0, minOf(512, rw), minOf(512, rh))
                    val region = brd.decodeRegion(rect, android.graphics.BitmapFactory.Options())
                    val sample = if (region != null && region.width > 0) rw / region.width else -1
                    AppLog.i(TAG, "AVIF BRD probe OK ${rw}x${rh} region=${region?.width}x${region?.height} sample=$sample")
                    brd.recycle()
                } catch (t: Throwable) {
                    AppLog.w(TAG, "AVIF BRD probe FAILED: ${t.message}", t)
                }
            }
            CacheFileResult(
                file = target,
                width = preview?.width ?: 0,
                height = preview?.height ?: 0,
                isHdr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && preview?.hasGainmap() == true,
                preview = preview
            )
        } catch (t: Throwable) {
            AppLog.w(TAG, "copyToCacheFile failed for $uri", t)
            null
        }
    }

    /**
     * Detect Ultra HDR (gainmap) on a URI. Only meaningful on API 34+ where
     * [android.graphics.ImageDecoder] surfaces gainmaps. Null when the format
     * isn't supported or the file can't be probed.
     */
    suspend fun hasGainmap(uri: Uri): Boolean = withContext(decodeDispatcher) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return@withContext false
        try {
            val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
            val bmp = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                // Downsample aggressively — we only need the gainmap flag.
                decoder.setTargetSampleSize(16)
            }
            bmp.hasGainmap()
        } catch (t: Throwable) {
            false
        }
    }

    /** Cheap downscale used only as the sub-sampling preview bitmap. */
    private fun decodePreview(file: java.io.File): Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = java.lang.Math.max(1, java.lang.Math.max(bounds.outWidth, bounds.outHeight) / 256)
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (t: Throwable) {
            AppLog.w(TAG, "decodePreview failed", t)
            null
        }
    }

    /** Convenience flow for previewing a frame-by-frame animation (GIF / animated WebP / animated AVIF). */
    fun loadAnimationFrames(item: com.smartvision.gallery.data.model.MediaItem): Flow<Bitmap> = flow {
        val format = item.format
        val decoder = decoderFor(format, useHardwareAccel())
        decoder.frameStream(item.uri).forEach { emit(it) }
    }.flowOn(decodeDispatcher)

    /** Read the 硬件加速 toggle (default on) — controls whether AVIF uses the
     *  system ImageDecoder (Android 12+ hardware AVIF codec) vs the native soft
     *  decoder. JXL has no system decoder so it always goes through native. */
    private suspend fun useHardwareAccel(): Boolean =
        SmartVisionApp.from(context).prefs.hardwareAccel.first()

    private fun decoderFor(format: MediaFormat, useHw: Boolean): Decoder =
        when (format) {
            // AVIF always routes through AvifNativeDecoder, never the bare
            // SystemImageDecoder. AvifNativeDecoder.decodeInternal tries the
            // system ImageDecoder FIRST (the same hw path the old useHw branch
            // picked) — but on failure it falls back to the libavif native
            // bridge, which is the ONLY thing that decodes a 16K AVIF on this
            // device (system HW AV1 caps at ~4K → "getPixels failed invalid
            // input"). Routing AVIF to systemDecoder removed that fallback, so
            // every 16K AVIF grid cell rendered null → blank. useHw is now
            // honored inside AvifNativeDecoder (system-first) rather than here.
            MediaFormat.AVIF_STATIC, MediaFormat.AVIF_ANIMATED -> avifDecoder
            MediaFormat.JXL -> jxlDecoder
            MediaFormat.MP4, MediaFormat.MOV, MediaFormat.AVI, MediaFormat.MKV, MediaFormat.WEBM -> videoDecoder
            MediaFormat.GIF, MediaFormat.WEBP_ANIMATED -> gifDecoder
            MediaFormat.TIFF -> systemDecoder
            MediaFormat.RAW -> rawDecoder
            else -> systemDecoder
        }

    private val systemDecoder by lazy { SystemImageDecoder(context) }
    private val avifDecoder by lazy { AvifNativeDecoder(context) }
    private val jxlDecoder by lazy { JxlNativeDecoder(context) }
    private val gifDecoder by lazy { AnimatedFrameDecoder(context) }
    private val videoDecoder by lazy { VideoFrameDecoder(context) }
    private val rawDecoder by lazy { RawImageDecoder(context) }

    private companion object {
        private const val TAG = "MediaLoader"
    }
}

internal class DeferredDecode {
    val lock = Object()
    @Volatile var result: Bitmap? = null
}

/**
 * Result of copying a media file into the app cache for tiled rendering.
 *
 * @param file the cache file BitmapRegionDecoder will tile-decode
 * @param width / [height] intrinsic pixel dimensions (0 if unknowable)
 * @param isHdr true when the source carries an Ultra HDR gainmap (API 34+)
 * @param preview a small (≈256 px) decode for instant placeholder display
 */
data class CacheFileResult(
    val file: java.io.File,
    val width: Int,
    val height: Int,
    val isHdr: Boolean,
    val preview: Bitmap?
)