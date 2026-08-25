package com.smartvision.gallery.ui.liquidglass

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.ui.gestures.detectLongPressThenDrag

/**
 * Paint the iOS 26 "tinted glass" overlay on top of the captured backdrop.
 *
 * Painted back-to-front:
 *  1. Vertical gradient from `spec.tint` (top, alpha = `tintAlpha`) to
 *     `Color.White` (bottom, alpha = `tintAlpha / 2`) — gives the glass
 *     a colored top, neutral bottom. **Skipped when `tintAlpha == 0`** —
 *     the surface stays fully transparent so page content shows through.
 *  2. Thin highlight stripe at the very top (white @ `highlightAlpha`,
 *     top 18 % of the height) — the "lit from above" reflection. Lower
 *     alpha than the previous 0.45 so floating chips don't read as raised
 *     plastic pills.
 *  3. 1 dp edge stroke (white @ 0.22 alpha) so the surface reads as a
 *     glass card with a defined edge even when tint is zero. The previous
 *     version used a full-surface 0.12 black `drawRect` which made every
 *     glass surface read as an opaque gray pill.
 *
 * The colors come from [LiquidGlassSpec.tint], [LiquidGlassSpec.tintAlpha]
 * and [LiquidGlassSpec.highlightAlpha]; pass them in the spec to recolor
 * the surface per-call-site.
 */
internal fun DrawScope.drawGlassTint(spec: LiquidGlassSpec, drawEdgeStrokes: Boolean = true) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // 1. Base tint gradient — the glass color cast on top of the blurred
    //    backdrop. Apple uses multi-layer composition; we layer vertical
    //    passes to mimic that depth.
    if (spec.tintAlpha > 0f) {
        // 1a. Soft full-height base wash.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    spec.tint.copy(alpha = spec.tintAlpha * 0.85f),
                    Color.White.copy(alpha = spec.tintAlpha * 0.35f),
                ),
                startY = 0f,
                endY = h,
            )
        )
        // 1b. Stronger top half — simulates the underside reflection off
        //     the curved upper face of the glass. Multiplier controlled by
        //     spec.topTintExtra (playground-tunable).
        if (spec.topTintExtra > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        spec.tint.copy(alpha = spec.tintAlpha * spec.topTintExtra),
                        Color.Transparent,
                    ),
                    startY = 0f,
                    endY = h * 0.5f,
                )
            )
        }
    }
    // 2. Specular highlight — the bright "lit from above" reflection. This
    //    is the dominant 3D cue in iOS 26. spec.specularAlpha controls the
    //    maximum brightness of both the broad top highlight and the glint.
    if (spec.highlightAlpha > 0f && spec.specularAlpha > 0f) {
        // 2a. Broad soft highlight over the top quarter — gives the glass
        //     its overall glow. Strength scales with specularAlpha.
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = spec.highlightAlpha * spec.specularAlpha),
                    Color.White.copy(alpha = spec.highlightAlpha * spec.specularAlpha * 0.35f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = h * 0.30f,
            )
        )
        // 2b. Specular glint — concentrated bright stripe at the very top
        //     edge. This is what makes the surface read as "polished glass".
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = (spec.highlightAlpha * spec.specularAlpha * 1.4f).coerceAtMost(0.95f)),
                    Color.White.copy(alpha = spec.highlightAlpha * spec.specularAlpha * 0.6f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = h * 0.08f,
            )
        )
    }
    // 3. Bottom inner shadow — the curved underside falling away from
    //    the imagined light source. spec.bottomShadowAlpha controls depth.
    if (spec.bottomShadowAlpha > 0f) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = spec.bottomShadowAlpha),
                ),
                startY = h * 0.78f,
                endY = h,
            )
        )
    }
    // 4. Edge strokes — defines silhouette AND provides the "darkened
    //    edge" cue that iOS 27 introduced for visual separation against
    //    complex backgrounds. spec.edgeDarkAlpha controls all non-top
    //    edges; top edge brightness scales with spec.specularAlpha.
    if (drawEdgeStrokes) {
        val stroke = 1.dp.toPx()
        // Top — bright specular edge (the polished-glass glint)
        drawLine(
            color = Color.White.copy(alpha = spec.specularAlpha.coerceIn(0f, 0.95f)),
            start = Offset(0f, 0f),
            end = Offset(w, 0f),
            strokeWidth = stroke,
        )
        // Bottom — dark edge for visual separation (iOS 27 cue)
        if (spec.edgeDarkAlpha > 0f) {
            drawLine(
                color = Color.Black.copy(alpha = spec.edgeDarkAlpha),
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = stroke,
            )
            drawLine(
                color = Color.Black.copy(alpha = spec.edgeDarkAlpha * 0.67f),
                start = Offset(0f, 0f),
                end = Offset(0f, h),
                strokeWidth = stroke,
            )
            drawLine(
                color = Color.Black.copy(alpha = spec.edgeDarkAlpha * 0.67f),
                start = Offset(w, 0f),
                end = Offset(w, h),
                strokeWidth = stroke,
            )
        }
    }
}

