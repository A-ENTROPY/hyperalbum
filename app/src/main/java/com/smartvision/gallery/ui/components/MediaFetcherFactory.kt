package com.smartvision.gallery.ui.components

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import coil.size.Dimension
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.decoder.format.MediaFormat
import com.smartvision.gallery.util.AppLog

/**
 * Coil [Fetcher.Factory] for [MediaItem] models.
 *
 * `create()` ALWAYS returns non-null: Coil routes models by type before
 * consulting factories, so returning null for a `MediaItem` would mean the
 * model is serviced by nothing (Coil 2.x has no Mapper from [MediaItem] to
 * [Uri] registered here, and `fetch()` returning null does NOT trigger a
 * fallback factory — it fails the request). That's what caused silent blank
 * grid cells before: the old code intercepted every [MediaItem] and forced
 * `loadSystemThumbnail`, which returns null on failure → blank cell.
 *
 * Instead we do the fallback INSIDE `fetch()`:
 *  1. JXL / AVIF → our native `MediaLoader` (full-res for ORIGINAL requests,
 *     DC-decode thumbnail otherwise).
 *  2. Anything else → best-effort system thumbnail; on failure continue to
 *     Coil's default pipeline for the `item.uri` by re-mapping and delegating
 *     to the loader's system/custom fetchers — this is what lets GIFs animate
 *     (GifDecoder) and avoids blank cells.
 */
