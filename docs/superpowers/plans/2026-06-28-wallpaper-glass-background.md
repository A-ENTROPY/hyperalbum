# Wallpaper Glass Background Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace all solid MaterialTheme page backgrounds with the system wallpaper rendered through the Kyant `drawBackdrop` pipeline (48dp blur + vibrancy + glass tint) so the entire app shows frosted wallpaper.

**Architecture:** A new `WallpaperGlassBackground` composable at the bottom of Z=0 inside `layerBackdrop(liquidBackdrop)` renders the wallpaper via `rememberCanvasBackdrop` with full Liquid Glass optical effects. Pages become transparent so frosted wallpaper shows through. Chrome at Z=1 naturally samples wallpaper + content via the existing `liquidBackdrop`.

**Tech Stack:** Android Compose, Kyant Backdrop library, `WallpaperManager`, `ImageBitmap`, `RenderEffect`

---
 
### Task 1: Add BackgroundGlassConfig to GlassConfig.kt

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\data\glass\GlassConfig.kt`

- [ ] **Step 1: Add BackgroundGlassConfig data class and toSpec() converter after ToggleGlassConfig**

Add after `data class ToggleGlassConfig`:
```kotlin
data class BackgroundGlassConfig(
    val blurRadius: Dp = 48.dp,
    val lensAmount: Dp = 0.dp,
    val vibrancy: Boolean = true,
    val lightTintArgb: Long = 0xFFFFFFFFL,
    val lightTintAlpha: Float = 0.25f,
    val darkTintArgb: Long = 0xFF1A1A2EL,
    val darkTintAlpha: Float = 0.55f,
    val highlightAlpha: Float = 0.08f,
)
```

- [ ] **Step 2: Add `background` field to GlassConfig**

Modify `data class GlassConfig` to add:
```kotlin
data class GlassConfig(
    val tabBar: TabBarGlassConfig = TabBarGlassConfig(),
    val staticGlass: StaticGlassConfig = StaticGlassConfig(),
    val topBar: TopBarGlassConfig = TopBarGlassConfig(),
    val control: ControlGlassConfig = ControlGlassConfig(),
    val toggle: ToggleGlassConfig = ToggleGlassConfig(),
    val lens: LensGlassConfig = LensGlassConfig(),
    val backdrop: BackdropGlassConfig = BackdropGlassConfig(),
    val background: BackgroundGlassConfig = BackgroundGlassConfig(),  // NEW
)
```

### Task 2: Create WallpaperProvider.kt

**Files:**
- Create: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\liquidglass\WallpaperProvider.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.smartvision.gallery.ui.liquidglass

import android.app.WallpaperManager
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * Observe the system wallpaper as a Compose [ImageBitmap].
 *
 * Returns `null` briefly until the initial drawable resolves. Refreshes
 * automatically when the user changes wallpaper via System Settings.
 */
@Composable
fun rememberWallpaperBitmap(): ImageBitmap? {
    val context = LocalContext.current
    val wm = WallpaperManager.getInstance(context)
    var bitmap by remember {
        mutableStateOf(
            (wm.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
        )
    }

    DisposableEffect(Unit) {
        val listener = WallpaperManager.OnColorsChangedListener { _ ->
            bitmap = (wm.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
        }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return bitmap
}
```

---

### Task 3: Create WallpaperGlassBackground.kt

**Files:**
- Create: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\liquidglass\WallpaperGlassBackground.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.smartvision.gallery.ui.liquidglass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.smartvision.gallery.data.glass.BackgroundGlassConfig

/**
 * Full-screen wallpaper rendered through the Liquid Glass drawBackdrop
 * pipeline — heavy blur + vibrancy + glass tint — so the app background
 * "vaguely shows the phone's desktop" with iOS 26 frosted aesthetics.
 *
 * Renders at the bottom of Z=0 (inside [layerBackdrop] in AppRoot), so
 * chrome at Z=1 naturally samples wallpaper + page content together.
 */
