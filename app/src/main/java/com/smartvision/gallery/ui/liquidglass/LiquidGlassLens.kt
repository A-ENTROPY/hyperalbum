package com.smartvision.gallery.ui.liquidglass

import android.os.Build
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.capsule.ContinuousCapsule
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * iOS 26 "Liquid Lensing" controller — the long-press magnifier that follows
 * the finger after a 400 ms long-press on a tab bar item.
 *
 * Architecture note (this iteration): the magnifier is no longer a separate
 * global overlay. The press-origin tab item itself becomes the lens — its
 * container is sized to `lensSize × (lensSize * widthScale)` and decorated
 * with `drawBackdrop(barBackdrop) + lens()` so it magnifies the bar's own
 * icons, never the page underneath. The controller's only remaining job is
 * to drive the position/widthScale/alpha Animatables that the press-origin
 * item reads.
 *
 * Range clamping: the bar sets [bounds] via `setBounds(rect)` (the bar's
 * pill rect in window coordinates). `show` / `moveTo` clamp the lens center
 * inside that rect: horizontal free, vertical biased toward the bar's top
 * edge so most of the magnifier rises above the bar over page content.
 */
class LiquidGlassLensController {

    val position: Animatable<Offset, AnimationVector2D> =
        Animatable(Offset.Zero, Offset.VectorConverter)
    /**
     * Width multiplier applied to the lens's natural size (= bar item
     * height). 1.0 = circle, ~1.5 = slight capsule. Vertical scale is
     * always 1 — vertical squash produces a sharp ellipse look that
     * contradicts iOS 26.
     */
    val widthScale: Animatable<Float, AnimationVector1D> = Animatable(1f)
    val alpha: Animatable<Float, AnimationVector1D> = Animatable(0f)
    /**
     * Overall scale of the lens. Springs from ~0 (tiny dot at pressed tab) to
     * 1 (full size) on appear, and reverses on hide — producing the iOS 26
     * "grow from tab, shrink back into tab" transition.
     *
     * Stays at 0.01f between shows so the next appear animation always has a
     * visible starting point. The DrawBackdropNode's internal GraphicsLayer
     * buffer stays at full Box size regardless of this scale (scale is a
     * post-render transform), so warmup rendering is unaffected.
     */
    val scale: Animatable<Float, AnimationVector1D> = Animatable(0.01f)
    val isVisible = mutableStateOf(false)

    /**
     * Alpha of the frosted-glass capsule during the hide morph. Starts at 0
     * when the lens appears; springs to 1 in parallel with the lens shrinking,
     * so the capsule fades in with a slight bounce while the lens collapses
     * onto it — creating a single continuous transformation rather than a cut.
     */
    val capsuleAlpha: Animatable<Float, AnimationVector1D> = Animatable(0f)

    /** Bar pill rect in window coordinates; null = no clamp. */
    var bounds: Rect? by mutableStateOf(null)

    /**
     * Lens diameter in pixels (HEIGHT, since we keep a square aspect — width
     * is derived from `widthScale * baseSizePx`). Set by the bar on measure.
     */
    var baseSizePx: Float = 560f

    /** Mirrored from [com.smartvision.gallery.data.glass.LensGlassConfig]. */
    var stretchMax: Float = 1.5f

    /** EMA-smoothed finger velocity in px/s. Used only by widthScale. */
    private var velocityX: Float = 0f
    private var velocityY: Float = 0f
    private var lastTimestampNanos: Long = 0L

    fun show(at: Offset, scope: CoroutineScope) {
        val clamped = clampToBar(at)
        velocityX = 0f; velocityY = 0f; lastTimestampNanos = 0L
        isVisible.value = true
        Log.d("LiquidLens", "DBG lens.show() at=$clamped (raw=$at)")
        scope.launch {
            position.snapTo(clamped)
            launch { widthScale.snapTo(1f) }
            launch { capsuleAlpha.snapTo(0f) }
            // Snap to small then spring to full size with slight overshoot.
            // This snap is safe — the graphicsLayer scale is a post-render
            // transform; DrawBackdropNode's internal buffer stays at full
            // Box size, so no re-allocation / dark frame.
            launch {
                scale.snapTo(0.01f)
                scale.animateTo(1f, spring(stiffness = 350f, dampingRatio = 0.55f))
            }
            launch {
                alpha.animateTo(1f, spring(stiffness = 600f, dampingRatio = 0.9f))
            }
        }
    }