class MediaFetcherFactory(

    private val context: Context
) : Fetcher.Factory<MediaItem> {

    private val app: SmartVisionApp = SmartVisionApp.from(context)

    override fun create(
        data: MediaItem,
        options: Options,
        imageLoader: ImageLoader
    ): Fetcher? {
        return MediaFetcher(app, context, options, imageLoader, data)
    }

    private class MediaFetcher(
        private val app: SmartVisionApp,
        private val context: Context,
        private val options: Options,
        private val imageLoader: ImageLoader,
        private val item: MediaItem
    ) : Fetcher {

        override suspend fun fetch(): FetchResult? {
            val size = options.size
            val wDim = size?.width
            val hDim = size?.height
            val wPx = (wDim as? Dimension.Pixels)?.px
            val hPx = (hDim as? Dimension.Pixels)?.px
            // 查看器 (ZoomableAsyncImage) 请求 ORIGINAL — 无像素目标。此时若走
            // thumbnail 降采样会 fallback 到 720px，放大到 100%+ 就糊。改为
            // 全分辨率解码（decoders 的 fullResolution 路径），让原生像素可用。
            val isOriginalRequest = wPx == null || hPx == null || wPx <= 0 || hPx <= 0
            val needsOurs = item.format == MediaFormat.JXL ||
                item.format == MediaFormat.AVIF_STATIC ||
                item.format == MediaFormat.AVIF_ANIMATED

            if (needsOurs) {
                // Path 1: formats Coil can't decode natively. Native decode;
                // fall back to system thumb on failure.
                val raw = nativeDecode(item, wPx, hPx, isOriginalRequest)
                if (raw != null) return resultFromBitmap(raw)
                return sysThumbOrNull(item.uri, wPx, hPx)
            }

            // Path 2: native formats (JPEG/PNG/GIF/WebP/HEIC/video). Animated
            // formats MUST skip the system thumbnail: loadThumbnail returns the
            // first frame as a static bitmap, which is exactly the "GIF 播放不了"
            // bug. Delegate them straight to Coil so GifDecoder runs. Static
            // formats try the system thumbnail cache first (instant on
            // MediaStore) and delegate to Coil only on failure.
            if (item.format.isAnimated) return delegateToCoil(item.uri)
            sysThumbOrNull(item.uri, wPx, hPx)?.let { return it }
            return delegateToCoil(item.uri)
        }

        /** Try our native decoder (JXL/AVIF only) at the request resolution. */
        private suspend fun nativeDecode(
            item: MediaItem,
            wPx: Int?,
            hPx: Int?,
            isOriginalRequest: Boolean
        ): Bitmap? {
            val t0 = System.nanoTime()
            val raw = if (isOriginalRequest) {
                (app.mediaLoader.loadFull(item) as? DecodedPayload.BitmapPayload)?.bitmap
            } else {
                app.mediaLoader.loadThumbnail(item, wPx!!, hPx!!)
            }
            if (raw == null) {
                AppLog.i(TAG, "fmt=${item.format} native decode failed, fallback to system thumb")
                return null
            }
            // Cap the long edge at the GPU/Canvas safe ceiling. Drawing a
            // bitmap whose byte count exceeds the platform Canvas limit
            // (~100MB on most devices, even lower in hardware-accelerated
            // RecordingCanvas) throws "trying to draw too large bitmap"
            // from BitmapPainter.onDraw — observed with full-res JXL
            // (4096x3072) on ColorOS. Downscale in-place before handing
            // the bitmap to telephoto; zoom quality is preserved up to
            // MAX_LONG_EDGE_PX.
            val longEdge = maxOf(raw.width, raw.height)
            val bmp = if (longEdge > MAX_LONG_EDGE_PX) {
                val ratio = MAX_LONG_EDGE_PX.toFloat() / longEdge
                val nw = (raw.width * ratio).toInt().coerceAtLeast(1)
                val nh = (raw.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(raw, nw, nh, true)
                if (scaled !== raw) raw.recycle()
                scaled
            } else raw
            val ms = (System.nanoTime() - t0) / 1_000_000
            AppLog.i(TAG, "fmt=${item.format} decode ${raw.width}x${raw.height}->${bmp.width}x${bmp.height} cfg=${bmp.config} bytes=${bmp.byteCount} ${ms}ms")
            return bmp
        }

        private fun resultFromBitmap(bmp: Bitmap): FetchResult? {
            val drawable: Drawable = BitmapDrawable(app.resources, bmp)
            return DrawableResult(
                drawable = drawable,
                isSampled = true,
                dataSource = DataSource.DISK
            )
        }

        /**
         * Delegate to Coil's default Uri pipeline. Coil 2.x maps [Uri] →
         * UriFetcher → registered decoders (GifDecoder for GIFs,
         * VideoFrameDecoder for video, ImageDecoderDecoder otherwise). The
         * loader we were given IS this app's loader, so its component registry
         * already includes our custom factories + GifDecoder.
         */
        private suspend fun delegateToCoil(uri: Uri): FetchResult? {
            // Use Coil's public execute() pipeline — the loader already has
            // GifDecoder / VideoFrameDecoder / UriFetcher registered, so a GIF
            // animates and a video yields a keyframe. Avoids the internal
            // `components.map()` API (signature changed across 2.6/2.7).
            return runCatching {
                val req = coil.request.ImageRequest.Builder(context)
                    .data(uri)
                    .apply {
                        val s = options.size
                        if (s != null) size(s)
                    }
                    .build()
                imageLoader.execute(req)
            }.getOrNull()?.let { result ->
                val drawable = result.drawable ?: return null
                DrawableResult(
                    drawable = drawable,
                    isSampled = true,
                    dataSource = DataSource.MEMORY
                )
            }
        }

        /** Frame the sys-thumb fallback so a null propagates to [delegateToCoil]. */
        private suspend fun sysThumbOrNull(
            uri: Uri,
            wPx: Int?,
            hPx: Int?
        ): FetchResult? = loadSystemThumbnail(context, uri, wPx, hPx)
    }

    private companion object {
        const val TAG = "MediaFetcher"
        // Long-edge ceiling for full-res bitmaps handed to the viewer.
        // 4096 keeps the bitmap under ~67MB (ARGB_8888) — below the platform
        // Canvas draw limit (~100MB) and within typical GPU max texture size.
        // Tuned down from raw 4096x3072 which threw Canvas: too large on
        // ColorOS RecordingCanvas.
        const val MAX_LONG_EDGE_PX = 4096
    }
}

private const val SYSTHUMB_TAG = "MediaFetcher"

/** 系统 ContentResolver 缩略图后备。供 MediaFetcher 在非 JXL/AVIF 或 native 失败时调用。 */
@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun loadSystemThumbnail(
    context: Context,
    uri: android.net.Uri,
    reqW: Int?,
    reqH: Int?
): FetchResult? {
    val w = reqW?.coerceIn(96, 1024) ?: 512
    val h = reqH?.coerceIn(96, 1024) ?: 384
    val t0 = System.nanoTime()
    return runCatching {
        val bmp = context.contentResolver.loadThumbnail(uri, Size(w, h), null)
        val ms = (System.nanoTime() - t0) / 1_000_000
        AppLog.i(SYSTHUMB_TAG, "systemThumb uri=${uri.lastPathSegment} req=${w}x${h} bmp=${bmp.width}x${bmp.height} ${ms}ms")
        DrawableResult(
            drawable = BitmapDrawable(context.resources, bmp),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }.getOrElse {
        AppLog.w(SYSTHUMB_TAG, "systemThumb failed for $uri", it)
        null
    }
}