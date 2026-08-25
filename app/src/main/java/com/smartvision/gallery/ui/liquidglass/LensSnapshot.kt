package com.smartvision.gallery.ui.liquidglass

import androidx.compose.ui.geometry.Offset

/**
 * Immutable snapshot of the lens state consumed by the AGSL shader.
 *
 * Snapshots are immutable so the AGSL `renderEffect` lambda (which reads
 * uniforms on the render thread) cannot race with the property updates
 * done on the main thread inside the controller.
 *
 *  - [cx], [cy]     — lens centre in absolute screen pixels.
 *  - [scaleX], [scaleY] — semi-axis multipliers on the SDF sphere (1.0 = at rest).
 *  - [alpha]        — overall opacity (0..1).
 *  - [rimAlpha]     — edge highlight opacity.
 *  - [baseRadiusPx] — rest radius in pixels (driven by config.lensRestRadius).
 *  - [lensAmount]   — refraction magnitude in normalised screen units.
 *  - [chromaticAberration] — RGB split coefficient.
 */
data class LensSnapshot(
    val cx: Float = 0f,
    val cy: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 0f,
    val rimAlpha: Float = 0.85f,
    val baseRadiusPx: Float = 144f,
    val lensAmount: Float = 0.08f,
    val chromaticAberration: Float = 0.012f,
) {
    companion object {
        val ZERO = LensSnapshot()
    }
}
