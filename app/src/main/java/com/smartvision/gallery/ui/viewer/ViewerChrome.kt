package com.smartvision.gallery.ui.viewer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wand
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.smartvision.gallery.livephoto.LivePhotoBadge
import com.smartvision.gallery.livephoto.LivePhotoPressHold
import com.smartvision.gallery.data.glass.ControlGlassConfig
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.ui.liquidglass.LiquidGlassAlertDialog
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassScreenBackdrop
import com.smartvision.gallery.ui.liquidglass.drawGlassTint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    LiquidGlassAlertDialog(
        onDismiss = onDismiss,
        title = "删除这张照片？",
        message = "照片会移动到\"最近删除\"，30 天后自动清除。",
        confirmText = "删除",
        dismissText = "取消",
        onConfirm = onConfirm,
        confirmColor = MaterialTheme.colorScheme.error,
        titleColor = MaterialTheme.colorScheme.onSurface,
        messageColor = MaterialTheme.colorScheme.onSurfaceVariant,
        dismissColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
    )
}

@Composable
fun LivePhotoOverlay(
    visible: Boolean,
    isPlaying: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier.padding(bottom = 120.dp),
        contentAlignment = Alignment.Center
    ) {
        LivePhotoPressHold(
            isActive = isPlaying,
            onPress = onPress,
            onRelease = onRelease,
        )
    }
}

@Composable
fun LiveBadgePill(modifier: Modifier = Modifier) {
    LivePhotoBadge(modifier = modifier)
}

/**
 * Drives the photo viewer chrome show/hide animation. The chrome composables
 * MUST stay in the composition tree at all times — a conditional `if (visible) { ... }`
 * would unmount them on hide and skip the spring entirely (regression to the current
 * instant-disappear behavior). Instead, [progress] drives `alpha` + `translateY` and
 * [visible] only gates `Modifier.clickable(enabled = visible)` so taps pass through.
 *
 * One instance is shared by top + bottom so they always toggle together.
 */
@Stable
class ChromeVisibilityState internal constructor(
    initialVisible: Boolean,
    private val autoHideMs: Long,
    private val scope: CoroutineScope,
) {
    private val anim = Animatable(if (initialVisible) 1f else 0f)

    /** 0f = fully hidden, 1f = fully shown. Read from chrome composables. */
    val progress: Float get() = anim.value

    /** Target state — true = visible, false = hidden. */
    var visible: Boolean = initialVisible
        private set

    fun show() {
        visible = true
        scope.launch { anim.animateTo(1f, spring(stiffness = 380f, dampingRatio = 0.85f)) }
    }

    fun hide() {
        visible = false
        scope.launch { anim.animateTo(0f, spring(stiffness = 380f, dampingRatio = 1.0f)) }
    }

    fun toggle() = if (visible) hide() else show()

    /** Restart the 3-second auto-hide timer. Call after every user interaction. */
    fun kickAutoHide() {
        if (!visible) return
        scope.launch {
            delay(autoHideMs)
            if (isActive) hide()
        }
    }
}

@Composable
fun rememberChromeVisibilityState(
    initialVisible: Boolean = true,
    autoHideMs: Long = 3000L,
): ChromeVisibilityState {
    // rememberCoroutineScope carries the AndroidUiDispatcher (which provides
    // MonotonicFrameClock). Using a hand-rolled MainScope() instead throws
    // IllegalStateException inside Animatable.animateTo because withFrameNanos
    // has no frame clock to drive from.
    val scope = rememberCoroutineScope()
    return remember { ChromeVisibilityState(initialVisible, autoHideMs, scope) }
}

