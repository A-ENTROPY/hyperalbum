package com.smartvision.gallery.data.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSpec

/**
 * Container for all 4 user-tunable Liquid Glass specs.
 *
 * Each spec is a plain data class (no Composable functions) so the same
 * instance can be:
 *  - read out of DataStore as JSON,
 *  - held in a ViewModel `StateFlow`,
 *  - passed to a `LiquidGlassSpec(...)` factory for live consumption.
 *
 * Defaults match the iOS 26 values in [LiquidGlassSpec.Companion] so an
 * app that has never opened the tuning page behaves exactly as before.
 */
data class GlassConfig(
    val tabBar: TabBarGlassConfig = TabBarGlassConfig(),
    val staticGlass: StaticGlassConfig = StaticGlassConfig(),
    val topBar: TopBarGlassConfig = TopBarGlassConfig(),
    val control: ControlGlassConfig = ControlGlassConfig(),
    val toggle: ToggleGlassConfig = ToggleGlassConfig(),
    val lens: LensGlassConfig = LensGlassConfig(),
    val backdrop: BackdropGlassConfig = BackdropGlassConfig(),
    val background: BackgroundGlassConfig = BackgroundGlassConfig(),
    val searchBar: SearchBarGlassConfig = SearchBarGlassConfig(),
    val chipFilter: ChipFilterGlassConfig = ChipFilterGlassConfig(),
    val heroFrost: HeroFrostGlassConfig = HeroFrostGlassConfig(),
)

data class TabBarGlassConfig(
    val cornerRadius: Dp = 32.dp,
    val shadowElevation: Dp = 1.1396973.dp,
    val blurRadius: Dp = 3.0147204.dp,
    val lensAmount: Dp = 32.dp,
    val tintArgb: Long = 0xFFEAF4FFL,
    val tintAlpha: Float = 0.15f,
    val highlightAlpha: Float = 0.35f,
    val vibrancy: Boolean = true,
    // 3D layered effect controls (iOS 26/27 cues). iOS 26 tab bar reads as
    // a polished pill so defaults are punchier than static surfaces.
    val specularAlpha: Float = 0.55f,    // top bright edge + glint (0..1)
    val bottomShadowAlpha: Float = 0.07801767f, // bottom dark inner shadow (0..1)
    val edgeDarkAlpha: Float = 0.06835171f,    // bottom + side dark edges (0..1)
    val topTintExtra: Float = 0.45f,     // extra top-half tint density (0..1)
)

data class StaticGlassConfig(
    val cornerRadius: Dp = 18.dp,
    val shadowElevation: Dp = 6.dp,
    val blurRadius: Dp = 2.9755275.dp,
    val lensAmount: Dp = 20.072989.dp,
    val tintArgb: Long = 0xFFF0F6FFL,
    val tintAlpha: Float = 0.12f,
    val highlightAlpha: Float = 0.35f,
    val vibrancy: Boolean = true,
    // 3D layered effect controls (Apple iOS 26/27 cues) — all tunable in
    // the playground so the user can dial each layer down if the look
    // is too punchy on their device.
    val specularAlpha: Float = 0.45f,    // 0..1 — top bright edge + glint
    val bottomShadowAlpha: Float = 0.086947605f, // 0..1 — bottom dark inner shadow
    val edgeDarkAlpha: Float = 0.08635917f,    // 0..1 — bottom + side dark edges
    val topTintExtra: Float = 0.35f,     // 0..1 — extra top-half tint density
)

data class TopBarGlassConfig(
    val cornerRadius: Dp = 0.dp,
    val shadowElevation: Dp = 0.dp,
    val blurRadius: Dp = 3.1104944.dp,
    val lensAmount: Dp = 20.035328.dp,
    val tintArgb: Long = 0xFFF2F7FFL,
    val tintAlpha: Float = 0.12f,
    val highlightAlpha: Float = 0.15f,
    val vibrancy: Boolean = true,
    // 3D layered effect controls. Top bar chrome is full-width rect with
    // very subtle tint — defaults are lighter than static / tab bar.
    val specularAlpha: Float = 0.25f,    // top bright edge + glint (0..1)
    val bottomShadowAlpha: Float = 0.05f, // bottom dark inner shadow (0..1)
    val edgeDarkAlpha: Float = 0.07968616f,    // bottom edge separation (0..1)
    val topTintExtra: Float = 0.25f,     // extra top-half tint density (0..1)
)

