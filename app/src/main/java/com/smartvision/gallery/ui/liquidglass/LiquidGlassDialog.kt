package com.smartvision.gallery.ui.liquidglass

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.smartvision.gallery.data.glass.toSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen overlay glass dialogs — rendered in the same Activity window tree
 * (no PopupWindow). Inherit wallpaper bleed-through + CompositionLocal.
 *
 * In viewer pages (PhotoViewerActivity), [LocalLiquidGlassScreenBackdrop] is
 * available with real photo pixels → use that with NO drawGlassTint → no color
 * cast, just pure frosted blur of the photo underneath.
 *
 * Elsewhere (main album list), fallback to [LocalLiquidGlassBackdrop] (canvas
 * gradient) with drawGlassTint for the frosted matte look.
 */

/**
 * Center-screen alert overlay.
 *
 * Callers: DeleteConfirmDialog, SlideshowDialog, WallpaperConfirmDialog
 * (all inside PhotoViewerActivity — so they get screenBackdrop, no tint).
 */
@Composable
fun LiquidGlassOverlayAlert(
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    confirmText: String = "确认",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismissClick: () -> Unit = onDismiss,
    confirmColor: Color = Color.Black,
    titleColor: Color = Color.Black,
    messageColor: Color = Color.Black.copy(alpha = 0.75f),
    dismissColor: Color = Color.Black.copy(alpha = 0.6f),
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val spec = LocalGlassConfig.current.staticGlass.toSpec()
    val shape = RoundedCornerShape(spec.cornerRadius)

    // Viewer page has real pixel backdrop from rememberLayerBackdrop;
    // elsewhere LocalLiquidGlassScreenBackdrop is emptyBackdrop().
    val screenBd = LocalLiquidGlassScreenBackdrop.current
    val inViewer = screenBd !== emptyBackdrop()
    val backdrop = if (inViewer) screenBd else LocalLiquidGlassBackdrop.current
    val onDrawSurface: (androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit)? =
        if (inViewer) null else { { this.drawGlassTint(spec) } }

    var visible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    visible = false
                    scope.launch { delay(180); onDismissClick() }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .clip(shape)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            if (spec.vibrancy) vibrancy()
                            blur(with(density) { spec.blurRadius.toPx() })
                            lens(
                                with(density) { spec.lensAmount.toPx() },
                                with(density) { spec.lensAmount.toPx() },
                            )
                        },
                        // No drawGlassTint in viewer → no color cast, pure blur
                        onDrawSurface = onDrawSurface,
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { /* consume clicks */ }
                    .padding(24.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = titleColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (message != null) {
                        Text(
                            text = message,
                            fontSize = 15.sp,
                            color = messageColor,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    content?.invoke()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    ) {
                        TextButton(onClick = {
                            visible = false; scope.launch { delay(180); onDismissClick() }
                        }) {
                            Text(dismissText, color = dismissColor)
                        }
                        TextButton(onClick = {
                            visible = false; scope.launch { delay(180); onConfirm() }
                        }) {
                            Text(confirmText, color = confirmColor, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom-sheet overlay.
 *
 * Callers: InfoPanel (viewer), ShareSheet (viewer + main?).
 */
@Composable
fun LiquidGlassOverlaySheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val spec = LocalGlassConfig.current.staticGlass.toSpec()

    val screenBd = LocalLiquidGlassScreenBackdrop.current
    val inViewer = screenBd !== emptyBackdrop()
    val backdrop = if (inViewer) screenBd else LocalLiquidGlassBackdrop.current
    val onDrawSurface: (androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit)? =
        if (inViewer) null else { { this.drawGlassTint(spec) } }

    var visible by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    // Back gesture → close sheet instead of finishing Activity
    BackHandler(enabled = visible) {
        visible = false; scope.launch { delay(250); onDismiss() }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(250, delayMillis = 50)) { it / 4 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 4 },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    visible = false
                    scope.launch { delay(250); onDismiss() }
                },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            if (spec.vibrancy) vibrancy()
                            blur(with(density) { spec.blurRadius.toPx() })
                            lens(
                                with(density) { spec.lensAmount.toPx() },
                                with(density) { spec.lensAmount.toPx() },
                            )
                        },
                        onDrawSurface = onDrawSurface,
                    )
                    .clip(shape)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { /* consume clicks */ }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    content = content,
                )
            }
        }
    }
}

// ── Backward-compat aliases ──────────────────────────────────────────────────

@Composable
@Deprecated(
    "Use LiquidGlassOverlayAlert instead",
    ReplaceWith("LiquidGlassOverlayAlert(onDismiss, title, message, confirmText, dismissText, onConfirm, onDismissClick, confirmColor, titleColor, messageColor, dismissColor, modifier = modifier, content = content)"),
)
fun LiquidGlassAlertDialog(
    onDismiss: () -> Unit,
    title: String,
    message: String? = null,
    confirmText: String = "确认",
    dismissText: String = "取消",
    onConfirm: () -> Unit,
    onDismissClick: () -> Unit = onDismiss,
    confirmColor: Color = Color.Black,
    titleColor: Color = Color.Black,
    messageColor: Color = Color.Black.copy(alpha = 0.75f),
    dismissColor: Color = Color.Black.copy(alpha = 0.6f),
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    LiquidGlassOverlayAlert(
        onDismiss = onDismiss,
        title = title,
        message = message,
        confirmText = confirmText,
        dismissText = dismissText,
        onConfirm = onConfirm,
        onDismissClick = onDismissClick,
        confirmColor = confirmColor,
        titleColor = titleColor,
        messageColor = messageColor,
        dismissColor = dismissColor,
        modifier = modifier,
        content = content,
    )
}

@Composable
@Deprecated(
    "Use LiquidGlassOverlaySheet instead",
    ReplaceWith("LiquidGlassOverlaySheet(onDismiss, modifier, shape, content)"),
)
fun LiquidGlassBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    LiquidGlassOverlaySheet(onDismiss, modifier, shape, content)
}