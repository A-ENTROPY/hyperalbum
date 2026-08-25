package com.smartvision.gallery.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.smartvision.gallery.data.glass.BackdropGlassConfig
import com.smartvision.gallery.data.glass.BackgroundGlassConfig
import com.smartvision.gallery.data.glass.ChipFilterGlassConfig
import com.smartvision.gallery.data.glass.ControlGlassConfig
import com.smartvision.gallery.data.glass.GlassConfig
import com.smartvision.gallery.data.glass.HeroFrostGlassConfig
import com.smartvision.gallery.data.glass.LensGlassConfig
import com.smartvision.gallery.data.glass.SearchBarGlassConfig
import com.smartvision.gallery.data.glass.StaticGlassConfig
import com.smartvision.gallery.data.glass.TabBarGlassConfig
import com.smartvision.gallery.data.glass.ToggleGlassConfig
import com.smartvision.gallery.data.glass.TopBarGlassConfig
import com.smartvision.gallery.data.glass.argbToColor
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSpec
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassBackdrop
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassScreenBackdrop
import com.smartvision.gallery.ui.liquidglass.LiquidGlassBar
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import com.smartvision.gallery.ui.liquidglass.LiquidGlassControlPill
import com.smartvision.gallery.ui.liquidglass.LiquidGlassTopBar

/**
 * Tuning page for the iOS 26 Liquid Glass effect — all 8 sub-specs.
 *
 * Visual layout (iOS Settings inspired):
 *
 *   ┌──────────────────────────────────┐
 *   │  HERO CARD                       │   big colorful glass showcase
 *   │  LivePreview — 6 surfaces        │
 *   ├──────────────────────────────────┤
 *   │  Tab Bar            [↑ pill]     │   each spec lives in its own glass card
 *   │   ─────────────────────────────  │   with a tiny inline preview chip at top
 *   │   5 sliders + vibrancy switch    │
 *   ├──────────────────────────────────┤
 *   │  Static Card         [↑ card]    │
 *   │   ...                            │
 *   ├──────────────────────────────────┤
 *   │  TopBar / Control / Toggle /     │
 *   │  Lens / Backdrop / Background    │
 *   └──────────────────────────────────┘
 *
 * Reads the live config from [vm] (a [GlassConfigViewModel]). Every
 * slider change calls the corresponding `vm.setXxx(...)` which writes
 * to DataStore; the StateFlow re-emits and the live-preview surfaces
 * (and the rest of the app) re-render in the same frame.
 *
 * ## Render-tree architecture
 *
 * The playground is rendered as a SIBLING of the app's main
 * `Modifier.layerBackdrop(liquidBackdrop)` capture Box — see AppRoot.
 * That means LivePreview's `Modifier.drawBackdrop` primitives (which
 * nest graphics layers internally) are NOT inside a `layerBackdrop`
 * capture, avoiding the recursive `prepareTreeImpl` stack overflow
 * that crashed the page on ColorOS 16.
 */
