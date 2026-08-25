# Liquid Lens — Switch to Kyant `drawBackdrop + lens()` Path

**Date:** 2026-06-25
**Status:** Approved (verbal)
**Replaces:** `2026-06-24-liquid-lens-design.md` (custom AGSL path)

## Context

The custom AGSL `RenderEffect.createRuntimeShaderEffect` path for the iOS 26
long-press magnifier has failed after 3+ fix rounds:

1. `RuntimeShader(AGSL_LENS_SHADER)` compiles but the `graphicsLayer` reports
   `size.width = 0.0, size.height = 0.0` because the empty `Box` has no
   measured content. A `Spacer(Modifier.matchParentSize())` child fixes this
   one instance, but the lens still does not appear on the device.

2. The user reports the same symptom (深蓝色 icon highlight + small box
   shadow, no magnifier ball) across all AGSL attempts. They suspect a
   Compose / `RenderEffect` / `RuntimeShader` compatibility issue with the
   device's API level.

The project already depends on `com.kyant.backdrop` (AndroidLiquidGlass by
Kyant, 2792★) for the static glass surfaces and the SDK<33 lens fallback. The
same library ships a production-tested `lens()` effect with
`chromaticAberration` that runs through `drawBackdrop(backdrop, shape, effects)`
— the exact pattern Kyant uses in their `LiquidSlider` reference component.

**Decision:** drop the custom AGSL path entirely. Move the SDK≥33 lens to the
same `drawBackdrop + lens()` path the SDK<33 fallback already uses, but with a
160dp movable magnifier container (instead of a fullscreen overlay).

## Design

### Layout hierarchy (unchanged)

```
AppRoot Box (Z=0/1/2)
├─ Z=0: content Box (Modifier.fillMaxSize().layerBackdrop(liquidBackdrop))
├─ Z=1: iOSTabBar
└─ Z=2: LiquidGlassLensOverlay()
        └─ if SDK < 33 → LegacyLensOverlay (UNCHANGED, already works)
        └─ if SDK ≥ 33 → NewMagnifierOverlay (REWRITTEN)
```

### `NewMagnifierOverlay` — 160dp movable container

Replaces the fullscreen `Box` + `RenderEffect.createRuntimeShaderEffect`
+ AGSL shader. The magnifier is now a **small Box** (160dp × 160dp) that
moves with the finger via `Modifier.offset`. Inside it, `drawBackdrop` with
`shape = { CircleShape }` and `effects = { lens(...) }` reads the captured
`liquidBackdrop` and refracts it through the magnifier's own shape. Kyant's
`highlight` + `shadow` + `innerShadow` provide the iOS 26 "glass sphere" depth.

```kotlin
val backdrop = LocalLiquidGlassScreenBackdrop.current
val density = LocalDensity.current
val config = LocalGlassConfig.current
val size = config.lens.lensSize.toPx()
val height = config.lens.lensRefractionHeight.toPx()
val amount = config.lens.lensRefractionAmount.toPx()

Box(
    modifier = Modifier
        .offset { IntOffset(
            (controller.position.value.x - size / 2f).roundToInt(),
            (controller.position.value.y - size / 2f).roundToInt(),
        ) }
        .size(config.lens.lensSize)
        .graphicsLayer {
            scaleX = controller.scaleX.value
            scaleY = controller.scaleY.value
            alpha = controller.alpha.value
        }
        .drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                lens(
                    refractionHeight = height,
                    refractionAmount = amount,
                    chromaticAberration = true,
                )
            },
            highlight = { Highlight.Ambient.copy(alpha = 0.85f) },
            shadow = { Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.15f)) },
            innerShadow = { InnerShadow(radius = 6.dp, alpha = 0.30f) },
            onDrawSurface = { drawRect(Color.White.copy(alpha = 0.05f)) },
        )
)
```

### Controller — unchanged

`LiquidGlassLensController` (`position`, `scaleX`, `scaleY`, `alpha`,
`isVisible`, `bounds`, `clampToBar`) is **unchanged** — its API still matches
what `NewMagnifierOverlay` reads.

