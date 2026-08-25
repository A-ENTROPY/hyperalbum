package com.smartvision.gallery.ui.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.capsule.ContinuousCapsule

/**
 * Modifier that bends the surrounding glass around the iOS 26 "Liquid Lensing"
 * magnifier.
 *
 * When the magnifier is active (`lensCenter` != null) we layer a strong
 * `lens(80.dp, 80.dp, chromaticAberration = true)` on top of whatever
 * glass surface this modifier is attached to. Kyant0's SDF-based refraction
 * displaces the visible pixels of the shape radially around its own centre,
 * so the rest of the screen reads as if the surrounding glass is being
 * tugged toward the magnifier — the iOS 26 "adjust each glass block's
 * distortion" effect.
 *
 * Pass [lensCenter] in window coordinates (the same coordinates
 * [LiquidGlassLensController.position] exposes). When null, no extra effect
 * is applied — the wrapped shape falls back to its own native glass look.
 */
fun Modifier.liquidLensingDistortion(lensCenter: Offset?): Modifier = composed {
    if (lensCenter == null) {
        this
    } else {
        val backdrop = LocalLiquidGlassScreenBackdrop.current
        val density = LocalDensity.current
        this.drawBackdrop(
            backdrop = backdrop,
            shape = { ContinuousCapsule() },
            effects = {
                lens(
                    with(density) { 80.dp.toPx() },
                    with(density) { 80.dp.toPx() },
                    chromaticAberration = true,
                )
            },
        )
    }
}