@Composable
fun GlassConfigPanel(
    vm: GlassConfigViewModel,
    modifier: Modifier = Modifier,
) {
    val config by vm.config.collectAsState()

    // Match [WallpaperGlassBackground] so the playground's glass surfaces
    // refract the EXACT same colorful backdrop the Settings page does.
    // Glass surfaces above blur this layer via `drawBackdrop`, producing the
    // iOS 26 frosted-glass look. Light/dark variants match WallpaperGlass.
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val stops = if (isDark) listOf(
        Color(0xFF3D2418),  // deep cocoa
        Color(0xFF2E2235),  // muted plum
        Color(0xFF352030),  // wine
    ) else listOf(
        Color(0xFFFCD9BD),  // warm peach (top)
        Color(0xFFFAEFE6),  // cream (middle)
        Color(0xFFF1E2EA),  // soft pink-lavender (bottom)
    )
    val accentStart = if (isDark) Color(0xFFFF9F66).copy(alpha = 0.18f)
                      else Color(0xFFFFB58A).copy(alpha = 0.45f)
    val accentEnd = if (isDark) Color(0xFFE07090).copy(alpha = 0.16f)
                    else Color(0xFFE8B6CC).copy(alpha = 0.45f)

    val previewBackdrop = rememberCanvasBackdrop {
        drawPlaygroundDemoBackdrop(stops, accentStart, accentEnd)
    }

    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides previewBackdrop,
        LocalLiquidGlassScreenBackdrop provides previewBackdrop,
    ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ---- HEADER ----
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "液态玻璃",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                        )
                        Text(
                            text = "实时调参 · 拖动滑块立即生效",
                            fontSize = 14.sp,
                            color = Color(0xFF666666),
                        )
                    }
                    SpecCountBadge(count = 11)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = { vm.reset() },
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(text = "重置全部", fontSize = 13.sp)
                    }
                }
            }

            // ---- HERO LIVE PREVIEW (glass showcase card) ----
            GlassSectionCard(
                title = "实时预览",
                subtitle = "6 种液态玻璃面 · 全部跟随滑块",
            ) {
                LivePreview(config = config)
            }

            // ---- TAB BAR ----
            GlassSectionCard(
                title = "底栏",
                subtitle = "Tab Bar · 主药丸",
                inlinePreview = {
                    TinyTabBarPreview(spec = config.tabBar.toSpec())
                },
            ) {
                TabBarSection(config = config.tabBar, onChange = vm::setTabBar)
            }

            // ---- STATIC CARD ----
            GlassSectionCard(
                title = "静态卡片",
                subtitle = "静态卡片",
                inlinePreview = {
                    TinyStaticPreview(spec = config.staticGlass.toSpec())
                },
            ) {
                StaticSection(config = config.staticGlass, onChange = vm::setStatic)
            }

            // ---- TOP BAR ----
            GlassSectionCard(
                title = "顶栏",
                subtitle = "顶栏",
                inlinePreview = {
                    TinyTopBarPreview(tint = config.topBar.tintArgb, alpha = config.topBar.tintAlpha)
                },
            ) {
                TopBarSection(config = config.topBar, onChange = vm::setTopBar)
            }

            // ---- CONTROL ----
            GlassSectionCard(
                title = "控件",
                subtitle = "Control · 按钮 / 芯片",
                inlinePreview = {
                    TinyControlPreview(
                        tint = config.control.tintArgb,
                        alpha = config.control.tintAlpha,
                        cornerRadius = config.control.cornerRadius.value.toInt(),
                    )
                },
            ) {
                ControlSection(config = config.control, onChange = vm::setControl)
            }

            // ---- TOGGLE ----
            GlassSectionCard(
                title = "开关",
                subtitle = "开关",
                inlinePreview = { TogglePreview(config = config.toggle) },
            ) {
                ToggleSection(config = config.toggle, onChange = vm::setToggle)
            }

            // ---- SEARCH BAR ----
            GlassSectionCard(
                title = "搜索栏",
                subtitle = "Search Bar · 搜索页输入框",
                inlinePreview = {
                    TinySearchBarPreview(spec = config.searchBar.toSpec())
                },
            ) {
                SearchBarSection(config = config.searchBar, onChange = vm::setSearchBar)
            }

            // ---- CHIP FILTER ----
            GlassSectionCard(
                title = "筛选芯片",
                subtitle = "全部/视频/收藏/实况",
                inlinePreview = {
                    TinyChipFilterPreview(spec = config.chipFilter.toSpec())
                },
            ) {
                ChipFilterSection(config = config.chipFilter, onChange = vm::setChipFilter)
            }

            // ---- HERO FROST ----
            GlassSectionCard(
                title = "Hero 磨砂玻璃",
                subtitle = "相册详情页卡片渐变磨砂",
                inlinePreview = {
                    TinyHeroFrostPreview(spec = config.heroFrost.toSpec())
                },
            ) {
                HeroFrostSection(config = config.heroFrost, onChange = vm::setHeroFrost)
            }

            // ---- LENS ----
            GlassSectionCard(
                title = "放大镜",
                subtitle = "Lens · 长按放大",
            ) {
                LensSection(config = config.lens, onChange = vm::setLens)
                Text(
                    text = "在底栏/分段控件上长按拖动即可看到效果。",
                    fontSize = 12.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // ---- BACKDROP ----
            GlassSectionCard(
                title = "背景渐变",
                subtitle = "渐变起点",
                inlinePreview = {
                    TinyBackdropPreview(lightStart = config.backdrop.lightStart)
                },
            ) {
                BackdropSection(config = config.backdrop, onChange = vm::setBackdrop)
            }

            // ---- BACKGROUND ----
            GlassSectionCard(
                title = "全局蒙版",
                subtitle = "Background · 模糊+色调",
                inlinePreview = {
                    TinyBackgroundPreview(
                        tint = config.background.lightTintArgb,
                        alpha = config.background.lightTintAlpha,
                    )
                },
            ) {
                BackgroundSection(config = config.background, onChange = vm::setBackground)
            }

            Spacer(Modifier.size(40.dp))
        }
    }
}

// ====================================================================
//  Section card primitive
// ====================================================================

@Composable
private fun GlassSectionCard(
    title: String,
    subtitle: String? = null,
    inlinePreview: (@Composable () -> Unit)? = null,
    cardSpec: LiquidGlassSpec? = null,
    content: @Composable () -> Unit,
) {
    // All playground wrapper cards share the SAME static-glass spec the
    // app's real static cards use (LiquidGlassCard's default). This way
    // the 静态卡片 slider section drives every big playground frame —
    // not just one. Pass an explicit `cardSpec` only for one-off overrides.
    val resolvedSpec = cardSpec ?: LocalGlassConfig.current.staticGlass.toSpec()
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        spec = resolvedSpec,
        shape = RoundedCornerShape(resolvedSpec.cornerRadius.coerceAtLeast(8.dp)),
        contentPadding = PaddingValues0,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black,
                )
                if (subtitle != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF666666),
                    )
                }
                if (inlinePreview != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Box(modifier = Modifier.height(36.dp)) {
                        inlinePreview()
                    }
                }
            }
            content()
        }
    }
}

