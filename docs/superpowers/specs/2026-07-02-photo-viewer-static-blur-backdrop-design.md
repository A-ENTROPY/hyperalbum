# Photo Viewer Chrome — Static Blurred Photo Backdrop

> **Status:** Brainstorming approved by user 2026-07-02 (path B: static blurred photo bitmap).
> **Scope:** Restore real Liquid Glass physics on the photo viewer chrome without triggering
> ColorOS 16 RenderThread SIGSEGV.

## Context & Problem

**Round 8 regression:**
- Tried live `LayerBackdrop` + `Modifier.drawBackdrop` over the photo
- On realme ColorOS 16, the RenderEffect chain depth exceeded ~11 and the HWUI render thread
  crashed with `Fatal signal 11 (SIGSEGV) ... Cause: stack pointer is not in a rw map; likely
  due to stack overflow`. App died on tap-photo.
- Round 8b removed live backdrop sampling entirely. Result: flat white-translucent panels.
  User rejected: *"完全没有液态玻璃效果，只是一层白色半透明的bar！上下都是！必须要有完整的液态玻璃物理效果！而且button根本不显示（可能是字体颜色问题）"*

**Reference: existing iOS 26 Liquid Glass bars already ship in the app.**
- `LiquidGlassTopBar` (`LiquidGlassComponents.kt:313-342`) and `iOSButtonStyle.Glass`
  (`AppleComponents.kt:1180-1260`) both use `Modifier.drawBackdrop(... vibrancy() + blur() + lens(), drawGlassTint)`
  with `LocalLiquidGlassBackdrop` (canvas-based gradient — NOT the screen-capture variant).
- The viewer's problem is unique: the chrome lives *inside* the photo-pager subtree, so a
  live layer capture would re-enter the render tree.

**Sacred rule (MEMORY):** *"Liquid Glass effect is sacred — NEVER delete, strip, simplify, or
break the iOS 26 Liquid Glass visual effect"*. We must deliver real photo refraction through the
chrome, not a flat panel.

## Approach — Static Snapshot, Sampled Once Per Page

Sample the current photo **once** into a static `ImageBitmap`, blur it, and render that bitmap as
the chrome panel background with a dark translucent tint + vibrancy-like gradient overlay.

**Why this works on ColorOS 16:**
- Zero concurrent live render effect chains. The snapshot is a one-shot `Modifier.blur` on a
  small `Image`, captured into `GraphicsLayer.toImageBitmap()`.
- After capture, the chrome renders the bitmap through standard `drawImage` — the same
  low-level path as any image composable. No recursive RenderEffect layer.
- Per-page: one tiny blur (128×128 source) on a hidden GraphicsLayer. Cost is negligible.

**Why this still looks like real glass:**
- The bitmap IS the photo (slightly blurred). It refracts the actual photo content under the
  chrome, not a procedural gradient — matches iOS 26 visual intent.
- The dark tint + blur produces the characteristic "see-through glass with shadow" depth cue
  users expect from the iOS Photos viewer.
- Subtle vibrancy can be approximated via a vertical gradient overlay (light at top → dark at
  bottom) drawn on top of the bitmap — cheap, no live RenderEffect.

## File Structure

### Files to create

1. `app/src/main/java/com/smartvision/gallery/ui/viewer/BlurredPhotoBackdrop.kt`
   - Public API: `@Composable fun rememberBlurredPhotoBackdrop(uri: Uri?): ImageBitmap?`
   - Implementation:
     - `LaunchedEffect(uri)` triggers Coil load of current photo at `Size(128, 128)`
       (`allowHardware = false` so we can draw it into a software GraphicsLayer).
     - Hidden `Box(size(160.dp))` with `Modifier.blur(28.dp)` wraps the loaded bitmap.
     - `Modifier.drawWithContent` records the blurred draw into `rememberGraphicsLayer()`.
     - A second `LaunchedEffect(loadedBitmap)` waits one frame after bitmap arrives
       (delay ≈ 16 ms), reads `graphicsLayer.toImageBitmap()`, exposes it via `mutableStateOf`.
     - If `uri` is null (rare) or load fails, return null — chrome falls back to dark
       translucent panel only.

### Files to modify

