---
name: liquidglass_full_ui_conversion
description: Spec for converting all remaining traditional AppleComponents to iOS 26 Liquid Glass, including new GlassConfig specs, collapsible top bar, glass buttons, glass segmented control, glass toggle
metadata:
  type: project
---

# iOS 26 Liquid Glass Full UI Conversion — Design Spec

> Approved 2026-06-26. All-in-one conversion (Approach A).

## Summary

Convert all remaining traditional UI components in `AppleComponents.kt` to iOS 26 Liquid Glass. Add new GlassConfig specs for chrome-level (top bar), control-level (buttons, toggle, segmented), and surface-level (cards/list containers) glass variants. Implement collapsible large-title navigation bar with scroll behavior.

## New GlassConfig Specs

### TopBarGlassConfig (chrome-level, medium)
- `cornerRadius: Dp = 0.dp` (full-width bar)
- `blurRadius: Dp = 12.dp` (matches iOS 26 toolbar medium blur)
- `tintArgb: Long = 0xFFF2F7FFL`
- `tintAlpha: Float = 0.15f`
- LARGE title height: 96pt, COMPACT title height: 44pt
- Collapse animation: 0.3s spring

### ControlGlassConfig (control-level, small)
- `cornerRadius: Dp = 999.dp` (pill)
- `blurRadius: Dp = 7.dp` (matches iOS 26 small control)
- `tintArgb: Long = 0xFFF0F4FFL`
- `tintAlpha: Float = 0.08f`

### SegmentedGlassConfig
- `cornerRadius: Dp = 12.dp` (container), 9.dp (selected pill)
- `blurRadius: Dp = 7.dp`
- Selected segment: white pill (`Color.White.copy(alpha = 0.95f)`)
- Track background: rgba(116,116,128,0.08) / rgba(118,118,128,0.18)

### ToggleGlassConfig
- Width: 51.dp, Height: 31.dp
- Track corner radius: 15.5.dp
- Knob diameter: 27.dp
- Knob shadow: 0 1px 3px rgba(0,0,0,0.3)
- ON color: #0088FF
- OFF track: rgba(118,118,128,0.12) / rgba(118,118,128,0.24)

## Component Changes

### 1. iOSTopBar — Full Chrome-Level Glass + Collapse
- Wrap in `LiquidGlassSurface` using `TopBarGlassConfig`
- Add large-title-to-compact collapse animation on scroll
- Back button becomes 36dp glass circle (ControlGlassConfig)
- Right action buttons become 36dp glass circles
- Title text stays on top of glass background

### 2. iOSSegmentedControl — Glass with Selected Pill
- Container background: ControlGlassConfig track
- Selected segment: solid white pill with shadow
- Unselected: transparent with primary text
- Optional `scrollBehavior` parameter for pinned/scroll modes

### 3. iOSButton — New Glass Styles
- Keep Primary (solid blue), Destructive (red solid) as-is
- Add `iOSButtonStyle.Glass` — 44pt pill, ControlGlassConfig, press scale 0.97
- Add `iOSButtonStyle.GlassSymbol` — 44x44pt circle, ControlGlassConfig
- Keep Plain (text only) as-is
- Keep Secondary as tinted glass

### 4. iOSToggle — Glass Track with iOS 26 Spec
- Track: Use ControlGlassConfig backdrop with drawBackdrop
- ON: cyan/blue tint overlay
- OFF: gray transparent
- Knob: White circle with shadow
- 0.3s spring snappy animation

### 5. iOSListGroupedContainer — Liquid Glass Background
- Use `LiquidGlassSurface` with `StaticGlassConfig` instead of solid background color
- Maintain grouped section layout

### 6. iOSLargeTitle — Integrated with iOSTopBar
- Remove standalone usage, integrate into iOSTopBar's collapse behavior
- Large title (34pt) → compact (17pt) on scroll

### 7. Curated Collections Cards — Glass Surface
- Use `LiquidGlassSurface` with blur(12dp) for card backgrounds
- Maintain existing gradient overlay for readability

## Files Changed

| File | Change |
|------|--------|
| `data/glass/GlassConfig.kt` | Add TopBarGlassConfig, ControlGlassConfig, ToggleGlassConfig |
| `ui/liquidglass/LiquidGlassSpec.kt` | Add iOS26TopBar, iOS26Control, iOS26Toggle specs |
| `ui/apple/AppleComponents.kt` | iOSTopBar → glass+scroll, iOSSegmentedControl → glass, iOSButton → +Glass style, iOSToggle → glass, iOSListGroupedContainer → glass bg |
| `ui/liquidglass/LiquidGlassComponents.kt` | Maybe add `LiquidGlassTopBar`, `LiquidGlassSegmentedPill` helpers |
| `ui/pages/TimelinePage.kt` | Integrate collapsible top bar |
| `ui/settings/SettingsPage.kt` | Uses glass list rows automatically |