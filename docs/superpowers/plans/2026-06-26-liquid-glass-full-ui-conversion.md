# iOS 26 Liquid Glass Full UI Conversion — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement plan task-by-task.

**Goal:** Convert all remaining traditional UI components in `AppleComponents.kt` to iOS 26 Liquid Glass: top bar, segmented control, buttons, toggle, list containers, collapsible large-title scroll behavior.

**Architecture:** New GlassConfig specs define per-component glass parameters (blur, tint, corner radius). Each component in AppleComponents.kt independently upgraded to use `drawBackdrop` from `LocalLiquidGlassScreenBackdrop` or `LocalLiquidGlassBackdrop` depending on chrome vs surface level. Collapsible top bar uses Compose LazyList scroll state, not nested scroll.

**Tech Stack:** Compose `rememberLazyListState()`, `Animatable<Float>` for title collapse, `drawBackdrop` + `blur()` + `lens()` + `vibrancy()` from Kyant0 backdrop lib, `graphicsLayer` for press animations.

---

## File Structure

| File | Responsibility | Change |
|------|---------------|--------|
| `data/glass/GlassConfig.kt` | TopBarGlassConfig, ControlGlassConfig, ToggleGlassConfig + toSpec() | Add 3 new data classes + convert functions |
| `ui/liquidglass/LiquidGlassSpec.kt` | iOS26TopBar, iOS26Control, iOS26Toggle spec presets | Add 3 new companion presets |
| `ui/liquidglass/LiquidGlassComponents.kt` | LiquidGlassTopBar helper — collapsible+glass chrome bar | New composable |
| `ui/apple/AppleComponents.kt` | iOSTopBar, iOSSegmentedControl, iOSButton, iOSToggle, iOSListGroupedContainer | Modify 5 composables |
| `ui/pages/TimelinePage.kt` | Use collapsible top bar + scroll state | Modify to pass LazyListState |

### Task 1: New GlassConfig classes + LiquidGlassSpec presets

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\data\glass\GlassConfig.kt` (add after line 46, before `LensGlassConfig`)
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\liquidglass\LiquidGlassSpec.kt` (add to companion)

**Step 1: Add TopBarGlassConfig to GlassConfig.kt**

Insert after `StaticGlassConfig` closing brace (after line 47) and before `LensGlassConfig`:

```kotlin
data class TopBarGlassConfig(
    val cornerRadius: Dp = 0.dp,
    val shadowElevation: Dp = 0.dp,
    val blurRadius: Dp = 12.dp,
    val lensAmount: Dp = 0.dp,
    val tintArgb: Long = 0xFFF2F7FFL,
    val tintAlpha: Float = 0.15f,
    val highlightAlpha: Float = 0.15f,
    val vibrancy: Boolean = true,
)

data class ControlGlassConfig(
    val cornerRadius: Dp = 999.dp,
    val shadowElevation: Dp = 4.dp,
    val blurRadius: Dp = 7.dp,
    val lensAmount: Dp = 12.dp,
    val tintArgb: Long = 0xFFF0F4FFL,
    val tintAlpha: Float = 0.08f,
    val highlightAlpha: Float = 0.3f,
    val vibrancy: Boolean = true,
)

data class ToggleGlassConfig(
    val width: Dp = 51.dp,
    val height: Dp = 31.dp,
    val trackCornerRadius: Dp = 15.5.dp,
    val knobDiameter: Dp = 27.dp,
    val knobShadowBlur: Dp = 3.dp,
    val onColorArgb: Long = 0xFF0088FFL,
    val offTrackAlpha: Float = 0.12f,
    val blurRadius: Dp = 7.dp,
)
```

**Step 2: Add fields to GlassConfig data class**

Add to the existing `GlassConfig` data class at line 20:

```kotlin
data class GlassConfig(
    val tabBar: TabBarGlassConfig = TabBarGlassConfig(),
    val staticGlass: StaticGlassConfig = StaticGlassConfig(),
    val topBar: TopBarGlassConfig = TopBarGlassConfig(),   // NEW
    val control: ControlGlassConfig = ControlGlassConfig(), // NEW
    val toggle: ToggleGlassConfig = ToggleGlassConfig(),    // NEW
    val lens: LensGlassConfig = LensGlassConfig(),
    val backdrop: BackdropGlassConfig = BackdropGlassConfig(),
)
```

**Step 3: Add toSpec() converters**

Add after `StaticGlassConfig.toSpec()` (after line 102):