data class ControlGlassConfig(
    val cornerRadius: Dp = 999.dp,
    val shadowElevation: Dp = 4.dp,
    val blurRadius: Dp = 2.997056.dp,
    val lensAmount: Dp = 19.95682.dp,
    val lensPressExtra: Dp = 64.dp,
    val tintArgb: Long = 0xFFF0F4FFL,
    val tintAlpha: Float = 0.08f,
    val highlightAlpha: Float = 0.3f,
    val vibrancy: Boolean = true,
    // 3D layered effect controls. Controls (chips / segmented tracks) are
    // small so the punchier specular would feel like raised plastic — keep
    // moderate defaults and let the playground dial up if desired.
    val specularAlpha: Float = 0.40f,    // top bright edge + glint (0..1)
    val bottomShadowAlpha: Float = 0.101177245f, // bottom dark inner shadow (0..1)
    val edgeDarkAlpha: Float = 0.10f,    // bottom + side dark edges (0..1)
    val topTintExtra: Float = 0.30f,     // extra top-half tint density (0..1)
)

data class SearchBarGlassConfig(
    val cornerRadius: Dp = 16.dp,
    val shadowElevation: Dp = 1.0542965.dp,
    val blurRadius: Dp = 3.0453568.dp,
    val lensAmount: Dp = 19.981937.dp,
    val tintArgb: Long = 0xFFF0F4FFL,
    val tintAlpha: Float = 0.10f,
    val highlightAlpha: Float = 0.30f,
    val vibrancy: Boolean = true,
    // 3D layered effect controls. Search bar is wider & taller than a
    // chip so it can carry slightly stronger specular without looking
    // like raised plastic.
    val specularAlpha: Float = 0.35f,    // top bright edge + glint (0..1)
    val bottomShadowAlpha: Float = 0.09921453f, // bottom dark inner shadow (0..1)
    val edgeDarkAlpha: Float = 0.10f,    // bottom + side dark edges (0..1)
    val topTintExtra: Float = 0.30f,     // extra top-half tint density (0..1)
)

/**
 * 筛选芯片栏 (AlbumDetailPage 全部/视频/收藏/实况).
 *
 * 物理参数控制芯片选中切换的弹簧动画:
 *  - springDampingRatio: 0.1(弹) ~ 1.0(无弹), 默认 0.6 轻微过冲
 *  - springStiffness: 刚度, 100(软) ~ 1000(硬), 默认 300
 *  - selectedScale: 选中芯片的放大倍数, 1.0~1.15
 *  - floatingElevation: 整行浮动条的高度偏移 dp
 */
/**
 * Hero 渐变磨砂玻璃 (AlbumDetailPage 卡片下方).
 *
 * 在 HeroGlassCard 的 opaque 信息卡下方渲染一层渐变液态磨砂玻璃:
 *  - 垂直渐变: 上方透明 → 下方磨砂玻璃 (fadeStart → fadeEnd)
 *  - drawBackdrop + blur/vibrancy/lens 实现动态模糊
 *  - 叠加 drawGlassTint 实现 3D 立体效果
 */
data class HeroFrostGlassConfig(
    val blurRadius: Dp = 20.424711.dp,
    val lensAmount: Dp = 30.01058.dp,
    val vibrancy: Boolean = true,
    val tintArgb: Long = 0xFFF0F4FFL,
    val tintAlpha: Float = 0.10f,
    val highlightAlpha: Float = 0.20f,
    // 渐变控制 — 磨砂从位置 fadeStart (0 = 顶) 到 fadeEnd (1 = 底) 渐显
    val fadeStart: Float = 0.30f,
    val fadeEnd: Float = 0.85f,
    val specularAlpha: Float = 0.30f,
    val bottomShadowAlpha: Float = 0.11040235f,
    val edgeDarkAlpha: Float = 0.08f,
    val topTintExtra: Float = 0.25f,
)

data class ChipFilterGlassConfig(
    // 视觉参数默认值为设备调试值 (GlassPlayground 调好), 与 ControlGlassConfig 风格统一
    val cornerRadius: Dp = 30.297167.dp,
    val shadowElevation: Dp = 4.dp,
    val blurRadius: Dp = 3.0264966.dp,
    val lensAmount: Dp = 20.745031.dp,
    val tintArgb: Long = 0xFFF0F4FFL,
    val tintAlpha: Float = 0.08f,
    val highlightAlpha: Float = 0.3f,
    val vibrancy: Boolean = true,
    val specularAlpha: Float = 0.40f,
    val bottomShadowAlpha: Float = 0.07154111f,
    val edgeDarkAlpha: Float = 0.10f,
    val topTintExtra: Float = 0.30f,
    // 物理动画参数 (chip 独有, 在 GlassPlayground 可调)
    val springDampingRatio: Float = 0.2869336f,
    val springStiffness: Float = 100f,
    val selectedScale: Float = 1.0977943f,
    val floatingElevation: Dp = 10.013742.dp,
)