2. `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt`
   - Add `photoBitmap: ImageBitmap?` parameter to `ViewerTopBarChrome` and
     `ViewerBottomBarChrome`.
   - Replace each panel's flat `Color.White.copy(alpha = 0.10f/0.18f)` background with:
     ```kotlin
     Modifier.drawWithCache {
         val bmp = photoBitmap
         onDrawBehind {
             if (bmp != null) {
                 // Scale small snapshot to panel size with linear filtering
                 drawImage(
                     image = bmp,
                     dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                     filterQuality = FilterQuality.Low,
                 )
             } else {
                 drawRect(Color.Black.copy(alpha = 0.30f))
             }
             // Dark translucent tint so white icons read clearly
             drawRect(Color.Black.copy(alpha = 0.30f))
             // Top-to-bottom vibrancy stand-in: subtle gradient overlay
             drawRect(
                 brush = Brush.verticalGradient(
                     0f to Color.White.copy(alpha = 0.10f),
                     1f to Color.Black.copy(alpha = 0.10f),
                 ),
             )
         }
     }
     ```
   - Keep `Modifier.blur(28.dp)` on the panel Box — Compose foundation's blur is local and
     does NOT enter the RenderEffect chain. (Used by `CapsuleActionIcon` already.)
   - Drop `Modifier.background(Color.White.copy(alpha = ...))` — `drawWithCache` replaces it.

3. `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt`
   - Add at the root of `ViewerScreen`:
     ```kotlin
     val photoBackdrop = rememberBlurredPhotoBackdrop(currentUri)
     ```
   - Pass `photoBackdrop` to `ViewerTopBarChrome` and `ViewerBottomBarChrome` calls.

### Files NOT to touch

- `app/src/main/java/com/smartvision/gallery/ui/liquidglass/*` — main app's existing Liquid Glass
  bars stay untouched. This viewer-side approach is self-contained.
- `InfoPanel.kt`, `SlideshowDialog.kt`, `DeleteConfirmDialog.kt`, `ShareSheet.kt` — modal
  surfaces, not chrome.
- `ZoomableImage.kt`, `VideoPlayerViewer.kt` — sit behind the chrome, no change needed.
- `Coil` ImageLoader configuration — use the existing singleton; the snapshot uses Coil's
  standard pipeline at `Size(128, 128)`, ~64× memory smaller than the original.

## Component & Data Flow

```
PhotoViewerActivity.onCreate
  └ setContent { SmartVisionTheme { ViewerScreen(...) } }

ViewerScreen
  ├ photoBackdrop = rememberBlurredPhotoBackdrop(currentUri)   ← ★ ImageBitmap? from snapshot
  ├ visibilityState = rememberChromeVisibilityState()
  ├ chromeBottomHeightPx = remember { mutableIntStateOf(0) }
  └ Surface(Color.Black) {
      Box(Modifier.fillMaxSize()) {
        ├ HorizontalPager { page -> ZoomableImage(uri) / VideoPlayerViewer }
        ├ LivePhotoOverlay / LiveBadgePill (unchanged)
        ├ Column { always-rendered chrome tree:
        │   ViewerTopBarChrome(photoBitmap = photoBackdrop, ...)   ← draws bitmap + dark tint
        │   Spacer(weight 1f)
        │   ViewerBottomBarChrome(photoBitmap = photoBackdrop, ...)
        │ }
        └ SnackbarHost(...)
      }
    }

rememberBlurredPhotoBackdrop(uri) internals:
  ├ LaunchedEffect(uri) → Coil load Size(128,128), allowHardware=false → loadedBitmap
  ├ Hidden Box(160.dp).blur(28.dp).drawWithContent { drawContent(); graphicsLayer.record {...} }
  ├ LaunchedEffect(loadedBitmap) → delay(16ms) → graphicsLayer.toImageBitmap() → result
  └ Returns result (null until snapshot ready)
```

**Gating rule (carried over from Round 8):** chrome composables MUST stay in the composition
tree at all times — never wrap in `if (visible) { ... }`. The `progress` Animatable drives the
show/hide spring; `visible` only gates `Modifier.clickable(enabled = visible)` so taps fall
through to the photo when chrome is hidden.

## Visual Spec