### `GlassConfig.LensGlassConfig` — simplified

Drop AGSL-specific params; add Kyant-aligned params:

| Old field                  | New field                  | Default | Notes                                  |
|----------------------------|----------------------------|---------|----------------------------------------|
| `lensRestRadius: Dp = 80`  | `lensSize: Dp = 160`       | 160.dp  | diameter (was radius); 2× for Kyant path|
| `lensRefraction: Float = 0.55f` | `lensRefractionHeight: Dp = 24` | 24.dp | Kyant `lens(refractionHeight=...)`     |
| _(none)_                   | `lensRefractionAmount: Dp = 32` | 32.dp | Kyant `lens(refractionAmount=...)`     |
| `chromaticAberration: Float = 0.045f` | `chromaticAberration: Boolean = true` | true | Kyant only takes Boolean        |
| `stretchMax: Float = 3.5f` | `stretchMax: Float = 3.5f` | 3.5f    | unchanged (controller still uses it)   |
| `squashMax: Float = 0.35f` | `squashMax: Float = 0.35f` | 0.35f   | unchanged                              |
| `wallCompressK`            | _removed_                  | —       | AGSL-only; not used by Kyant path      |
| `wallBulgeK`               | _removed_                  | —       | AGSL-only                              |
| `iconScaleInside`          | _removed_                  | —       | no magnified icon in Kyant path        |
| `iconTintAlpha`            | _removed_                  | —       | same                                   |
| `rimAlpha`                 | _removed_                  | —       | folded into `Highlight.Ambient.copy(alpha=0.85f)` |

### Dropped code

- `AGSL_LENS_SHADER` string constant (lines 197-266 in current `LiquidGlassLens.kt`)
- All AGSL-related imports: `android.graphics.RenderEffect`, `android.graphics.RuntimeShader`, `android.os.Build` (Build still needed for SDK check, but `Build.VERSION_CODES.TIRAMISU` is fine)
- `LensSnapshot` data class (controller still has `toSnapshot` but no consumer)

`LegacyLensOverlay` is **kept** — it already runs on SDK<33 and is the
proof the `drawBackdrop + lens()` path works on this device.

## Risks

| Risk | Mitigation |
|---|---|
| Magnifier container is small (160dp) — refraction may look subtle | iOS 26 magnifier is roughly the same size; `lens(refractionHeight=24dp, refractionAmount=32dp)` produces visible distortion |
| `clampToBar` puts lens center ~30% above bar top → container overflows content area | This is the iOS 26 behavior; keep it |
| `drawBackdrop` re-evaluates backdrop on every recomposition | Already true for LegacyLensOverlay; project accepted this cost |
| `highlight`/`shadow`/`innerShadow` API is from `com.kyant.backdrop` | Already imported in `AppleComponents.kt` and `LegacyLensOverlay` |
| User wanted thicker visual chrome (spec highlight, bottom shadow) | `Highlight.Ambient` + `Shadow` + `InnerShadow` + thin white `onDrawSurface` approximates it; can be tuned later |

## Test plan

1. Build + install on device `5ddfea15`
2. Open the app to any tab (default is `精选`, selected)
3. Long-press the selected tab ~400ms
4. Verify:
   - 160dp circular magnifier appears, centered slightly above the bar
   - Behind the magnifier, page content (album thumbnails / text) is visibly magnified
   - Edge of the magnifier shows a thin rainbow band (chromatic aberration)
   - Magnifier has a white rim highlight and a soft drop shadow
5. Drag horizontally: magnifier follows finger, stretches along drag direction
6. Lift: magnifier animates out with spring
7. Repeat on a different tab to confirm cross-tab long-press works

## Out of scope

- AGSL shader bugfixing (dropped entirely; can be revisited if Kyant path proves
  insufficient)
- The visual tuning page (`GlassConfigPanel`) for the new `lensSize` / `lensRefractionHeight` /
  `lensRefractionAmount` sliders — defaults only for this iteration; tuning UI in a
  follow-up spec
- `lens()` performance benchmarking
