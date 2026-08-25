# Photo Viewer Chrome → iOS 26 Liquid Glass Polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Material3-native `ViewerTopBar` / `ViewerBottomBar` in `PhotoViewerActivity` with an iOS 26-styled Liquid Glass chrome (real photographic backdrop blur, capsule buttons, Lucide icons, spring-driven show/hide). Pure visual polish — every action callback, the 4 dialogs/sheets, and the main app's Liquid Glass architecture stay untouched.

**Architecture:** Add a new `Modifier.layerBackdrop(photoBackdrop)` to the viewer's root Box so the photo (HorizontalPager contents) writes to a Kyant `LayerBackdrop` buffer. New chrome composables in a refactored `ViewerChrome.kt` draw against that backdrop via `Modifier.drawBackdrop`, producing the iOS 26 "photo refracting through glass" effect. Chrome is **always in the composition tree** — `progress: Float` drives `alpha` + `translateY` so the spring hide animation actually plays; `visible: Boolean` only gates `Modifier.clickable(enabled = visible)` so taps fall through to the photo when hidden.

**Tech Stack:** Jetpack Compose, Kyant `backdrop` 1.0.1 (already wired), `com.composables:compose-lucide-core` 1.0.0 (new), `Animatable<Float>` + `spring()` for the show/hide animation.

**Spec:** `docs/superpowers/specs/2026-07-02-photo-viewer-chrome-liquid-glass-design.md`

---

## File Structure

