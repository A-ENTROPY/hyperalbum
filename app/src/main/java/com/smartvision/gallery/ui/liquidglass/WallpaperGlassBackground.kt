package com.smartvision.gallery.ui.liquidglass

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Full-screen gradient background. Clean, polished, adaptive to theme.
 *
 * Dark mode: deep purple→navy gradient with magenta accents (the app's
 * glass accent palette, so glass surfaces refract a colourful scene).
 * Light mode: warm cream→beige base with soft pastel swatches at the
 * corners — visible content under glass surfaces, never plain white.
 *
 * Glass surfaces above blur this layer via Kyant's `drawBackdrop`, so the
 * resulting look is "frosted glass over a soft gradient scene" — the iOS 26
 * Liquid Glass appearance.
 */
@Composable
fun WallpaperGlassBackground() {
    val isDark = isSystemInDarkTheme()

    val stops = remember(isDark) {
        if (isDark) listOf(
            Color(0xFF3D2418),  // deep cocoa (peach-shadow)
            Color(0xFF2E2235),  // muted plum (cream-shadow)
            Color(0xFF352030),  // wine (pink-shadow)
        ) else listOf(
            Color(0xFFFCD9BD),  // warm peach (top)
            Color(0xFFFAEFE6),  // cream (middle)
            Color(0xFFF1E2EA),  // soft pink-lavender (bottom)
        )
    }
    val accentStart = remember(isDark) {
        if (isDark) Color(0xFFFF9F66).copy(alpha = 0.18f)
        else Color(0xFFFFB58A).copy(alpha = 0.45f)   // peach glow top-left
    }
    val accentEnd = remember(isDark) {
        if (isDark) Color(0xFFE07090).copy(alpha = 0.16f)
        else Color(0xFFE8B6CC).copy(alpha = 0.45f)   // soft pink glow bottom-right
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = stops,
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            )
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(accentStart, Color.Transparent),
                    center = Offset(0.15f * Float.POSITIVE_INFINITY, 0.20f * Float.POSITIVE_INFINITY),
                    radius = 0.65f * Float.POSITIVE_INFINITY,
                )
            )
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(accentEnd, Color.Transparent),
                    center = Offset(0.95f * Float.POSITIVE_INFINITY, 0.85f * Float.POSITIVE_INFINITY),
                    radius = 0.75f * Float.POSITIVE_INFINITY,
                )
            )
    )
}
