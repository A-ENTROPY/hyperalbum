package com.smartvision.gallery.ui.apple

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.ui.gestures.detectLongPressThenDrag
import android.util.Log
import com.smartvision.gallery.ui.liquidglass.LiquidGlassBar
import com.smartvision.gallery.ui.liquidglass.LiquidGlassControlPill
import com.smartvision.gallery.ui.liquidglass.LiquidGlassTopBar
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSpec
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSurface
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.kyant.capsule.ContinuousCapsule
import com.smartvision.gallery.ui.liquidglass.drawGlassTint
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassBackdrop
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassLens
import com.smartvision.gallery.ui.liquidglass.LocalSegmentedControlLens
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassScreenBackdrop
import com.smartvision.gallery.ui.liquidglass.LocalLensOverlayIconState
import com.smartvision.gallery.ui.liquidglass.LensOverlayIconInfo
import com.smartvision.gallery.ui.liquidglass.LensTabInfo
import com.smartvision.gallery.ui.liquidglass.TabRect
import androidx.compose.runtime.LaunchedEffect

/**
 * Apple Human Interface Guidelines aligned primitives. Each component is a thin
 * wrapper that maps Apple's "design language" to Compose, then layers the Liquid
 * Glass material on top for surfaces that benefit from it (tab bars, cards,
 * toggles' track, segmented controls).
 *
 *  * [iOSTabBar]       — bottom tab bar with selected pill (iOS 26 style) and
 *                        liquid-lensing distortion under the floating magnifier.
 *  * [iOSTopBar]       — large-title navigation bar with optional back chevron.
 *  * [iOSToggle]       — iOS-style switch with glass track.
 *  * [iOSListSection]  — grouped list section with header / footer.
 *  * [iOSListRow]      — single list row with chevron / value / icon / switch.
 *  * [iOSSegmentedControl] — segmented control with liquid glass selected segment.
 *  * [iOSButton]       — primary / secondary / destructive buttons.
 *  * [iOSListGrouped]  — container for grouped sections (provides the background).
 */

/**
 * iOS Photos-style bottom tab bar.
 *
 * Layout: a single Liquid Glass pill that floats 12dp from the bottom edge. Each tab
 * is a single icon + optional label. The selected tab gets a vibrant glass background
 * that morphs in/out via animateColorAsState.
 *
 * iOS 26 "Liquid Lensing" integration (this iteration):
 *  * A single `pointerInput` on the bar owns the gesture (long-press then
 *    drag). The press-origin tab is tracked via `pressStartRoute`; the
 *    hovered tab is found by matching `lens.position.value.x` against each
 *    tab's window-space center. On release the hovered tab is selected.
 *  * The lens itself is rendered at AppRoot Z=2 via `LiquidGlassLensOverlay`
 *    using `LocalLiquidGlassScreenBackdrop` (the page content layer, NOT
 *    just the bar) — the magnifier shows page content with the lens shader
 *    effect, identical to iOS 26's tab-bar magnifier behavior.
 *  * The bar body is just `LiquidGlassBar` + icons Row; no separate overlay
 *    composable inside the bar tree.
 */
