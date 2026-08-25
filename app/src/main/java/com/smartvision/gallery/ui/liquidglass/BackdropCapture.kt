package com.smartvision.gallery.ui.liquidglass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Backwards-compatibility shim for the old self-developed backdrop capture
 * stack (kept so the rest of the codebase can still reference the type
 * names it used to import — they are now no-ops because Kyant0 backdrop
 * captures pixels through the standard `Modifier.layerBackdrop` pipeline,
 * which [LiquidGlassTheme] already sets up).
 *
 * The old `captureRegion`/`captureFullScreen` methods return null because
 * we no longer do software sampling — Kyant0 handles this at the GPU layer.
 */

@Deprecated(
    "Use LocalLiquidGlassBackdrop (Kyant0 Backdrop) instead.",
    ReplaceWith("LocalLiquidGlassBackdrop"),
)
typealias BackdropCaptureController = Unit

@Deprecated(
    "No longer needed; LocalLiquidGlassBackdrop is provided by LiquidGlassTheme.",
    ReplaceWith("LocalLiquidGlassBackdrop"),
)
val LocalBackdropCapture = compositionLocalOf<Unit?> { null }

@Composable
fun ProvideBackdropCapture(
    @Suppress("UNUSED_PARAMETER") controller: Unit,
    content: @Composable () -> Unit,
) {
    content()
}