@Composable
fun WallpaperGlassBackground(
    spec: BackgroundGlassConfig,
    content: @Composable () -> Unit,
) {
    val wallpaperBmp = rememberWallpaperBitmap()
    val isDark = isSystemInDarkTheme()
    val tintColor = if (isDark) {
        Color(spec.darkTintArgb).copy(alpha = spec.darkTintAlpha)
    } else {
        Color(spec.lightTintArgb).copy(alpha = spec.lightTintAlpha)
    }
    val density = LocalDensity.current

    val canvasBackdrop = rememberCanvasBackdrop {
        wallpaperBmp?.let { bmp ->
            // Scale bitmap to fill the canvas, preserving aspect ratio
            val src = androidx.compose.ui.geometry.Rect(
                offset = androidx.compose.ui.unit.Offset.Zero,
                size = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height).toSize()
            )
            val dst = androidx.compose.ui.geometry.Rect(
                offset = androidx.compose.ui.unit.Offset.Zero,
                size = size
            )
            drawImage(bmp, src, dst)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Z=0: Glass backdrop — wallpaper with blur + vibrancy + tint.
        // NO lens — full-screen lens creates symmetry crack at horizontal center.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = canvasBackdrop,
                    shape = { RectangleShape },
                    effects = {
                        if (spec.vibrancy) vibrancy()
                        with(density) { blur(spec.blurRadius.toPx()) }
                    },
                    onDrawSurface = {
                        // Use a minimal LiquidGlassSpec just for drawGlassTint
                        val glassSpec = LiquidGlassSpec(
                            cornerRadius = 0.dp,
                            shadowElevation = 0.dp,
                            blurRadius = spec.blurRadius,
                            vibrancy = spec.vibrancy,
                            lensAmount = 0.dp,
                            tint = tintColor,
                            tintAlpha = 1f,
                            highlightAlpha = spec.highlightAlpha,
                        )
                        drawGlassTint(glassSpec, drawEdgeStrokes = false)
                    },
                )
        )

        // Z=1: Actual page content on top of the frosted wallpaper
        content()
    }
}
```

---

### Task 4: Modify LiquidGlassTheme.kt

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\liquidglass\LiquidGlassTheme.kt`

- [ ] **Step 1: Wrap content inside WallpaperGlassBackground**

Replace the function body:
```kotlin
package com.smartvision.gallery.ui.liquidglass

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.smartvision.gallery.data.glass.BackgroundGlassConfig

/**
 * Root wrapper for screens that want Liquid Glass surfaces.
 *
 * Provides the in-content [LocalLiquidGlassBackdrop] — a static canvas
 * backdrop painted with the screen gradient — AND renders the system
 * wallpaper with full Liquid Glass physics as the app background.
 *
 * The wallpaper layer renders inside [layerBackdrop] in AppRoot (Z=0)
 * so chrome at Z=1 naturally samples wallpaper + page content together.
 */
@Composable
fun LiquidGlassTheme(
    backdrop: LiquidGlassBackdrop = LiquidGlassBackdrop.PhotosMosaic,
    backgroundSpec: BackgroundGlassConfig = BackgroundGlassConfig(),
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val brush = if (isDark) backdrop.dark else backdrop.light
    val canvasBackdrop = rememberCanvasBackdrop {
        drawRect(brush = brush)
    }

    CompositionLocalProvider(LocalLiquidGlassBackdrop provides canvasBackdrop) {
        WallpaperGlassBackground(spec = backgroundSpec) {
            content()
        }
    }
}
```

---

### Task 5: Remove solid backgrounds from all pages (batch)

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\search\SearchPage.kt:73`
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\album\AlbumListPage.kt:121`
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\album\AlbumDetailPage.kt:98`
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\trash\TrashPage.kt:63`
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\privacy\PrivacyVaultPage.kt:91`
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\cloud\CloudSyncPage.kt:80`
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\settings\CloudProviderPickerPage.kt:47`

All pages follow the same pattern. Example (SearchPage.kt lines 70-74):
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)  // ← REMOVE THIS LINE
    ) {
```

- [ ] **Step 1: SearchPage.kt** — Remove `.background(MaterialTheme.colorScheme.background)` from outer Column

Old:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
```
New:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
```

- [ ] **Step 2: AlbumListPage.kt** — Same pattern at line 121

Old:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
```
New:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
```

- [ ] **Step 3: AlbumDetailPage.kt** — Same pattern at line 98

Old:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
```
New:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
```

- [ ] **Step 4: TrashPage.kt** — Same pattern at line 63

Old:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
```
New:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
```

- [ ] **Step 5: PrivacyVaultPage.kt** — Same pattern at line 91

Old:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
```
New:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
```

- [ ] **Step 6: CloudSyncPage.kt** — Same pattern at line 80

Old:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
```
New:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
```

- [ ] **Step 7: CloudProviderPickerPage.kt** — Same pattern at line 47

Old:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
```
New:
```kotlin
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
```

---

### Task 6: Build & Install

- [ ] **Step 1: Build debug APK**

Run: `cd H:\workspace-minimaxcode\超级相册 && .\gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install on device**

Run: `adb install -r H:\workspace-minimaxcode\超级相册\app\build\outputs\apk\debug\app-debug.apk`
Expected: Success

- [ ] **Step 3: Launch and verify**

Verify:
- All page backgrounds show frosted wallpaper (blurred, tinted, vaguely visible)
- Tab bar, title bar, segmented control still sample content correctly
- Liquid glass surfaces (cards, chips, list rows) still render properly
- Dark/light themes apply correct tint
- Wallpaper change triggers refresh
- PhotoViewerPage keeps solid dark background