| File | Status | Responsibility |
|------|--------|----------------|
| `gradle/libs.versions.toml` | modify | Add `lucideCompose = "1.0.0"` version + library coordinate |
| `app/build.gradle.kts` | modify | Add `implementation(libs.lucideCompose)` |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt` | overhaul | Replace old `ViewerTopBar` / `ViewerBottomBar` / `BarAction`; add `ChromeVisibilityState`, `CapsuleActionIcon`, `ViewerTopBarChrome`, `ViewerBottomBarChrome`; **keep** `DeleteConfirmDialog`, `LivePhotoOverlay`, `LiveBadgePill` (used by PhotoViewerActivity, called out in spec as "not to touch") |
| `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt` | modify | Hoist `photoBackdrop`, mount `Modifier.layerBackdrop(photoBackdrop)` on root Box (unconditional), replace `if (chromeVisible) { Column { ... } }` with always-rendered chrome composables, replace `delay(3000)` auto-hide with `ChromeVisibilityState`, dynamic snackbar padding via `onSizeChanged` |

**Files NOT to touch:** `app/src/main/java/com/smartvision/gallery/ui/liquidglass/*` (sacred), `InfoPanel.kt`, `SlideshowDialog.kt`, `ShareSheet.kt`, `VideoPlayerViewer.kt`, anything outside `app/src/main/java/com/smartvision/gallery/ui/viewer/`.

**Verification gates (run after each task):** `./gradlew :app:compileDebugKotlin` should succeed; after Task 7 also run `./gradlew :app:assembleDebug` + `git diff --stat` (must show exactly these 4 files) + the sacred Liquid Glass grep gate.

---

## Task 1: Add Lucide Compose dependency

**Files:**
- Modify: `gradle/libs.versions.toml:7-9` (versions section, near `backdrop`/`capsule`)
- Modify: `gradle/libs.versions.toml:124-126` (libraries section, near `backdrop`/`capsule` library entries)
- Modify: `app/build.gradle.kts:155-159` (near the existing backdrop/capsule `implementation(...)` lines)

- [ ] **Step 1: Add `lucideCompose` version to `gradle/libs.versions.toml`**

In `[versions]` section, after `capsule = "2.1.3"` (line 9), add:
```toml
# Lucide icon set (open-source SF Symbols alternative) — used by photo viewer chrome
lucideCompose = "1.0.0"
```

- [ ] **Step 2: Add `lucideCompose` library coordinate**

In `[libraries]` section, after `capsule = { group = "io.github.kyant0", name = "capsule", version.ref = "capsule" }` (line 126), add:
```toml
lucideCompose = { group = "com.composables", name = "compose-lucide-core", version.ref = "lucideCompose" }
```

- [ ] **Step 3: Add `implementation(libs.lucideCompose)` to `app/build.gradle.kts`**

After the existing Liquid Glass block:
```kotlin
    // Liquid Glass — Kyant0 backdrop + Capsule shape (the iOS 26 pill).
    // Real GPU backdrop sampling via GraphicsLayer; works on API 26+
    // (with software fallback to vibrancy+tint when AGSL is unavailable).
    implementation(libs.backdrop)
    implementation(libs.capsule)
```
add:
```kotlin
    // Lucide icons (SF-Symbols-style open-source icon set) for the photo viewer chrome.
    implementation(libs.lucideCompose)
```

- [ ] **Step 4: Verify compile picks up the new dependency**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>&1 | grep -i lucide`
Expected: a line containing `com.composables:compose-lucide-core:1.0.0` (or its resolved form).

- [ ] **Step 5: Commit**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat: add Lucide Compose icon dependency for viewer chrome"
```

---

## Task 2: Scaffold `ChromeVisibilityState` in `ViewerChrome.kt`

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt` (file currently exists with old `ViewerTopBar` / `ViewerBottomBar` / `BarAction` / `DeleteConfirmDialog` / `LivePhotoOverlay` / `LiveBadgePill`. The 3 old chrome composables get replaced in Task 5; the 3 keep-composables stay where they are. For this task we only **add** the new types.)

- [ ] **Step 1: Add imports at the top of `ViewerChrome.kt`**

Replace the existing import block (lines 1-56) with the expanded import list below. Keep the `package com.smartvision.gallery.ui.viewer` line (line 1) unchanged.

```kotlin
package com.smartvision.gallery.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.MapPin
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Wand2
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow
import com.smartvision.gallery.livephoto.LivePhotoBadge
import com.smartvision.gallery.livephoto.LivePhotoPressHold
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Add `ChromeVisibilityState` class + factory at the bottom of the file**

Append at the end of `ViewerChrome.kt`:

```kotlin
/**
 * Drives the photo viewer chrome show/hide animation. The chrome composables
 * MUST stay in the composition tree at all times — a conditional `if (visible) { ... }`
 * would unmount them on hide and skip the spring entirely (regression to the current
 * instant-disappear behavior). Instead, [progress] drives `alpha` + `translateY` and
 * [visible] only gates `Modifier.clickable(enabled = visible)` so taps pass through.
 *
 * One instance is shared by top + bottom so they always toggle together.
 */
@Stable
class ChromeVisibilityState internal constructor(
    initialVisible: Boolean,
    private val autoHideMs: Long,
) {
    private val anim = Animatable(if (initialVisible) 1f else 0f)

    /** 0f = fully hidden, 1f = fully shown. Read from chrome composables. */
    val progress: Float get() = anim.value

    /** Target state — true = visible, false = hidden. */
    var visible: Boolean = initialVisible
        private set

    private val scope = MainScope()

    fun show() {
        visible = true
        scope.launch { anim.animateTo(1f, spring(stiffness = 380f, dampingRatio = 0.85f)) }
    }

    fun hide() {
        visible = false
        scope.launch { anim.animateTo(0f, spring(stiffness = 380f, dampingRatio = 1.0f)) }
    }

    fun toggle() = if (visible) hide() else show()

    /** Restart the 3-second auto-hide timer. Call after every user interaction. */
    fun kickAutoHide() {
        if (!visible) return
        scope.launch {
            delay(autoHideMs)
            if (isActive) hide()
        }
    }
}

