package com.smartvision.gallery.ui.liquidglass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Playground page for visually inspecting every Liquid Glass surface that
 * the iOS 26 design language uses. Drop into Settings → Glass Playground
 * to A/B test the material.
 *
 * Every surface in this page reads real pixels from [LocalLiquidGlassBackdrop]
 * (set up by [LiquidGlassTheme]) via `Modifier.drawBackdrop` — no procedural
 * wallpaper, no software sampling.
 */
@Composable
fun LiquidGlassPlayground() {
    val isDark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Liquid Glass Playground",
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "iOS 26 surfaces, all backdrop-sampled via Kyant0's " +
                "rememberLayerBackdrop. No procedural wallpaper — every " +
                "panel reads real pixels of the gradient painted by " +
                "LiquidGlassTheme.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column {
                Text(
                    "Static card (vibrancy + blur + lens)",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "20dp corners, 4dp shadow, 16dp blur, 24dp lens.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                "List section card (12dp corners)",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiquidGlassChip { Text("Photos") }
            LiquidGlassChip { Text("Favorites") }
            LiquidGlassChip { Text("Live") }
            LiquidGlassChip { Text("Cinematic") }
        }

        LiquidGlassBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = null,
                    tint = if (isDark) Color.White else Color.Black,
                    modifier = Modifier.size(24.dp),
                )
                Text("Tab Bar Pill", fontSize = 15.sp)
                Box(modifier = Modifier.size(24.dp))
            }
        }
    }
}