@Composable
fun iOSTabBar(
    items: List<iOSTabItem>,
    selectedRoute: String?,
    onSelect: (iOSTabItem) -> Unit,
    modifier: Modifier = Modifier,
    backdropOnly: Boolean = false,
) {
    val lens = LocalLiquidGlassLens.current
    val scope = rememberCoroutineScope()

    // Bar's window-space top-left — needed to convert gesture press positions
    // (which are local to the bar) into absolute screen coordinates.
    var barOrigin by remember { mutableStateOf(Offset.Zero) }

    // Per-tab window-space rect (origin, size). Populated by each
    // [iOSTabBarItem] via its `onLayout` callback.
    val tabLayouts = remember { mutableStateMapOf<String, TabRect>() }

    // Per-tab icon window-space rect. Populated by each [iOSTabBarItem]
    // via onGloballyPositioned on the Icon composable.
    val iconRects = remember { mutableStateMapOf<String, ComposeRect>() }

    // The tab that was under the finger when long-press fired. Used to
    // decide whether the user has slid to a new tab on release.
    var pressStartRoute by remember { mutableStateOf<String?>(null) }
    // Window-space position of the down event that triggered the lens. The
    // press-origin item's offset is computed as `lens.position.value -
    // pressStartPosition`.
    var pressStartPosition by remember { mutableStateOf<Offset?>(null) }
    // True when onSelect has been called for a different tab during the
    // current lens gesture. Once committed, the newly selected tab shows
    // cyan immediately (even though lensActive is still true during the
    // hide animation). Reset on next long-press or cancel.
    var lensSelectionCommitted by remember { mutableStateOf(false) }

    // Drop-target tab: the tab whose centre x is closest to the lens centre x.
    // Used by onDragEnd to select the new tab on lens release.
    val hoveredRoute by remember(lens, items) {
        derivedStateOf {
            if (!lens.isVisible.value || tabLayouts.isEmpty()) return@derivedStateOf null
            val lensX = lens.position.value.x
            tabLayouts.entries
                .minByOrNull { entry ->
                    val rect = entry.value
                    val cx = rect.origin.x + rect.size.width / 2f
                    kotlin.math.abs(cx - lensX)
                }?.key
        }
    }

    // Find the tab whose horizontal extent contains a given window position.
    val tabAt: (Offset) -> iOSTabItem? = { windowPos: Offset ->
        items.firstOrNull { item ->
            val r: TabRect = tabLayouts[item.route] ?: return@firstOrNull false
            windowPos.x in r.origin.x..(r.origin.x + r.size.width)
        }
    }

    // rememberUpdatedState so the pointerInput lambda below reads the
    // CURRENT selectedRoute even though pointerInput only keys on [items].
    val currentSelectedRoute by rememberUpdatedState(selectedRoute)

    // Push tab icon positions to the lens overlay so it can render native
    // cyan icons clipped to the lens shape (instead of the broken
    // iconsBackdrop + ColorMatrix approach).
    val lensIconState = LocalLensOverlayIconState.current
    if (!backdropOnly) {
        LaunchedEffect(tabLayouts, iconRects, selectedRoute, lens.isVisible.value, items) {
            if (lens.isVisible.value && tabLayouts.isNotEmpty()) {
            val tabs = items.mapNotNull { item ->
                val rect = tabLayouts[item.route] ?: return@mapNotNull null
                val iRect = iconRects[item.route]
                LensTabInfo(
                    route = item.route,
                    label = item.label,
                    icon = item.icon,
                    windowRect = ComposeRect(
                        offset = rect.origin,
                        size = androidx.compose.ui.geometry.Size(
                            rect.size.width.toFloat(),
                            rect.size.height.toFloat()
                        )
                    ),
                    iconWindowRect = iRect ?: ComposeRect(
                        offset = rect.origin,
                        size = androidx.compose.ui.geometry.Size(
                            rect.size.width.toFloat(),
                            rect.size.height.toFloat()
                        )
                    ),
                )
            }
            lensIconState.value = LensOverlayIconInfo(
                tabs = tabs,
                selectedRoute = selectedRoute,
            )
        } else {
            lensIconState.value = null
        }
    }
    }  // end if (!backdropOnly)

    // Outer Box hosts both the bar body AND the lens overlay as siblings.
    // The bar body's fillMaxWidth pins its own width regardless of the
    // lens overlay's intrinsic size (requiredSize(width = lensWidthDp)
    // can exceed the bar's width during stretch, which is intentional in
    // iOS 26 for the "more liquid feel" — the lens overflows visually but
    // never widens the bar).
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                barOrigin = pos
                lens.bounds = ComposeRect(
                    left = pos.x,
                    top = pos.y,
                    right = pos.x + coords.size.width,
                    bottom = pos.y + coords.size.height,
                )
                Log.d("TabBar", "w=${coords.size.width} lensVisible=${lens.isVisible.value}")
            }
            .let { m ->
                if (!backdropOnly) {
                    m.pointerInput(items) {
                        detectLongPressThenDrag(
                            onTap = { pressLocal ->
                                val pressWindow = barOrigin + pressLocal
                                val tapped = tabAt(pressWindow)
                                if (tapped != null) onSelect(tapped)
                            },
                            onLongPress = { pressLocal ->
                                lensSelectionCommitted = false
                                val pressWindow = barOrigin + pressLocal
                                pressStartRoute = tabAt(pressWindow)?.route
                                    ?: currentSelectedRoute
                                    ?: items.firstOrNull()?.route
                                val selectedRect = currentSelectedRoute?.let { tabLayouts[it] }
                                val lensStart = if (selectedRect != null) {
                                    Offset(
                                        selectedRect.origin.x + selectedRect.size.width / 2f,
                                        selectedRect.origin.y + selectedRect.size.height / 2f
                                    )
                                } else {
                                    pressWindow
                                }
                                pressStartPosition = lensStart
                                lens.show(lensStart, scope)
                                if (pressWindow != lensStart) {
                                    lens.animateToPosition(pressWindow, scope)
                                }
                            },
                            onDrag = { currentLocal, _ ->
                                val currentWindow = barOrigin + currentLocal
                                lens.moveTo(currentWindow, scope, System.nanoTime())
                            },
                            onDragEnd = {
                                val target = hoveredRoute
                                val original = currentSelectedRoute
                                if (target != null && target != original) {
                                    items.firstOrNull { it.route == target }?.let(onSelect)
                                    lensSelectionCommitted = true
                                }
                                val finalRoute = target ?: currentSelectedRoute
                                val morphTarget = finalRoute?.let { route ->
                                    tabLayouts[route]?.let { rect ->
                                        Offset(
                                            rect.origin.x + rect.size.width / 2f,
                                            rect.origin.y + rect.size.height / 2f
                                        )
                                    }
                                }
                                lens.hide(scope, morphTarget)
                                pressStartRoute = null
                                pressStartPosition = null
                            },
                            onDragCancel = {
                                // Cancel: stay in place (no morph target)
                                lensSelectionCommitted = false
                                lens.hide(scope, null)
                                pressStartRoute = null
                                pressStartPosition = null
                            },
                        )
                    }
                } else m
            }
    ) {
        // Sibling 1 — the bar body. The icons Row renders normally; the
        // lens is rendered separately at AppRoot Z=2 via
        // LiquidGlassLensOverlay, which uses the screen-level backdrop
        // for the magnifier effect.
        LiquidGlassBar(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 80.dp),
            spec = LocalGlassConfig.current.tabBar.toSpec()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                        val isSelected = item.route == selectedRoute
                        val lensVisible = lens.isVisible.value
                        val isHovered = lensVisible && hoveredRoute == item.route
                        val isPressOrigin = lensVisible && pressStartRoute == item.route
                        iOSTabBarItem(
                            item = item,
                            selected = isSelected,
                            isHovered = isHovered,
                            isPressOrigin = isPressOrigin,
                            lensActive = lensVisible,
                            forceCyan = backdropOnly,
                            onLayout = { origin, size ->
                                tabLayouts[item.route] = TabRect(origin, size)
                            },
                            onIconLayout = { iconRect ->
                                iconRects[item.route] = iconRect
                            },
                        )
                    }
                }
            }
        }
    }