```kotlin
fun TopBarGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(
    cornerRadius = cornerRadius,
    shadowElevation = shadowElevation,
    blurRadius = blurRadius,
    vibrancy = vibrancy,
    lensAmount = lensAmount,
    tint = argbToColor(tintArgb),
    tintAlpha = tintAlpha,
    highlightAlpha = highlightAlpha,
)

fun ControlGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(
    cornerRadius = cornerRadius,
    shadowElevation = shadowElevation,
    blurRadius = blurRadius,
    vibrancy = vibrancy,
    lensAmount = lensAmount,
    tint = argbToColor(tintArgb),
    tintAlpha = tintAlpha,
    highlightAlpha = highlightAlpha,
)
```

**Step 4: Add spec presets to LiquidGlassSpec.kt companion**

Add before closing `}` of companion object (before line 60):

```kotlin
/** iOS 26 top nav bar: full-width chrome, 12dp blur, white tint. */
val iOS26TopBar = LiquidGlassSpec(
    cornerRadius = 0.dp,
    shadowElevation = 0.dp,
    blurRadius = 12.dp,
    lensAmount = 0.dp,
    tint = Color(0xFFF2F7FF),
    tintAlpha = 0.15f,
    highlightAlpha = 0.15f,
)

/** iOS 26 small control glass: buttons, segmented track, toggles. 7dp blur, pill radius. */
val iOS26Control = LiquidGlassSpec(
    cornerRadius = 999.dp,
    shadowElevation = 4.dp,
    blurRadius = 7.dp,
    lensAmount = 12.dp,
    tint = Color(0xFFF0F4FF),
    tintAlpha = 0.08f,
    highlightAlpha = 0.3f,
)
```

**Step 5: Verify**

Ensure both files compile correctly — `toSpec()` converters match the new `GlassConfig` fields and all existing references continue working.

---

### Task 2: LiquidGlassTopBar helper composable

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\liquidglass\LiquidGlassComponents.kt`

**Step 1: Add LiquidGlassTopBar composable**

Add after the closing `}` of `drawBackdropContainer` (after line 247), before the file ends:

```kotlin
/**
 * Chrome-level Liquid Glass top navigation bar.
 *
 * Renders a full-width glass strip using [LocalLiquidGlassScreenBackdrop] with
 * the [LiquidGlassSpec.iOS26TopBar] spec. Unlike [LiquidGlassBar] which uses
 * ContinuousCapsule, this is a flat rect (full-width chrome).
 *
 * When [collapsedRatio] is provided (0.0 = expanded / large title, 1.0 = fully
 * collapsed), the bar's internal content adjusts. The caller drives this value
 * from [androidx.compose.foundation.lazy.LazyListState.firstVisibleItemIndex]
 * and scroll offset.
 */