    fun moveTo(at: Offset, scope: CoroutineScope, nowNanos: Long) {
        val clamped = clampToBar(at)
        scope.launch { position.snapTo(clamped) }
        updateVelocity(clamped, nowNanos)
        recomputeWidthScale(scope)
    }

    /**
     * Smoothly animate from current lens position to [target]. Used when the
     * lens starts at the selected tab but the finger is elsewhere — creates
     * a fluid "liquid flow" transition instead of an instant snap.
     */
    fun animateToPosition(target: Offset, scope: CoroutineScope) {
        val clamped = clampToBar(target)
        scope.launch {
            position.animateTo(clamped, spring(stiffness = 300f, dampingRatio = 0.7f))
        }
    }

    fun hide(scope: CoroutineScope, morphTarget: Offset? = null) {
        Log.d("LiquidLens", "DBG lens.hide() morphTarget=$morphTarget")
        // Reset visibility synchronously BEFORE launching the animation
        // coroutine. If scope is cancelled (e.g. popBackStack() from within
        // onDragEnd disposes the composable mid-gesture), the animation
        // never runs but isVisible is already false — preventing the lens
        // from getting permanently stuck on screen.
        isVisible.value = false
        scope.launch {
            // Morph-back: fly to the selected tab's capsule center while
            // shrinking, fading, and cross-fading the capsule in beneath.
            //
            // Two parallel groups:
            //   Lens → overdamped fast shrink (stiffness 3000, no bounce)
            //   Capsule → springy fade-in  (stiffness 600, 0.55 damping)
            //
            // The capsule (at Z=1, behind the lens Z=2) fades in with a
            // slight bounce while the lens shrinks and fades onto the same
            // position — the eye sees a single "lens dissolves into capsule"
            // transformation.
            coroutineScope {
                if (morphTarget != null) {
                    launch {
                        position.animateTo(
                            clampToBar(morphTarget),
                            spring(stiffness = 3000f, dampingRatio = 1.2f),
                        )
                    }
                }
                launch {
                    scale.animateTo(0.1f, spring(stiffness = 3000f, dampingRatio = 1.2f))
                }
                launch {
                    capsuleAlpha.animateTo(1f, spring(stiffness = 600f, dampingRatio = 0.55f))
                }
                alpha.animateTo(0f, spring(stiffness = 3000f, dampingRatio = 1.2f))
            }
            // Reset state and hide in the same frame.
            scale.snapTo(1f)
            isVisible.value = false
        }
    }

    private fun updateVelocity(target: Offset, nowNanos: Long) {
        if (lastTimestampNanos == 0L) {
            lastTimestampNanos = nowNanos
            return
        }
        val dtSec = ((nowNanos - lastTimestampNanos).coerceAtLeast(1L)) / 1_000_000_000f
        lastTimestampNanos = nowNanos
        val prev = position.value
        val instVx = (target.x - prev.x) / dtSec
        val instVy = (target.y - prev.y) / dtSec
        val emaAlpha = 1f - kotlin.math.exp(-dtSec / 0.05f)
        velocityX = instVx * emaAlpha + velocityX * (1f - emaAlpha)
        velocityY = instVy * emaAlpha + velocityY * (1f - emaAlpha)
    }

    private fun recomputeWidthScale(scope: CoroutineScope) {
        val maxVx = 1800f * (baseSizePx / 560f)
        val absVx = kotlin.math.abs(velocityX).coerceAtMost(maxVx)
        // stretchMax < 1 means "compress" — velocity makes the lens
        // narrower than circle; stretchMax > 1 stretches into a capsule.
        // Unconstrained (was `.coerceAtLeast(0f)`) so the 0..3 slider
        // actually compresses at the low end.
        val newWidthScale = 1f + (absVx / maxVx) * (stretchMax - 1f)
        scope.launch {
            widthScale.animateTo(newWidthScale, spring(stiffness = 380f, dampingRatio = 0.55f))
        }
    }

    private fun clampToBar(p: Offset): Offset {
        val b = bounds ?: return p
        val y = b.top + (b.bottom - b.top) / 2f
        val x = p.x.coerceIn(b.left, b.right)
        return Offset(x, y)
    }
}

val LocalLiquidGlassLens = staticCompositionLocalOf<LiquidGlassLensController> {
    error("LocalLiquidGlassLens not provided. Wrap your screen in CompositionLocalProvider.")
}

@Composable
fun rememberLiquidGlassLensController(): LiquidGlassLensController =
    remember { LiquidGlassLensController() }