@Composable
fun rememberChromeVisibilityState(
    initialVisible: Boolean = true,
    autoHideMs: Long = 3000L,
): ChromeVisibilityState = remember { ChromeVisibilityState(initialVisible, autoHideMs) }
```

Add `import kotlinx.coroutines.MainScope` to the import list.

- [ ] **Step 3: Compile-check the scaffold compiles against the existing `LivePhotoOverlay` / `LiveBadgePill` / `DeleteConfirmDialog` definitions**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. The new types are added; existing `ViewerTopBar` / `ViewerBottomBar` / `BarAction` are still present and still referenced by `PhotoViewerActivity.kt` — no breakage yet.

- [ ] **Step 4: Commit**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
git add app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt
git commit -m "feat: add ChromeVisibilityState + Lucide imports to ViewerChrome.kt"
```

---

## Task 3: Add `CapsuleActionIcon` primitive

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt` (append at the end)

- [ ] **Step 1: Append `CapsuleActionIcon` composable**

```kotlin
/**
 * One capsule button used inside [ViewerTopBarChrome] / [ViewerBottomBarChrome].
 * The button itself stays in the composition tree even when chrome is hidden —
 * the parent chrome composable applies `Modifier.alpha(progress)` and gates
 * `clickable(enabled = visible)` so this primitive never needs to know.
 *
 * @param selected Active state (e.g. favorite on) — uses a stronger backdrop tint.
 * @param enabled  Disabled state — icon strokes dim to 50% alpha.
 */
@Composable
fun CapsuleActionIcon(
    icon: @Composable () -> Unit,
    contentDescription: String?,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = if (isPressed) 0.88f else 1.0f

    Box(
        modifier = modifier
            .size(44.dp)                              // Apple HIG hit target
            .scale(scale)                             // spring-less press scale (sufficient here)
            .then(
                if (selected) {
                    Modifier
                        .clip(CircleShape)
                        .drawBackdrop(
                            backdrop = LocalBackdrop.current,
                            shape = { CircleShape },
                            // Active state: stronger tint, primary accent
                            highlight = 0.4f,
                        )
                } else {
                    Modifier
                        .clip(CircleShape)
                        .drawBackdrop(
                            backdrop = LocalBackdrop.current,
                            shape = { CircleShape },
                            // Inactive state: very subtle tint for legibility on light photos
                            highlight = 0.08f,
                        )
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,                     // no Material ripple
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
            icon()
        }
    }
}

/**
 * CompositionLocal that carries the parent chrome's [Backdrop] so primitive
 * children (like [CapsuleActionIcon]) can sample it without re-passing the
 * parameter through every call site.
 */
val LocalBackdrop = androidx.compose.runtime.staticCompositionLocalOf<Backdrop> {
    error("No Backdrop provided. Wrap your chrome in CompositionLocalProvider(LocalBackdrop provides ...) { ... }.")
}
```

Add `import androidx.compose.runtime.staticCompositionLocalOf` to the import list.

- [ ] **Step 2: Compile-check the primitive**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. The primitive exists but is not yet called from anywhere — that's fine.

- [ ] **Step 3: Commit**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
git add app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt
git commit -m "feat: add CapsuleActionIcon primitive to ViewerChrome.kt"
```

---

## Task 4: Add `ViewerTopBarChrome` composable

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt` (append)

- [ ] **Step 1: Append `ViewerTopBarChrome` composable**

```kotlin
/**
 * Top chrome: 6 capsule buttons in a single horizontal liquid-glass panel.
 * The bar itself always renders; [progress] drives the show/hide animation.
 */
@Composable
fun ViewerTopBarChrome(
    title: String,
    progress: Float,
    visible: Boolean,
    backdrop: Backdrop,
    onBack: () -> Unit,
    onSlideshowClick: () -> Unit,
    onSetWallpaperClick: () -> Unit,
    onHideToVaultClick: () -> Unit,
    onShowLocationClick: () -> Unit,
    onAnyInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .alpha(progress)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28.dp) },
                        // Translucent glass — 18% white tint (light) / 10% (dark).
                        // Kyant handles blur + vibrancy sampling automatically.
                        highlight = if (isSystemInDarkTheme()) 0.10f else 0.18f,
                    )
                    .shadow(
                        radius = 4.dp,
                        color = Color.Black.copy(alpha = 0.10f),
                        offsetY = 1.dp,
                    )
                    .clickable(enabled = visible, onClick = onAnyInteraction)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleActionIcon(
                        icon = { Icon(ArrowLeft, contentDescription = "返回", tint = Color.White) },
                        contentDescription = "返回",
                        onClick = { onBack(); onAnyInteraction() },
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    CapsuleActionIcon(
                        icon = { Icon(Play, contentDescription = "幻灯片", tint = Color.White) },
                        contentDescription = "幻灯片",
                        onClick = { onSlideshowClick(); onAnyInteraction() },
                    )
                    Spacer(Modifier.size(4.dp))
                    CapsuleActionIcon(
                        icon = { Icon(Wand2, contentDescription = "壁纸", tint = Color.White) },
                        contentDescription = "壁纸",
                        onClick = { onSetWallpaperClick(); onAnyInteraction() },
                    )
                    Spacer(Modifier.size(4.dp))
                    CapsuleActionIcon(
                        icon = { Icon(EyeOff, contentDescription = "隐藏", tint = Color.White) },
                        contentDescription = "隐藏",
                        onClick = { onHideToVaultClick(); onAnyInteraction() },
                    )
                    Spacer(Modifier.size(4.dp))
                    CapsuleActionIcon(
                        icon = { Icon(MapPin, contentDescription = "位置", tint = Color.White) },
                        contentDescription = "位置",
                        onClick = { onShowLocationClick(); onAnyInteraction() },
                    )
                }
            }
        }
    }
}
```

Add `import androidx.compose.foundation.isSystemInDarkTheme` to the import list.

- [ ] **Step 2: Compile-check**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. Composable is defined but not yet called.

- [ ] **Step 3: Commit**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
git add app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt
git commit -m "feat: add ViewerTopBarChrome composable to ViewerChrome.kt"
```