@Composable
private fun RowScope.iOSTabBarItem(
    item: iOSTabItem,
    selected: Boolean,
    isHovered: Boolean,
    isPressOrigin: Boolean,
    lensActive: Boolean,
    forceCyan: Boolean = false,
    onLayout: (origin: Offset, size: IntSize) -> Unit,
    onIconLayout: (ComposeRect) -> Unit,
) {
    // iOS 26 Liquid Lensing: the item ALWAYS renders its normal content
    // (icon + label). The lens is a SEPARATE sibling composable in
    // [iOSTabBar] that draws JUST the magnifier shader over the bar's
    // own backdrop. The selected icon stays put — the lens magnifies
    // whatever bar content sits underneath it. This is the new contract:
    // the icon does NOT move with the lens.
    val density = LocalDensity.current
    val tabConfig = LocalGlassConfig.current.tabBar
    val selectedSpec = tabConfig.toSpec()
    val screenBackdrop = LocalLiquidGlassScreenBackdrop.current

    // Lens controller + config — read up front so they're available to the
    // icon colour + size blocks below.
    val lensCtl = LocalLiquidGlassLens.current
    val lensCfg = LocalGlassConfig.current.lens

    // Tint the lens-hovered icon with the user's dialed `iconTintAlpha` so
    // the cyan can be dimmed at the playground. Default 1.0 keeps the
    // previous behaviour (fully cyan, same as iOS 26).
    val cyanTint = highlightCyan.copy(alpha = lensCfg.iconTintAlpha)

    val iconColor = if (forceCyan) {
        cyanTint
    } else if (selected && lensActive && isHovered) {
        // Lens is hovering over the selected tab — keep it cyan.
        cyanTint
    } else if (lensActive) {
        // During active lens gesture, any tab not under the lens goes gray.
        // The lens itself provides the visual focus cue.
        MaterialTheme.colorScheme.onSurfaceVariant
    } else if (selected) {
        highlightCyan
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val labelColor = iconColor

    // Stable sizes based on selected state alone — never changes during lens
    // gesture, so the bar never perceptually "narrows" when the capsule hides.
    // The drop-target magnifier effect is applied via graphicsLayer below
    // (not via Modifier.size) so layout stays put and a smooth spring
    // animates the transition. Range 0.4..2.0:
    //   0.4 = shrinker (looks ~40% size)
    //   1.0 = neutral
    //   2.0 = magnifier (looks 2x size)
    val iconSize = if (selected) 28.dp else 24.dp
    val labelSize = if (selected) 11.sp else 10.sp
    val labelWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium

    Box(
        modifier = Modifier
            .weight(1f)
            .onGloballyPositioned { coords ->
                onLayout(coords.positionInWindow(), coords.size)
            },
        contentAlignment = Alignment.Center,
    ) {
        // 72.dp container keeps the capsule clipped to a circle, not the
        // full weight(1f) area. Capsule background uses matchParentSize
        // to match this container; Column renders on top.
        Box(
            modifier = Modifier.width(72.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Frosted-glass capsule background layer — separate Box so its
            // graphicsLayer alpha (cross-fade with the lens on hide) does NOT
            // affect the icon and text rendered above it.
            if (selected && (!lensActive || lensCtl.isVisible.value)) {
                val capsuleAlpha = if (lensActive) lensCtl.capsuleAlpha.value else 1f
                val warmAlpha = capsuleAlpha.coerceAtLeast(0.003f)
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clip(ContinuousCapsule)
                        .graphicsLayer { alpha = warmAlpha }
                        .drawBackdrop(
                        backdrop = screenBackdrop,
                        shape = { ContinuousCapsule },
                        effects = {
                            blur(with(density) { selectedSpec.blurRadius.toPx() })
                            lens(
                                with(density) { selectedSpec.lensAmount.toPx() },
                                with(density) { selectedSpec.lensAmount.toPx() },
                            )
                        },
                        onDrawSurface = {
                            val strokePx = with(density) { 1.5.dp.toPx() }
                            // 1. Glass tint — cyan gradient top → clear bottom
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        highlightCyan.copy(alpha = 0.06f),
                                        Color.White.copy(alpha = 0.03f),
                                    ),
                                    startY = 0f,
                                    endY = size.height,
                                )
                            )
                            // 2. Top highlight stripe — "lit from above" 3D glass
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.55f),
                                        Color.Transparent,
                                    ),
                                    startY = 0f,
                                    endY = size.height * 0.12f,
                                )
                            )
                            // 3. Cyan rim glow — top edge fading horizontally
                            if (size.width > 0f) {
                                drawLine(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            highlightCyan.copy(alpha = 0.50f),
                                            highlightCyan.copy(alpha = 0.15f),
                                            Color.Transparent,
                                        ),
                                    ),
                                    start = Offset.Zero,
                                    end = Offset(size.width, 0f),
                                    strokeWidth = strokePx,
                                )
                            }
                            // 4. Cyan rim glow — right edge fading downward
                            if (size.height > 0f) {
                                drawLine(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            highlightCyan.copy(alpha = 0.50f),
                                            highlightCyan.copy(alpha = 0.08f),
                                            Color.Transparent,
                                        ),
                                    ),
                                    start = Offset(size.width, 0f),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = strokePx,
                                )
                            }
                        },
                    )
            )
        }
        Column(
            modifier = Modifier
                .width(72.dp)
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = iconColor,
                modifier = Modifier
                    .size(iconSize)
                    .onGloballyPositioned { coords ->
                        val pos = coords.positionInWindow()
                        val size = coords.size
                        onIconLayout(ComposeRect(
                            left = pos.x,
                            top = pos.y,
                            right = pos.x + size.width,
                            bottom = pos.y + size.height,
                        ))
                    }
            )
            Text(
                text = item.label,
                color = labelColor,
                fontSize = labelSize,
                fontWeight = labelWeight,
                letterSpacing = 0.2.sp,
            )
        }
    }  // end 72dp inner wrapper
    }  // end weight(1f) outer Box
}

