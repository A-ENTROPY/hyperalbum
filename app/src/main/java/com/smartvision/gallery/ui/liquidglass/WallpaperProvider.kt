package com.smartvision.gallery.ui.liquidglass

import android.app.WallpaperManager
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Observe system wallpaper changes and return a version counter.
 *
 * The version counter increments each time the user changes their wallpaper.
 * Consumers use `key(version)` to force recomposition / recreation.
 */
@Composable
fun rememberWallpaperChangeVersion(): Int {
    val context = LocalContext.current
    val wm = WallpaperManager.getInstance(context)
    var version by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val listener = WallpaperManager.OnColorsChangedListener { _, _ ->
            version++
        }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return version
}

/**
 * Observe system wallpaper colors (primary / secondary / tertiary) and return
 * them as Compose [Color] values.
 *
 * Uses [WallpaperManager.getWallpaperColors] which returns color swatches
 * only — NOT the wallpaper bitmap — so it works on Android 14+ (API 34).
 */
@Composable
fun rememberWallpaperColors(): WallpaperColorsState {
    val context = LocalContext.current
    val wm = WallpaperManager.getInstance(context)

    val initial = try {
        wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
    } catch (_: Exception) {
        null
    }

    var state by remember {
        mutableStateOf(
            WallpaperColorsState(
                primary = initial?.let { wpColorsToComposeColor(it.primaryColor) },
                secondary = initial?.let { wpColorsToComposeColor(it.secondaryColor) },
                tertiary = initial?.let { wpColorsToComposeColor(it.tertiaryColor) },
                version = 0,
            )
        )
    }

    DisposableEffect(Unit) {
        val listener = WallpaperManager.OnColorsChangedListener { colors, _ ->
            state = WallpaperColorsState(
                primary = colors?.let { wpColorsToComposeColor(it.primaryColor) },
                secondary = colors?.let { wpColorsToComposeColor(it.secondaryColor) },
                tertiary = colors?.let { wpColorsToComposeColor(it.tertiaryColor) },
                version = state.version + 1,
            )
        }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }

    return state
}

/** Convert an Android platform [AndroidColor] instance to Compose [Color]. */
private fun wpColorsToComposeColor(ac: AndroidColor?): Color? {
    if (ac == null) return null
    // Android Color instance methods return float in [0f, 1f] range.
    return Color(red = ac.red(), green = ac.green(), blue = ac.blue(), alpha = ac.alpha())
}

/**
 * Snapshot of wallpaper colors at a given version.
 *
 * Each [Color] is null when the WallpaperManager hasn't returned swatches
 * (fresh boot, before first callback). Consumers should provide defaults.
 */
data class WallpaperColorsState(
    val primary: Color?,
    val secondary: Color?,
    val tertiary: Color?,
    val version: Int,
) {
    companion object {
        val fallbackLight = WallpaperColorsState(
            primary = Color(0xFFD4E4FF),
            secondary = Color(0xFFFFE8E0),
            tertiary = Color(0xFFE8E8F0),
            version = 0,
        )

        val fallbackDark = WallpaperColorsState(
            primary = Color(0xFF445577),
            secondary = Color(0xFF553344),
            tertiary = Color(0xFF1A1A2E),
            version = 0,
        )
    }
}