package com.smartvision.gallery.ui.liquidglass

import androidx.compose.runtime.compositionLocalOf
import com.smartvision.gallery.data.glass.GlassConfig

/**
 * Live [GlassConfig] provided by `AppRoot` (backed by
 * `GlassConfigViewModel` + DataStore). Every glass surface in the app
 * reads from this so a slider drag in the tuning page is visible
 * everywhere in the same frame.
 */
val LocalGlassConfig = compositionLocalOf<GlassConfig> { GlassConfig() }
