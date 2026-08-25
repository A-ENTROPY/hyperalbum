package com.smartvision.gallery.ui.liquidglass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * iOS 26 Liquid Glass surface spec.
 *
 * iOS 26 tab bar uses a 32dp pill radius with a 12dp shadow; static surfaces
 * (cards, list rows) use a 20dp corner radius with a softer 4dp shadow.
 *
 * Kyant0 backdrop exposes the actual blur/vibrancy/lens magnitudes via the
 * `effects { … }` builder — these values are baked into the composables that
 * use them so callers only have to choose the spec preset.
 */
data class LiquidGlassSpec(
    val cornerRadius: Dp,
    val shadowElevation: Dp,
    val blurRadius: Dp = 16.dp,
    val vibrancy: Boolean = true,
    val lensAmount: Dp = 24.dp,
    val tint: Color = Color.White,
    val tintAlpha: Float = 0.04f,
    val highlightAlpha: Float = 0.5f,
    // ---- 3D layered effect controls (Apple iOS 26/27 cues) ----
    // Apple uses 3-4 reflection layers per control (per
    // developer reverse-engineering). These knobs expose each layer so
    // they can be tuned independently — top specular glint, bottom inner
    // shadow, darkened edges, and the extra top-half tint density that
    // simulates the upper face catching more light.
    // 0f = invisible, 1f = maximum. Defaults are iOS 26-inspired; the
    // playground's 静态卡片 section lets the user dial each down to
    // taste without losing the underlying glass effect.
    val specularAlpha: Float = 0.72f,    // top bright edge + glint (0..1)
    val bottomShadowAlpha: Float = 0.22f, // bottom dark inner shadow (0..1)
    val edgeDarkAlpha: Float = 0.15f,    // bottom + side dark edges (0..1)
    val topTintExtra: Float = 0.45f,     // extra top-half tint density (0..1)
) {
    companion object {
        /** Generic static glass surface (cards, list rows, dialogs). */
        val iOS26Static = LiquidGlassSpec(
            cornerRadius = 18.dp,
            shadowElevation = 6.dp,
            blurRadius = Dp(2.9755275f),
            lensAmount = Dp(20.072989f),
            tint = Color(0xFFF0F6FF),
            tintAlpha = 0.12f,
            highlightAlpha = 0.35f,
            specularAlpha = 0.45f,
            bottomShadowAlpha = 0.086947605f,
            edgeDarkAlpha = 0.08635917f,
            topTintExtra = 0.35f,
        )

        /** iOS 26 tab bar: pill corner, 12dp shadow, 16dp blur. Regular tint. */
        val iOS26TabBar = LiquidGlassSpec(
            cornerRadius = 32.dp,
            shadowElevation = Dp(1.1396973f),
            blurRadius = Dp(3.0147204f),
            lensAmount = 32.dp,
            tint = Color(0xFFEAF4FF),
            tintAlpha = 0.15f,
            highlightAlpha = 0.35f,
            specularAlpha = 0.55f,
            bottomShadowAlpha = 0.07801767f,
            edgeDarkAlpha = 0.06835171f,
            topTintExtra = 0.45f,
        )

        /** iOS 26 long-press "Liquid Lensing" magnifier. */
        val iOS26Lens = LiquidGlassSpec(
            cornerRadius = 999.dp,
            shadowElevation = 8.dp,
            blurRadius = 4.dp,
            lensAmount = 96.dp,
            tint = Color(0xFFDEFAFF),
            tintAlpha = 0.20f,
        )

        // ---- Backwards-compat aliases for old API names ----
        val Default = iOS26Static
        val Vibrant = iOS26Static.copy(blurRadius = 20.dp, shadowElevation = 8.dp)
        val VibrantPlus = iOS26Static.copy(blurRadius = 28.dp, shadowElevation = 12.dp)

        /** iOS 26 top nav bar: full-width chrome, 12dp blur, faint white tint. */
        val iOS26TopBar = LiquidGlassSpec(
            cornerRadius = 0.dp,
            shadowElevation = 0.dp,
            blurRadius = Dp(3.1104944f),
            lensAmount = Dp(20.035328f),
            tint = Color(0xFFF2F7FF),
            tintAlpha = 0.12f,
            highlightAlpha = 0.15f,
            specularAlpha = 0.25f,
            bottomShadowAlpha = 0.05f,
            edgeDarkAlpha = 0.07968616f,
            topTintExtra = 0.25f,
        )

        /** iOS 26 small control glass: buttons, segmented track, toggles. 7dp blur, pill radius. */
        val iOS26Control = LiquidGlassSpec(
            cornerRadius = 999.dp,
            shadowElevation = 4.dp,
            blurRadius = Dp(2.997056f),
            lensAmount = Dp(19.95682f),
            tint = Color(0xFFF0F4FF),
            tintAlpha = 0.08f,
            highlightAlpha = 0.3f,
            specularAlpha = 0.40f,
            bottomShadowAlpha = 0.101177245f,
            edgeDarkAlpha = 0.10f,
            topTintExtra = 0.30f,
        )

        /**
         * iOS 26 floating control — minimum visual weight, fully transparent body.
         *
         * Used for chips and segmented controls that float above page content
         * (e.g. Library page's 选择/排序 row, the 全部/日月年/选择 segmented
         * control). iOS 26 makes these nearly invisible — just a thin white
         * edge stroke so they read as separate glass cards — so the colorful
         * photo grid shows through unobstructed.
         *
         * Zero tint, zero highlight, zero blur, zero lens: the [drawBackdrop]
         * pass-through is effectively a no-op, leaving the underlying canvas
         * gradient visible at the chip's footprint. [drawGlassTint] paints
         * only the 1dp white edge stroke.
         */
        val iOS26FloatingControl = LiquidGlassSpec(
            cornerRadius = 999.dp,
            shadowElevation = 0.dp,
            blurRadius = 0.dp,
            vibrancy = false,
            lensAmount = 0.dp,
            tint = Color.Transparent,
            tintAlpha = 0.0f,
            highlightAlpha = 0.0f,
        )
    }
}
