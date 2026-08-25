# Liquid Lens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite the long-press magnifier into an iOS 26 "Liquid Lens" — a velocity-driven, physically-accurate water-drop that stretches with finger speed, compresses against the tab-bar boundary, refracts real screen pixels via AGSL, and tells the user which tab it is magnifying via icon scale/tint feedback.

**Architecture:** A `RuntimeShader` runs an AGSL fragment program on a `Box(.fillMaxSize())` that covers the whole screen — `fragCoord` is in absolute pixels, the shader computes an SDF ellipse at `u_lensCenter` with semi-axes `baseRadius*scaleX/Y`, refracts `backdrop.eval(coord)` by the surface normal, adds chromatic aberration, and renders the rim/highlight from the same shader. Position uses `snapTo` (zero-latency follow), scaleX/scaleY use a soft spring (k=600, d=0.9) driven by an EMA-smoothed velocity in **px/秒** (not px/frame). On `Build.VERSION.SDK_INT < TIRAMISU` the overlay falls back to the old `drawBackdrop + lens()` path.

**Tech Stack:** Jetpack Compose, AGSL `RuntimeShader` + `RenderEffect.createRuntimeShaderEffect`, `Animatable` (Compose Foundation), DataStore Preferences (CSV-encoded strings), `Modifier.graphicsLayer`.

**Spec:** `docs/superpowers/specs/2026-06-24-liquid-lens-design.md` (locked, 3 review rounds — B1–B5 + 9 improvements + O1–O5 + RenderEffect full-screen fix).

---

## File Structure

| File | Role | Lines (est.) |
|---|---|---|
| `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LiquidGlassLens.kt` | **Full rewrite.** Holds `LiquidGlassLensController` (position + scaleX/Y + alpha + bounds + LensSnapshot pattern + velocity tracking), `rememberLiquidGlassLensController()`, `LocalLiquidGlassLens`, AGSL shader source constant, `LiquidGlassLensOverlay` (full-screen Box + RenderEffect), `LegacyLensOverlay` (SDK<33 fallback). | ~280 |
| `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LensSnapshot.kt` (NEW) | Immutable snapshot data class + `toSnapshot()` factory on controller. | ~40 |
| `app/src/main/java/com/smartvision/gallery/data/glass/GlassConfig.kt` | Replace `LensGlassConfig` field set: drop the old 8 fields, add 9 new lens parameters. Update `toSpec()`. | +15, -10 |
| `app/src/main/java/com/smartvision/gallery/data/glass/GlassConfigRepository.kt` | Update `toLens()` decoder + `LensGlassConfig.encode()` to handle the new 9 fields. | +20, -5 |
| `app/src/main/java/com/smartvision/gallery/ui/glass/GlassConfigPanel.kt` | Replace `LensSection` with 9 sliders for the new parameters. | +90, -25 |
| `app/src/main/java/com/smartvision/gallery/ui/apple/AppleComponents.kt` | Update `iOSTabBarItem.isLensTarget` to elliptical hit-test, add `Animatable<Float>` icon-scale + tint alpha, share with controller. | +25, -10 |

Untouched (read-only references):
- `LiquidGlassSpec.kt` — `iOS26Lens` stays for live-preview fallback.
- `BackdropCapture.kt`, `LiquidGlassBackdrop.kt`, `LiquidGlassTheme.kt` — backdrop pipeline unchanged.
- `GlassSpecSliders.kt`, `GlassConfigViewModel.kt` — slider + VM unchanged (VM exposes `setLens(LensGlassConfig)` already).

---