/** Neutral frosted-glass chip — single white-on-glass treatment, no color accents. */
@Composable
private fun SpecCountBadge(count: Int) {
    LiquidGlassCard(
        modifier = Modifier,
        spec = LiquidGlassSpec.iOS26Static.copy(
            cornerRadius = 999.dp,
            shadowElevation = 0.dp,
            tintAlpha = 0.18f,
            blurRadius = 12.dp,
        ),
        shape = RoundedCornerShape(999.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = "$count 个规格",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Black,
        )
    }
}

// PaddingValues0 referenced from inside GlassSectionCard
private val PaddingValues0 = androidx.compose.foundation.layout.PaddingValues(0.dp)

// ====================================================================
//  Inline mini-previews for section headers
// ====================================================================

@Composable
private fun TinyTabBarPreview(spec: LiquidGlassSpec) {
    LiquidGlassBar(
        modifier = Modifier
            .width(140.dp)
            .height(28.dp),
        spec = spec,
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text("甲", fontSize = 10.sp, color = Color.Black)
            Text("乙", fontSize = 10.sp, color = Color.Black)
            Text("丙", fontSize = 10.sp, color = Color.Black)
        }
    }
}

@Composable
private fun TinyStaticPreview(spec: LiquidGlassSpec) {
    LiquidGlassCard(
        modifier = Modifier
            .width(112.dp)
            .height(46.dp),
        spec = spec,
        shape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(8.dp)),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("字", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun TinyTopBarPreview(tint: Long, alpha: Float) {
    LiquidGlassCard(
        modifier = Modifier
            .width(120.dp)
            .height(28.dp),
        spec = LiquidGlassSpec.iOS26TopBar.copy(
            cornerRadius = 8.dp,
            shadowElevation = 0.dp,
            tintAlpha = alpha.coerceIn(0f, 1f),
            blurRadius = 10.dp,
            tint = argbToColor(tint),
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("顶", fontSize = 10.sp, color = Color.Black)
        }
    }
}

@Composable
private fun TinyControlPreview(tint: Long, alpha: Float, cornerRadius: Int) {
    LiquidGlassCard(
        modifier = Modifier
            .width(70.dp)
            .height(26.dp),
        spec = LiquidGlassSpec.iOS26Control.copy(
            cornerRadius = cornerRadius.coerceIn(4, 36).dp,
            shadowElevation = 0.dp,
            tintAlpha = alpha.coerceIn(0f, 1f),
            blurRadius = 8.dp,
            tint = argbToColor(tint),
        ),
        shape = RoundedCornerShape(cornerRadius.coerceIn(4, 36).dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("按钮", fontSize = 10.sp, color = Color.Black)
        }
    }
}

@Composable
private fun TinyBackdropPreview(lightStart: Long) {
    LiquidGlassCard(
        modifier = Modifier.size(width = 56.dp, height = 28.dp),
        spec = LiquidGlassSpec.iOS26Static.copy(
            cornerRadius = 6.dp,
            shadowElevation = 0.dp,
            tintAlpha = 0.30f,
            blurRadius = 6.dp,
            tint = argbToColor(lightStart),
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {}
}

@Composable
private fun TinySearchBarPreview(spec: LiquidGlassSpec) {
    LiquidGlassCard(
        modifier = Modifier
            .width(120.dp)
            .height(28.dp),
        spec = spec,
        shape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(8.dp)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("搜索", fontSize = 10.sp, color = Color.Black)
        }
    }
}

@Composable
private fun TinyBackgroundPreview(tint: Long, alpha: Float) {
    LiquidGlassCard(
        modifier = Modifier.size(width = 56.dp, height = 28.dp),
        spec = LiquidGlassSpec.iOS26Static.copy(
            cornerRadius = 6.dp,
            shadowElevation = 0.dp,
            tintAlpha = alpha.coerceIn(0f, 1f),
            blurRadius = 6.dp,
            tint = argbToColor(tint),
        ),
        shape = RoundedCornerShape(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {}
}

// ====================================================================
//  Demo backdrop
// ====================================================================

private fun DrawScope.drawPlaygroundDemoBackdrop(
    stops: List<Color>,
    accentStart: Color,
    accentEnd: Color,
) {
    val w = size.width
    val h = size.height
    val maxVal = Float.POSITIVE_INFINITY
    // Mirror [WallpaperGlassBackground]: warm cream → lilac → powder-blue
    // base + two soft pastel radial gradients at the corners. Glass surfaces
    // above blur this layer; the colors become soft pastels under each
    // glass panel — the iOS 26 Liquid Glass appearance.
    drawRect(
        brush = Brush.linearGradient(
            colors = stops,
            start = Offset(0f, 0f),
            end = Offset(maxVal, maxVal),
        ),
        size = Size(w, h),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accentStart, Color.Transparent),
            center = Offset(w * 0.15f, h * 0.20f),
            radius = w * 0.65f,
        ),
        radius = w * 0.65f,
        center = Offset(w * 0.15f, h * 0.20f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accentEnd, Color.Transparent),
            center = Offset(w * 0.95f, h * 0.85f),
            radius = w * 0.75f,
        ),
        radius = w * 0.75f,
        center = Offset(w * 0.95f, h * 0.85f),
    )
}

// ====================================================================
//  Section content (sliders + switches)
// ====================================================================

@Composable
private fun TabBarSection(
    config: TabBarGlassConfig,
    onChange: (TabBarGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "模糊半径",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..48f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "圆角半径",
            value = config.cornerRadius.value,
            onValueChange = { onChange(config.copy(cornerRadius = Dp(it))) },
            valueRange = 8f..48f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "透镜形变",
            value = config.lensAmount.value,
            onValueChange = { onChange(config.copy(lensAmount = Dp(it))) },
            valueRange = 0f..64f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "阴影高度",
            value = config.shadowElevation.value,
            onValueChange = { onChange(config.copy(shadowElevation = Dp(it))) },
            valueRange = 0f..24f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "染色透明度",
            value = config.tintAlpha,
            onValueChange = { onChange(config.copy(tintAlpha = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSwitch(
            label = "色彩活力",
            checked = config.vibrancy,
            onCheckedChange = { onChange(config.copy(vibrancy = it)) },
        )
        // ---- 3D layered effect knobs (Apple iOS 26/27 cues) ----
        Text(
            text = "立体层级 (Apple iOS 26/27)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "顶部高光 (specular)",
            value = config.specularAlpha,
            onValueChange = { onChange(config.copy(specularAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "底部内阴影",
            value = config.bottomShadowAlpha,
            onValueChange = { onChange(config.copy(bottomShadowAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "暗化边缘",
            value = config.edgeDarkAlpha,
            onValueChange = { onChange(config.copy(edgeDarkAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "顶部 tint 增强",
            value = config.topTintExtra,
            onValueChange = { onChange(config.copy(topTintExtra = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}

@Composable
private fun StaticSection(
    config: StaticGlassConfig,
    onChange: (StaticGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "模糊半径",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..48f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "圆角半径",
            value = config.cornerRadius.value,
            onValueChange = { onChange(config.copy(cornerRadius = Dp(it))) },
            valueRange = 4f..36f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "透镜形变",
            value = config.lensAmount.value,
            onValueChange = { onChange(config.copy(lensAmount = Dp(it))) },
            valueRange = 0f..64f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "染色透明度",
            value = config.tintAlpha,
            onValueChange = { onChange(config.copy(tintAlpha = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSwitch(
            label = "色彩活力",
            checked = config.vibrancy,
            onCheckedChange = { onChange(config.copy(vibrancy = it)) },
        )
        // ---- 3D layered effect knobs (Apple iOS 26/27 cues) ----
        // Each layer is independently tunable so the user can dial down
        // any one without losing the underlying glass blur.
        Text(
            text = "立体层级 (Apple iOS 26/27)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "顶部高光 (specular)",
            value = config.specularAlpha,
            onValueChange = { onChange(config.copy(specularAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "底部内阴影",
            value = config.bottomShadowAlpha,
            onValueChange = { onChange(config.copy(bottomShadowAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "暗化边缘",
            value = config.edgeDarkAlpha,
            onValueChange = { onChange(config.copy(edgeDarkAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "顶部 tint 增强",
            value = config.topTintExtra,
            onValueChange = { onChange(config.copy(topTintExtra = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}

@Composable
private fun TopBarSection(
    config: TopBarGlassConfig,
    onChange: (TopBarGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "模糊半径",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..32f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "圆角半径",
            value = config.cornerRadius.value,
            onValueChange = { onChange(config.copy(cornerRadius = Dp(it))) },
            valueRange = 0f..24f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "透镜形变",
            value = config.lensAmount.value,
            onValueChange = { onChange(config.copy(lensAmount = Dp(it))) },
            valueRange = 0f..32f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "染色透明度",
            value = config.tintAlpha,
            onValueChange = { onChange(config.copy(tintAlpha = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSwitch(
            label = "色彩活力",
            checked = config.vibrancy,
            onCheckedChange = { onChange(config.copy(vibrancy = it)) },
        )
        // ---- 3D layered effect knobs (Apple iOS 26/27 cues) ----
        Text(
            text = "立体层级 (Apple iOS 26/27)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "顶部高光 (specular)",
            value = config.specularAlpha,
            onValueChange = { onChange(config.copy(specularAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "底部内阴影",
            value = config.bottomShadowAlpha,
            onValueChange = { onChange(config.copy(bottomShadowAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "暗化边缘",
            value = config.edgeDarkAlpha,
            onValueChange = { onChange(config.copy(edgeDarkAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "顶部 tint 增强",
            value = config.topTintExtra,
            onValueChange = { onChange(config.copy(topTintExtra = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}

@Composable
private fun ControlSection(
    config: ControlGlassConfig,
    onChange: (ControlGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "模糊半径",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..24f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "透镜形变",
            value = config.lensAmount.value,
            onValueChange = { onChange(config.copy(lensAmount = Dp(it))) },
            valueRange = 0f..32f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "按下额外形变",
            value = config.lensPressExtra.value,
            onValueChange = { onChange(config.copy(lensPressExtra = Dp(it))) },
            valueRange = 0f..64f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "染色透明度",
            value = config.tintAlpha,
            onValueChange = { onChange(config.copy(tintAlpha = it)) },
            valueRange = 0f..0.3f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSwitch(
            label = "色彩活力",
            checked = config.vibrancy,
            onCheckedChange = { onChange(config.copy(vibrancy = it)) },
        )
        // ---- 3D layered effect knobs (Apple iOS 26/27 cues) ----
        Text(
            text = "立体层级 (Apple iOS 26/27)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "顶部高光 (specular)",
            value = config.specularAlpha,
            onValueChange = { onChange(config.copy(specularAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "底部内阴影",
            value = config.bottomShadowAlpha,
            onValueChange = { onChange(config.copy(bottomShadowAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "暗化边缘",
            value = config.edgeDarkAlpha,
            onValueChange = { onChange(config.copy(edgeDarkAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "顶部 tint 增强",
            value = config.topTintExtra,
            onValueChange = { onChange(config.copy(topTintExtra = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}

@Composable
private fun ToggleSection(
    config: ToggleGlassConfig,
    onChange: (ToggleGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "开关宽度",
            value = config.width.value,
            onValueChange = { onChange(config.copy(width = Dp(it))) },
            valueRange = 32f..72f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "开关高度",
            value = config.height.value,
            onValueChange = { onChange(config.copy(height = Dp(it))) },
            valueRange = 20f..44f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "圆点直径",
            value = config.knobDiameter.value,
            onValueChange = { onChange(config.copy(knobDiameter = Dp(it))) },
            valueRange = 16f..40f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "关闭轨道透明度",
            value = config.offTrackAlpha,
            onValueChange = { onChange(config.copy(offTrackAlpha = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "开关模糊",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..16f,
            valueFormatter = { "%.1f dp".format(it) },
        )
    }
}

@Composable
private fun LensSection(
    config: LensGlassConfig,
    onChange: (LensGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "透镜直径",
            value = config.lensSize.value,
            onValueChange = { onChange(config.copy(lensSize = Dp(it))) },
            valueRange = 96f..240f,
            valueFormatter = { "%.0f dp".format(it) },
        )
        LabeledSlider(
            label = "折射高度",
            value = config.lensRefractionHeight.value,
            onValueChange = { onChange(config.copy(lensRefractionHeight = Dp(it))) },
            valueRange = 0f..24f,
            valueFormatter = { "%.0f dp".format(it) },
        )
        LabeledSlider(
            label = "折射量",
            value = config.lensRefractionAmount.value,
            onValueChange = { onChange(config.copy(lensRefractionAmount = Dp(it))) },
            valueRange = 0f..24f,
            valueFormatter = { "%.0f dp".format(it) },
        )
        LabeledSlider(
            label = "横向拉伸",
            value = config.stretchMax,
            onValueChange = { onChange(config.copy(stretchMax = it)) },
            valueRange = 0f..3.0f,
            valueFormatter = { "%.2fx".format(it) },
        )
        LabeledSlider(
            label = "垂直压缩",
            value = config.squashMax,
            onValueChange = { onChange(config.copy(squashMax = it)) },
            valueRange = 0f..0.6f,
            valueFormatter = { "%.2f".format(it) },
        )
        // iconScaleInside slider REMOVED 2026-07-04: Kyant drawBackdrop+lens()
        // cannot decouple the sample region from the visible region. All 4
        // attempts to add variable magnification produced centering or visual
        // artifacts. Field is kept in the config struct (CSV-stable) but
        // forced to 1.0 in the lens overlays — see LensGlassConfig docstring.
        LabeledSlider(
            label = "落点染色",
            value = config.iconTintAlpha,
            onValueChange = { onChange(config.copy(iconTintAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "彩色色散 (chromatic aberration)",
            value = config.lensChromaticAberration,
            onValueChange = { onChange(config.copy(lensChromaticAberration = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}

@Composable
private fun SearchBarSection(
    config: SearchBarGlassConfig,
    onChange: (SearchBarGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "模糊半径",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..48f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "圆角半径",
            value = config.cornerRadius.value,
            onValueChange = { onChange(config.copy(cornerRadius = Dp(it))) },
            valueRange = 4f..32f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "透镜形变",
            value = config.lensAmount.value,
            onValueChange = { onChange(config.copy(lensAmount = Dp(it))) },
            valueRange = 0f..32f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "阴影高度",
            value = config.shadowElevation.value,
            onValueChange = { onChange(config.copy(shadowElevation = Dp(it))) },
            valueRange = 0f..16f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "染色透明度",
            value = config.tintAlpha,
            onValueChange = { onChange(config.copy(tintAlpha = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSwitch(
            label = "色彩活力",
            checked = config.vibrancy,
            onCheckedChange = { onChange(config.copy(vibrancy = it)) },
        )
        // ---- 3D layered effect knobs (Apple iOS 26/27 cues) ----
        Text(
            text = "立体层级 (Apple iOS 26/27)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "顶部高光 (specular)",
            value = config.specularAlpha,
            onValueChange = { onChange(config.copy(specularAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "底部内阴影",
            value = config.bottomShadowAlpha,
            onValueChange = { onChange(config.copy(bottomShadowAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "暗化边缘",
            value = config.edgeDarkAlpha,
            onValueChange = { onChange(config.copy(edgeDarkAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "顶部 tint 增强",
            value = config.topTintExtra,
            onValueChange = { onChange(config.copy(topTintExtra = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}

/**
 * 筛选芯片栏 (AlbumDetailPage 全部/视频/收藏/实况).
 * 包括玻璃参数 + 物理弹簧动画参数.
 */
@Composable
private fun ChipFilterSection(
    config: ChipFilterGlassConfig,
    onChange: (ChipFilterGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "模糊半径",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..48f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "圆角半径",
            value = config.cornerRadius.value,
            onValueChange = { onChange(config.copy(cornerRadius = Dp(it))) },
            valueRange = 4f..36f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "透镜形变",
            value = config.lensAmount.value,
            onValueChange = { onChange(config.copy(lensAmount = Dp(it))) },
            valueRange = 0f..64f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "染色透明度",
            value = config.tintAlpha,
            onValueChange = { onChange(config.copy(tintAlpha = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSwitch(
            label = "色彩活力",
            checked = config.vibrancy,
            onCheckedChange = { onChange(config.copy(vibrancy = it)) },
        )
        Text(
            text = "立体层级 (Apple iOS 26/27)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "顶部高光 (specular)",
            value = config.specularAlpha,
            onValueChange = { onChange(config.copy(specularAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "底部内阴影",
            value = config.bottomShadowAlpha,
            onValueChange = { onChange(config.copy(bottomShadowAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "暗化边缘",
            value = config.edgeDarkAlpha,
            onValueChange = { onChange(config.copy(edgeDarkAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "顶部 tint 增强",
            value = config.topTintExtra,
            onValueChange = { onChange(config.copy(topTintExtra = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        // ---- 物理弹簧动画参数 ----
        Text(
            text = "物理动画 (spring)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "弹簧阻尼",
            value = config.springDampingRatio,
            onValueChange = { onChange(config.copy(springDampingRatio = it)) },
            valueRange = 0.1f..1.0f,
            valueFormatter = { "%.1f".format(it) },
        )
        LabeledSlider(
            label = "弹簧刚度",
            value = config.springStiffness,
            onValueChange = { onChange(config.copy(springStiffness = it)) },
            valueRange = 100f..1000f,
            valueFormatter = { "%.0f".format(it) },
        )
        LabeledSlider(
            label = "选中放大",
            value = config.selectedScale,
            onValueChange = { onChange(config.copy(selectedScale = it)) },
            valueRange = 1.0f..1.15f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "浮动高度",
            value = config.floatingElevation.value,
            onValueChange = { onChange(config.copy(floatingElevation = Dp(it))) },
            valueRange = 0f..20f,
            valueFormatter = { "%.1f dp".format(it) },
        )
    }
}

@Composable
private fun TinyChipFilterPreview(spec: LiquidGlassSpec) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("全部", "视频", "收藏", "实况").forEach { label ->
            LiquidGlassCard(
                modifier = Modifier.height(24.dp),
                spec = spec.copy(cornerRadius = 999.dp),
                shape = RoundedCornerShape(999.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(label, fontSize = 9.sp, color = Color.Black)
            }
        }
    }
}

@Composable
private fun TinyHeroFrostPreview(spec: LiquidGlassSpec) {
    LiquidGlassCard(
        modifier = Modifier.size(width = 100.dp, height = 32.dp),
        spec = spec.copy(cornerRadius = 8.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("磨砂", fontSize = 10.sp, color = Color.Black)
        }
    }
}

/**
 * Hero 渐变磨砂玻璃参数面板.
 * 控制 AlbumDetailPage 16:9 hero 卡片下方渐变模糊层的所有参数.
 */
@Composable
private fun HeroFrostSection(
    config: HeroFrostGlassConfig,
    onChange: (HeroFrostGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledSlider(
            label = "模糊半径",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..48f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "透镜形变",
            value = config.lensAmount.value,
            onValueChange = { onChange(config.copy(lensAmount = Dp(it))) },
            valueRange = 0f..48f,
            valueFormatter = { "%.1f dp".format(it) },
        )
        LabeledSlider(
            label = "染色透明度",
            value = config.tintAlpha,
            onValueChange = { onChange(config.copy(tintAlpha = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "高光",
            value = config.highlightAlpha,
            onValueChange = { onChange(config.copy(highlightAlpha = it)) },
            valueRange = 0f..0.6f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "渐变起点 (fadeStart)",
            value = config.fadeStart,
            onValueChange = { onChange(config.copy(fadeStart = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "渐变终点 (fadeEnd)",
            value = config.fadeEnd,
            onValueChange = { onChange(config.copy(fadeEnd = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSwitch(
            label = "色彩活力",
            checked = config.vibrancy,
            onCheckedChange = { onChange(config.copy(vibrancy = it)) },
        )
        Text(
            text = "立体层级 (Apple iOS 26/27)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "顶部高光 (specular)",
            value = config.specularAlpha,
            onValueChange = { onChange(config.copy(specularAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "底部内阴影",
            value = config.bottomShadowAlpha,
            onValueChange = { onChange(config.copy(bottomShadowAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "暗化边缘",
            value = config.edgeDarkAlpha,
            onValueChange = { onChange(config.copy(edgeDarkAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "顶部 tint 增强",
            value = config.topTintExtra,
            onValueChange = { onChange(config.copy(topTintExtra = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}

@Composable
private fun BackdropSection(
    config: BackdropGlassConfig,
    onChange: (BackdropGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "背景渐变第 1 色的 RGB 通道。3 通道合起来组成 lightStart。",
            fontSize = 12.sp,
            color = Color(0xFF666666),
        )
        LabeledSlider(
            label = "起点 红",
            value = ((config.lightStart shr 16) and 0xFF).toFloat(),
            onValueChange = { v ->
                val r = v.toInt().coerceIn(0, 255)
                onChange(config.copy(lightStart = rebuildArgb(config.lightStart, r, ((config.lightStart shr 8) and 0xFF).toInt(), (config.lightStart and 0xFF).toInt())))
            },
            valueRange = 0f..255f,
            valueFormatter = { "${it.toInt()}" },
        )
        LabeledSlider(
            label = "起点 绿",
            value = ((config.lightStart shr 8) and 0xFF).toFloat(),
            onValueChange = { v ->
                val g = v.toInt().coerceIn(0, 255)
                onChange(config.copy(lightStart = rebuildArgb(config.lightStart, ((config.lightStart shr 16) and 0xFF).toInt(), g, (config.lightStart and 0xFF).toInt())))
            },
            valueRange = 0f..255f,
            valueFormatter = { "${it.toInt()}" },
        )
        LabeledSlider(
            label = "起点 蓝",
            value = (config.lightStart and 0xFF).toFloat(),
            onValueChange = { v ->
                val b = v.toInt().coerceIn(0, 255)
                onChange(config.copy(lightStart = rebuildArgb(config.lightStart, ((config.lightStart shr 16) and 0xFF).toInt(), ((config.lightStart shr 8) and 0xFF).toInt(), b)))
            },
            valueRange = 0f..255f,
            valueFormatter = { "${it.toInt()}" },
        )
    }
}

@Composable
private fun BackgroundSection(
    config: BackgroundGlassConfig,
    onChange: (BackgroundGlassConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "全局背景模糊 + 浅色蒙版的 Alpha + 高光。控制 WallpaperGlassBackground 的可见强度。",
            fontSize = 12.sp,
            color = Color(0xFF666666),
        )
        LabeledSlider(
            label = "背景模糊",
            value = config.blurRadius.value,
            onValueChange = { onChange(config.copy(blurRadius = Dp(it))) },
            valueRange = 0f..96f,
            valueFormatter = { "%.0f dp".format(it) },
        )
        LabeledSlider(
            label = "背景形变",
            value = config.lensAmount.value,
            onValueChange = { onChange(config.copy(lensAmount = Dp(it))) },
            valueRange = 0f..48f,
            valueFormatter = { "%.0f dp".format(it) },
        )
        LabeledSlider(
            label = "浅色蒙版",
            value = config.lightTintAlpha,
            onValueChange = { onChange(config.copy(lightTintAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "深色蒙版",
            value = config.darkTintAlpha,
            onValueChange = { onChange(config.copy(darkTintAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "高光",
            value = config.highlightAlpha,
            onValueChange = { onChange(config.copy(highlightAlpha = it)) },
            valueRange = 0f..0.5f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSwitch(
            label = "色彩活力",
            checked = config.vibrancy,
            onCheckedChange = { onChange(config.copy(vibrancy = it)) },
        )
        // ---- 3D layered effect knobs (Apple iOS 26/27 cues) ----
        Text(
            text = "立体层级 (Apple iOS 26/27)",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF888888),
            modifier = Modifier.padding(top = 6.dp),
        )
        LabeledSlider(
            label = "顶部高光 (specular)",
            value = config.specularAlpha,
            onValueChange = { onChange(config.copy(specularAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "底部内阴影",
            value = config.bottomShadowAlpha,
            onValueChange = { onChange(config.copy(bottomShadowAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "暗化边缘",
            value = config.edgeDarkAlpha,
            onValueChange = { onChange(config.copy(edgeDarkAlpha = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
        LabeledSlider(
            label = "顶部 tint 增强",
            value = config.topTintExtra,
            onValueChange = { onChange(config.copy(topTintExtra = it)) },
            valueRange = 0f..1f,
            valueFormatter = { "%.2f".format(it) },
        )
    }
}

private fun rebuildArgb(orig: Long, r: Int, g: Int, b: Int): Long {
    val a = ((orig shr 24) and 0xFF).toLong()
    return (a shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

// ====================================================================
//  Hero LivePreview (the big showcase at the top)
// ====================================================================

@Composable
private fun LivePreview(config: GlassConfig) {
    val tabBarSpec = config.tabBar.toSpec()
    val staticSpec = config.staticGlass.toSpec()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LivePreviewRow(label = "底栏") {
            LiquidGlassBar(
                modifier = Modifier.fillMaxWidth(),
                spec = tabBarSpec,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Icon(Icons.Outlined.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("图库", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Medium)
                    Text("年月", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Medium)
                    Text("搜索", fontSize = 13.sp, color = Color.Black, fontWeight = FontWeight.Medium)
                    Icon(Icons.Outlined.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }

        // All subsequent previews use the PROVEN LiquidGlassBar so we can
        // isolate whether the data flow works (bar responds) vs. a specific
        // component issue. Each shows its blur value for instant feedback.
        LivePreviewRow(label = "静态卡片") {
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                spec = staticSpec,
                shape = RoundedCornerShape(staticSpec.cornerRadius.coerceAtLeast(8.dp)),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("玻璃面板", fontSize = 16.sp, color = Color.Black, fontWeight = FontWeight.SemiBold)
                    Text(
                        "模糊=${staticSpec.blurRadius.value.toInt()}dp 透镜=${staticSpec.lensAmount.value.toInt()}dp 染色=${"%.2f".format(staticSpec.tintAlpha)} 圆角=${staticSpec.cornerRadius.value.toInt()}dp",
                        fontSize = 10.sp,
                        color = Color(0xFF666666),
                    )
                }
            }
        }

        LivePreviewRow(label = "顶栏") {
            LiquidGlassBar(
                modifier = Modifier.fillMaxWidth().height(44.dp),
                spec = config.topBar.toSpec().copy(cornerRadius = 12.dp, blurRadius = config.topBar.blurRadius),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text("顶栏示意", fontSize = 14.sp, color = Color.Black, fontWeight = FontWeight.Medium)
                    Text("模糊=${config.topBar.blurRadius.value.toInt()}dp", fontSize = 11.sp, color = Color(0xFF666666))
                }
            }
        }

        LivePreviewRow(label = "控件") {
            LiquidGlassBar(
                modifier = Modifier.height(40.dp).width(160.dp),
                spec = config.control.toSpec().copy(cornerRadius = 999.dp, blurRadius = config.control.blurRadius),
            ) {
                Text("按钮 (模糊=${config.control.blurRadius.value.toInt()}dp)", fontSize = 11.sp, color = Color.Black)
            }
        }

        LivePreviewRow(label = "开关") {
            TogglePreview(config = config.toggle)
        }

        LivePreviewRow(label = "全局蒙版") {
            LiquidGlassBar(
                modifier = Modifier.fillMaxWidth().height(40.dp),
                spec = LiquidGlassSpec.iOS26Static.copy(
                    cornerRadius = 12.dp,
                    blurRadius = config.background.blurRadius,
                    lensAmount = config.background.lensAmount,
                    vibrancy = config.background.vibrancy,
                    tintAlpha = config.background.lightTintAlpha,
                    tint = argbToColor(config.background.lightTintArgb),
                    specularAlpha = config.background.specularAlpha,
                    bottomShadowAlpha = config.background.bottomShadowAlpha,
                    edgeDarkAlpha = config.background.edgeDarkAlpha,
                    topTintExtra = config.background.topTintExtra,
                ),
            ) {
                Text("模糊=${config.background.blurRadius.value.toInt()}dp", fontSize = 11.sp, color = Color.Black)
            }
        }
    }
}

@Composable
private fun LivePreviewRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label.uppercase(),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black.copy(alpha = 0.85f),
            letterSpacing = 1.5.sp,
        )
        content()
    }
}

/** Simple non-glass toggle preview: flat track with a small knob. */
@Composable
private fun TogglePreview(config: ToggleGlassConfig) {
    val onColor = argbToColor(config.onColorArgb)
    val offTrack = onColor.copy(alpha = config.offTrackAlpha)
    val knobSize = config.knobDiameter
    val trackHeight = config.height
    val trackWidth = config.width
    val trackRadius = config.trackCornerRadius
    val knobPadding = (trackHeight - knobSize) / 2
    Box(modifier = Modifier.height(trackHeight)) {
        Box(
            modifier = Modifier
                .size(width = trackWidth, height = trackHeight)
                .clip(RoundedCornerShape(trackRadius))
                .background(offTrack)
        )
        Box(
            modifier = Modifier
                .padding(start = knobPadding, top = knobPadding)
                .size(knobSize)
                .clip(RoundedCornerShape(50))
                .background(Color.White)
        )
    }
}
