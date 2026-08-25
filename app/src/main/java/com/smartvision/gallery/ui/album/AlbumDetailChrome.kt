package com.smartvision.gallery.ui.album

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousCapsule
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSpec
import com.smartvision.gallery.ui.liquidglass.drawGlassTint

/**
 * State written by [AlbumDetailPage], read by [AlbumDetailChrome] in AppRoot Z=1.
 *
 * Only controls the floating filter chip bar — album header is now inside
 * [AlbumDetailPage] (Z=0) so no layout measurement is needed.
 */
data class AlbumDetailChromeState(
    val isVisible: Boolean = false,
    val selectedFilter: String = "全部",
    val onFilterChange: (String) -> Unit = {},
    val headerHeightPx: Float = 0f,
)

val LocalAlbumDetailChromeState = compositionLocalOf {
    mutableStateOf(AlbumDetailChromeState())
}

/**
 * Z=1 chrome: only the floating filter chip bar.
 *
 * Positioned at a fixed top offset ([topOffset]) corresponding to the
 * album header height inside [AlbumDetailPage] (Z=0). No dynamic
 * measurement — the offset is a close estimate that aligns the chip bar
 * just below the header text, regardless of device configuration.
 */
@Composable
fun AlbumDetailChrome(
    state: AlbumDetailChromeState,
    chipSpec: LiquidGlassSpec,
    backdrop: Backdrop,
    springDampingRatio: Float,
    springStiffness: Float,
    selectedScale: Float,
) {
    if (!state.isVisible) return

    // 芯片栏固定在 status bar 下方
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        FloatingFilterBar(
            selectedFilter = state.selectedFilter,
            onFilterChange = state.onFilterChange,
            backdrop = backdrop,
            spec = chipSpec,
            springDampingRatio = springDampingRatio,
            springStiffness = springStiffness,
            selectedScale = selectedScale,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 浮动筛选芯片栏 — 与 LibraryOverlay.ChromeChip 同风格.
 */
@Composable
private fun FloatingFilterBar(
    selectedFilter: String,
    onFilterChange: (String) -> Unit,
    backdrop: Backdrop,
    spec: LiquidGlassSpec,
    springDampingRatio: Float,
    springStiffness: Float,
    selectedScale: Float,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current

    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(listOf("全部", "视频", "收藏", "实况")) { label ->
            val isSelected = label == selectedFilter
            val scale = remember { Animatable(1f) }
            LaunchedEffect(isSelected) {
                scale.animateTo(
                    targetValue = if (isSelected) selectedScale else 1f,
                    animationSpec = spring(
                        dampingRatio = springDampingRatio,
                        stiffness = springStiffness,
                    )
                )
            }

            val tintAlpha = if (isSelected) spec.tintAlpha.coerceAtLeast(0.40f)
                            else (spec.tintAlpha * 0.70f).coerceIn(0.15f, 0.50f)
            val interactionSource = remember { MutableInteractionSource() }

            Box(
                modifier = Modifier
                    .graphicsLayer(
                        scaleX = scale.value,
                        scaleY = scale.value,
                        shape = ContinuousCapsule(),
                        clip = true,
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onFilterChange(label) }
                    )
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { ContinuousCapsule() },
                        effects = {
                            if (spec.vibrancy) vibrancy()
                            blur(with(density) { spec.blurRadius.toPx() })
                            if (spec.lensAmount > 0.dp) {
                                val l = with(density) { spec.lensAmount.toPx() }
                                lens(l, l)
                            }
                        },
                        onDrawSurface = {
                            drawGlassTint(spec.copy(tintAlpha = tintAlpha))
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
        }
    }
}