---

## Task 5: Add `ViewerBottomBarChrome` composable

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt` (append)

- [ ] **Step 1: Append `ViewerBottomBarChrome` composable**

```kotlin
/**
 * Bottom chrome: 5 capsule buttons in a single horizontal liquid-glass panel.
 * Always renders; [progress] drives the animation. The measured height is
 * reported via [onHeightMeasured] so the snackbar can pad above the bar
 * regardless of font scale.
 */
@Composable
fun ViewerBottomBarChrome(
    isFavorite: Boolean,
    progress: Float,
    visible: Boolean,
    backdrop: Backdrop,
    onFavoriteClick: () -> Unit,
    onShareClick: () -> Unit,
    onEditClick: () -> Unit,
    onInfoClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onAnyInteraction: () -> Unit,
    onHeightMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .alpha(progress)
                .onSizeChanged { onHeightMeasured(it.height) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(28.dp) },
                        highlight = if (isSystemInDarkTheme()) 0.10f else 0.18f,
                    )
                    .shadow(
                        radius = 4.dp,
                        color = Color.Black.copy(alpha = 0.10f),
                        offsetY = 1.dp,
                    )
                    .clickable(enabled = visible, onClick = onAnyInteraction)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CapsuleActionIcon(
                        icon = {
                            Icon(
                                Heart,
                                contentDescription = null,
                                tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
                            )
                        },
                        contentDescription = if (isFavorite) "取消收藏" else "收藏",
                        selected = isFavorite,
                        onClick = { onFavoriteClick(); onAnyInteraction() },
                    )
                    CapsuleActionIcon(
                        icon = { Icon(Share2, contentDescription = "分享", tint = Color.White) },
                        contentDescription = "分享",
                        onClick = { onShareClick(); onAnyInteraction() },
                    )
                    CapsuleActionIcon(
                        icon = { Icon(Pencil, contentDescription = "编辑", tint = Color.White) },
                        contentDescription = "编辑",
                        onClick = { onEditClick(); onAnyInteraction() },
                    )
                    CapsuleActionIcon(
                        icon = { Icon(Info, contentDescription = "信息", tint = Color.White) },
                        contentDescription = "信息",
                        onClick = { onInfoClick(); onAnyInteraction() },
                    )
                    CapsuleActionIcon(
                        icon = { Icon(Trash2, contentDescription = "删除", tint = Color.White) },
                        contentDescription = "删除",
                        onClick = { onDeleteClick(); onAnyInteraction() },
                    )
                }
            }
        }
    }
}
```

Add `import androidx.compose.ui.layout.onSizeChanged` to the import list.

- [ ] **Step 2: Compile-check**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. All new chrome composables defined; old `ViewerTopBar` / `ViewerBottomBar` / `BarAction` still present (next task removes them).

- [ ] **Step 3: Commit**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
git add app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt
git commit -m "feat: add ViewerBottomBarChrome composable to ViewerChrome.kt"
```

