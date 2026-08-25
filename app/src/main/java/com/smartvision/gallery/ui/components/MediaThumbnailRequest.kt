package com.smartvision.gallery.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Scale
import coil.size.Size
import androidx.compose.ui.layout.ContentScale

/**
 * Global performance-mode switch for thumbnail decode resolution.
 * SettingsPage writes it when the 性能模式 toggle flips; [AsyncThumbnail]
 * reads it at request-build time. Kept off the DataStore/Flow path so grid
 * cells (hundreds in flight) don't each collect a Flow per frame.
 */
object PerfMode {
    @Volatile
    var lowResThumbnails: Boolean = false
}

/**
 * 网格缩略图解码尺寸**档位**（升序）。缩小后格子变小，解码也逐档变小，
 * 一屏上百项时 bitmap 内存 + GPU 上传按比例下降；2 列时取真实 cellPx（受
 * [THUMB_MAX_PX] 上限）。档位化让相邻列数共享解码尺寸 → Coil 内存缓存 key
 * 稳定 → 双指缩放吸附时不整屏重解码，正是系统级相册（Google Photos）的
 * "少量固定缩略图尺寸"做法。
 */
private val THUMB_BUCKETS = intArrayOf(128, 192, 288, 384)

/** 2 列时解码上限 — 1080p 屏 2 列格子约 540px，但 384px 已足够清晰且省一半内存。 */
private const val THUMB_MAX_PX = 384

/**
 * 把渲染格子像素映射到解码档位（向上取整，保证解码 >= 渲染，不模糊）。
 * 纯函数 — 供单元测试。
 */
internal fun thumbDecodeSize(renderPx: Int): Int {
    val capped = renderPx.coerceAtLeast(1)
    for (b in THUMB_BUCKETS) if (capped <= b) return b
    return capped.coerceAtMost(THUMB_MAX_PX)
}

/**
 * Thumbnail-grade [AsyncImage] wrapper.
 *
 * Why this exists: Coil's `AsyncImage(model = uri, ...)` defaults to
 * `Size.ORIGINAL` on every Compose call site in this app. That means a
 * 4K photo gets decoded at full resolution into a ~24MB bitmap, then
 * Compose crops it down to a 1080px grid cell. Three things hurt:
 *
 *  1. **Decode time** scales with pixel count. Decoding 4096x3072 just
 *     to display a 360x360 thumbnail is ~12x more work than needed.
 *  2. **Memory cache pressure** — a 24MB ARGB_8888 bitmap blows through
 *     the 20%-of-heap memory cache in ~70 thumbnails. Switching to
 *     `RGB_565` halves that for photo thumbnails (no alpha needed).
 *  3. **GPU upload** — full-size bitmaps get uploaded to the GPU even
 *     though the on-screen rectangle only occupies a fraction.
 *
 * Passing an explicit `Size` matching the rendered cell lets Coil's
 * subsample step (`calculateInSampleSize`) short-circuit the decode to
 * exactly the pixels the cell needs. That is THE perf lever — the
 * other config tweaks are rounding errors by comparison.
 *
 * `crossfade(false)` is intentional. The 100ms crossfade animation
 * delays the bitmap's first paint — fine for one-off images but in a
 * scrolling grid it makes thumbnails visibly "pop in late" and feels
 * sluggish. With explicit Size the decode is already fast enough that
 * an instant cut-in looks snappier than a fade.
 *
 * `allowHardware(true)` keeps Coil's default hardware-bitmap path —
 * GPU-resident bitmaps avoid the per-frame GPU upload on every scroll.
 *
 * @param model       Any Coil-acceptable data (Uri, MediaItem, File, …).
 * @param size        Target decode size in **pixels**. Use a square
 *                    [Size] for grid cells; rectangular for hero
 *                    covers (16:9 etc.). Coil preserves aspect ratio
 *                    and `ContentScale.Crop` does the rest.
 * @param contentDescription  Accessibility label; pass `null` for
 *                    purely decorative thumbs (hero covers).
 */