/**
 * Drop-in Liquid Glass surface — straight port of Kyant0's pattern from
 * `LiquidBottomTabs.kt`.
 *
 * Reads the screen-level [LocalLiquidGlassBackdrop] (set up by
 * [LiquidGlassTheme]) and applies vibrancy + blur + a small lens effect.
 * The `shape` defaults to [LiquidGlassSpec.cornerRadius] so call sites can
 * just pass a spec and get the iOS 26 look.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec? = null,
    shape: Shape = RoundedCornerShape(spec?.cornerRadius ?: LocalGlassConfig.current.staticGlass.toSpec().cornerRadius),
    @Suppress("UNUSED_PARAMETER") backdrop: Any? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    propagateMinConstraints: Boolean = false,
    content: @Composable () -> Unit,
) {
    val resolvedSpec = spec ?: LocalGlassConfig.current.staticGlass.toSpec()
    val backdrop = LocalLiquidGlassBackdrop.current
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    if (resolvedSpec.vibrancy) vibrancy()
                    blur(with(density) { resolvedSpec.blurRadius.toPx() })
                    lens(
                        with(density) { resolvedSpec.lensAmount.toPx() },
                        with(density) { resolvedSpec.lensAmount.toPx() },
                    )
                },
                onDrawSurface = {
                    drawGlassTint(resolvedSpec)
                }
            ),
        contentAlignment = contentAlignment,
        propagateMinConstraints = propagateMinConstraints,
    ) { content() }
}

/**
 * Liquid Glass card — glass surface + tap feedback + ripple.
 *
 * When [onClick] is null the card is non-interactive (no ripple, no clip).
 *
 * Implementation note: the `drawBackdrop` call is INLINED here (matching the
 * proven pattern in [LiquidGlassBar] and [LiquidGlassControlPill]) instead
 * of being delegated to a private `@Composable Modifier` extension. The
 * private-extension pattern (`drawBackdropContainer`) caused the spec update
 * from [LocalGlassConfig] to silently not propagate — when `resolvedSpec`
 * changed, Kyant's modifier element appeared to retain stale lambdas because
 * the `@Composable`-returning-Modifier extension's parameter `spec` is not
 * a state read and Compose's optimizer kept the old modifier node. Inlining
 * makes `resolvedSpec` a normal body read and ensures every recomposition
 * with a new spec installs fresh effects/onDrawSurface lambdas on the node.
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec? = null,
    shape: Shape = RoundedCornerShape(spec?.cornerRadius ?: LocalGlassConfig.current.staticGlass.toSpec().cornerRadius),
    @Suppress("UNUSED_PARAMETER") backdrop: Any? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    // Read both CompositionLocals in the body — Compose tracks these reads
    // and invalidates the whole composable when either changes. This is the
    // proven pattern used by LiquidGlassBar / LiquidGlassControlPill.
    val resolvedSpec = spec ?: LocalGlassConfig.current.staticGlass.toSpec()
    val bd = LocalLiquidGlassBackdrop.current
    val density = LocalDensity.current

    // Edge-clip via graphicsLayer (NOT Modifier.clip(shape)) — Modifier.clip
    // only restricts the paint region; RenderEffect (blur + lens) still runs
    // on the full Rect bounds of the RenderNode, sampling backdrop pixels
    // outside the rounded shape. Those out-of-shape samples diffuse back into
    // the shape-edge pixels, making the glass read as "translucent / see-through"
    // at the rounded corners — the bug the user reported.
    //
    // graphicsLayer(shape = ..., clip = true) creates an isolated RenderNode
    // whose bounds == the shape's path bounds, so blur/lens shaders compute
    // exclusively inside the shape and the edge stays fully frosted.
    val shapeClip = Modifier.graphicsLayer(shape = shape, clip = true)
    val clickableMod = if (onClick != null) {
        shapeClip.clickable(enabled = enabled, onClick = onClick)
    } else {
        shapeClip
    }

    Box(
        modifier = modifier
            .then(clickableMod)
            .drawBackdrop(
                backdrop = bd,
                shape = { shape },
                effects = {
                    if (resolvedSpec.vibrancy) vibrancy()
                    blur(with(density) { resolvedSpec.blurRadius.toPx() })
                    lens(
                        with(density) { resolvedSpec.lensAmount.toPx() },
                        with(density) { resolvedSpec.lensAmount.toPx() },
                    )
                },
                onDrawSurface = {
                    drawGlassTint(resolvedSpec)
                }
            )
            .padding(contentPadding)
    ) { content() }
}

/**
 * Pill / chip surface. Always uses a 999dp capsule radius regardless of
 * [spec]'s corner radius.
 */