/**
 * One capsule glass button used inside [ViewerTopBarChrome] / [ViewerBottomBarChrome].
 * The button itself stays in the composition tree even when chrome is hidden —
 * the parent chrome composable applies `Modifier.alpha(progress)` and gates
 * `clickable(enabled = visible)` so this primitive never needs to know.
 *
 * Visual: full iOS 26 Liquid Glass primitive — `Modifier.drawBackdrop` with
 * the screen-captured photo pixels (same `LocalLiquidGlassScreenBackdrop` the
 * parent chrome panel reads) + small `blur(8.dp)` + small `lens(8.dp)` +
 * [drawGlassTint]. Reads real photo pixels through the button so the user
 * sees the same refracted-glass physics on every chip — not a flat black
 * fill.
 *
 * Small magnitudes are intentional:
 *  - 44dp button can't host a 32dp lens without distortion
 *  - 11 buttons (6 top + 5 bottom) on screen at once — keeping the effect
 *    chain lean (blur + lens only, no `vibrancy()`) avoids the
 *    RenderEffect chain depth that crashed ColorOS 16 in round 8
 *
 * @param selected Active state (e.g. favorite on) — uses a stronger backdrop
 *                 tint + highlight so the chip reads as "pressed in".
 * @param enabled  Disabled state — icon strokes dim to 50% alpha.
 */
@Composable
fun CapsuleActionIcon(
    icon: @Composable () -> Unit,
    contentDescription: String?,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = if (isPressed) 0.88f else 1.0f
    val density = LocalDensity.current
    val glassBackdrop = LocalLiquidGlassScreenBackdrop.current

    // Reads the "控件" slider section in GlassConfigPanel — the capsule
    // button and the chrome panels share the SAME spec so the user's
    // tweak propagates to both simultaneously. `selected` bumps tint +
    // highlight so the chip reads "on" without forking the spec source.
    val baseSpec = LocalGlassConfig.current.control.toSpec()
    val spec = if (selected) baseSpec.copy(
        tintAlpha = (baseSpec.tintAlpha + 0.20f).coerceAtMost(1f),
        highlightAlpha = (baseSpec.highlightAlpha + 0.20f).coerceAtMost(1f),
    ) else baseSpec

    Box(
        modifier = modifier
            .size(44.dp)                              // Apple HIG hit target
            .scale(scale)                             // press scale
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,                     // no Material ripple
                enabled = enabled,
                onClick = onClick,
            )
            .drawBackdrop(
                backdrop = glassBackdrop,
                shape = { CircleShape },
                effects = {
                    blur(with(density) { spec.blurRadius.toPx() })
                    lens(
                        with(density) { spec.lensAmount.toPx() },
                        with(density) { spec.lensAmount.toPx() },
                    )
                },
                onDrawSurface = {
                    drawGlassTint(spec)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // No blur on the icon layer — the backdrop's own blur only affects
        // the captured photo, not the icon glyph on top.
        Box(modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
            icon()
        }
    }
}

/**
 * Top chrome: 6 capsule buttons in a single horizontal liquid-glass panel.
 * The bar itself always renders; [progress] drives the show/hide animation.
 *
 * Reads [LocalLiquidGlassScreenBackdrop] (real captured photo pixels from the
 * pager subtree) and applies the iOS 26 drawBackdrop chain — vibrancy +
 * 20dp blur + 24dp lens — through [viewerChromeSpec]'s dark tint. Same
 * physical effect as the main app's [LiquidGlassBar] tab bar.
 *
 * [photoBitmap] is no longer used (the live captured backdrop does the
 * work); kept in the signature so the call site stays stable.
 */
@Composable
fun ViewerTopBarChrome(
    title: String,
    progress: Float,
    visible: Boolean,
    onBack: () -> Unit,
    onSlideshowClick: () -> Unit,
    onSetWallpaperClick: () -> Unit,
    onHideToVaultClick: () -> Unit,
    onShowLocationClick: () -> Unit,
    onAnyInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") photoBitmap: Bitmap? = null,
) {
    val density = LocalDensity.current
    val glassBackdrop = LocalLiquidGlassScreenBackdrop.current
    // Chrome panel shares the "控件" slider section with the capsule buttons
    // so the user's tweak propagates to both simultaneously — see
    // [CapsuleActionIcon]'s base spec source.
    val spec = LocalGlassConfig.current.control.toSpec()
    val panelShape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(8.dp))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(progress)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = glassBackdrop,
                    shape = { panelShape },
                    effects = {
                        if (spec.vibrancy) vibrancy()
                        blur(with(density) { spec.blurRadius.toPx() })
                        lens(
                            with(density) { spec.lensAmount.toPx() },
                            with(density) { spec.lensAmount.toPx() },
                        )
                    },
                    onDrawSurface = {
                        drawGlassTint(spec)
                    },
                )
                .clickable(enabled = visible, onClick = onAnyInteraction)
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.ArrowLeft, contentDescription = "返回", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "返回",
                        enabled = visible,
                        onClick = { onBack(); onAnyInteraction() },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.Play, contentDescription = "幻灯片", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "幻灯片",
                        enabled = visible,
                        onClick = { onSlideshowClick(); onAnyInteraction() },
                    )
                    Spacer(Modifier.size(4.dp))
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.Wand, contentDescription = "壁纸", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "壁纸",
                        enabled = visible,
                        onClick = { onSetWallpaperClick(); onAnyInteraction() },
                    )
                    Spacer(Modifier.size(4.dp))
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.EyeOff, contentDescription = "隐藏", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "隐藏",
                        enabled = visible,
                        onClick = { onHideToVaultClick(); onAnyInteraction() },
                    )
                    Spacer(Modifier.size(4.dp))
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.MapPin, contentDescription = "位置", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "位置",
                        enabled = visible,
                        onClick = { onShowLocationClick(); onAnyInteraction() },
                    )
                }
        }
    }
}