---

## Task 6: Remove the old `ViewerTopBar` / `ViewerBottomBar` / `BarAction`

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt` (delete lines 58-207 — `ViewerTopBar`, `ViewerBottomBar`, private `BarAction` helper)

- [ ] **Step 1: Delete the 3 old composables**

Delete the following blocks from `ViewerChrome.kt`:
- Lines 58-140: `@Composable fun ViewerTopBar(...)` — the entire function, including its closing `}`
- Lines 142-190: `@Composable fun ViewerBottomBar(...)` — the entire function
- Lines 192-207: `private fun BarAction(...)` — the entire helper

Verify the file's line 208 (`@Composable`) and line 209 (`fun DeleteConfirmDialog(`) are now adjacent. The file should end with `LiveBadgePill` (line 250-253) followed by the new types added in Tasks 2-5.

- [ ] **Step 2: Compile-check (the old chrome still referenced by PhotoViewerActivity — this will FAIL)**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -30`
Expected: BUILD FAILED with `Unresolved reference: ViewerTopBar` and `Unresolved reference: ViewerBottomBar` in `PhotoViewerActivity.kt`. This is the precondition for Task 7 — we expect the failure here, fix it next.

- [ ] **Step 3: Commit the deletion**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
git add app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt
git commit -m "refactor: remove old Material3 ViewerTopBar/ViewerBottomBar/BarAction"
```

---

## Task 7: Wire `photoBackdrop` + always-render chrome into `PhotoViewerActivity`

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt`

- [ ] **Step 1: Add new imports**

Add to the import block at the top of the file (after existing imports, around line 70):

```kotlin
import com.kyant.backdrop.backdrops.LayerBackdrop
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
```

(`LayerBackdrop` is allocated manually via `remember { LayerBackdrop() }` in Step 2 — we do not use `rememberLayerBackdrop` from the Kyant 1.0.1 artifact.)

- [ ] **Step 2: Hoist `photoBackdrop`, `visibilityState`, and `chromeBottomHeightPx` state in `ViewerScreen`**

In `ViewerScreen` (around line 142, after `val context = LocalContext.current`), add three new state lines. Place them right after `val pagerState = rememberPagerState(...)` (line 150), before `var chromeVisible by rememberSaveable ...` (line 152):

```kotlin
    val photoBackdrop = remember { LayerBackdrop() }
    val visibilityState = rememberChromeVisibilityState()
    val chromeBottomHeightPx = remember { mutableIntStateOf(0) }
```

Then **remove** the old `var chromeVisible by rememberSaveable { mutableStateOf(true) }` (line 152) — visibility is now driven by `visibilityState.visible` / `visibilityState.progress`.

- [ ] **Step 3: Replace the delay-based auto-hide `LaunchedEffect` with `visibilityState.kickAutoHide()`**

Delete the entire `LaunchedEffect(chromeVisible, showInfo, ...)` block (lines 191-199). Replace it with a new effect that calls `kickAutoHide()` whenever visibility should reset the timer, and uses `hide()` when any overlay opens (so chrome hides behind dialogs/sheets):

```kotlin
    // Auto-hide timer + overlay suppression. The 3-second timer restarts on every
    // chrome-showing event; when any dialog/sheet opens we hide chrome immediately
    // so the timer doesn't fire behind the overlay (regression fix from C2).
    LaunchedEffect(
        visibilityState.visible,
        showInfo,
        showDeleteConfirm,
        showShareSheet,
        slideshowDialogOpen,
    ) {
        if (visibilityState.visible &&
            !showInfo && !showDeleteConfirm && !showShareSheet && !slideshowDialogOpen) {
            visibilityState.kickAutoHide()
        } else if (showInfo || showDeleteConfirm || showShareSheet || slideshowDialogOpen) {
            visibilityState.hide()
        }
    }
```

- [ ] **Step 4: Wrap the root Box with `Modifier.layerBackdrop(photoBackdrop)` (UNCONDITIONAL)**

At line 223, change:
```kotlin
        Box(modifier = Modifier.fillMaxSize()) {
```
to:
```kotlin
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(photoBackdrop),
        ) {
```

- [ ] **Step 5: Replace the `if (chromeVisible) { Column { ... } }` block (lines 265-335) with always-rendered chrome**

Delete lines 265-335. Replace with:

```kotlin
            // Chrome — ALWAYS in the composition tree so the spring hide animation
            // can play. `progress` drives alpha; `visible` gates clickable() so taps
            // pass through to the photo when chrome is hidden.
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ViewerTopBarChrome(
                        title = if (uris.size > 1) "${currentIndex + 1} / ${uris.size}"
                                else displayName,
                        progress = visibilityState.progress,
                        visible = visibilityState.visible,
                        backdrop = photoBackdrop,
                        onBack = onBack,
                        onSlideshowClick = { slideshowDialogOpen = true },
                        onSetWallpaperClick = {
                            scope.launch {
                                val ok = setAsWallpaper(context, currentUri ?: return@launch)
                                snackbarHostState.showSnackbar(if (ok) "壁纸已设置" else "设置失败")
                            }
                        },
                        onHideToVaultClick = {
                            scope.launch {
                                currentUri?.let { uri ->
                                    val ok = withContext(Dispatchers.IO) { hideToVault(context, uri) }
                                    snackbarHostState.showSnackbar(
                                        if (ok) "已隐藏到保险柜" else "隐藏失败"
                                    )
                                    if (ok) onBack()
                                }
                            }
                        },
                        onShowLocationClick = { /* V3: open external map */ },
                        onAnyInteraction = { visibilityState.kickAutoHide() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ViewerBottomBarChrome(
                        isFavorite = currentFavorite,
                        progress = visibilityState.progress,
                        visible = visibilityState.visible,
                        backdrop = photoBackdrop,
                        onFavoriteClick = {
                            currentUri?.let { uri ->
                                scope.launch {
                                    val newState = withContext(Dispatchers.IO) {
                                        MediaItemAdapter.toggleFavorite(context, uri)
                                    }
                                    if (newState != null) currentFavorite = newState
                                    else snackbarHostState.showSnackbar("收藏失败")
                                }
                            }
                        },
                        onShareClick = { showShareSheet = true },
                        onEditClick = {
                            currentUri?.let { uri ->
                                val edit = Intent(Intent.ACTION_EDIT).apply {
                                    setDataAndType(uri, "image/*")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                val chooser = Intent.createChooser(edit, "编辑图片")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(chooser) }
                                    .onFailure {
                                        scope.launch { snackbarHostState.showSnackbar("未找到可用的编辑器") }
                                    }
                            }
                        },
                        onInfoClick = { showInfo = true },
                        onDeleteClick = { showDeleteConfirm = true },
                        onAnyInteraction = { visibilityState.kickAutoHide() },
                        onHeightMeasured = { chromeBottomHeightPx.intValue = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
```

- [ ] **Step 6: Update single-tap toggle inside the HorizontalPager**

At line 239 (`onSingleTap = { chromeVisible = !chromeVisible }`), replace with:
```kotlin
                        onSingleTap = { visibilityState.toggle() },
```

- [ ] **Step 7: Update slideshow start to use the new visibility API**

At line 206, `chromeVisible = false` (inside the `LaunchedEffect(slideshowConfig)` block) becomes:
```kotlin
                    visibilityState.hide()
```

- [ ] **Step 8: Make snackbar padding dynamic**

At lines 421-426, replace the `SnackbarHost` block with:

```kotlin
            val density = LocalDensity.current
            val chromeBottomHeightDp = with(density) { chromeBottomHeightPx.intValue.toDp() }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = chromeBottomHeightDp + 12.dp),
            ) { Snackbar(it) }
```

Add `import androidx.compose.ui.platform.LocalDensity` to the import list (it may already be present).

- [ ] **Step 9: Compile-check (should succeed now)**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL. No unresolved references — `ViewerTopBar` / `ViewerBottomBar` were deleted in Task 6 and the new `ViewerTopBarChrome` / `ViewerBottomBarChrome` from Tasks 4-5 are wired in here.

- [ ] **Step 10: Commit**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
git add app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt
git commit -m "feat: wire Liquid Glass chrome + backdrop sampling in PhotoViewerActivity"
```

---

## Task 8: Final build, regression sweep, and device handoff

**Files:** (no source changes — verification only)

- [ ] **Step 1: Full debug build**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:assembleDebug 2>&1 | tail -15`
Expected: `BUILD SUCCESSFUL`. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 2: Sacred Liquid Glass grep gate**

Run:
```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册"
git stash
grep -r "iOSLiquidTabBar\|liquidglass.LiquidGlassLens" app/src/main --include="*.kt" -l | wc -l
git stash pop
grep -r "iOSLiquidTabBar\|liquidglass.LiquidGlassLens" app/src/main --include="*.kt" -l | wc -l
```
Expected: both counts equal. The two stash/unstash cycles ensure the diff is clean for the second count. (If you prefer a non-stash approach: count before making any changes, count again now, compare — they should match because we did NOT touch any file in `app/src/main/java/com/smartvision/gallery/ui/liquidglass/`.)

- [ ] **Step 3: Git diff stat gate**

Run: `cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && git diff --stat HEAD~7 HEAD -- app/src/main`
Expected: exactly these 4 files show modifications:
- `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerActivity.kt` (modified)
- `app/src/main/java/com/smartvision/gallery/ui/viewer/ViewerChrome.kt` (modified)
- `app/build.gradle.kts` (modified)
- `gradle/libs.versions.toml` (modified)

If anything else changed (e.g. auto-formatter touched an unrelated file), revert that file with `git checkout HEAD~7 -- <path>` and re-run the build.

- [ ] **Step 4: Install APK on the user's test device**

Per the user's existing test device (`5ddfea15`):
```bash
adb -s 5ddfea15 install -r "H:\workspace-minimaxcode\新建文件夹\超级相册\app\build\outputs\apk/debug/app-debug.apk"
```
Expected: `Success`.

- [ ] **Step 5: Hand off to the user for device verification**

Per the spec's device gate (Verification § 3), the user checks on-device:
- Chrome slides/fades with overshoot when tapped.
- Top/bottom bars visibly sample the underlying photo (colors bleeding through the chrome).
- Buttons feel responsive (44dp hit area, scale-on-press, no Material ripple).
- On a 3-photo slideshow, chrome auto-hides smoothly without frame drops.
- LivePhoto overlay still works above the new bottom chrome.
- Snackbar sits above the bottom chrome; back gesture still returns from viewer.

Report build success + APK install confirmation to the user with a one-line summary: "Build green, APK installed. Tap any photo to verify the Liquid Glass chrome — let me know if spring overshoot feels off or if backdrop sampling regresses on your device."

---

## Self-Review (per writing-plans skill)

1. **Spec coverage** — each spec section maps to a task:
   - Approach § 1 (translucent surface + `layerBackdrop`) → Task 7 step 4 (`Modifier.layerBackdrop(photoBackdrop)` on root Box) + Task 4/5 `drawBackdrop` calls
   - Approach § 2 (capsule buttons) → Task 3 `CapsuleActionIcon`
   - Approach § 3 (Lucide icons) → Task 1 (dependency) + Tasks 4-5 (Lucide icon usage)
   - Approach § 4 (spring auto-hide) → Task 2 `ChromeVisibilityState` + Task 7 step 3 (replace delay-based effect)
   - Approach § 5 (no wire changes) → callbacks preserved verbatim in Task 7 step 5
   - File Structure → Tasks 1, 2-6 (ViewerChrome.kt), 7 (PhotoViewerActivity.kt)
   - Component & Data Flow → Task 7 step 5 (always-render Box{Column{TopBar,Spacer,BottomBar}})
   - Visual Spec → Tasks 3-5 (44dp hit, 22dp icons, 2f stroke, 28dp RoundedCornerShape, blur radius 28dp, etc.)
   - Risk 1 mitigation (always-mount layerBackdrop) → Task 7 step 4
   - Risk 2 mitigation (dampingRatio 0.85f show / 1.0f hide) → Task 2 `ChromeVisibilityState.show()` / `hide()` spring params
   - Risk 3 (ColorOS fallback) → out of scope (spec says fall back is a future change if device crashes; not blocking)
   - Risk 4 (Lucide stroke 2.0f) → Task 4-5 default `Icon` size 24dp @ default stroke ≈ 2f (no override needed)
   - Verification § 1-5 → Task 8

2. **Placeholder scan** — no "TBD" / "TODO" / "implement later" anywhere. All step code blocks are complete.

3. **Type consistency** —
   - `ChromeVisibilityState.progress: Float` (Task 2) used as `Float` parameter in Tasks 4-5 ✓
   - `ChromeVisibilityState.visible: Boolean` used as `Boolean` parameter ✓
   - `rememberChromeVisibilityState()` factory called in Task 7 step 2 ✓
   - `CapsuleActionIcon`'s `selected` / `enabled` parameters used in Tasks 4-5 ✓
   - `LocalBackdrop` `staticCompositionLocalOf<Backdrop>` defined in Task 3, provided in Tasks 4-5 ✓
   - `onHeightMeasured: (Int) -> Unit` defined in Task 5 signature, used in Task 7 step 5 ✓
   - `chromeBottomHeightPx: MutableIntState` (Task 7) used in snackbar padding step 8 ✓
   - `photoBackdrop: LayerBackdrop` (Task 7) used as `Backdrop` parameter ✓

4. **One gap flagged** — the `LocalBackdrop` indirection in `CapsuleActionIcon` is a refactoring choice. If the implementer prefers to pass `backdrop: Backdrop` through the chrome composables' parameter list (without `LocalBackdrop`), that works too — just delete the `LocalBackdrop` definitions and the `CompositionLocalProvider` wrappers in Tasks 4-5, and replace `LocalBackdrop.current` with a `backdrop` parameter on `CapsuleActionIcon`. The spec doesn't mandate one over the other.

5. **`Backdrop` vs `LayerBackdrop` alignment** — `LocalBackdrop` is `staticCompositionLocalOf<Backdrop>` (Task 3) and the value provided is `photoBackdrop: LayerBackdrop` (Task 7). `LayerBackdrop` implements `Backdrop` in Kyant 1.0.1, so the assignment type-checks. If the build complains, cast at the call site or relax `LocalBackdrop` to `Any`.