/** Configuration entry for [iOSTabBar]. */
data class iOSTabItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

/** iOS system blue — used for the selected / active highlight color across all Apple components. */
private val highlightCyan = Color(0xFF007AFF)

/**
 * iOS-style top bar with optional back chevron, title, and right action.
 *
 *  * When [largeTitle] is true, the bar renders in "large title" mode (large font
 *    on first scroll position, animates to compact on scroll-up — for V1.0 we just
 *    show the large variant).
 *  * When [largeTitle] is false, it's a compact navigation bar similar to iOS 17.
 *
 * Wrapped in a Liquid Glass material so it sits above content like the iOS chrome.
 *
 * @param collapsedRatio 0f = fully expanded, 1f = fully collapsed (driven by scroll state).
 */
@Composable
fun iOSTopBar(
    title: String,
    modifier: Modifier = Modifier,
    largeTitle: Boolean = true,
    collapsedRatio: Float = 0f,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {

    val titleFontSize = if (largeTitle) {
        34f - (17f * collapsedRatio)
    } else 17f

    val titleWeight = if (largeTitle && collapsedRatio < 0.5f) FontWeight.Bold
                      else FontWeight.SemiBold

    val barHeight = if (largeTitle) {
        96.dp * (1f - collapsedRatio * 0.54f)
    } else 44.dp

    LiquidGlassTopBar(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                LiquidGlassControlPill(
                    modifier = Modifier.size(36.dp),
                    onClick = onBack,
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
                text = title,
                fontSize = titleFontSize.sp,
                fontWeight = titleWeight,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (largeTitle) 8.dp else 0.dp),
            )
            if (actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
    }
}

/**
 * iOS 26 Liquid Glass segmented control — independent liquid lens on long-press.
 *
 * Glass track using [LocalLiquidGlassBackdrop] for the frosted background, with
 * its OWN [LocalSegmentedControlLens] (Z=2 overlay) that shows the page content
 * behind the control with the refraction shader — same mechanics as the bottom
 * tab bar but without the barBackdrop/icon compositing.
 *
 * Gesture lifecycle:
 *   Tap       → select the tapped segment immediately (no lens)
 *   Long-press → lens appears at the pressed segment, follows the finger
 *   Drag       → lens tracks finger, hovered segment highlights
 *   Release    → select the hovered segment, lens morphs back + shrinks
 */
@Composable
fun iOSSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    backdropOnly: Boolean = false,
    modifier: Modifier = Modifier,
    trackSpec: LiquidGlassSpec = LocalGlassConfig.current.topBar.toSpec(),
    scrollTranslateY: Dp = 0.dp,
    /**
     * 在 AppRoot 的 layerBackdrop capture 子树内部使用时（如 SmbMediaGrid），
     * 必须传入 [LocalLiquidGlassBackdrop]（程序化 canvas 渐变）——
     * screen backdrop 包含本控件自身，采样会造成 circular backdrop
     * dependency → RenderNode 递归 → 栈溢出（见下方 backdropOnly 注释）。
     */
    backdropOverride: com.kyant.backdrop.Backdrop? = null,
) {
    val density = LocalDensity.current
    val tabSpec = trackSpec
    android.util.Log.i("SegCtrl", "trackSpec: blur=${tabSpec.blurRadius.value}dp, tintAlpha=${tabSpec.tintAlpha}, vibrancy=${tabSpec.vibrancy}, tint=${tabSpec.tint}")
    // Read the SCREEN-CAPTURE backdrop (real photo pixels) rather than the
    // in-content canvas gradient — the segmented control floats at Z=1 as a
    // sibling of the captured subtree (see LibraryOverlay), so the visible
    // track must refract what's actually on screen. The canvas-gradient
    // backdrop would just blur a procedural image, giving the "opaque blob"
    // look the user reported. The hidden backdropOnly copy at Z=0 uses
    // drawBackdrop(pageBackdrop, vibrancy+blur) to capture the actual
    // frosted glass track — matching the tab bar's backdropOnly pattern
    // (see LiquidGlassBar line 240) — so SegmentedControlLensOverlay's
    // lens() refraction produces proper frosted glass + optical effects.
    val backdrop = backdropOverride ?: LocalLiquidGlassScreenBackdrop.current
    val lens = LocalSegmentedControlLens.current
    val scope = rememberCoroutineScope()

    var controlOrigin by remember { mutableStateOf(Offset.Zero) }
    val segmentRects = remember { mutableStateMapOf<Int, ComposeRect>() }
    val currentSelected by rememberUpdatedState(selected)
    var lensSelectionCommitted by remember { mutableStateOf(false) }

    // Which segment is under the lens center (for highlighting during drag).
    val hoveredIndex by remember(lens, segmentRects) {
        derivedStateOf {
            if (!lens.isVisible.value || segmentRects.isEmpty()) return@derivedStateOf -1
            val lx = lens.position.value.x
            val ly = lens.position.value.y
            segmentRects.entries
                .minByOrNull { (_, r) ->
                    val cx = r.left + r.width / 2f
                    val cy = r.top + r.height / 2f
                    Offset(lx - cx, ly - cy).getDistance()
                }?.key ?: -1
        }
    }

    // Find the segment that contains a given window-space point.
    val segmentAt: (Offset) -> Int = { windowPos ->
        segmentRects.entries.firstOrNull { (_, r) ->
            windowPos.x in r.left..r.right
        }?.key ?: -1
    }

    // Backdrop-only path: renders the segmented control as static cyan text
    // for segBackdrop capture. NO drawBackdrop, NO backdrop sampling —
    // sampling liquidBackdrop inside a segBackdrop capture creates a
    // circular backdrop dependency (liquidBackdrop captures Z=0 which
    // contains the rendering pipeline, segBackdrop samples liquidBackdrop
    // at the same window position → infinite render tree recursion →
    // stack overflow in libhwui.so's RenderNode::prepareTreeImpl). Static
    // text + subtle pill background is captured instead; the lens shader
    // applies the lens distortion to those static pixels.
    if (backdropOnly) {
        return Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(ContinuousCapsule())
                // Matches the tab bar's backdropOnly pattern: render the
                // actual frosted glass track via drawBackdrop(pageBackdrop,
                // vibrancy+blur) inside the layerBackdrop capture. The
                // captured pixels include the blurred page content + glass
                // tint, so SegmentedControlLensOverlay's lens() refraction
                // at Z=2 produces proper frosted glass + optical effects
                // instead of a flat white pill. See LiquidGlassBar (line 240)
                // for the equivalent tab bar rendering.
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule() },
                    effects = {
                        if (trackSpec.vibrancy) vibrancy()
                        blur(with(density) { trackSpec.blurRadius.toPx() })
                        if (trackSpec.lensAmount > 0.dp) {
                            val lensPx = with(density) { trackSpec.lensAmount.toPx() }
                            lens(lensPx, lensPx)
                        }
                    },
                    onDrawSurface = {
                        drawGlassTint(trackSpec, drawEdgeStrokes = false)
                    },
                )
                .offset(y = scrollTranslateY)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, label ->
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = highlightCyan,
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 10.dp),
                    )
                }
            }
        }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                controlOrigin = pos
                lens.bounds = ComposeRect(
                    left = pos.x,
                    top = pos.y,
                    right = pos.x + coords.size.width,
                    bottom = pos.y + coords.size.height,
                )
            }
            .pointerInput(options) {
                detectLongPressThenDrag(
                    onTap = { localPos ->
                        val windowPos = controlOrigin + localPos
                        var idx = segmentAt(windowPos)
                        // Fallback: if the containment check fails (e.g. floating-point
                        // coordinate mismatch between the visible and backdrop-only
                        // segmented controls in the two-pass rendering), find the
                        // segment whose center is closest to the tap.
                        if (idx < 0 && segmentRects.isNotEmpty()) {
                            val nearest = segmentRects.entries.minByOrNull { (_, r) ->
                                val cx = r.left + r.width / 2f
                                kotlin.math.abs(windowPos.x - cx)
                            }?.key
                            if (nearest != null) idx = nearest
                        }
                        if (idx >= 0) onSelect(idx)
                    },
                    onLongPress = { localPos ->
                        lensSelectionCommitted = false
                        val pressWindow = controlOrigin + localPos
                        val idx = segmentAt(pressWindow)
                        if (idx < 0) {
                            return@detectLongPressThenDrag
                        }
                        // Match tab bar: lens starts at CURRENTLY SELECTED segment
                        // center, then flows to the pressed position.
                        val selectedRect = segmentRects[currentSelected]
                        val lensStart = if (selectedRect != null) {
                            Offset(
                                selectedRect.left + selectedRect.width / 2f,
                                selectedRect.top + selectedRect.height / 2f,
                            )
                        } else pressWindow
                        lens.show(lensStart, scope)
                        if (pressWindow != lensStart) {
                            lens.animateToPosition(pressWindow, scope)
                        }
                    },
                    onDrag = { currentLocal, _ ->
                        val currentWindow = controlOrigin + currentLocal
                        lens.moveTo(currentWindow, scope, System.nanoTime())
                        },
                    onDragEnd = {
                        val target = hoveredIndex
                        val original = currentSelected
                        if (target >= 0 && target != original) {
                            onSelect(target)
                            lensSelectionCommitted = true
                        }
                        val finalIdx = if (target >= 0) target else currentSelected
                        val morphTarget = segmentRects[finalIdx]?.let { r ->
                            Offset(r.left + r.width / 2f, r.top + r.height / 2f)
                        }
                        lens.hide(scope, morphTarget)
                    },
                    onDragCancel = {
                        lensSelectionCommitted = false
                        lens.hide(scope, null)
                    },
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ContinuousCapsule())
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule() },
                    effects = {
                        if (trackSpec.vibrancy) vibrancy()
                        blur(with(density) { trackSpec.blurRadius.toPx() })
                        if (trackSpec.lensAmount > 0.dp) {
                            val lensPx = with(density) { trackSpec.lensAmount.toPx() }
                            lens(lensPx, lensPx)
                        }
                    },
                    onDrawSurface = {
                        drawGlassTint(trackSpec, drawEdgeStrokes = false)
                    },
                )
                .offset(y = scrollTranslateY)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selected
                val isHovered = index == hoveredIndex && hoveredIndex >= 0
                val lensActive = lens.isVisible.value

                val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                // EXACT match of bottom bar's iOSTabBarItem color logic:
                //   forceCyan/hovered → highlightCyan (iOS blue #007AFF)
                //   lensActive but not hovered → onSurfaceVariant (gray/dim)
                //   selected → highlightCyan (not theme primary!)
                //   default → onSurfaceVariant
                val textColor = when {
                    isHovered && lensActive -> highlightCyan
                    lensActive -> onSurfaceVariant
                    isSelected -> highlightCyan
                    else -> onSurfaceVariant
                }
                // Selected segment: real frosted glass capsule via drawBackdrop.
                // Row uses offset(y) instead of graphicsLayer, so Backdrop.position
                // returns window-space coordinates — child drawBackdrop works.
                // Capsule layout matches bottom tab bar's iOSTabBarItem pattern:
                // NO padding on this Box — matchParentSize would then match the
                // FULL segment (iOS 26: selected capsule touches segment edges).
                // Padding moves to the Text so the label has breathing room
                // inside the capsule without shrinking the capsule itself.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInWindow()
                            segmentRects[index] = ComposeRect(
                                left = pos.x,
                                top = pos.y,
                                right = pos.x + coords.size.width,
                                bottom = pos.y + coords.size.height,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Frosted glass capsule behind text — same architecture as
                    // iOSTabBarItem's selected capsule. Cross-fades via
                    // lens.capsuleAlpha during lens hide animation.
                    if (isSelected && (!lensActive || lens.isVisible.value)) {
                        val capAlpha = if (lensActive) lens.capsuleAlpha.value else 1f
                        val strokePx = with(density) { 1.5.dp.toPx() }
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clip(ContinuousCapsule())
                                .graphicsLayer { alpha = capAlpha.coerceAtLeast(0.003f) }
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { ContinuousCapsule() },
                                    effects = {
                                        blur(with(density) { tabSpec.blurRadius.toPx() })
                                        if (tabSpec.lensAmount > 0.dp) {
                                            val lensPx = with(density) { tabSpec.lensAmount.toPx() }
                                            lens(lensPx, lensPx)
                                        }
                                    },
                                    onDrawSurface = {
                                        drawGlassTint(tabSpec, drawEdgeStrokes = false)
                                        // 2. Top highlight stripe — "lit from above" 3D glass
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.White.copy(alpha = 0.55f),
                                                    Color.Transparent,
                                                ),
                                                startY = 0f,
                                                endY = size.height * 0.12f,
                                            )
                                        )
                                        // 3. Cyan rim glow — top edge fading horizontally
                                        if (size.width > 0f) {
                                            drawLine(
                                                brush = Brush.horizontalGradient(
                                                    colors = listOf(
                                                        highlightCyan.copy(alpha = 0.50f),
                                                        highlightCyan.copy(alpha = 0.15f),
                                                        Color.Transparent,
                                                    ),
                                                ),
                                                start = Offset.Zero,
                                                end = Offset(size.width, 0f),
                                                strokeWidth = strokePx,
                                            )
                                        }
                                        // 4. Cyan rim glow — right edge fading downward
                                        if (size.height > 0f) {
                                            drawLine(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(
                                                        highlightCyan.copy(alpha = 0.50f),
                                                        highlightCyan.copy(alpha = 0.08f),
                                                        Color.Transparent,
                                                    ),
                                                ),
                                                start = Offset(size.width, 0f),
                                                end = Offset(size.width, size.height),
                                                strokeWidth = strokePx,
                                            )
                                        }
                                    },
                                )
                        )
                    }
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = textColor,
                        // Padding moved from the parent Box so the capsule
                        // (matchParentSize) fills the FULL segment instead of
                        // the post-padding content area — fixes the "visibly
                        // smaller by a few pixels" bug.
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * iOS 26 Liquid Glass toggle switch. 51x31dp track, 27dp knob, #0088FF ON color.
 */
