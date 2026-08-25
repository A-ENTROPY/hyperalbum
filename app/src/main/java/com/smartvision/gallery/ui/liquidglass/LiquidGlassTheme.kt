package com.smartvision.gallery.ui.liquidglass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.smartvision.gallery.data.glass.BackdropGlassConfig
import kotlin.random.Random

/**
 * Root wrapper for screens that want Liquid Glass surfaces.
 *
 * Provides [LocalLiquidGlassBackdrop] — a canvas backdrop painted with the
 * backdrop gradient colors (from [BackdropGlassConfig]) plus soft irregular
 * spots. Blur scatters the spots into organic frosted texture.
 *
 * The system wallpaper shows through transparent areas via FLAG_SHOW_WALLPAPER
 * (set in [com.smartvision.gallery.ui.MainActivity]). The canvas backdrop
 * provides a textured sample source for the Kyant blur pipeline — without
 * texture variation, blurring a flat gradient produces zero visible effect.
 *
 * The canvas backdrop recreates when the user changes their wallpaper
 * (via [key] on [rememberWallpaperChangeVersion]).
 */
@Composable
fun LiquidGlassTheme(
    backdrop: LiquidGlassBackdrop = LiquidGlassBackdrop.PhotosMosaic,
    backgroundSpec: BackdropGlassConfig = BackdropGlassConfig(),
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val wallpaperVersion = rememberWallpaperChangeVersion()

    key(wallpaperVersion) {
        val canvasBackdrop = rememberCanvasBackdrop {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@rememberCanvasBackdrop

            // 1. Base gradient from config colors
            val baseColors = if (isDark) {
                listOf(
                    Color(backgroundSpec.darkStart),
                    Color(backgroundSpec.darkMid),
                    Color(backgroundSpec.darkEnd),
                )
            } else {
                listOf(
                    Color(backgroundSpec.lightStart),
                    Color(backgroundSpec.lightMid),
                    Color(backgroundSpec.lightEnd),
                )
            }
            drawRect(
                brush = Brush.linearGradient(
                    colors = baseColors,
                    start = Offset.Zero,
                    end = Offset(w, h),
                )
            )

            // 2. Soft irregular spots — blur scatters them into organic mottle
            val rng = Random(w.hashCode() xor h.hashCode())
            val spotCount = (w / 90f).toInt().coerceIn(8, 25)
            val spotAlphaBase = if (isDark) 90 else 110
            for (i in 0 until spotCount) {
                val cx = rng.nextFloat() * w
                val cy = rng.nextFloat() * h
                val radius = (15f + rng.nextFloat() * 55f) * (w / 400f).coerceIn(0.5f, 1.5f)
                val spotColor = if (isDark) {
                    Color(
                        red = rng.nextInt(15, 50),
                        green = rng.nextInt(15, 48),
                        blue = rng.nextInt(18, 55),
                        alpha = rng.nextInt(spotAlphaBase - 30, spotAlphaBase + 30),
                    )
                } else {
                    Color(
                        red = rng.nextInt(190, 245),
                        green = rng.nextInt(195, 248),
                        blue = rng.nextInt(200, 255),
                        alpha = rng.nextInt(spotAlphaBase - 30, spotAlphaBase + 30),
                    )
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(spotColor, spotColor.copy(alpha = 0f)),
                        center = Offset(cx, cy),
                        radius = radius,
                    )
                )
            }
        }

        CompositionLocalProvider(LocalLiquidGlassBackdrop provides canvasBackdrop) {
            content()
        }
    }
}