/**
 * Bottom chrome: 5 capsule buttons in a single horizontal liquid-glass panel.
 * Always renders; [progress] drives the animation. The measured height is
 * reported via [onHeightMeasured] so the snackbar can pad above the bar
 * regardless of font scale.
 */
@Composable
fun ViewerBottomBarChrome(
    isFavorite: Boolean,
    progress: Float,
    visible: Boolean,
    onFavoriteClick: () -> Unit,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    onInfoClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onAnyInteraction: () -> Unit,
    onHeightMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") photoBitmap: Bitmap? = null,
) {
    val density = LocalDensity.current
    val glassBackdrop = LocalLiquidGlassScreenBackdrop.current
    // Same spec as the top chrome — see [ViewerTopBarChrome] for rationale.
    val spec = LocalGlassConfig.current.control.toSpec()
    val panelShape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(8.dp))
    Box(
        modifier = modifier
            .fillMaxWidth()
            .alpha(progress)
            .onSizeChanged { onHeightMeasured(it.height) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = glassBackdrop,
                    shape = { panelShape },
                    effects = {
                        if (spec.vibrancy) vibrancy()
                        blur(with(density) { spec.blurRadius.toPx() })
                        lens(
                            with(density) { spec.lensAmount.toPx() },
                            with(density) { spec.lensAmount.toPx() },
                        )
                    },
                    onDrawSurface = {
                        drawGlassTint(spec)
                    },
                )
                .clickable(enabled = visible, onClick = onAnyInteraction)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleActionIcon(
                        icon = {
                            Icon(
                                com.composables.icons.lucide.Lucide.Heart,
                                contentDescription = null,
                                tint = if (isFavorite) Color(0xFFFF1744) else MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        selected = isFavorite,
                        enabled = visible,
                        onClick = { onFavoriteClick(); onAnyInteraction() },
                    )
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.Share2, contentDescription = "分享", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "分享",
                        enabled = visible,
                        onClick = { onShareClick(); onAnyInteraction() },
                    )
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.Pencil, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "编辑",
                        enabled = visible,
                        onClick = { onEditClick(); onAnyInteraction() },
                    )
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.Info, contentDescription = "信息", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "信息",
                        enabled = visible,
                        onClick = { onInfoClick(); onAnyInteraction() },
                    )
                    CapsuleActionIcon(
                        icon = { Icon(com.composables.icons.lucide.Lucide.Trash2, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurface) },
                        contentDescription = "删除",
                        enabled = visible,
                        onClick = { onDeleteClick(); onAnyInteraction() },
                    )
                }
            }
        }
}