data class ToggleGlassConfig(
    val width: Dp = 51.dp,
    val height: Dp = 31.dp,
    val trackCornerRadius: Dp = 15.5.dp,
    val knobDiameter: Dp = 27.dp,
    val knobShadowBlur: Dp = 3.dp,
    val onColorArgb: Long = 0xFF0088FFL,
    val offTrackAlpha: Float = 0.12f,
    val blurRadius: Dp = 5.5395455.dp,
)

data class BackgroundGlassConfig(
    val blurRadius: Dp = 48.dp,
    val lensAmount: Dp = 0.dp,
    val vibrancy: Boolean = true,
    val lightTintArgb: Long = 0xFFFFFFFFL,
    val lightTintAlpha: Float = 0.18f,
    val darkTintArgb: Long = 0xFF1A1A2EL,
    val darkTintAlpha: Float = 0.40f,
    val highlightAlpha: Float = 0.08f,
    // 3D layered effect controls (iOS 26/27 cues). The wallpaper overlay
    // is full-screen, so 3D knobs shape its overall light/shadow rather
    // than glass-edge micro-detail. Defaults are subtle.
    val specularAlpha: Float = 0.20f,    // top highlight overlay (0..1)
    val bottomShadowAlpha: Float = 0.08390579f, // bottom dark inner shadow (0..1)
    val edgeDarkAlpha: Float = 0.08105948f,    // edge darkening (0..1)
    val topTintExtra: Float = 0.25f,     // extra top-half tint density (0..1)
)

data class LensGlassConfig(
    // Kyant drawBackdrop + lens() path.
    // 100dp diameter fits the bar height (~97dp) so the lens sits on the bar
    // without spilling over the page below. Reference: 形变参考/*.jpg — lens is
    // a circle roughly matching the bar's vertical extent.
    val lensSize: Dp = 100.dp,             // 96–140 — magnifier container diameter
    val lensRefractionHeight: Dp = 14.004309.dp,  // 0–24 — Kyant lens refraction height
    val lensRefractionAmount: Dp = 16.442223.dp,  // 0–24 — Kyant lens refraction amount

    // Controller-driven stretch. iOS 26 is SUBTLE — the lens widens only
    // slightly into a rounded rectangle (capsule), not a sharp ellipse.
    // `stretchMax` is a width multiplier on the bar's height (1.0 = circle,
    // 1.5 = slight capsule). `squashMax` is kept at 0 because vertical squash
    // would produce a sharp ellipse look that contradicts iOS 26.
    val stretchMax: Float = 1.5f,          // 0.0–3.0 — width multiplier cap (subtle)
    val squashMax: Float = 0.0f,           // 0.0 only — no vertical squash

    // Drop-target icon highlight (read by iOSTabBarItem in AppleComponents.kt).
    // 1.0 = neutral (no zoom); 0.4 = shrinker (content reads 40% size);
    // 2.0 = magnifier (content reads 2x size). Default is neutral so
    // an unconfigured app doesn't introduce a surprise scale.
    //
    // 2026-07-04: Variable magnification was removed from the lens overlays
    // because Kyant drawBackdrop+lens() cannot decouple sample region from
    // visible region. The slider was removed from GlassConfigPanel. The field
    // is retained here ONLY to keep the CSV encoder/decoder stable for users
    // who had saved configs — reading it has no visual effect.
    val iconScaleInside: Float = 1.0f,    // inert — kept for CSV compatibility
    val iconTintAlpha: Float = 1.0f,       // 0.0–1.0 — drop-target icon tint alpha

    // Lens refraction chromatic aberration (RGB channel split). Floats let
    // the playground dial smoothly between no-split (0.0) and full-split
    // (1.0); the Kyant `lens()` shader accepts only `Boolean`, so we
    // threshold at the call site (`> 0.5f`).
    val lensChromaticAberration: Float = 1.0f,  // 0.0–1.0
)

