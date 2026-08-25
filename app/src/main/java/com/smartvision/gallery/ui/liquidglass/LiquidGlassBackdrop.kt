package com.smartvision.gallery.ui.liquidglass

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop

/**
 * Screen-level gradient that the Liquid Glass surfaces refract.
 *
 * On iOS 26 the Control Center / Photos pages use a single bright gradient
 * that fills the screen; the glass then reads as a real window. We paint the
 * same gradient on the [LiquidGlassTheme] root and capture it via
 * `rememberLayerBackdrop()` so every glass panel on the screen can sample
 * real pixels (not a procedural approximation).
 */
data class LiquidGlassBackdrop(
    val light: Brush,
    val dark: Brush,
) {
    companion object {
        val PhotosMosaic = LiquidGlassBackdrop(
            light = Brush.linearGradient(
                colors = listOf(
                    Color(0xFFFFE4EC),
                    Color(0xFFE8F4FF),
                    Color(0xFFFFF8E8),
                )
            ),
            dark = Brush.linearGradient(
                colors = listOf(
                    Color(0xFF2A1B2E),
                    Color(0xFF1A2B3D),
                    Color(0xFF2E2A1B),
                )
            ),
        )

        val Default = PhotosMosaic
    }
}

/**
 * Backdrop read by glass surfaces that live INSIDE the content subtree
 * (cards, list rows, dialogs, etc.). This is a static canvas-painted
 * gradient — it cannot be a `rememberLayerBackdrop()` because the content
 * itself is what gets captured, and a glass surface sampling its own
 * captured layer would recurse infinitely.
 *
 * Provided by [LiquidGlassTheme] via `rememberCanvasBackdrop { drawRect(brush) }`.
 */
val LocalLiquidGlassBackdrop = compositionLocalOf<Backdrop> { emptyBackdrop() }

/**
 * Backdrop read by glass surfaces that live at SCREEN CHROME level
 * (the bottom tab bar, the long-press magnifier overlay). These are
 * siblings of the content-capture Box in [com.smartvision.gallery.ui.AppRoot],
 * so they can safely sample the real-time layer that captures the content
 * underneath them.
 *
 * Provided by [com.smartvision.gallery.ui.AppRoot] via
 * `rememberLayerBackdrop()` placed on the content-capture Box.
 */
val LocalLiquidGlassScreenBackdrop = compositionLocalOf<Backdrop> { emptyBackdrop() }