**Chrome panel — with `photoBackdrop` available:**
- Layer 1 (bottom): scaled-up blurred photo bitmap (`FilterQuality.Low`).
- Layer 2: `Color.Black.copy(alpha = 0.30f)` — dark tint over the bitmap. White icons read
  clearly. (This is the bug fix for *"button 根本不显示"* — flipping from white-translucent to
  black-translucent on top of a real bitmap makes the icons legible.)
- Layer 3: vertical gradient `Color.White(0.10f) → Color.Black(0.10f)` — cheap vibrancy
  stand-in matching the iOS 26 Photos look (top brighter, bottom darker).
- Layer 4: panel-level `Modifier.blur(28.dp)` for soft edges.
- Shadow: `4.dp` blur, alpha `0.10` black, offset `(0, 1)` — unchanged.

**Chrome panel — fallback when `photoBackdrop == null`:**
- Single `Color.Black.copy(alpha = 0.30f)` fill. Same dark-tint depth cue, no fake image.
- Shown only for the first ~16 ms after viewer opens (Coil load + blur takes one frame).

**Capsule buttons (`CapsuleActionIcon`) — unchanged.**
- `Modifier.background(Color.White.copy(alpha = 0.08f))` + local `Modifier.blur(16.dp)` works
  because white-tint capsule over dark-tint panel reads as a glassy icon button. Selected
  state stays at `alpha = 0.40f`. Icons stay `Color.White`. **No changes needed here.**

## Risks & Mitigations

**Risk 1: GraphicsLayer record timing.** Reading `graphicsLayer.toImageBitmap()` immediately
after `LaunchedEffect(loadedBitmap)` may yield an empty bitmap if Compose hasn't recorded yet.
Mitigation: `delay(16ms)` inside `LaunchedEffect` after bitmap arrives — waits for the next
frame's record pass. The chrome panel shows fallback dark tint for that single frame; visually
indistinguishable.

**Risk 2: Coil hardware bitmap incompatibility.** Coil returns hardware bitmaps by default;
`GraphicsLayer.record` requires software bitmaps. Mitigation: `allowHardware(false)` on the
ImageRequest. Slight memory cost — 128×128 ARGB_8888 = 64 KB, negligible.

**Risk 3: Pager preloading adjacent photos.** `HorizontalPager` keeps neighbouring pages in
memory. If we snapshot on every `currentUri` change, we get one snapshot per swipe — fine
(negligible cost). If we eagerly snapshot all pages, we eat N×64KB — still fine, but YAGNI.

**Risk 4: Live photo + video pages.** `currentUri` still resolves for those pages; Coil will
fail to decode the MOV into a 128×128 JPEG-sized bitmap. Mitigation: `try/catch` the Coil
result; on failure, `photoBitmap = null` and chrome uses fallback dark tint. No crash.

## Non-Goals

- Real-time refraction as user pinch-zooms the photo (would re-introduce live sampling — out).
- Animating the backdrop bitmap as the photo changes (current photo, current page → one
  snapshot. Swipe triggers a fresh snapshot on the next frame; the old bitmap stays as the
  chrome background until the new one arrives — visible for ≤16 ms, indistinguishable).
- Reordering or adding chrome buttons.
- Touching any other surface's Liquid Glass implementation.

## Verification

1. **Build:** `./gradlew :app:assembleDebug` succeeds with no new warnings.
2. **Install + smoke (handoff to user):**
   - Tap any photo in Timeline → chrome appears with photo pixels visibly refracted through
     the top/bottom bars (colors bleed from the photo into the chrome).
   - Buttons are clearly visible (white icons on dark-translucent bitmap — the white-on-white
     ghosting from Round 8b is gone).
   - Tap photo to toggle chrome → spring animation plays (show slides up + fades in, hide
     collapses + fades out).
   - Swipe to next photo → within ≤16 ms the chrome backdrop updates to the new photo's
     blurred colors.
   - Tap any chrome button → registers immediately, no eaten taps.
3. **Regression sweep:** rest of app (Timeline / Albums / Trash / Camera) visually unchanged.
   `git diff --stat` should show: this design doc + 3 files (1 new + 2 modified).
4. **No-SIGSEGV sweep:** open viewer 20× rapidly, swipe rapidly between pages — no crashes.
   This is the explicit regression gate for Round 8.

## Open Questions for Plan

- None. Implementation is bounded by Round 8's `ChromeVisibilityState` (no changes) and the
  reference `LiquidGlassTopBar` pattern (signature similarity, not source reuse).