@Composable
fun iOSToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val toggleCfg = LocalGlassConfig.current.toggle
    val onColor = Color(
        red = ((toggleCfg.onColorArgb shr 16) and 0xFF).toInt(),
        green = ((toggleCfg.onColorArgb shr 8) and 0xFF).toInt(),
        blue = (toggleCfg.onColorArgb and 0xFF).toInt(),
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFFE5E5EA)
            checked -> onColor
            else -> Color(0xFF787880).copy(alpha = toggleCfg.offTrackAlpha)
        },
        label = "toggleTrack",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "toggleThumb",
    )
    val knobSize = toggleCfg.knobDiameter

    Box(
        modifier = modifier
            .size(width = toggleCfg.width, height = toggleCfg.height)
            .clip(RoundedCornerShape(toggleCfg.trackCornerRadius))
            .background(trackColor)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset)
                .size(knobSize)
                .clip(CircleShape)
                .background(Color.White)
                .align(Alignment.CenterStart),
        )
    }
}

/**
 * iOS-style list section with header / footer + grouped content.
 */
@Composable
fun iOSListSection(
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        if (header != null) {
            Text(
                text = header.uppercase(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp)
            )
        }
        com.smartvision.gallery.ui.liquidglass.LiquidGlassCard(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            onClick = null,
            contentPadding = PaddingValues(0.dp)
        ) {
            Column {
                content()
            }
        }
        if (footer != null) {
            Text(
                text = footer,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * iOS-style list row — icon (optional) + title + value/chevron/switch/trailing.
 */
@Composable
fun iOSListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: ImageVector? = null,
    leadingTint: Color? = null,
    trailing: iOSRowTrailing = iOSRowTrailing.None,
    onClick: (() -> Unit)? = null
) {
    val clickable = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(leadingTint ?: MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = leading,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.1).sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    fontSize = 13.sp,
                    letterSpacing = (-0.05).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        when (trailing) {
            is iOSRowTrailing.Value -> Text(
                trailing.text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            iOSRowTrailing.Chevron -> Icon(
                imageVector = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            is iOSRowTrailing.Switch -> iOSToggle(
                checked = trailing.checked,
                onCheckedChange = trailing.onChange
            )
            iOSRowTrailing.None -> Unit
        }
    }
}

sealed class iOSRowTrailing {
    data class Value(val text: String) : iOSRowTrailing()
    data object Chevron : iOSRowTrailing()
    data class Switch(val checked: Boolean, val onChange: (Boolean) -> Unit) : iOSRowTrailing()
    data object None : iOSRowTrailing()
}

/**
 * iOS-style button row. Variants:
 *  * [iOSButtonStyle.Primary]   — filled blue
 *  * [iOSButtonStyle.Secondary] — glass-tinted
 *  * [iOSButtonStyle.Destructive] — red
 *  * [iOSButtonStyle.Plain]     — text only
 */
enum class iOSButtonStyle { Primary, Secondary, Destructive, Plain, Glass, GlassSymbol }

@Composable
fun iOSButton(
    text: String,
    modifier: Modifier = Modifier,
    style: iOSButtonStyle = iOSButtonStyle.Primary,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val controlSpec = LocalGlassConfig.current.control.toSpec()
    val backdrop = LocalLiquidGlassBackdrop.current
    val controlCfg = LocalGlassConfig.current.control

    val (bg, fg) = when (style) {
        iOSButtonStyle.Primary -> MaterialTheme.colorScheme.primary to Color.White
        iOSButtonStyle.Secondary -> Color.White.copy(alpha = 0.18f) to MaterialTheme.colorScheme.onSurface
        iOSButtonStyle.Destructive -> Color(0xFFFF3B30) to Color.White
        iOSButtonStyle.Plain -> Color.Transparent to MaterialTheme.colorScheme.primary
        iOSButtonStyle.Glass -> Color.Transparent to MaterialTheme.colorScheme.onSurface
        iOSButtonStyle.GlassSymbol -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // iOS 26 water-droplet press: elastic spring, scale UP to 1.08x, dynamic lens
    val pressProgress = remember { Animatable(0f) }
    val baseLensPx = with(density) { controlSpec.lensAmount.toPx() }
    val extraLensPx = with(density) { controlCfg.lensPressExtra.toPx() }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            pressProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.45f, stiffness = 300f),
            )
        } else {
            pressProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.65f, stiffness = 400f),
            )
        }
    }

    val baseClip = when (style) {
        iOSButtonStyle.GlassSymbol -> CircleShape
        iOSButtonStyle.Glass -> RoundedCornerShape(999.dp)
        else -> RoundedCornerShape(14.dp)
    }
    val baseMod = modifier.clip(baseClip)

    val finalMod = when (style) {
        iOSButtonStyle.Glass -> baseMod.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(999.dp) },
            effects = {
                if (controlSpec.vibrancy) vibrancy()
                blur(with(density) { controlSpec.blurRadius.toPx() })
                lens(
                    refractionHeight = baseLensPx + extraLensPx * pressProgress.value,
                    refractionAmount = baseLensPx + extraLensPx * pressProgress.value,
                )
            },
            onDrawSurface = { drawGlassTint(controlSpec) },
        )
        iOSButtonStyle.GlassSymbol -> baseMod.size(44.dp).drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                if (controlSpec.vibrancy) vibrancy()
                blur(with(density) { controlSpec.blurRadius.toPx() })
                lens(
                    refractionHeight = baseLensPx + extraLensPx * pressProgress.value,
                    refractionAmount = baseLensPx + extraLensPx * pressProgress.value,
                )
            },
            onDrawSurface = { drawGlassTint(controlSpec) },
        )
        iOSButtonStyle.Primary, iOSButtonStyle.Secondary,
        iOSButtonStyle.Destructive, iOSButtonStyle.Plain -> baseMod.background(bg)
    }

    Box(
        modifier = finalMod
            .graphicsLayer {
                if (style == iOSButtonStyle.Glass || style == iOSButtonStyle.GlassSymbol) {
                    val s = 1f + 0.08f * pressProgress.value
                    scaleX = s
                    scaleY = s
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}

/** iOS 26 Liquid Glass list-section container. */
@Composable
fun iOSListGroupedContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
    ) {
        content()
    }
}

/**
 * iOS 26 large-title header. Renders a single big title with an optional subtitle below.
 * Uses the standard 34pt / 13pt / 22pt rhythm from iOS HIG.
 */
@Composable
fun iOSLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 0.dp, bottom = 4.dp)
    ) {
        Text(
            text = title,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            color = MaterialTheme.colorScheme.onBackground
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = 13.sp,
                letterSpacing = (-0.05).sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Smaller section header used between subsections. */
@Composable
fun iOSSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}