data class BackdropGlassConfig(
    val lightStart: Long = 0xFFFFE4ECL,
    val lightMid: Long = 0xFFE8F4FFL,
    val lightEnd: Long = 0xFFFFF8E8L,
    val darkStart: Long = 0xFF2A1B2EL,
    val darkMid: Long = 0xFF1A2B3DL,
    val darkEnd: Long = 0xFF2E2A1BL,
)

/** Convert a config row into the [LiquidGlassSpec] the live composables read. */
fun TabBarGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(
    cornerRadius = cornerRadius,
    shadowElevation = shadowElevation,
    blurRadius = blurRadius,
    vibrancy = vibrancy,
    lensAmount = lensAmount,
    tint = argbToColor(tintArgb),
    tintAlpha = tintAlpha,
    highlightAlpha = highlightAlpha,
    specularAlpha = specularAlpha,
    bottomShadowAlpha = bottomShadowAlpha,
    edgeDarkAlpha = edgeDarkAlpha,
    topTintExtra = topTintExtra,
)

fun StaticGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(
    cornerRadius = cornerRadius,
    shadowElevation = shadowElevation,
    blurRadius = blurRadius,
    vibrancy = vibrancy,
    lensAmount = lensAmount,
    tint = argbToColor(tintArgb),
    tintAlpha = tintAlpha,
    highlightAlpha = highlightAlpha,
    specularAlpha = specularAlpha,
    bottomShadowAlpha = bottomShadowAlpha,
    edgeDarkAlpha = edgeDarkAlpha,
    topTintExtra = topTintExtra,
)

fun TopBarGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(
    cornerRadius = cornerRadius,
    shadowElevation = shadowElevation,
    blurRadius = blurRadius,
    vibrancy = vibrancy,
    lensAmount = lensAmount,
    tint = argbToColor(tintArgb),
    tintAlpha = tintAlpha,
    highlightAlpha = highlightAlpha,
    specularAlpha = specularAlpha,
    bottomShadowAlpha = bottomShadowAlpha,
    edgeDarkAlpha = edgeDarkAlpha,
    topTintExtra = topTintExtra,
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
    specularAlpha = specularAlpha,
    bottomShadowAlpha = bottomShadowAlpha,
    edgeDarkAlpha = edgeDarkAlpha,
    topTintExtra = topTintExtra,
)

fun SearchBarGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(
    cornerRadius = cornerRadius,
    shadowElevation = shadowElevation,
    blurRadius = blurRadius,
    vibrancy = vibrancy,
    lensAmount = lensAmount,
    tint = argbToColor(tintArgb),
    tintAlpha = tintAlpha,
    highlightAlpha = highlightAlpha,
    specularAlpha = specularAlpha,
    bottomShadowAlpha = bottomShadowAlpha,
    edgeDarkAlpha = edgeDarkAlpha,
    topTintExtra = topTintExtra,
)

fun ChipFilterGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(
    cornerRadius = cornerRadius,
    shadowElevation = shadowElevation,
    blurRadius = blurRadius,
    vibrancy = vibrancy,
    lensAmount = lensAmount,
    tint = argbToColor(tintArgb),
    tintAlpha = tintAlpha,
    highlightAlpha = highlightAlpha,
    specularAlpha = specularAlpha,
    bottomShadowAlpha = bottomShadowAlpha,
    edgeDarkAlpha = edgeDarkAlpha,
    topTintExtra = topTintExtra,
)

fun HeroFrostGlassConfig.toSpec(): LiquidGlassSpec = LiquidGlassSpec(
    cornerRadius = 0.dp,
    shadowElevation = 0.dp,
    blurRadius = blurRadius,
    vibrancy = vibrancy,
    lensAmount = lensAmount,
    tint = argbToColor(tintArgb),
    tintAlpha = tintAlpha,
    highlightAlpha = highlightAlpha,
    specularAlpha = specularAlpha,
    bottomShadowAlpha = bottomShadowAlpha,
    edgeDarkAlpha = edgeDarkAlpha,
    topTintExtra = topTintExtra,
)


/**
 * Convert a 0xAARRGGBB Long into a proper sRGB Compose [Color]. The
 * `Color(Long)` / `Color(ULong)` constructor expects a 64-bit value with
 * HDR / color-space metadata in the upper bits; passing a raw ARGB Long
 * there produces an invalid color and crashes `Color.copy()` at draw time.
 * Channel-by-channel construction produces a normal sRGB color.
 */
internal fun argbToColor(argb: Long): Color {
    val a = ((argb shr 24) and 0xFF).toInt()
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    return Color(red = r, green = g, blue = b, alpha = a)
}