val LocalSegmentedControlLens = staticCompositionLocalOf<LiquidGlassLensController> {
    error("LocalSegmentedControlLens not provided")
}

// ─── Tab layout data shared between iOSTabBar and LiquidGlassLensOverlay ───

/** Window-space rect of a single tab item. */
data class TabRect(val origin: Offset, val size: IntSize)

/** Data needed to render a single tab's icon+text native inside the lens overlay. */
data class LensTabInfo(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val windowRect: Rect,
    /** Exact window-space rect of the Icon composable — measured from the actual
     *  rendered icon via onGloballyPositioned. The lens overlay uses this for
     *  pixel-perfect positioning instead of estimating column layout offsets. */
    val iconWindowRect: Rect,
)

/** Container for all tab icon info that the lens overlay reads each frame. */
data class LensOverlayIconInfo(
    val tabs: List<LensTabInfo>,
    val selectedRoute: String?,
)

/**
 * Mutable state slot provided by [AppRoot] and written by [iOSTabBar] so the
 * lens overlay can read current tab positions without creating a circular
 * dependency between LiquidGlassLens.kt and AppleComponents.kt.
 */
val LocalLensOverlayIconState = staticCompositionLocalOf<MutableState<LensOverlayIconInfo?>> {
    error("LocalLensOverlayIconState not provided")
}

/**
 * Mutable state slot for the segmented control backdrop. Written by [TimelinePage]
 * and read by [SegmentedControlLensOverlay] — same pattern as [LocalLensOverlayIconState].
 * Provides pixel-level blue text compositing inside the lens when the lens sweeps
 * over a segment, matching the tab bar's barBackdrop compositing.
 */
val LocalSegmentedControlBackdropState = staticCompositionLocalOf<MutableState<LayerBackdrop?>> {
    error("LocalSegmentedControlBackdropState not provided")
}

/**
 * State shared between [TimelinePage] and the Z=1 [LibraryOverlay] chrome.
 *
 * The Library page's action row (选择 / 排序 Tune chips) and the segmented
 * control (全部 / 日月年 / 选择) render at Z=1 as siblings of the captured
 * subtree so they can read [LocalLiquidGlassScreenBackdrop] — the real
 * screen-captured photo content underneath them. That gives them the same
 * iOS 26 Liquid Glass physics as the bottom tab bar (blur + lens + vibrancy
 * over actual photos).
 *
 * To keep this architecturally clean without dragging the page's full state
 * tree up to [AppRoot], we share just three things:
 *  * [segment] — currently selected segment (read by overlay for highlight;
 *    written by overlay via onSegmentChange when user taps).
 *  * [scrollCollapsedRatio] — 0f..1f, driven by the grid's first visible item
 *    scroll offset. The overlay reads this to fade + translate the segmented
 *    control up on scroll (the hide animation stays in user-visible TimelinePage).
 *  * [onSegmentChange] — callback registered by [TimelinePage] to receive
 *    segment updates from the overlay so it can persist and react.
 *
 * Written by TimelinePage's grid scroll observer; read by AppRoot's
 * LibraryOverlay. Same pattern as [LocalTopBarState].
 */
data class LibraryOverlayState(
    val segment: Int = 0,
    val scrollCollapsedRatio: Float = 0f,
    val onSegmentChange: (Int) -> Unit = {},
    val selectModeEnabled: Boolean = false,
    val onToggleSelectMode: () -> Unit = {},
    val selectedCount: Int = 0,
    val onToggleItemSelection: (Long) -> Unit = {},
    val sortNewestFirst: Boolean = true,
    val onToggleSort: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    val onFavoriteSelected: () -> Unit = {},
    val onShareSelected: () -> Unit = {},
    val onHideSelected: () -> Unit = {},
    val onOpenAlbums: () -> Unit = {},
)

val LocalLibraryOverlayState = staticCompositionLocalOf<MutableState<LibraryOverlayState>> {
    error("LocalLibraryOverlayState not provided")
}

/**
 * iOS 26 "Liquid Lensing" magnifier — renders above the tab bar using
 * the screen-level backdrop (page content). When [barBackdrop] is provided
 * the lens composites TWO layers: the page content (bottom) and the bar
 * content (top), both with the lens refraction shader, so the magnifier
 * shows whatever is on screen at that position — both page AND bar icons.
 *
 * Layer 3 (top): native cyan-tinted icons rendered as Compose composables,
 * clipped to the lens shape so only pixels inside the lens boundary turn
 * cyan — matching iOS 26's per-pixel highlight behavior.
 */
