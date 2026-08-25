# Wallpaper Glass Background Design

> Full-screen system wallpaper with iOS 26 Liquid Glass frosted effect as the app background.

**Goal:** Replace all solid `MaterialTheme.colorScheme.background` page backgrounds with the phone's system wallpaper, rendered through the same Kyant `drawBackdrop` pipeline (blur + vibrancy + drawGlassTint) used by tab bar and title bar — so the entire app background is semi-transparent frosted glass that "vaguely shows the desktop wallpaper."

**Architecture:** A new `WallpaperGlassBackground` composable renders at the bottom of Z=0 (inside `layerBackdrop(liquidBackdrop)`). It obtains the system wallpaper via `WallpaperManager`, injects it into a `rememberCanvasBackdrop` (Kyant CanvasBackdrop), and applies `drawBackdrop` with heavy blur + vibrancy + tint. Pages become transparent so the frosted wallpaper shows through. Chrome at Z=1 naturally samples wallpaper + content via the existing `liquidBackdrop`.

**Tech Stack:** Android Compose, Kyant Backdrop library, `WallpaperManager`, `RenderEffect` (GPU blur), `ImageBitmap`

---

## 1. New File: WallpaperProvider.kt

**Path:** `app/src/main/java/com/smartvision/gallery/ui/liquidglass/WallpaperProvider.kt`

Responsibility: obtain the system wallpaper as a Compose `ImageBitmap` and react to wallpaper changes.

```kotlin
@Composable
fun rememberWallpaperBitmap(): ImageBitmap? {
    val context = LocalContext.current
    val wm = WallpaperManager.getInstance(context)
    var bitmap by remember { mutableStateOf(wm.drawable?.toImageBitmap()) }

    DisposableEffect(Unit) {
        val listener = WallpaperManager.OnColorsChangedListener { _ ->
            bitmap = wm.drawable?.toImageBitmap()
        }
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { wm.removeOnColorsChangedListener(listener) }
    }
    return bitmap
}
```

Key decisions:
- `WallpaperManager.getDrawable()` returns the current system wallpaper drawable.
- `Drawable.toImageBitmap()` is an Android extension that converts any Drawable to `ImageBitmap`.
- The listener ensures the background refreshes when the user changes wallpaper via Settings.
- No scaling logic here; scaling happens in the CanvasBackdrop draw block.

---

## 2. New Composable: WallpaperGlassBackground

**Path:** `app/src/main/java/com/smartvision/gallery/ui/liquidglass/WallpaperGlassBackground.kt`

Responsibility: render the wallpaper through Kyant `drawBackdrop` with full Liquid Glass optical physics.

```kotlin
@Composable
fun WallpaperGlassBackground(
    spec: BackgroundGlassConfig,
    content: @Composable () -> Unit,
) {
    val wallpaperBmp = rememberWallpaperBitmap()
    val isDark = isSystemInDarkTheme()
    val tintColor = if (isDark) Color(spec.darkTintArgb).copy(alpha = spec.darkTintAlpha)
                    else Color(spec.lightTintArgb).copy(alpha = spec.lightTintAlpha)
    val glassSpec = LiquidGlassSpec(
        cornerRadius = 0.dp,           // full screen — no corners
        blurRadius = spec.blurRadius,
        vibrancy = spec.vibrancy,
        lensAmount = 0.dp,             // NO lens — full-screen lens creates symmetry crack
        tint = tintColor,
        tintAlpha = 1f,                // already baked into tintColor
        highlightAlpha = spec.highlightAlpha,
        shadowElevation = 0.dp,
    )

    val canvasBackdrop = rememberCanvasBackdrop {
        wallpaperBmp?.let { bmp ->
            // Scale bitmap to fill screen while preserving aspect ratio
            val src = Rect(0, 0, bmp.width, bmp.height)
            val dst = Rect(0, 0, size.width.toInt(), size.height.toInt())
            drawImage(bmp, src, dst)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Z=0: Glass backdrop — wallpaper with blur + vibrancy + tint
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
                    onDrawSurface = { drawGlassTint(glassSpec) },
                )
        )
        // Z=1: Actual page content on top
        content()
    }
}
```

Key decisions:
- **lensAmount = 0dp**: Full-screen lens creates a symmetry crack at the horizontal center (same phenomenon as the segmented control bar). The wallpaper background is a static subtle texture; lens distortion is unnecessary and harmful.
- **blurRadius = 48dp**: Heavy enough to make the wallpaper "vaguely visible" without overwhelming page content. Configurable via GlassConfig.
- **drawGlassTint**: Applies the same vertical gradient tint, white top highlight, and thin edge strokes used by all other Liquid Glass surfaces.
- **No shadow**: Full-screen surface doesn't cast shadows.

---

## 3. LiquidGlassTheme Integration

**Path:** `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LiquidGlassTheme.kt`

