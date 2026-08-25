package com.smartvision.gallery.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassScreenBackdrop
import com.smartvision.gallery.ui.liquidglass.drawGlassTint
import kotlin.math.roundToLong

/**
 * iOS 风格视频控制条 — 与 [ViewerChrome] 菜单栏完全一致的液态玻璃效果。
 * 共用 [LocalLiquidGlassScreenBackdrop]（photoviewer 的真实像素）+
 * [LocalGlassConfig].control 配置 + vibrancy/blur/lens + drawGlassTint。
 * playground 调参一改全改。
 */
@Composable
fun VideoControlBar(
    isPlaying: Boolean,
    isMuted: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = LocalGlassConfig.current.control.toSpec()
    val shape = RoundedCornerShape(spec.cornerRadius)
    val density = LocalDensity.current
    val backdrop = LocalLiquidGlassScreenBackdrop.current

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
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
                onDrawSurface = {
                    drawGlassTint(spec)
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircleIconButton(
                    icon = if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    onClick = onPlayPause,
                )
                Text(formatTime(positionMs), color = Color.Black, fontSize = 11.sp)
                Slider(
                    value = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f,
                    onValueChange = { fraction ->
                        val newPos = (fraction * durationMs).roundToLong()
                        onSeek(newPos)
                    },
                    modifier = Modifier.weight(1f).height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.Black,
                        activeTrackColor = Color.Black,
                        inactiveTrackColor = Color.Black.copy(alpha = 0.3f),
                    ),
                )
                Text(formatTime(durationMs), color = Color.Black.copy(alpha = 0.6f), fontSize = 11.sp)
                CircleIconButton(
                    icon = if (isMuted) Icons.Outlined.VolumeOff else Icons.Outlined.VolumeUp,
                    contentDescription = if (isMuted) "静音" else "有声",
                    onClick = onToggleMute,
                )
            }
        }
    }
}

@Composable
private fun CircleIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = if (pressed) 0.92f else 1.0f
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.08f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.scale(scale)) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = Color.Black,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val hr = min / 60
    return if (hr > 0) "%d:%02d:%02d".format(hr, min % 60, sec)
    else "%d:%02d".format(min, sec)
}