@Composable
fun LiquidGlassLensOverlay(
    modifier: Modifier = Modifier,
    barBackdrop: Backdrop? = null,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        LegacyLensOverlay(modifier)
        return
    }
    val controller = LocalLiquidGlassLens.current
    val visible = controller.isVisible.value
    if (!visible) return

    val pageBackdrop = LocalLiquidGlassScreenBackdrop.current
    val density = LocalDensity.current
    val config = LocalGlassConfig.current
    val lensCfg = config.lens
    val sizeDp = lensCfg.lensSize
    val sizePx = with(density) { sizeDp.toPx() }
    val widthScale = controller.widthScale.value
    val widthPx = sizePx * widthScale
    val widthDp = with(density) { widthPx.toDp() }
    val refractionHeightPx = with(density) { lensCfg.lensRefractionHeight.toPx() }
    val refractionAmountPx = with(density) { lensCfg.lensRefractionAmount.toPx() }
    val chromaticAberration = lensCfg.lensChromaticAberration > 0.5f

    controller.baseSizePx = sizePx
    controller.stretchMax = lensCfg.stretchMax

    val lscale = controller.scale.value
    val pos = controller.position.value
    val halfW = widthPx / 2f
    val halfH = sizePx / 2f
    val lensShape = RoundedCornerShape(sizeDp / 2)

    Box(
        modifier = modifier
            .offset { IntOffset((pos.x - halfW).roundToInt(), (pos.y - halfH).roundToInt()) }
            .size(width = widthDp, height = sizeDp)
            .graphicsLayer {
                scaleX = lscale
                scaleY = lscale
                transformOrigin = TransformOrigin.Center
                alpha = if (visible) controller.alpha.value else 0.003f
            }
            .clip(lensShape)
    ) {
        // iOS 26 native lens — sample area == visible area at the lens center.
        // Variable magnification (iconScaleInside slider) was REMOVED 2026-07-04:
        // Kyant's drawBackdrop cannot decouple the sample region from the visible
        // region. 4 attempts all introduced centering / visual artifacts. The
        // physics is now strictly identity-scale capture + lens refraction — the
        // exact behavior the iOS 26 sample app demonstrates.
        Box(
            modifier = Modifier.matchParentSize()
                .drawBackdrop(
                    backdrop = pageBackdrop,
                    shape = { lensShape },
                    effects = {
                        lens(
                            refractionHeight = refractionHeightPx,
                            refractionAmount = refractionAmountPx,
                            chromaticAberration = chromaticAberration,
                        )
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.05f)) },
                )
        )
        if (barBackdrop != null) {
            Box(
                modifier = Modifier.matchParentSize()
                    .drawBackdrop(
                        backdrop = barBackdrop,
                        shape = { lensShape },
                        effects = {
                            lens(
                                refractionHeight = refractionHeightPx,
                                refractionAmount = refractionAmountPx,
                                chromaticAberration = chromaticAberration,
                            )
                        },
                    )
            )
        }
    }
}

/**
 * iOS 26 liquid lens overlay for the segmented control (全部/日月年/选择).
 *
 * Renders at Z=2 with the [LocalSegmentedControlBackdropState] (the hidden
 * backdrop-only capture). The lens shows the bar's own content — a
 * track + selected-segment capsule + cyan text — with the refraction
 * shader, NOT the page content underneath. This is the "magnifier shows
 * the bar, not the photos" pattern that fixes the "lens passes through
 * the bar" bug.
 *
 * The hidden capture (in [iOSSegmentedControl] when `backdropOnly = true`)
 * uses static tints — no drawBackdrop — so the lens shader is applied
 * exactly once. The visible control's frosted-glass rendering is preserved
 * separately on the page; the lens is a distinct visual.
 */