Current:
```kotlin
fun LiquidGlassTheme(backdrop: ..., content: ...) {
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(brush = brush) }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalLiquidGlassBackdrop provides canvasBackdrop) {
            content()
        }
    }
}
```

New:
```kotlin
fun LiquidGlassTheme(
    backdrop: LiquidGlassBackdrop = LiquidGlassBackdrop.PhotosMosaic,
    backgroundSpec: BackgroundGlassConfig = BackgroundGlassConfig(),
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val brush = if (isDark) backdrop.dark else backdrop.light
    val canvasBackdrop = rememberCanvasBackdrop { drawRect(brush = brush) }

    CompositionLocalProvider(LocalLiquidGlassBackdrop provides canvasBackdrop) {
        WallpaperGlassBackground(spec = backgroundSpec) {
            content()
        }
    }
}
```

Key change: `WallpaperGlassBackground` wraps the content inside LiquidGlassTheme. This means:
- The wallpaper glass is rendered **inside** `layerBackdrop(liquidBackdrop)` in AppRoot (at Z=0)
- Chrome at Z=1 samples both wallpaper + content through the same backdrop
- `LocalLiquidGlassBackdrop` (canvas gradient for in-content surfaces) is still provided for cards/rows that need it

---

## 4. GlassConfig Additions

**Path:** `app/src/main/java/com/smartvision/gallery/data/glass/GlassConfig.kt`

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

Add to `GlassConfig`:
```kotlin
data class GlassConfig(
    ...
    val background: BackgroundGlassConfig = BackgroundGlassConfig(),
)
```

---

## 5. Page Background Cleanup

Remove `Modifier.background(...)` from all non-viewer pages so the frosted wallpaper shows through.

| File | Remove |
|------|--------|
| `AlbumListPage.kt` | `.background(MaterialTheme.colorScheme.surface)` on outer Column |
| `AlbumDetailPage.kt` | solid background on outer container |
| `SearchPage.kt` | `.background(MaterialTheme.colorScheme.background)` |
| `SettingsPage.kt` | `.background(MaterialTheme.colorScheme.background)` |
| `TrashPage.kt` | solid background |
| `PrivacyVaultPage.kt` | solid background |
| `CloudSyncPage.kt` | solid background |
| `CloudProviderPickerPage.kt` | solid background |

Pages with in-content glass surfaces (iOSListSection, iOSListRow, LiquidGlassCard) already use `LocalLiquidGlassBackdrop` for their own glass rendering — the gap between them becomes transparent, showing the wallpaper glass.

**TimelinePage.kt:** Already transparent (fixed in previous session). No change needed.

**PhotoViewerPage.kt / PhotoEditorPage.kt:** Full-screen viewers keep a solid dark background for photo viewing accuracy. No change.

---

## 6. File Summary

| Action | File |
|--------|------|
| **Create** | `ui/liquidglass/WallpaperProvider.kt` — `rememberWallpaperBitmap()` |
| **Create** | `ui/liquidglass/WallpaperGlassBackground.kt` — `WallpaperGlassBackground()` composable |
| **Modify** | `ui/liquidglass/LiquidGlassTheme.kt` — integrate `WallpaperGlassBackground` |
| **Modify** | `data/glass/GlassConfig.kt` — add `BackgroundGlassConfig` |
| **Modify** | `ui/album/AlbumListPage.kt` — remove solid background |
| **Modify** | `ui/album/AlbumDetailPage.kt` — remove solid background |
| **Modify** | `ui/search/SearchPage.kt` — remove solid background |
| **Modify** | `ui/settings/SettingsPage.kt` — remove solid background |
| **Modify** | `ui/trash/TrashPage.kt` — remove solid background |
| **Modify** | `ui/privacy/PrivacyVaultPage.kt` — remove solid background |
| **Modify** | `ui/cloud/CloudSyncPage.kt` — remove solid background |
| **Modify** | `ui/settings/CloudProviderPickerPage.kt` — remove solid background |

No changes needed: `TimelinePage.kt` (already transparent), `PhotoViewerPage.kt` (keep dark), `PhotoEditorPage.kt` (keep dark).

---

## 7. Visual Behavior

| Scenario | Appearance |
|----------|-----------|
| Light theme, default wallpaper | Wallpaper visible through 48dp blur, semi-transparent white tint, faint top highlight. Page content floats above. |
| Dark theme | Same blur, darker tint (0.55 alpha). Content remains readable. |
| Wallpaper changes (Settings) | Background updates automatically via `OnColorsChangedListener`. |
| Scroll in TimelinePage | Grid content scrolls behind Z=1 chrome; chrome samples wallpaper + grid. |
| In-content glass cards | Cards use `LocalLiquidGlassBackdrop` (canvas gradient) — unchanged. Cards sit on the frosted wallpaper. |