@Composable
fun LiquidGlassTopBar(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec = LiquidGlassSpec.iOS26TopBar,
    content: @Composable () -> Unit,
) {
    val bd = LocalLiquidGlassScreenBackdrop.current
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBackdrop(
                backdrop = bd,
                shape = { RoundedCornerShape(0.dp) },
                effects = {
                    if (spec.vibrancy) vibrancy()
                    blur(with(density) { spec.blurRadius.toPx() })
                },
                onDrawSurface = {
                    drawGlassTint(spec)
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Control-level Liquid Glass pill (button/chip).
 *
 * Uses [LiquidGlassSpec.iOS26Control]: 7dp blur, pill radius, subtle tint.
 * Apply [Modifier.size(44.dp)] for symbol buttons or fillMaxWidth for text buttons.
 */
@Composable
fun LiquidGlassControlPill(
    modifier: Modifier = Modifier,
    spec: LiquidGlassSpec = LiquidGlassSpec.iOS26Control,
    shape: Shape = RoundedCornerShape(999.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val bd = LocalLiquidGlassBackdrop.current
    val density = LocalDensity.current
    val m = if (onClick != null) modifier.clip(shape).clickable(onClick = onClick) else modifier
    Box(
        modifier = m
            .drawBackdrop(
                backdrop = bd,
                shape = { shape },
                effects = {
                    if (spec.vibrancy) vibrancy()
                    blur(with(density) { spec.blurRadius.toPx() })
                    lens(
                        with(density) { spec.lensAmount.toPx() },
                        with(density) { spec.lensAmount.toPx() },
                    )
                },
                onDrawSurface = {
                    drawGlassTint(spec)
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}
```

Add imports at top of file (they may already exist — verify):

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Shape
```

---

### Task 3: Convert iOSTopBar to chrome-level glass + collapsible

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\apple\AppleComponents.kt` (iOSTopBar composable at lines 532-579)

**Step 1: Replace iOSTopBar body**

Replace the entire iOSTopBar composable (lines 531-579) with:

```kotlin
@Composable
fun iOSTopBar(
    title: String,
    modifier: Modifier = Modifier,
    largeTitle: Boolean = true,
    collapsedRatio: Float = 0f,  // 0f = expanded, 1f = fully collapsed
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val topBarSpec = LocalGlassConfig.current.topBar.toSpec()

    val titleFontSize = if (largeTitle) {
        // Animate between 34.sp (expanded) and 17.sp (collapsed)
        34f - (17f * collapsedRatio)
    } else 17f

    val titleWeight = if (largeTitle && collapsedRatio < 0.5f) FontWeight.Bold
                      else FontWeight.SemiBold

    val barHeight = if (largeTitle) {
        96.dp * (1f - collapsedRatio * 0.54f)  // 96dp -> 44dp
    } else 44.dp

    LiquidGlassTopBar(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                LiquidGlassControlPill(
                    modifier = Modifier.size(36.dp),
                    onClick = onBack,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = title,
                fontSize = titleFontSize.sp,
                fontWeight = titleWeight,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (largeTitle) 8.dp else 0.dp),
            )
            if (actions != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
    }
}
```

**Step 2: Add new imports to AppleComponents.kt**

Add near top of file:

```kotlin
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.NonRestartableComposable
import com.smartvision.gallery.ui.liquidglass.LiquidGlassTopBar
import com.smartvision.gallery.ui.liquidglass.LiquidGlassControlPill
```

---

### Task 4: Convert iOSSegmentedControl to glass

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\apple\AppleComponents.kt` (iOSSegmentedControl at lines 584-622)

**Step 1: Replace iOSSegmentedControl body**

Replace the composable (lines 584-622) with:

```kotlin
@Composable
fun iOSSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val controlSpec = LocalGlassConfig.current.control.toSpec()
    val backdrop = LocalLiquidGlassBackdrop.current

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(12.dp) },
                effects = {
                    if (controlSpec.vibrancy) vibrancy()
                    blur(with(density) { controlSpec.blurRadius.toPx() })
                },
                onDrawSurface = {
                    drawGlassTint(controlSpec)
                },
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .let {
                        if (isSelected) {
                            it.background(Color.White.copy(alpha = 0.95f))
                        } else it
                    }
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) Color.Black
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

**Step 2: Add imports**

```kotlin
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassBackdrop
import com.smartvision.gallery.ui.liquidglass.drawGlassTint
```

---

### Task 5: Add iOSButtonStyle.Glass + GlassSymbol

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\apple\AppleComponents.kt` (iOSButton + related)

**Step 1: Add Glass to the enum**

Replace line 796:

```kotlin
enum class iOSButtonStyle { Primary, Secondary, Destructive, Plain, Glass, GlassSymbol }
```

**Step 2: Replace iOSButton composable**

Replace lines 798-826 with:

```kotlin
@Composable
fun iOSButton(
    text: String,
    modifier: Modifier = Modifier,
    style: iOSButtonStyle = iOSButtonStyle.Primary,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val controlSpec = LocalGlassConfig.current.control.toSpec()
    val backdrop = LocalLiquidGlassBackdrop.current

    val (bg, fg) = when (style) {
        iOSButtonStyle.Primary -> MaterialTheme.colorScheme.primary to Color.White
        iOSButtonStyle.Secondary -> Color.White.copy(alpha = 0.18f) to MaterialTheme.colorScheme.onSurface
        iOSButtonStyle.Destructive -> Color(0xFFFF3B30) to Color.White
        iOSButtonStyle.Plain -> Color.Transparent to MaterialTheme.colorScheme.primary
        iOSButtonStyle.Glass -> Color.Transparent to MaterialTheme.colorScheme.onSurface
        iOSButtonStyle.GlassSymbol -> Color.Transparent to MaterialTheme.colorScheme.onSurface
    }

    var pressed by remember { mutableStateOf(false) }

    val baseMod = modifier
        .clip(if (style == iOSButtonStyle.GlassSymbol) CircleShape else RoundedCornerShape(14.dp))

    val finalMod = when (style) {
        iOSButtonStyle.Glass -> baseMod.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(999.dp) },
            effects = {
                if (controlSpec.vibrancy) vibrancy()
                blur(with(density) { controlSpec.blurRadius.toPx() })
                lens(
                    with(density) { controlSpec.lensAmount.toPx() },
                    with(density) { controlSpec.lensAmount.toPx() },
                )
            },
            onDrawSurface = { drawGlassTint(controlSpec) },
        )
        iOSButtonStyle.GlassSymbol -> baseMod.size(44.dp).drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                if (controlSpec.vibrancy) vibrancy()
                blur(with(density) { controlSpec.blurRadius.toPx() })
                lens(
                    with(density) { controlSpec.lensAmount.toPx() },
                    with(density) { controlSpec.lensAmount.toPx() },
                )
            },
            onDrawSurface = { drawGlassTint(controlSpec) },
        )
        iOSButtonStyle.Primary, iOSButtonStyle.Secondary,
        iOSButtonStyle.Destructive, iOSButtonStyle.Plain -> baseMod.background(bg)
    }

    Box(
        modifier = finalMod
            .graphicsLayer {
                val s = if (pressed) 0.97f else 1f
                scaleX = s
                scaleY = s
                alpha = if (pressed) 0.7f else 1f
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    }
                )
            }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
    }
}
```

**Step 3: Add imports**

```kotlin
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.ripple.MutableInteractionSource
// or if no ripple import exists:
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
```

---

### Task 6: Convert iOSToggle to iOS 26 spec

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\apple\AppleComponents.kt` (iOSToggle at lines 627-659)

Replace the composable with:

```kotlin
@Composable
fun iOSToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val density = LocalDensity.current
    val toggleCfg = LocalGlassConfig.current.toggle
    val onColor = Color(
        red = ((toggleCfg.onColorArgb shr 16) and 0xFF).toInt(),
        green = ((toggleCfg.onColorArgb shr 8) and 0xFF).toInt(),
        blue = (toggleCfg.onColorArgb and 0xFF).toInt(),
    )
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color(0xFFE5E5EA)
            checked -> onColor
            else -> Color(0xFF787880).copy(alpha = toggleCfg.offTrackAlpha)
        },
        label = "toggleTrack",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "toggleThumb",
    )
    val knobSize = toggleCfg.knobDiameter

    Box(
        modifier = modifier
            .size(width = toggleCfg.width, height = toggleCfg.height)
            .clip(RoundedCornerShape(toggleCfg.trackCornerRadius))
            .background(trackColor)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
    ) {
        Box(
            modifier = Modifier
                .padding(start = thumbOffset, top = (toggleCfg.height - knobSize) / 2)
                .size(knobSize)
                .clip(CircleShape)
                .background(Color.White)
                .graphicsLayer {
                    shadowElevation = with(density) { toggleCfg.knobShadowBlur.toPx() }
                    shape = CircleShape
                    clip = false
                }
                .align(Alignment.CenterStart),
        )
    }
}
```

Add imports:

```kotlin
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.shadow
```

---

### Task 7: Convert iOSListGroupedContainer to Liquid Glass background

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\apple\AppleComponents.kt` (lines 829-841)

Replace with:

```kotlin
@Composable
fun iOSListGroupedContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        spec = LiquidGlassSpec.iOS26Static.copy(
            cornerRadius = 0.dp,
            shadowElevation = 0.dp,
            blurRadius = 12.dp,
            tintAlpha = 0.08f,
        ),
    ) {
        content()
    }
}
```

---

### Task 8: Integrate collapsible top bar with TimelinePage

**Files:**
- Modify: `H:\workspace-minimaxcode\超级相册\app\src\main\java\com\smartvision\gallery\ui\pages\TimelinePage.kt`

**Step 1: Add scroll state tracking to TimelinePage**

Replace the Column in TimelinePage (lines 89-133) — the page content should use a `LazyColumn` instead of `LazyVerticalGrid` inside a Column, OR we keep the Column+LazyVerticalGrid and add a `LazyListState` to the grid.

Actually, looking at the current code more carefully: the Column contains `iOSLargeTitle`, `iOSSegmentedControl`, `CuratedCollectionsRow`, and then `LazyVerticalGrid`. The top bar that needs collapsible behavior is actually the title + segmented control.

The simplest approach: Use the LazyVerticalGrid's `lazyListState` to detect scroll position, and pass `collapsedRatio` down. Add a spacer at the top of the grid for the collapsed bar.

Add at the top of TimelinePage composable, before the Column (after line 85):

```kotlin
val gridState = rememberLazyListState()
val collapsedRatio by remember {
    derivedStateOf {
        if (gridState.firstVisibleItemIndex > 0) 1f
        else {
            val offset = gridState.firstVisibleItemScrollOffset
            (offset / 200f).coerceIn(0f, 1f)  // collapse over 200px scroll
        }
    }
}
```

**Step 2: Update the Column header**

Replace lines 95-105 with:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .background(bgBrush)
) {
    // Collapsible glass top bar
    iOSTopBar(
        title = "图库",
        largeTitle = true,
        collapsedRatio = collapsedRatio,
    )

    // Segmented control — fades as it collapses
    androidx.compose.animation.AnimatedVisibility(
        visible = collapsedRatio < 0.8f,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        iOSSegmentedControl(
            options = listOf("全部", "日月年", "选择"),
            selected = segment,
            onSelect = { segment = it },
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth(),
        )
    }
```

**Step 3: Remove iOSLargeTitle**

Remove the `iOSLargeTitle` call (currently line 96) since the title is now in `iOSTopBar`.

**Step 4: Pass gridState to LazyVerticalGrid**

Replace `LazyVerticalGrid(` with:

```kotlin
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
```

**Step 5: Add imports**

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.rememberLazyListState
import com.smartvision.gallery.ui.apple.iOSTopBar
```