@Composable
fun SegmentedControlLensOverlay(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val controller = LocalSegmentedControlLens.current
    val visible = controller.isVisible.value
    // CRITICAL: skip rendering entirely when hidden. Same bug as the bar
    // lens — invisible capsule was sitting at (0,0) with a real size, eating
    // pointer events for thumbnails underneath.
    if (!visible) return

    val pageBackdrop = LocalLiquidGlassScreenBackdrop.current
    val segBackdropState = LocalSegmentedControlBackdropState.current
    val segBackdrop = segBackdropState.value
    val density = LocalDensity.current
    val config = LocalGlassConfig.current
    val lensCfg = config.lens
    val sizeDp = lensCfg.lensSize
    val sizePx = with(density) { sizeDp.toPx() }
    // Capsule proportions: wider than tall to match segment pill shape,
    // rather than the circular lens used for the bottom tab bar.
    val capsuleWidthDp = sizeDp * 1.6f
    val capsuleHeightDp = sizeDp * 0.65f
    val capsuleWidthPx = with(density) { capsuleWidthDp.toPx() }
    val capsuleHeightPx = with(density) { capsuleHeightDp.toPx() }
    val refractionHeightPx = with(density) { lensCfg.lensRefractionHeight.toPx() }
    val refractionAmountPx = with(density) { lensCfg.lensRefractionAmount.toPx() }
    val chromaticAberration = lensCfg.lensChromaticAberration > 0.5f

    controller.baseSizePx = capsuleHeightPx

    val lscale = controller.scale.value
    val pos = controller.position.value
    val halfW = capsuleWidthPx / 2f
    val halfH = capsuleHeightPx / 2f

    Box(
        modifier = modifier
            .offset { IntOffset((pos.x - halfW).roundToInt(), (pos.y - halfH).roundToInt()) }
            .size(width = capsuleWidthDp, height = capsuleHeightDp)
            .graphicsLayer {
                scaleX = lscale
                scaleY = lscale
                transformOrigin = TransformOrigin.Center
                alpha = if (visible) controller.alpha.value else 0.003f
            }
            .clip(ContinuousCapsule())
    ) {
        // See LiquidGlassLensOverlay comment — magnifierScale removed 2026-07-04.
        Box(
            modifier = Modifier.matchParentSize()
                .drawBackdrop(
                    backdrop = pageBackdrop,
                    shape = { ContinuousCapsule() },
                    effects = {
                        lens(
                            refractionHeight = refractionHeightPx,
                            refractionAmount = refractionAmountPx,
                            chromaticAberration = chromaticAberration,
                        )
                    },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.05f)) },
                )
        )
        if (segBackdrop != null) {
            Box(
                modifier = Modifier.matchParentSize()
                    .drawBackdrop(
                        backdrop = segBackdrop,
                        shape = { ContinuousCapsule() },
                        effects = {
                            lens(
                                refractionHeight = refractionHeightPx,
                                refractionAmount = refractionAmountPx,
                                chromaticAberration = chromaticAberration,
                            )
                        },
                    )
            )
        }
    }
}

/**
 * SDK < 33 fallback. Same Kyant `drawBackdrop + lens()` path, kept because
 * it has been visually verified on this device for older API levels.
 */
@Composable
fun LegacyLensOverlay(modifier: Modifier = Modifier) {
    val controller = LocalLiquidGlassLens.current
    val visible = controller.isVisible.value
    val backdrop = LocalLiquidGlassScreenBackdrop.current
    val density = LocalDensity.current
    val config = LocalGlassConfig.current
    val spec = config.lens
    val lscale = controller.scale.value
    val pos = controller.position.value
    val sizePx = with(density) { spec.lensSize.toPx() }
    val sizeDp = spec.lensSize
    val widthScale = controller.widthScale.value
    val widthPx = sizePx * widthScale
    val widthDp = with(density) { widthPx.toDp() }
    val heightPx = with(density) { spec.lensRefractionHeight.toPx() }
    val amountPx = with(density) { spec.lensRefractionAmount.toPx() }
    val halfW = widthPx / 2f
    val halfH = sizePx / 2f
    val lensShape = RoundedCornerShape(sizeDp / 2)

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // Outer: original lens size, only lscale animation
        Box(
            modifier = Modifier
                .offset { IntOffset((pos.x - halfW).roundToInt(), (pos.y - halfH).roundToInt()) }
                .size(width = widthDp, height = sizeDp)
                .graphicsLayer {
                    scaleX = lscale
                    scaleY = lscale
                    transformOrigin = TransformOrigin.Center
                    alpha = if (visible) controller.alpha.value else 0.003f
                }
                .clip(lensShape),
        ) {
            // See LiquidGlassLensOverlay comment — magnifierScale removed 2026-07-04.
            Box(
                modifier = Modifier.matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { lensShape },
                        effects = {
                            lens(
                                refractionHeight = heightPx,
                                refractionAmount = amountPx,
                                chromaticAberration = spec.lensChromaticAberration > 0.5f,
                            )
                        },
                        onDrawSurface = { drawRect(Color.White.copy(alpha = 0.05f)) },
                    ),
            )
        }
    }
}