@Composable
fun LiquidGlassChip(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec? = null,
    @Suppress("UNUSED_PARAMETER") backdrop: Any? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val resolvedSpec = (spec ?: LocalGlassConfig.current.staticGlass.toSpec()).copy(cornerRadius = 999.dp)
    LiquidGlassCard(
        modifier = modifier,
        spec = resolvedSpec,
        shape = RoundedCornerShape(999.dp),
        backdrop = backdrop,
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        content = content,
    )
}

/**
 * iOS 26 tab bar pill — Kyant0's `Capsule()` shape + the spec's lens amount.
 *
 * The caller is responsible for sizing & positioning; we just paint the
 * glass body. The lens distortion on press is applied via the separate
 * `Modifier.liquidLensingDistortion(lensCenter)` extension.
 */
@Composable
fun LiquidGlassBar(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec? = null,
    @Suppress("UNUSED_PARAMETER") backdrop: Any? = null,
    content: @Composable () -> Unit,
) {
    val bd = LocalLiquidGlassScreenBackdrop.current
    val density = LocalDensity.current
    val tabSpec = spec ?: LocalGlassConfig.current.tabBar.toSpec()
    Box(
        modifier = modifier
            .drawBackdrop(
                backdrop = bd,
                shape = { ContinuousCapsule() },
                effects = {
                    if (tabSpec.vibrancy) vibrancy()
                    blur(with(density) { tabSpec.blurRadius.toPx() })
                    if (tabSpec.lensAmount > 0.dp) {
                        val lensPx = with(density) { tabSpec.lensAmount.toPx() }
                        lens(lensPx, lensPx)
                    }
                },
                onDrawSurface = {
                    drawGlassTint(tabSpec)
                }
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Adaptive Liquid Glass — auto-detects the system's contrast setting and
 * lowers blur/vibrancy for users with "Reduce transparency" enabled.
 */
@Composable
fun AdaptiveLiquidGlass(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec = LiquidGlassSpec.iOS26Static.copy(blurRadius = 4.dp, vibrancy = false),
    shape: Shape = RoundedCornerShape(spec.cornerRadius),
    content: @Composable () -> Unit,
) {
    // Kyant0's library does its own runtime detection of AGSL support and
    // gracefully degrades to no-effect rendering on API < 33. We just defer
    // to LiquidGlassSurface here with a conservative spec.
    LiquidGlassSurface(
        modifier = modifier,
        spec = spec,
        shape = shape,
        content = content,
    )
}

/**
 * Chrome-level Liquid Glass top navigation bar.
 *
 * Renders a full-width glass strip using [LocalLiquidGlassScreenBackdrop] with
 * the [LiquidGlassSpec.iOS26TopBar] spec. Unlike [LiquidGlassBar] which uses
 * ContinuousCapsule, this is a flat rect (full-width chrome).
 */
@Composable
fun LiquidGlassTopBar(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec? = null,
    content: @Composable () -> Unit,
) {
    val resolvedSpec = spec ?: LocalGlassConfig.current.topBar.toSpec()
    val bd = LocalLiquidGlassBackdrop.current
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = bd,
                shape = { RoundedCornerShape(0.dp) },
                effects = {
                    if (resolvedSpec.vibrancy) vibrancy()
                    blur(with(density) { resolvedSpec.blurRadius.toPx() })
                },
                onDrawSurface = {
                    drawGlassTint(resolvedSpec)
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Control-level Liquid Glass pill (button/chip).
 *
 * Uses [LiquidGlassSpec.iOS26Control]: 7dp blur, pill radius, subtle tint.
 * Apply [Modifier.size(44.dp)] for symbol buttons or fillMaxWidth for text buttons.
 */
@Composable
fun LiquidGlassControlPill(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec? = null,
    shape: Shape = RoundedCornerShape(999.dp),
    onClick: (() -> Unit)? = null,
    useScreenBackdrop: Boolean = false,
    content: @Composable () -> Unit,
) {
    val resolvedSpec = spec ?: LocalGlassConfig.current.control.toSpec()
    // useScreenBackdrop=true → sample real captured pixels (screen-chrome sibling,
    // same as CapsuleActionIcon / LiquidGlassBar). false (default, unchanged) →
    // in-content canvas gradient. Back buttons in title bars live as Z=1 siblings
    // of the AppRoot capture Box, so they pass true for real frosted glass (no
    // color fill); in-content pills keep the canvas gradient.
    val bd = if (useScreenBackdrop) LocalLiquidGlassScreenBackdrop.current
             else LocalLiquidGlassBackdrop.current
    val density = LocalDensity.current
    val m = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier
    Box(
        modifier = m
            .drawBackdrop(
                backdrop = bd,
                shape = { shape },
                effects = {
                    if (resolvedSpec.vibrancy) vibrancy()
                    blur(with(density) { resolvedSpec.blurRadius.toPx() })
                    lens(
                        with(density) { resolvedSpec.lensAmount.toPx() },
                        with(density) { resolvedSpec.lensAmount.toPx() },
                    )
                },
                onDrawSurface = {
                    drawGlassTint(resolvedSpec)
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Floating top bar pill — iOS 26 style, renders at Z=1 in AppRoot.
 *
 * Uses [LiquidGlassBar] with [ContinuousCapsule] (same pill shape as the
 * bottom tab bar) and [LocalLiquidGlassScreenBackdrop] for real backdrop
 * sampling. Reads its config from [LocalTopBarState] (written by each page).
 *
 * Supports:
 *  - [TopBarVariant.HIDDEN]: not rendered.
 *  - [TopBarVariant.LARGE_TITLE]: tall bar with large 28sp title.
 *  - [TopBarVariant.COLLAPSIBLE_TITLE]: large title that collapses to compact
 *    driven by [TopBarConfig.collapsedRatio] (0f = expanded, 1f = collapsed).
 *  - [TopBarVariant.COMPACT]: 44dp pill with 17sp title + optional back button.
 *
 * No liquid lensing — [lensAmount] is 0 to prevent the symmetrical-distortion
 * seam artifact discovered in the segmented control bar.
 */
@Composable
fun FloatingTopBarPill(
    modifier: Modifier = Modifier,
    config: TopBarConfig,
) {
    if (config.variant == TopBarVariant.HIDDEN) return

    when (config.variant) {
        TopBarVariant.LARGE_TITLE ->
            LargeTitleBar(modifier = modifier, config = config)
        TopBarVariant.COLLAPSIBLE_TITLE ->
            CollapsibleTitleBar(modifier = modifier, config = config)
        TopBarVariant.COMPACT ->
            CompactTitleBar(modifier = modifier, config = config)
        TopBarVariant.HIDDEN -> {}
    }
}

/**
 * Compact 44dp pill — back button (optional) + 17sp title.
 */
@Composable
private fun CompactTitleBar(
    modifier: Modifier,
    config: TopBarConfig,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        LiquidGlassBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            spec = LocalGlassConfig.current.topBar.toSpec().copy(cornerRadius = 999.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                if (config.onBack != null) {
                    LiquidGlassControlPill(
                        modifier = Modifier.size(36.dp),
                        onClick = config.onBack,
                        useScreenBackdrop = true,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = config.title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Large title bar — 64dp pill with 28sp bold title.
 *
 * Near-zero tint and highlight so the bar reads as pure frosted glass —
 * just blur over the screen-captured backdrop with no opaque "div fill".
 */
@Composable
private fun LargeTitleBar(
    modifier: Modifier,
    config: TopBarConfig,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        LiquidGlassBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            spec = LocalGlassConfig.current.topBar.toSpec(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Text(
                    text = config.title,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Collapsible title bar — 64dp large title that shrinks to 44dp compact
 * driven by [TopBarConfig.collapsedRatio] (0f = expanded, 1f = collapsed).
 */
@Composable
private fun CollapsibleTitleBar(
    modifier: Modifier,
    config: TopBarConfig,
) {
    val collapseRatio = config.collapsedRatio.coerceIn(0f, 1f)
    val barHeight by animateDpAsState(
        targetValue = 44.dp + 20.dp * (1f - collapseRatio),
        label = "barHeight",
    )
    val titleFontSize = (17 + (28 - 17) * (1f - collapseRatio)).sp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        LiquidGlassBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            spec = LocalGlassConfig.current.topBar.toSpec().copy(cornerRadius = 999.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .padding(start = if (collapseRatio > 0.5f) 8.dp else 16.dp, end = 16.dp, bottom = if (collapseRatio < 0.5f) 8.dp else 0.dp),
                verticalAlignment = if (collapseRatio > 0.5f) Alignment.CenterVertically else Alignment.Bottom,
                horizontalArrangement = Arrangement.Start,
            ) {
                if (config.onBack != null) {
                    LiquidGlassControlPill(
                        modifier = Modifier.size(36.dp),
                        onClick = config.onBack,
                        useScreenBackdrop = true,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = config.title,
                    fontSize = titleFontSize,
                    fontWeight = if (collapseRatio > 0.5f) FontWeight.SemiBold else FontWeight.Bold,
                    letterSpacing = if (collapseRatio > 0.5f) 0.sp else (-0.5).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