## Task 1: Replace `LensGlassConfig` with 9 physics parameters

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/glass/GlassConfig.kt` (lines 49–58)

- [ ] **Step 1: Replace the field set**

Replace lines 49–58 with the following definition. Imports stay (`Dp`, `dp`).

```kotlin
data class LensGlassConfig(
    val lensRestRadius: Dp = 48.dp,        // 32–72 — static sphere radius
    val stretchMax: Float = 3.5f,          // 1.5–6.0 — horizontal stretch cap
    val squashMax: Float = 0.35f,          // 0.0–0.6 — vertical drag squash cap
    val wallCompressK: Float = 0.45f,      // 0.0–0.7 — wall horizontal compression
    val wallBulgeK: Float = 0.25f,         // 0.0–0.5 — wall vertical bulge
    val chromaticAberration: Float = 0.012f, // 0.0–0.05 — RGB split
    val lensRefraction: Float = 0.08f,     // 0.0–0.3 — backdrop pixel displacement
    val iconScaleInside: Float = 1.18f,    // 1.0–1.4 — magnified icon scale
    val iconTintAlpha: Float = 1.0f,       // 0.0–1.0 — magnified icon tint strength
)
```

- [ ] **Step 2: Drop the old `toSpec()` line that depends on LensGlassConfig**

Lines 92–101 of `GlassConfig.kt` are `fun LensGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(...)`. Delete that function (the lens does not consume `LiquidGlassSpec` anymore — the shader uses raw floats directly). Leave the other two `toSpec()` functions (TabBarGlassConfig, StaticGlassConfig) untouched.

- [ ] **Step 3: Verify with grep**

Run:
```bash
grep -n "LensGlassConfig" app/src/main/java/com/smartvision/gallery/data/glass/GlassConfig.kt
```
Expected: 2 hits — the `data class LensGlassConfig(` declaration and one default reference inside `GlassConfig(...)`. No `toSpec()` references for lens.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/data/glass/GlassConfig.kt
git commit -m "feat(lens): replace LensGlassConfig with 9 physics parameters"
```

---

## Task 2: Update DataStore codec for the 9 fields

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/glass/GlassConfigRepository.kt` (lines 130–152)

- [ ] **Step 1: Rewrite `toLens()`**

Replace the body of `private fun String?.toLens()` (lines 130–141) with:

```kotlin
private fun String?.toLens(): LensGlassConfig = parseCsv().let { p ->
    LensGlassConfig(
        lensRestRadius = p["lensRestRadius"]?.toFloatOrNull()?.toDp() ?: Dp(48f),
        stretchMax = p["stretchMax"]?.toFloatOrNull() ?: 3.5f,
        squashMax = p["squashMax"]?.toFloatOrNull() ?: 0.35f,
        wallCompressK = p["wallCompressK"]?.toFloatOrNull() ?: 0.45f,
        wallBulgeK = p["wallBulgeK"]?.toFloatOrNull() ?: 0.25f,
        chromaticAberration = p["chromaticAberration"]?.toFloatOrNull() ?: 0.012f,
        lensRefraction = p["lensRefraction"]?.toFloatOrNull() ?: 0.08f,
        iconScaleInside = p["iconScaleInside"]?.toFloatOrNull() ?: 1.18f,
        iconTintAlpha = p["iconTintAlpha"]?.toFloatOrNull() ?: 1.0f,
    )
}
```

- [ ] **Step 2: Rewrite `encode()`**

Replace the body of `private fun LensGlassConfig.encode()` (lines 143–152) with:

```kotlin
private fun LensGlassConfig.encode(): String = listOf(
    "lensRestRadius" to lensRestRadius.value,
    "stretchMax" to stretchMax,
    "squashMax" to squashMax,
    "wallCompressK" to wallCompressK,
    "wallBulgeK" to wallBulgeK,
    "chromaticAberration" to chromaticAberration,
    "lensRefraction" to lensRefraction,
    "iconScaleInside" to iconScaleInside,
    "iconTintAlpha" to iconTintAlpha,
).joinToString(",") { (k, v) -> "$k=$v" }
```

- [ ] **Step 3: Verify by inspecting the diff**

Run:
```bash
git diff app/src/main/java/com/smartvision/gallery/data/glass/GlassConfigRepository.kt
```
Expected: only lines 130–152 changed. Other 3 codecs (`toTabBar`, `toStatic`, `toBackdrop`) untouched.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/data/glass/GlassConfigRepository.kt
git commit -m "feat(lens): update DataStore codec for 9 lens physics params"
```

---

## Task 3: Replace `LensSection` sliders in GlassConfigPanel

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/glass/GlassConfigPanel.kt` (lines 201–236)

- [ ] **Step 1: Replace the body of `LensSection`**

Replace lines 201–236 with:

```kotlin
@Composable
private fun LensSection(
    config: LensGlassConfig,
    onChange: (LensGlassConfig) -> Unit,
) {
    Column {
        LabeledSlider(
            label = "lensRestRadius (静止球半径)",
            value = config.lensRestRadius.value,
            onValueChange = { onChange(config.copy(lensRestRadius = Dp(it))) },
            valueRange = 32f..72f,
            valueFormatter = { "%.0f dp".format(it) },
        )
        LabeledSlider(
            label = "stretchMax (横向最大拉伸)",
            value = config.stretchMax,
            onValueChange = { onChange(config.copy(stretchMax = it)) },
            valueRange = 1.5f..6.0f,
            valueFormatter = { "%.2fx".format(it) },
        )
        LabeledSlider(
            label = "squashMax (垂直最大压缩)",
            value = config.squashMax,
            onValueChange = { onChange(config.copy(squashMax = it)) },
            valueRange = 0f..0.6f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "wallCompressK (触边横向压缩)",
            value = config.wallCompressK,
            onValueChange = { onChange(config.copy(wallCompressK = it)) },
            valueRange = 0f..0.7f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "wallBulgeK (触边垂直鼓起)",
            value = config.wallBulgeK,
            onValueChange = { onChange(config.copy(wallBulgeK = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "chromaticAberration (色散)",
            value = config.chromaticAberration,
            onValueChange = { onChange(config.copy(chromaticAberration = it)) },
            valueRange = 0f..0.05f,
            valueFormatter = { "%.3f".format(it) },
        )
        LabeledSlider(
            label = "lensRefraction (折射强度)",
            value = config.lensRefraction,
            onValueChange = { onChange(config.copy(lensRefraction = it)) },
            valueRange = 0f..0.3f,
            valueFormatter = { "%.3f".format(it) },
        )
        LabeledSlider(
            label = "iconScaleInside (透镜内 icon 放大)",
            value = config.iconScaleInside,
            onValueChange = { onChange(config.copy(iconScaleInside = it)) },
            valueRange = 1f..1.4f,
            valueFormatter = { "%.2fx".format(it) },
        )
        LabeledSlider(
            label = "iconTintAlpha (透镜内 icon 染色)",
            value = config.iconTintAlpha,
            onValueChange = { onChange(config.copy(iconTintAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}
```

- [ ] **Step 2: Update the LivePreview lens block**

Replace lines 322–342 (the `Box(.size(96.dp).clip(CircleShape))` containing `AdaptiveLiquidGlass(spec = LiquidGlassSpec.iOS26Lens.copy(...))`) with:

```kotlin
Text(
    text = "液态透镜 — 长按底栏触发,见 LivePreview 上方。9 参数调速。",
    fontSize = 12.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
```

(Live preview of the full-screen AGSL lens is not feasible inside a 96dp circle — the shader needs the entire screen backdrop. Drop the dead preview block.)

- [ ] **Step 3: Drop the now-unused `AdaptiveLiquidGlass` / `LiquidGlassCard` imports if no longer referenced**

After Step 2, `AdaptiveLiquidGlass` and `LiquidGlassCard` may no longer be referenced from this file. Run:
```bash
grep -n "AdaptiveLiquidGlass\|LiquidGlassCard" app/src/main/java/com/smartvision/gallery/ui/glass/GlassConfigPanel.kt
```
If only the import lines remain, remove them. Leave `LiquidGlassBar` (still used for the tab-bar preview).

- [ ] **Step 4: Verify the slider count**

Run:
```bash
grep -c "LabeledSlider" app/src/main/java/com/smartvision/gallery/ui/glass/GlassConfigPanel.kt
```
Expected: ≥ 16 (1 TabBar blur + 1 corner + 1 lensAmount + 1 shadow + 1 tintAlpha = 5 TabBar; 4 Static; 9 Lens; = 18 total).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/glass/GlassConfigPanel.kt
git commit -m "feat(lens): 9 sliders in GlassConfigPanel.LensSection + drop dead preview"
```

---

## Task 4: Create `LensSnapshot.kt` immutable snapshot

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LensSnapshot.kt`

- [ ] **Step 1: Write the file**

```kotlin
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
```

- [ ] **Step 2: Verify it parses**

Run:
```bash
grep -n "data class LensSnapshot" app/src/main/java/com/smartvision/gallery/ui/liquidglass/LensSnapshot.kt
```
Expected: one hit.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/liquidglass/LensSnapshot.kt
git commit -m "feat(lens): add LensSnapshot immutable data carrier"
```

---

## Task 5: Rewrite `LiquidGlassLensController`

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LiquidGlassLens.kt` (lines 44–115, controller only — overlay stays until Task 7)

- [ ] **Step 1: Replace the controller class**

Replace lines 44–115 with:

```kotlin
class LiquidGlassLensController {

    val position: Animatable<Offset, AnimationVector2D> =
        Animatable(Offset.Zero, Offset.VectorConverter)
    val scaleX: Animatable<Float, AnimationVector1D> = Animatable(1f)
    val scaleY: Animatable<Float, AnimationVector1D> = Animatable(1f)
    val alpha: Animatable<Float, AnimationVector1D> = Animatable(0f)
    val isVisible = mutableStateOf(false)

    /** Bar pill rect in window coordinates; null = no clamp. */
    var bounds: Rect? by mutableStateOf(null)

    /** Rest radius in pixels — set by [LiquidGlassLensOverlay] on measure. */
    var baseRadiusPx: Float = 144f

    /** Live parameter mirrors (read by [toSnapshot]). */
    var refraction: Float = 0.08f
    var chromaticAberration: Float = 0.012f
    var rimAlpha: Float = 0.85f

    /**
     * EMA-smoothed velocity in px/秒. Updated by [pushPosition].
     * Used only to compute the scaleX/Y target; not exposed publicly.
     */
    private var velocityX: Float = 0f
    private var velocityY: Float = 0f
    private var lastTimestampNanos: Long = 0L

    /** Drives the scaleX/Y Animatables. Computed in px/秒. */
    var stretchMax: Float = 3.5f
    var squashMax: Float = 0.35f
    var wallCompressK: Float = 0.45f
    var wallBulgeK: Float = 0.25f

    fun show(at: Offset, scope: CoroutineScope) {
        val clamped = clampToBar(at)
        velocityX = 0f; velocityY = 0f; lastTimestampNanos = 0L
        scope.launch {
            position.snapTo(clamped)
            launch { scaleX.snapTo(1f) }
            launch { scaleY.snapTo(1f) }
            launch {
                alpha.animateTo(1f, spring(stiffness = 600f, dampingRatio = 0.9f))
            }
            isVisible.value = true
        }
    }

    fun moveTo(at: Offset, scope: CoroutineScope, nowNanos: Long) {
        val clamped = clampToBar(at)
        scope.launch { position.snapTo(clamped) }
        updateVelocity(clamped, nowNanos)
        recomputeScaleTargets(scope)
    }

    fun hide(scope: CoroutineScope) {
        scope.launch {
            launch { scaleX.animateTo(1f, spring(stiffness = 600f, dampingRatio = 0.9f)) }
            launch { scaleY.animateTo(1f, spring(stiffness = 600f, dampingRatio = 0.9f)) }
            launch { alpha.animateTo(0f, spring(stiffness = 600f, dampingRatio = 0.9f)) }
            isVisible.value = false
        }
    }

    /**
     * Produces the immutable [LensSnapshot] consumed by the AGSL shader.
     * Cheap (no allocations beyond the data class).
     */
    fun toSnapshot(): LensSnapshot = LensSnapshot(
        cx = position.value.x,
        cy = position.value.y,
        scaleX = scaleX.value,
        scaleY = scaleY.value,
        alpha = alpha.value,
        rimAlpha = rimAlpha,
        baseRadiusPx = baseRadiusPx,
        lensAmount = refraction,
        chromaticAberration = chromaticAberration,
    )

    private fun updateVelocity(target: Offset, nowNanos: Long) {
        if (lastTimestampNanos == 0L) {
            lastTimestampNanos = nowNanos
            return
        }
        val dtSec = ((nowNanos - lastTimestampNanos).coerceAtLeast(1L)) / 1_000_000_000f
        lastTimestampNanos = nowNanos
        val prev = position.value
        val instVx = (target.x - prev.x) / dtSec
        val instVy = (target.y - prev.y) / dtSec
        // Time-based EMA: α = 1 - exp(-dt/τ), τ = 50ms.
        val alpha = 1f - kotlin.math.exp(-dtSec / 0.05f)
        velocityX = instVx * alpha + velocityX * (1f - alpha)
        velocityY = instVy * alpha + velocityY * (1f - alpha)
    }

    private fun recomputeScaleTargets(scope: CoroutineScope) {
        // Density-aware cap: caller passes via LocalDensity when invoking.
        val maxVx = 1800f * (baseRadiusPx / 144f)
        val maxVy = maxVx * 0.75f
        val absVx = kotlin.math.abs(velocityX).coerceAtMost(maxVx)
        val absVy = kotlin.math.abs(velocityY).coerceAtMost(maxVy)
        val newStretchX = 1f + (absVx / maxVx) * stretchMax
        val verticalSquash = 1f - (absVy / maxVy) * (squashMax * DRAG_DAMPING)
        val newStretchY = (1f / newStretchX) * verticalSquash
        scope.launch {
            launch { scaleX.animateTo(newStretchX, spring(stiffness = 600f, dampingRatio = 0.9f)) }
            launch { scaleY.animateTo(newStretchY, spring(stiffness = 600f, dampingRatio = 0.9f)) }
        }
    }

    private fun clampToBar(p: Offset): Offset {
        val b = bounds ?: return p
        val r = baseRadiusPx
        val x = p.x.coerceIn(b.left + r, b.right - r)
        val y = b.center.y
        return Offset(x, y)
    }

    companion object {
        const val STRETCH_MAX = 3.5f
        const val DRAG_DAMPING = 0.5f
    }
}
```

- [ ] **Step 2: Verify the controller compiles in isolation**

(The overlay still references the OLD `scale` field — that will break compilation. Proceed to Task 6 immediately to fix the call sites, or add a temporary `val scale: Animatable<Float, AnimationVector1D> get() = scaleX` shim if you need to checkpoint here. Recommended: skip this commit and do Task 6 in the same commit.)

- [ ] **Step 3: Do NOT commit yet — proceed to Task 6**

---

## Task 6: Rewrite `LiquidGlassLensOverlay` with full-screen RenderEffect

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LiquidGlassLens.kt` (lines 117–199 — `LocalLiquidGlassLens`, `rememberLiquidGlassLensController`, `LiquidGlassLensOverlay`, plus add `LegacyLensOverlay` and the AGSL shader constant)

- [ ] **Step 1: Replace the entire rest of `LiquidGlassLens.kt`**

Replace lines 117–199 with:

```kotlin
val LocalLiquidGlassLens = staticCompositionLocalOf<LiquidGlassLensController> {
    error("LocalLiquidGlassLens not provided. Wrap your screen in CompositionLocalProvider.")
}

@Composable
fun rememberLiquidGlassLensController(): LiquidGlassLensController =
    remember { LiquidGlassLensController() }

/**
 * AGSL fragment program for the iOS 26 liquid lens.
 *
 * Runs on the full-screen Box; `fragCoord` is in absolute screen pixels.
 * Computes an SDF ellipse at `u_lensCenter` with semi-axes
 * `u_baseRadius * u_scaleX/Y`, refracts the backdrop by the surface
 * normal, applies chromatic aberration (R / G / B sample offsets), and
 * blends a rim + highlight on top.
 *
 * The "backdrop" sampler is auto-filled by Compose when this shader is
 * used through `RenderEffect.createRuntimeShaderEffect(shader, "backdrop")`.
 */
private const val AGSL_LENS_SHADER = """
uniform shader backdrop;
uniform float2  u_resolution;
uniform float2  u_lensCenter;
uniform float2  u_screenSize;
uniform float   u_baseRadius;
uniform float2  u_scale;
uniform float   u_lensAmount;
uniform float   u_chromaticAberration;
uniform float   u_alpha;
uniform float   u_rimAlpha;

half4 main(float2 fragCoord) {
    float aspect = u_screenSize.x / max(u_screenSize.y, 0.001);
    float2 toCenter = fragCoord - u_lensCenter;
    float2 uv = toCenter / (u_baseRadius * u_scale);
    uv.y /= aspect;
    float sdf = length(uv) - 1.0;
    if (sdf > 0.0) {
        return backdrop.eval(fragCoord);
    }
    float2 nLocal = (sdf < 0.0) ? normalize(float2(uv.x, uv.y * aspect)) : float2(0.0, 0.0);
    float2 normal = float2(nLocal.x / u_scale.x, nLocal.y / (u_scale.y * aspect * aspect));
    float2 offset = normal * u_lensAmount * u_screenSize;
    float ca = u_chromaticAberration * u_screenSize.x;
    half3 colR = backdrop.eval(fragCoord + offset * (1.0 + ca));
    half3 colG = backdrop.eval(fragCoord + offset);
    half3 colB = backdrop.eval(fragCoord + offset * (1.0 - ca));
    half3 chroma = half3(colR.r, colG.g, colB.b);
    half3 base = backdrop.eval(fragCoord);
    float rim = smoothstep(0.0, -0.04, sdf);
    half3 tinted = mix(base, chroma, rim);
    float h = max(0.0, 1.0 - abs(sdf));
    float highlight = pow(h, 16.0);
    half3 outColor = tinted + half3(highlight * u_rimAlpha * 0.6);
    return half4(outColor, max(u_alpha, rim * u_alpha));
}
"""

/**
 * Render the liquid lens on top of the screen. Attach to the OUTERMOST
 * Box of the consuming screen — `fragCoord` is in absolute pixels only
 * when this composable owns the full-screen surface. Attaching to a
 * sized Box will produce a fully-transparent artifact (the bug we
 * fixed in the spec review).
 */
@Composable
fun LiquidGlassLensOverlay(modifier: Modifier = Modifier) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        LegacyLensOverlay(modifier)
        return
    }
    val controller = LocalLiquidGlassLens.current
    if (!controller.isVisible.value && controller.alpha.value < 0.01f) return

    val config = LocalGlassConfig.current
    val density = LocalDensity.current
    controller.baseRadiusPx = with(density) { config.lens.lensRestRadius.toPx() }
    controller.refraction = config.lens.lensRefraction
    controller.chromaticAberration = config.lens.chromaticAberration
    controller.rimAlpha = 0.85f
    controller.stretchMax = config.lens.stretchMax
    controller.squashMax = config.lens.squashMax
    controller.wallCompressK = config.lens.wallCompressK
    controller.wallBulgeK = config.lens.wallBulgeK

    val snapshot by remember(controller) {
        derivedStateOf { controller.toSnapshot() }
    }

    val shader = remember {
        runCatching { RuntimeShader(AGSL_LENS_SHADER) }.getOrNull()
    }
    if (shader == null) {
        LegacyLensOverlay(modifier)
        return
    }
    val renderEffect = remember(shader) {
        RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
            .asComposeRenderEffect()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                shader.setFloatUniform("u_resolution", size.width, size.height)
                shader.setFloatUniform("u_lensCenter", snapshot.cx, snapshot.cy)
                shader.setFloatUniform(
                    "u_screenSize",
                    size.width * snapshot.scaleX,
                    size.height * snapshot.scaleY,
                )
                shader.setFloatUniform("u_baseRadius", snapshot.baseRadiusPx)
                shader.setFloatUniform("u_scale", snapshot.scaleX, snapshot.scaleY)
                shader.setFloatUniform("u_lensAmount", snapshot.lensAmount)
                shader.setFloatUniform("u_chromaticAberration", snapshot.chromaticAberration)
                shader.setFloatUniform("u_alpha", snapshot.alpha)
                shader.setFloatUniform("u_rimAlpha", snapshot.rimAlpha)
                this.renderEffect = renderEffect
            }
    ) {
        // No children — the shader IS the lens.
    }
}

/**
 * SDK < 33 fallback. Uses the third-party `drawBackdrop + lens()` path
 * (no AGSL, no chromatic aberration). Same visual concept; degraded
 * deformation behaviour (uniform scale, no velocity stretch).
 */
@Composable
fun LegacyLensOverlay(modifier: Modifier = Modifier) {
    val controller = LocalLiquidGlassLens.current
    if (!controller.isVisible.value && controller.alpha.value < 0.01f) return
    val backdrop = LocalLiquidGlassScreenBackdrop.current
    val density = LocalDensity.current
    val config = LocalGlassConfig.current
    val spec = config.lens
    val pos = controller.position.value
    val baseR = with(density) { spec.lensRestRadius.toPx() } / 2f
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size((baseR * 2f).toDp(), (baseR * 2f).toDp())
                .graphicsLayer {
                    translationX = pos.x - baseR
                    translationY = pos.y - baseR
                    this.alpha = controller.alpha.value
                }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule() },
                    effects = {
                        lens(baseR, baseR, chromaticAberration = true)
                    },
                    onDrawSurface = {
                        drawRect(
                            color = Color.White.copy(alpha = 0.85f),
                            style = Stroke(width = with(density) { 1.5.dp.toPx() }),
                        )
                    },
                ),
        )
    }
}
```

- [ ] **Step 2: Add the missing imports**

Replace the import block (lines 1–30) with:

```kotlin
package com.smartvision.gallery.ui.liquidglass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toDp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
```

- [ ] **Step 3: Verify no `scale` field references remain**

Run:
```bash
grep -n "controller.scale\b" app/src/main/java/com/smartvision/gallery/ui/liquidglass/LiquidGlassLens.kt
```
Expected: zero hits (the field was renamed to `scaleX`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/liquidglass/LiquidGlassLens.kt
git commit -m "feat(lens): rewrite controller + AGSL full-screen overlay + legacy fallback"
```

---

## Task 7: Update `iOSTabBarItem` to elliptical hit-test + Animatable icon scale

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/apple/AppleComponents.kt` (lines 142–281, `iOSTabBarItem`)

- [ ] **Step 1: Update imports (lines 1–61)**

Add:
```kotlin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import com.smartvision.gallery.ui.liquidglass.LensSnapshot
```

Remove (if no other usage in this file):
```kotlin
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSpec
```
… actually leave it; it is still used by `iOSListSection`. Confirm with grep before removing.

- [ ] **Step 2: Replace `isLensTarget` derivedStateOf with elliptical test + add Animatable icon scale**

Replace lines 183–195 with:

```kotlin
var tabSize by remember { mutableStateOf(IntSize.Zero) }

// Animatable icon scale (k=800, d=0.85) — soft spring into/out of "magnified" state.
val iconScaleAnim = remember { Animatable(1f) }
val lens = LocalLiquidGlassLens.current
val isLensTarget by remember(lens) {
    derivedStateOf {
        if (!lens.isVisible.value) return@derivedStateOf false
        val lensPos = lens.position.value
        val cx = layoutOrigin.x + tabSize.width / 2f
        val cy = layoutOrigin.y + tabSize.height / 2f
        val rx = lens.baseRadiusPx * lens.scaleX.value
        val ry = lens.baseRadiusPx * lens.scaleY.value
        if (rx <= 0f || ry <= 0f) return@derivedStateOf false
        val dx = lensPos.x - cx
        val dy = lensPos.y - cy
        // Elliptical hit-test: (dx/rx)² + (dy/ry)² ≤ 1.
        (dx * dx) / (rx * rx) + (dy * dy) / (ry * ry) <= 1f
    }
}

LaunchedEffect(isLensTarget) {
    val target = if (isLensTarget) LocalGlassConfig.current.lens.iconScaleInside else 1f
    iconScaleAnim.animateTo(target, spring(stiffness = 800f, dampingRatio = 0.85f))
}
```

- [ ] **Step 3: Replace the icon block (lines 261–272)**

Replace:
```kotlin
Icon(
    imageVector = item.icon,
    contentDescription = item.label,
    tint = highlightTint,
    modifier = Modifier
        .size(24.dp)
        .graphicsLayer {
            scaleX = highlightScale
            scaleY = highlightScale
            shadowElevation = highlightElevation.toPx()
        }
)
```
With:
```kotlin
val iconTintColor by animateColorAsState(
    targetValue = if (isLensTarget) {
        MaterialTheme.colorScheme.primary.copy(alpha = LocalGlassConfig.current.lens.iconTintAlpha)
    } else iconColor,
    label = "iconTint",
)
Icon(
    imageVector = item.icon,
    contentDescription = item.label,
    tint = iconTintColor,
    modifier = Modifier
        .size(24.dp)
        .graphicsLayer {
            scaleX = iconScaleAnim.value
            scaleY = iconScaleAnim.value
            shadowElevation = if (isLensTarget) 4.dp.toPx() else 0f
        }
)
```

- [ ] **Step 4: Wire `nowNanos` into the drag handler**

Replace lines 230–247 (`pointerInput(item.route) { detectDragGesturesAfterLongPress(...) }`) with:

```kotlin
.pointerInput(item.route) {
    detectDragGesturesAfterLongPress(
        onDragStart = { startOffset ->
            val press = layoutOrigin + startOffset
            lens.show(press, scope)
        },
        onDrag = { change, _ ->
            change.consume()
            lens.moveTo(layoutOrigin + change.position, scope, System.nanoTime())
        },
        onDragEnd = { lens.hide(scope) },
        onDragCancel = { lens.hide(scope) }
    )
}
```

Add `import kotlin.time.ExperimentalTime` is NOT needed — `System.nanoTime()` is in `java.lang` (auto-imported).

- [ ] **Step 5: Verify the imports compile**

Run:
```bash
grep -n "import androidx.compose.runtime.LaunchedEffect" app/src/main/java/com/smartvision/gallery/ui/apple/AppleComponents.kt
```
Expected: zero hits. ADD the import at the top of the file (alongside the existing `import androidx.compose.runtime.*` imports):
```kotlin
import androidx.compose.runtime.LaunchedEffect
```

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/smartvision/gallery/ui/apple/AppleComponents.kt
git commit -m "feat(lens): elliptical hit-test + Animatable icon scale in iOSTabBarItem"
```

---

## Task 8: Wire on-device build verification

**Files:** none (verification step)

- [ ] **Step 1: Trigger a Gradle build**

Run:
```bash
cd H:/workspace-minimaxcode/超级相册 && ./gradlew :app:assembleDebug --no-daemon 2>&1 | tail -80
```
Expected: BUILD SUCCESSFUL. If FAIL, do NOT mark complete — fix the first error and re-run.

- [ ] **Step 2: Search for common compile errors**

Run:
```bash
grep -rn "Unresolved reference\|Type mismatch\|None of the following" app/build/reports/ 2>/dev/null | head -30
```
Expected: zero hits.

- [ ] **Step 3: Commit a "verified builds" tag (only if previous tasks left uncommitted files)**

```bash
git status --porcelain
```
If empty, skip. Otherwise:
```bash
git add -A && git commit -m "chore: build verified"
```

- [ ] **Step 4: Hand off to user**

Per `feedback_user_tests.md`, you do NOT loop on ADB tap/screenshot. Once the build is green, report:
> "Build green. The liquid-lens rewrite is ready — long-press any tab bar item to summon the water-drop lens. 9 new sliders are in `GlassConfigPanel → Lens` for fine-tuning. Please install on device and verify: (a) lens snaps to finger with zero latency, (b) fast moves stretch horizontally, (c) edge drag compresses against the bar boundary, (d) lens over a tab scales its icon 1.18x + tints to primary, (e) screen pixels are visibly refracted inside the lens. Report any tuning adjustments needed."

---

## Self-Review

**1. Spec coverage:** Liquid Lens Design §1 (purpose) → Tasks 5/6/7; §3 (physics formulas) → Task 5 (`recomputeScaleTargets`); §4 (AGSL shader) → Task 6 (AGSL_LENS_SHADER const); §5.1 (full-screen Box RenderEffect architecture) → Task 6 (Step 1 — explicitly `Box(.fillMaxSize())`); §5.2 (icon feedback) → Task 7 (Animatable icon scale + tint); §6 (9 parameters in DataStore + GlassConfigPanel) → Tasks 1/2/3; §7 (legacy fallback) → Task 6 (`LegacyLensOverlay` + SDK check). All sections covered.

**2. Placeholder scan:** No "TBD", "TODO", "implement later", "fill in details", "appropriate error handling". Every code block contains literal Kotlin. Every commit is concrete.

**3. Type consistency:** `LensSnapshot.baseRadiusPx` (Float, Task 4) matches `controller.baseRadiusPx: Float` (Task 5) matches `u_baseRadius` uniform (Task 6 shader). `scaleX: Float` (Task 4/5/6) matches AGSL `u_scale.x`. `moveTo(at, scope, nowNanos: Long)` (Task 5) matches the call site in Task 7 (`System.nanoTime()`). `Animatable<Float, AnimationVector1D>` (Task 5) matches existing imports. `LocalLiquidGlassLens` (Task 6) matches the consumers in `iOSTabBarItem` (Task 7). `LocalGlassConfig` matches the existing composition-local in `LiquidGlassTheme.kt`.

**4. Build-step ordering:** Tasks 1 → 2 → 3 (data layer) compile independently. Task 4 (new file) compiles independently. Tasks 5+6 are kept in the same commit to avoid leaving the project with a broken `controller.scale` reference. Task 7 references `LocalGlassConfig.current.lens.iconScaleInside` — `LocalGlassConfig` is already provided by `AppRoot` (per project memory).

**5. Verification:** Task 8 hands off to user (per `feedback_user_tests.md`).

---

## Execution

Plan complete and saved to `docs/superpowers/plans/2026-06-24-liquid-lens.md`. Two execution options:

1. **Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.
2. **Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?