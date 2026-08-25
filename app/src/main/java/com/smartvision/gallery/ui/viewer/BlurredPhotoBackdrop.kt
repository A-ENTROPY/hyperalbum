package com.smartvision.gallery.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.ImageLoader
import coil.compose.LocalImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Snapshot of the current photo, downsampled to a small bitmap that the chrome
 * panel / letterbox renders as its background.
 *
 * **Why static instead of live backdrop sampling.** Round 8 of this viewer wired
 * `Modifier.layerBackdrop` over the photo, which made the chrome refract the
 * underlying image in real time. On realme ColorOS 16 that crossed the
 * RenderEffect chain depth limit and crashed the HWUI render thread with SIGSEGV
 * ("stack pointer is not in a rw map; likely due to stack overflow"). A static
 * bitmap is decoded once per page into a tiny ARGB_8888 sample and drawn
 * through standard `drawImage` — no recursive render tree, no live
 * RenderEffect chain. The bilinear filter applied at draw time to upscale this
 * small source into a much larger surface (chrome panel or full-screen
 * letterbox) is the blur effect; Modifier.blur still crashes the chain on
 * ColorOS 16 and also blurs glyphs sitting on top of the surface.
 *
 * **What this does NOT do.** The bitmap is captured when [uri] changes (i.e.
 * page swipe); it does NOT update on pinch-zoom or pan within the same photo.
 * That mirrors how iOS Photos captures a static preview for its floating chrome
 * during gestures.
 *
 * **Why Coil instead of BitmapFactory.** The old implementation decoded straight
 * from the content resolver with a two-pass BitmapFactory — every page change
 * was a cold full-file decode, so the background lagged a beat behind the photo
 * (which loads through Coil and lands instantly). Routing the request through
 * the app's [ImageLoader] means it rides the same fast path as the photo:
 * Coil's memory cache → the 250MB disk cache (raw bytes, no contentResolver
 * IO) → [coil.decode.ImageDecoderDecoder]'s system decoders. Requesting 2× the
 * display target also lands in the system MediaStore thumbnail band on the
 * gallery path (see [com.smartvision.gallery.ui.components.SystemThumbnailFetcherFactory]),
 * so cold loads usually resolve from a pregenerated thumb instead of a full decode.
 *
 * @param uri       Current page URI, or null when the viewer has no current
 *                  page yet.
 * @param targetPx  Target edge size for the downsampled source. Default 48 px
 *                  fits the chrome panel (~200 dp wide). Pass ~256 px when
 *                  filling a screen-sized surface (the photo letterbox) —
 *                  48 px upscaled to 1080 dp leaves visible mosaic; 256 px
 *                  bilinear-upscaled to 1080 dp reads as a smooth blur.
 * @return          Decoded bitmap (small ARGB_8888) ready to be drawn under
 *                  the surface, or null while loading / on decode failure /
 *                  when uri is null.
 */
@Composable
fun rememberBlurredPhotoBackdrop(
    uri: Uri?,
    targetPx: Int = 48,
): Bitmap? {
    val context = LocalContext.current
    val imageLoader = LocalImageLoader.current
    var bitmap by remember(uri, targetPx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri, targetPx, imageLoader) {
        // Intentionally do NOT clear the previous bitmap here. Keeping the old
        // frame until the new one is ready makes page-switch seamless: the
        // letterbox crossfades old→new instead of cutting to the dark fallback
        // for the decode duration.
        if (uri == null) return@LaunchedEffect
        loadSmallBitmap(imageLoader, context, uri, targetPx = targetPx)?.let { bitmap = it }
    }

    return bitmap
}

/**
 * Resolve [uri] into a small ARGB_8888 bitmap via the app's [ImageLoader].
 *
 * [targetPx] is doubled in the request so the upscaled blur keeps enough
 * resolution (mirrors the old BitmapFactory oversample), and 2×256=512 px
 * sits squarely in the MediaStore MINI-thumbnail band — the gallery loader's
 * [com.smartvision.gallery.ui.components.SystemThumbnailFetcherFactory] serves
 * most cold loads from a pregenerated thumb instead of a full decode.
 *
 * On any failure (missing URI permission, undecodable format, OOM on hostile
 * image) returns null. Callers fall back to a flat dark tint.
 */
private suspend fun loadSmallBitmap(
    imageLoader: ImageLoader,
    context: Context,
    uri: Uri,
    targetPx: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(2 * targetPx)
            .scale(Scale.FIT)
            // Software bitmap — the result feeds Modifier.blur / drawImage and
            // must be readable as a plain ARGB_8888, not a GPU-resident hardware
            // bitmap. Hardware bitmaps would also survive the pipeline here, but
            // keeping parity with the old BitmapFactory path is the safe default.
            .allowHardware(false)
            // No fade-in: this drawable is painted synchronously on page change,
            // a crossfade would re-introduce the lag we're removing.
            .crossfade(false)
            .build()
        val result = imageLoader.execute(request)
        (result as? SuccessResult)?.drawable
            ?.let { (it as? BitmapDrawable)?.bitmap }
    } catch (t: Throwable) {
        null
    }
}
