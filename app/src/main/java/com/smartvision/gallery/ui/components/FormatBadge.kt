package com.smartvision.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.decoder.format.MediaFormat
import com.smartvision.gallery.ui.liquidglass.LiquidGlassChip

/**
 * Compact, format-aware badge. Used in timeline grid cells and the viewer to surface the
 * "all formats" selling point without being noisy.
 *
 * V1.x: rendered as a tinted pill. V1.0+ uses a tiny Liquid Glass chip so the badge
 *       itself reads as a glass element on top of the photo.
 */
@Composable
fun FormatBadge(
    format: MediaFormat,
    modifier: Modifier = Modifier
) {
    val accent = colorResource(format.badgeColorRes)
    LiquidGlassChip(
        modifier = modifier,
    ) {
        Text(
            text = format.displayName,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}