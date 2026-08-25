# Photo Viewer Chrome → iOS 26 Liquid Glass Polish — Design Spec

> **Status:** Brainstorming approved by user 2026-07-02.
> **Scope:** Pure visual polish. Button functionality layout unchanged.

## Context & Problem

**Pain points reported:**
- "菜单栏原生感太强了" — chrome feels too Material3 / Android-native, not iOS enough
- "简陋而且生硬" — feels crude and stiff (visual treatment + animation)

**Reference visual:** User has 5 reference images under `H:\workspace-minimaxcode\参考图片\` showing iOS 26 Liquid Glass tab bars with capsule pills on translucent surfaces — the same Liquid Glass treatment must apply to the photo viewer's chrome.

**Codebase state:**
- `PhotoViewerActivity.kt` is a standalone Activity (extracted in Round 7 to bypass a realme ColorOS RenderThread stack overflow in the main app's Liquid Glass render tree).
- Current chrome: `ViewerTopBar` + `ViewerBottomBar` use Material3 `IconButton` + `Outlined.MaterialIcons` (clearly Android-flavored).
- Auto-hide animation: `delay(3000)` + a Compose `Visibility` toggle — feels like a fade, no spring.
- Kyant `backdrop` 1.0.1 + `capsule` 2.1.3 are already wired into the main app's Liquid Glass stack; we can use `Modifier.layerBackdrop` / `drawBackdrop` here too even inside the standalone Activity (the backdrop state only needs to live within the same Composition tree).

**Sacred rule (MEMORY):** "Liquid Glass effect is sacred — NEVER delete, strip, simplify, or break the iOS 26 Liquid Glass visual effect". Photo viewer chrome being standalone must NOT regress this. We are *applying* the visual treatment, not bypassing it.

## Approach

**Single visual stack for the chrome:**

1. **Translucent surface** — top and bottom panels wrap in `LiquidGlassSurface` (existing `drawBackdrop + blur + vibrancy + tint`) with a true `Modifier.layerBackdrop` that samples the photo content below. This gives real photographic backdrop blur under the chrome, matching iOS 26's behavior where the chrome literally refracts the photo behind it.

2. **Capsule buttons** — replace each `IconButton`'s solid 48dp tap target with a capsule-shaped `drawBackdrop` surface for *active* states (favorite on, copy, etc.) and a near-invisible `clickable` modifier for *inactive* states. This matches the iOS Photos toolbar where taps register on the icon at ~44pt with subtle spring scale, not on a colored square.

3. **Lucide icons** — replace `androidx.compose.material.icons.outlined.*` with icons from the `com.composables:compose-lucide-core` library (SF-Symbols-style rounded line caps, thin/medium stroke weights). Each icon is exposed as a Kotlin composable (e.g. `Lucide.Heart`, `Lucide.Share2`, `Lucide.ArrowLeft`, `Lucide.Info`, `Lucide.Trash2`, `Lucide.Image`, `Lucide.MapPin`, `Lucide.EyeOff`, `Lucide.Play`) — no per-icon artifact needed, one core dependency covers all of them.

4. **Spring auto-hide** — replace `delay(3000) + boolean toggle` with an `Animatable<Float>` driven by `spring(stiffness = 380f, dampingRatio = 0.85f)`. Show slides up + fades in, hide collapses down + fades out. ~220 ms perceived time with a hint of overshoot — mimics iOS Photos' "settle" feel while staying stable under pinch-zoom (see Risk 2).

5. **No wire changes** — all current chrome callbacks (`onBack`, `onFavoriteClick`, `onShareClick`, `onEditClick`, `onInfoClick`, `onDeleteClick`, `onSlideshowClick`, `onSetWallpaperClick`, `onHideToVaultClick`, `onShowLocationClick`) keep the same signature. `InfoPanel`, `SlideshowDialog`, `DeleteConfirmDialog`, `ShareSheet` are not touched.

## File Structure

### Files to modify

1. `gradle/libs.versions.toml`
   - Add a single version coordinate: `lucideCompose = "1.0.0"`. **No upgrade** of `backdrop` or `capsule` — current versions (1.0.1, 2.1.3) are sufficient.
   - The `compose-lucide` library exposes every individual icon as a Kotlin composable function (e.g. `Lucide.Heart`, `Lucide.Share2`) inside a single artifact, so we only need one dependency: `com.composables:compose-lucide-core`.

2. `app/build.gradle.kts`
   - Add one line: `implementation("com.composables:compose-lucide-core:${libs.versions.lucideCompose.get()}")` — referenced through the version catalog (no hardcoded version string, no duplicate coordinate).

3. `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt`
   - **NEW** file. Contains:
     - `ViewerTopBarChrome(...)` — capsule-shape, 6 top actions on a single horizontal liquid-glass surface; always rendered in the tree, driven by `progress` (see gating rule below).
     - `ViewerBottomBarChrome(...)` — capsule-shape, 5 bottom action buttons on a single horizontal liquid-glass surface; same always-rendered rule.
     - `ChromeVisibilityState` — owns the auto-hide `Animatable<Float>` + spring animation + `LaunchedEffect` debouncing; exposes `progress: Float, visible: Boolean, show(), hide(), toggle()`. **Chrome composables MUST stay in the Composition tree at all times** — they read `progress` to drive `alpha` + `translateY`, and read `visible` only to gate `Modifier.clickable(enabled = visible)` so taps pass through to the photo when chrome is hidden. A conditional `if (visible) { chrome() }` would unmount the chrome on hide and skip the spring entirely, producing the same instant-disappear behavior as the current boolean toggle.
     - `CapsuleActionIcon(icon, label, onClick, selected, enabled)` — one reusable capsule button primitive.
     - All chrome composables accept `backdrop: Backdrop` (Kyant's interface; the concrete instance is `com.kyant.backdrop.backdrops.LayerBackdrop`, which callers allocate via `remember { LayerBackdrop() }` and pass in) and use it inside `Modifier.drawBackdrop`. Lucide icons render with stroke width 2f and 22.dp draw size to match the iOS Photos visual weight.

4. `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt`
   - In `ViewerScreen`:
     - Add `val photoBackdrop = remember { LayerBackdrop() }` at the root (one allocation, never recreated).
     - Wrap the existing `Surface` + root `Box` with `Modifier.layerBackdrop(photoBackdrop)` **unconditionally** — the photo (HorizontalPager contents) is what gets sampled. The backdrop modifier stays mounted for the lifetime of the viewer; conditional mount/unmount would remount the entire Pager subtree and trigger image reloads + Pager state loss, which is far worse than the buffer cost we're trying to mitigate (see Risk 1).
     - Remove the current `if (chromeVisible) { Column { ... } }` wrapper. Replace the chrome Column body with `ViewerTopBarChrome(backdrop = photoBackdrop, ...)` + `ViewerBottomBarChrome(backdrop = photoBackdrop, ...)` always rendered.
     - Each new chrome composable receives `backdrop = photoBackdrop`.
   - Replace the `LaunchedEffect(... ) { delay(3000); chromeVisible = false }` with the new `ChromeVisibilityState` instance (one shared between top + bottom so they always toggle together).
   - `coroutinescope.launch` for hide/show on demand, using `Animatable.snapTo` and `animateTo(spring)` from the new state.
   - **Live Photo overlay** (`LivePhotoOverlay`, line ~248) and the new bottom chrome must share the same `drawBackdrop` parent so they composite against the same photo sample — wrap both in a single `Modifier.drawBackdrop(...)` block. The Live Badge Pill (line ~258) stays put (it's a separate small composable, not a chrome element).
   - **Snackbar host** padding `bottom = 110.dp` (line ~425) is replaced by a dynamic measurement: capture the bottom chrome's measured height via `Modifier.onSizeChanged { chromeBottomHeightPx = it.height }` and pass `SnackbarHost(Modifier.padding(bottom = chromeBottomHeightDp + 12.dp))` so the snackbar always sits above the chrome regardless of font scale or future chrome tweaks.

### Files NOT to touch

- `app/src/main/java/com/smartvision/gallery/ui/liquidglass/*` — the main app's Liquid Glass rendering stack must stay untouched. Reuse `LiquidGlassSurface`, `LiquidGlassSpec`, `ContinuousCapsule` from Kyant directly; do not modify those Compose components.
- `InfoPanel.kt`, `SlideshowDialog.kt`, `DeleteConfirmDialog.kt`, `ShareSheet.kt` — already inside the chrome flow but are dialogs/sheets, not chrome. They keep their Material3 default chrome because they are modal surfaces (out of visual scope).
- `ZoomableImage.kt` (file-private in PhotoViewerActivity.kt), `VideoPlayerViewer.kt` — no changes; they sit *behind* the new chrome.

## Component & Data Flow

```
PhotoViewerActivity.onCreate
  └ setContent { SmartVisionTheme { ViewerScreen(...) } }

ViewerScreen
  ├ photoBackdrop = remember { LayerBackdrop() }                       ← ★ allocated once, never recreated
  ├ visibilityState = rememberChromeVisibilityState()                  ← owns Animatable<Float>
  ├ chromeBottomHeightPx = remember { mutableIntStateOf(0) }
  └ Surface(Color.Black) {
      Box(Modifier.fillMaxSize().layerBackdrop(photoBackdrop)) {       ← ★ photo content writes to backdrop; ALWAYS mounted
        ├ HorizontalPager { page -> ZoomableImage(uri) or VideoPlayerViewer }
        ├ Box(Modifier.fillMaxSize()) {                                 ← chrome container, always in tree
        │   Column {
        │     ViewerTopBarChrome(                                       ← progress drives alpha + translateY
        │       progress = visibilityState.progress,                    ← visible is read internally for clickable()
        │       backdrop = photoBackdrop, ...
        │     )
        │     Spacer(weight 1f)
        │     ViewerBottomBarChrome(
        │       progress = visibilityState.progress,
        │       backdrop = photoBackdrop,
        │       onHeightMeasured = { chromeBottomHeightPx = it },       ← feeds Snackbar padding
        │       ...
        │     )
        │   }
        │ }
        ├ SnackbarHost(Modifier.padding(bottom = with(density) { chromeBottomHeightPx.toDp() } + 12.dp))
        └ LiveBadgePill / overlays (unchanged)
      }
    }
```

**Gating rule (critical):** chrome composables are NEVER wrapped in `if (visible) { ... }`. They are always rendered; `progress` drives `alpha` and `translateY`, and `visible` only toggles `Modifier.clickable(enabled = visible)` so taps fall through to the photo when chrome is hidden. This guarantees the spring hide animation plays to completion.

**Spring show/hide timing:**
- Show: `0 → 1` over `~220ms`, `spring(380f, 0.85f)` — small overshoot at top
- Hide: `1 → 0` over `~180ms`, `spring(380f, 1.0f)` — no overshoot, no bounce
- Translate-Y: `+32dp → 0dp` (top), `−32dp → 0dp` (bottom) — chrome slides in from its anchored edge
- Alpha: `0 → 1` linear on show, `1 → 0` linear on hide
- Triggered by single tap on photo, by any button touch (resets the 3s timer), by dialog open (cancel), by dialog close (restart).

## Visual Spec

**Chrome surface (`ViewerTopBarChrome` + `ViewerBottomBarChrome` shared):**
- Shape: `RoundedCornerShape(28.dp)` for the full-width translucent panel (chosen path).
- Effect: `blur(radius = 28.dp)` + `vibrancy()` + tint alpha 0.18 white (light theme) or 0.10 white (dark).
- Padding: `12.dp` vertical for the row, `8.dp` inner padding for buttons.
- Shadow: `4.dp` blur, alpha 0.10 black, offset (0, 1).

**Capsule button (per icon):**
- Active state (favorite on, etc.): `Modifier.drawBackdrop(...).clip(CircleShape)` with `tintAlpha = 0.40`, foreground icon stroke 2 dp, primary color tint.
- Inactive state: very low alpha backdrop tint (≈0.08 white) for legibility on light photos, icon stroke 1.75 dp, foreground `Color.White` (dark photo) or `MaterialTheme.colorScheme.onSurface` (light). Hover/press ripple replaced by `Modifier.scale(scale)` driven by `pressInteraction` state (no Material3 ripple).
- Hit target: 44.dp (Apple HIG) — physical tap area is 44x44 dp even when icon is 22 dp.

**Action mapping (kept identical to current code):**
- Top: `←` back · title `"N / total"` · `slideshow` · `wallpaper` · `hide` · `location`. All 6 buttons visible — capsule treatment only, no overflow menu (scope = pure visual polish).
- Bottom: `♥ favorite` · `↑ share` · `✎ edit` · `ⓘ info` · `🗑 delete`. Same as current.

## Risks & Mitigations

**Risk 1: `layerBackdrop` perf cost.** Wrapping the photo root in `layerBackdrop` causes Kyant to render the underlying graphics layer into an offscreen buffer every frame the buffer is invalidated. On a 6-min video-watching slideshow this could burn CPU. Mitigation: **keep `layerBackdrop` mounted unconditionally on the root Box** (do NOT conditionally mount/unmount it — that would remount the entire Pager subtree, trigger image reloads, and lose Pager state, which is much worse than the buffer cost). Instead, defer the actual sampling: when chrome is hidden, skip the per-frame invalidate by either (a) reading `progress > 0f` inside the chrome's `drawBackdrop` block so no drawBackdrop draw calls fire while hidden, or (b) relying on `LayerBackdrop`'s own lazy buffer-allocation behavior when nothing is reading it. Buffer stays warm only while chrome is visible.

**Risk 2: spring overshoot at >5x zoom.** When the user pinch-zooms the photo, the `contentTransformation` updates come through the same Modifier tree. Spring overshoot on chrome Y-translation under zoom could look jumpy. Mitigation: spring `dampingRatio = 0.85f` — slightly underdamped for a hint of overshoot but well below the resonance that would amplify zoom-induced jitter. Revisit after device test on actual zoomed content.

**Risk 3: PhotoViewerActivity's standalone render tree may reject `layerBackdrop` on ColorOS 16.** The original bug was RenderThread stack overflow on heavy blur in the main app's render tree — but the standalone Activity should be safe because it's its own HWUI context. Mitigation: `LayerBackdrop` is the same import already used in `LiquidGlassLens.kt` (line 43: `import com.kyant.backdrop.backdrops.LayerBackdrop`) — same library version, same render path semantics. If a runtime regression occurs, fall back to a static `BlurEffect`-based surface (no live backdrop sampling — visually degrades to a flat translucent panel, the photo no longer refracts through the chrome, but the chrome still looks glassy rather than native-Material).

**Risk 4: Lucide stroke width vs Material Icons outline weight.** Lucide's default "thin" weight may render visibly lighter than current Material Icons. Mitigation: lock stroke width to 2.0f for all viewer icons (Material Icons at 24dp render at ~2.0px stroke). Compare side-by-side on device before shipping.

## Non-Goals

- Reordering or adding chrome buttons (info panel, share sheet, slideshow dialog, delete confirm).
- Touching the InfoPanel, SlideshowDialog, DeleteConfirmDialog, or ShareSheet chrome (these are modal surfaces, not chrome).
- Changing the TopBarVariant or any other component elsewhere in the app.
- Migrating to a different Liquid Glass library (we keep Kyant).
- Adding new "swipe-down to dismiss viewer" gesture behavior — preserve current behavior per scope.

## Verification

1. **Compile gate:** `./gradlew :app:assembleDebug` succeeds without warnings about deprecation or missing imports.
2. **Unit gate:** no new unit tests (visual polish); existing test suite still passes.
3. **Device gate:** handoff to user for visual verification on `5ddfea15`. User checks:
   - Chrome slides/fades with overshoot when tapped.
   - Top/bottom bars visibly sample the underlying photo (you can see colors from the photo bleeding into the chrome).
   - Buttons feel responsive (44dp hit area, scale-on-press, no Material ripple).
   - On a 3-photo slideshow, chrome auto-hides smoothly without frame drops.
   - LivePhoto overlay still works above the new bottom chrome.
   - snackbar above bottom chrome; back gesture still returns from viewer.
4. **Regression sweep:** the rest of the app (Timeline page, Albums page, Trash page, etc.) MUST be visually unchanged. The `git diff --stat` against this branch should show: `ViewerChrome.kt` new + `PhotoViewerActivity.kt` modified + `build.gradle.kts` modified + `libs.versions.toml` modified. No other files.
5. **Sacred Liquid Glass grep gate:** `grep -r "iOSLiquidTabBar\|liquidglass.LiquidGlassLens" app/src/main` must match the **same** line count before and after the change (proves main app's Liquid Glass architecture wasn't touched).

## Open Questions for Plan

- Snackbar bottom padding is already locked in to **dynamic measurement** (`onSizeChanged` captures the bottom chrome height; snackbar pads `chromeBottomHeight + 12.dp`). No further decision needed.
