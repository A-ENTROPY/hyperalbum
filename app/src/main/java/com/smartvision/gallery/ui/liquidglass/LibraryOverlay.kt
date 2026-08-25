package com.smartvision.gallery.ui.liquidglass

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.capsule.ContinuousCapsule
import com.smartvision.gallery.ui.apple.iOSSegmentedControl
import com.smartvision.gallery.ui.theme.Spacing
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.smartvision.gallery.data.glass.toSpec

/**
 * Library page chrome rendered at Z=1 — the segmented control
 * (全部 / 日月年 / 图集) and the action row (选择 / 排序 Tune chips) float
 * as siblings of the captured subtree so they read
 * [LocalLiquidGlassScreenBackdrop] (real captured photo content) instead
 * of the in-content canvas gradient. Same architectural pattern as
 * [FloatingTopBarPill] and `iOSTabBar`: in-content surfaces use canvas
 * gradient (opaque look); sibling chrome uses screen capture (real frosted
 * glass over photos).
 *
 * Vertical order (top → bottom):
 *  1. Floating top bar pill (rendered separately by AppRoot) — top 0dp
 *  2. Segmented control — top 64dp
 *  3. Action row chips — top 112dp
 *  4. Photo grid (rendered in TimelinePage at Z=0) — starts at the chrome's bottom
 */
@Composable
fun LibraryOverlay(
    state: LibraryOverlayState,
) {
    val segBackdropState = LocalSegmentedControlBackdropState.current
    val hiddenSegBackdrop = rememberLayerBackdrop()
    // DisposableEffect：写入 + dispose 清理（同 SmbMediaGrid）。若只写不清，
    // 页面移除后 segBackdropState 仍指向已释放的 GraphicsLayer →
    // SegmentedControlLensOverlay drawBackdrop(已释放层) → prepareTreeImpl 递归
    // 栈溢出崩溃。=== 比较避免清掉其他写入者（SmbMediaGrid）的值。
    DisposableEffect(hiddenSegBackdrop) {
        segBackdropState.value = hiddenSegBackdrop
        onDispose {
            if (segBackdropState.value === hiddenSegBackdrop) segBackdropState.value = null
        }
    }

    // MUST read config here (not in default parameter) so Compose tracks the
    // CompositionLocal read and recomposes when playground sliders change.
    // Kotlin default-parameter expressions are NOT tracked by Compose for
    // recomposition — reading LocalGlassConfig.current inside a default
    // parameter like `trackSpec: LiquidGlassSpec = LocalGlassConfig.current...`
    // means the parent composable (LibraryOverlay) never sees the change and
    // never re-invokes iOSSegmentedControl with the new spec.
    val topBarSpec = LocalGlassConfig.current.topBar.toSpec()

    Box(modifier = Modifier.fillMaxSize()) {
        // ---- Z=0: Hidden backdrop-only capture (feeds SegmentedControlLensOverlay) ----
        // Same pattern as the tab bar's hidden backdropOnly twin: renders static
        // cyan text with no gesture handlers, captured by layerBackdrop for the
        // segmented control lens overlay to composite through its refraction shader.
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = Spacing.SegmentedControlTop)
                .layerBackdrop(hiddenSegBackdrop)
        ) {
            iOSSegmentedControl(
                options = listOf("全部", "日月年", "图集"),
                selected = state.segment,
                onSelect = {},
                backdropOnly = true,
                trackSpec = topBarSpec,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- Z=1: Visible interactive segmented control ----
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = Spacing.SegmentedControlTop)
        ) {
            iOSSegmentedControl(
                options = listOf("全部", "日月年", "图集"),
                selected = state.segment,
                onSelect = { state.onSegmentChange(it) },
                trackSpec = topBarSpec,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---- Action row: 选择 (left) + Tune 排序 (right) ----
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 18.dp, end = 12.dp, top = Spacing.ActionRowTop),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChromeChip(onClick = {
                state.onToggleSelectMode()
            }) {
                val active = state.selectModeEnabled
                Text(
                    if (active) "取消" else "选择",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.1).sp,
                    color = if (active) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface
                )
            }
            if (state.selectModeEnabled && state.selectedCount > 0) {
                Text(
                    "${state.selectedCount} 项已选",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            ChromeChip(onClick = {
                state.onToggleSort()
            }) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "排序与筛选",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        // Selection toolbar — floating glass bar at the bottom when items selected.
        // Slot-based: the caller passes the action buttons, so different pages
        // (TrashPage, VaultPage) can inject their own actions without touching
        // this chrome layer.
        if (state.selectModeEnabled && state.selectedCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = Spacing.TabBarClearance)
            ) {
                SelectionToolbarGlass {
                    SelectionAction(
                        icon = Icons.Outlined.Delete,
                        label = "删除",
                        activeColor = Color(0xFFFF3B30),
                        onClick = state.onDeleteSelected,
                    )
                    SelectionAction(
                        icon = Icons.Outlined.Star,
                        label = "收藏",
                        activeColor = Color(0xFFFFCC00),
                        onClick = state.onFavoriteSelected,
                    )
                    SelectionAction(
                        icon = Icons.Outlined.Share,
                        label = "分享",
                        activeColor = Color(0xFF007AFF),
                        onClick = state.onShareSelected,
                    )
                    SelectionAction(
                        icon = Icons.Outlined.Lock,
                        label = "隐藏",
                        activeColor = Color(0xFF8E8E93),
                        onClick = state.onHideSelected,
                    )
                }
            }
        }
    }
}

/**
 * Chrome-level liquid glass chip — sibling of the captured subtree, samples
 * [LocalLiquidGlassScreenBackdrop] (real photo content) via the same
 * `drawBackdrop` pipeline as the bottom tab bar and top bar pill. Unlike
 * [LiquidGlassChip] which uses the in-content canvas gradient, this is
 * the version that "floats above the photos" with full blur+lens+vibrancy.
 *
 * Pill shape (`ContinuousCapsule`), `iOS26Control` spec (7dp blur, soft
 * tint, subtle highlight). Used by [LibraryOverlay] for the 选择 / Tune
 * chips in the action row below the segmented control.
 */
@Composable
private fun ChromeChip(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val bd = LocalLiquidGlassScreenBackdrop.current
    val density = LocalDensity.current
    val spec = LocalGlassConfig.current.control.toSpec()
    Box(
        modifier = Modifier
            .clip(ContinuousCapsule())
            .clickable(onClick = onClick)
            // NO parent graphicsLayer — it would create a GPU layer that
            // breaks drawBackdrop's Backdrop.position sampling (children
            // would sample at layer-space coordinates instead of window).
            // Matches LiquidGlassBar / iOSTabBarItem parent chain pattern.
            .drawBackdrop(
                backdrop = bd,
                shape = { ContinuousCapsule() },
                effects = {
                    if (spec.vibrancy) vibrancy()
                    blur(with(density) { spec.blurRadius.toPx() })
                    lens(
                        with(density) { spec.lensAmount.toPx() },
                        with(density) { spec.lensAmount.toPx() },
                    )
                },
                onDrawSurface = { drawGlassTint(spec) },
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun SelectionToolbarGlass(
    actions: @Composable RowScope.() -> Unit,
) {
    LiquidGlassBar() {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

@Composable
private fun SelectionAction(
    icon: ImageVector,
    label: String,
    activeColor: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()
    val contentColor = if (isPressed.value) activeColor else Color.Black

    Column(
        modifier = Modifier
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
        )
    }
}