@Composable
fun AsyncThumbnail(
    model: Any?,
    size: Size,
    contentDescription: String?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val requestSize = if (PerfMode.lowResThumbnails) halfPixels(size) else size
    val request = remember(model, requestSize, contentScale) {
        ImageRequest.Builder(context)
            .data(model)
            .size(requestSize)
            .scale(if (contentScale == ContentScale.Crop) Scale.FILL else Scale.FIT)
            .crossfade(false)
            .allowHardware(true)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .build()
    }
    // rememberAsyncImagePainter (vs. coil's AsyncImage) — AsyncImage is a
    // thin wrapper over SubcomposeAsyncImage, which runs a subcomposition
    // pass per image. In a fast-scrolling 10-col grid 30-50 cells enter the
    // viewport per frame, and each subcomposition is measurably more
    // expensive than a plain composition. The painter path composes once and
    // avoids the subcomposition entirely — the single biggest win for grid
    // scroll smoothness.
    val painter = rememberAsyncImagePainter(
        model = request,
        contentScale = contentScale,
    )
    androidx.compose.foundation.Image(
        painter = painter,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/**
 * Square-size convenience — same as `AsyncThumbnail(model, Size(px, px), …)`.
 */
@Composable
fun AsyncThumbnail(
    model: Any?,
    sizePx: Int,
    contentDescription: String?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncThumbnail(
        model = model,
        size = Size(sizePx, sizePx),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

/**
 * Variant that captures the cell's actual pixel dimensions from layout
 * via [BoxWithConstraints]. Use when the cell width is determined by
 * `weight(1f)` inside a Row and you don't have an exact dp value at
 * the call site.
 *
 * Trade-off: `BoxWithConstraints` triggers a subcomposition pass on
 * first measure, then settles. For grids with hundreds of cells this is
 * measurable overhead — prefer [rememberGridCellSizePx] (computed once
 * at parent scope) when the cell size is known up front.
 */
@Composable
fun AsyncThumbnail(
    model: Any?,
    contentDescription: String?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    BoxWithConstraints {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.roundToPx() }
        val h = with(density) { maxHeight.roundToPx() }
        val size = if (w == Int.MAX_VALUE || h == Int.MAX_VALUE) {
            Size(360, 360)
        } else {
            Size(w, h)
        }
        AsyncThumbnail(
            model = model,
            size = size,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    }
}

/** Halve the request's pixel dimensions (minimum 1px) so Coil subsamples
 *  the decode to ~1/4 the pixels — the perf-mode lever. */
private fun halfPixels(size: Size): Size {
    val w = size.width
    val h = size.height
    if (w !is Dimension.Pixels || h !is Dimension.Pixels) return size
    return Size(
        Dimension.Pixels((w.px / 2).coerceAtLeast(1)),
        Dimension.Pixels((h.px / 2).coerceAtLeast(1)),
    )
}

/**
 * Compute the on-screen pixel size of one cell in a fixed-column grid.
 *
 * Used by pages where every cell is the same shape (TimelinePage,
 * AlbumDetailPage grid, SearchPage grid, TrashPage grid). One
 * computation per page rather than per cell — the page-level call
 * site passes the resulting `Int` to [AsyncThumbnail].
 *
 * Formula: `(screenWidth - 2*horizontalPadding - (cols-1)*spacing) / cols`,
 * converted to pixels at current density.
 */
@Composable
fun rememberGridCellSizePx(
    columnCount: Int,
    horizontalPaddingDp: Dp = 2.dp,
    spacingDp: Dp = 2.dp,
): Int {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(configuration.screenWidthDp, columnCount, horizontalPaddingDp, spacingDp) {
        val widthDp = configuration.screenWidthDp.toFloat()
        val consumed = horizontalPaddingDp.value * 2f +
            spacingDp.value * (columnCount - 1).coerceAtLeast(0)
        val cellDp = ((widthDp - consumed) / columnCount).coerceAtLeast(1f)
        with(density) { cellDp.dp.roundToPx() }
    }
}

/**
 * 网格缩略图解码尺寸：取实时 [rememberGridCellSizePx] 后映射到档位
 * [thumbDecodeSize]。布局仍用实时 cellPx；解码用档位值。
 *
 * 核心权衡（对标 Google Photos）：缩小后格子小 → 解码小 → 滚动分配量低
 * （消除"缩小后滑动卡"的 GC 根源）；2 列时解码 384px 上限（不模糊）；
 * 相邻列数共享档位 → Coil 缓存 key 稳定 → 缩放吸附不整屏重解码。
 */
@Composable
fun rememberGridThumbSizePx(columnCount: Int): Int {
    val cellPx = rememberGridCellSizePx(columnCount = columnCount)
    return thumbDecodeSize(cellPx)
}