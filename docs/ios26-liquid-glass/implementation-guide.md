# iOS 26 Liquid Glass 实现指南

> 在 Android Compose 中复现 iOS 26 风格的液态玻璃效果（Liquid Glass / Liquid Lensing）。
> 包含毛玻璃标签栏（Tab Bar）、长按放大镜透镜（Long-press Magnifier Lens）以及交互动画。

---

## 目录

1. [架构概述](#1-架构概述)
2. [文件清单](#2-文件清单)
3. [Z-Stack 布局（AppRoot）](#3-z-stack-布局approot)
4. [LiquidGlassLensController](#4-liquidglasslenscontroller)
5. [LiquidGlassLensOverlay](#5-liquidglasslensoverlay)
6. [iOSTabBar 手势处理](#6-iostabbar-手势处理)
7. [iOSTabBarItem 图标颜色逻辑](#7-iostabbaritem-图标颜色逻辑)
8. [选中胶囊渲染](#8-选中胶囊渲染)
9. [两遍渲染的标签栏](#9-两遍渲染的标签栏)
10. [GlassConfig 配置系统](#10-glassconfig-配置系统)
11. [TimelinePage 背景修复](#11-timelinepage-背景修复)
12. [关键经验与坑点](#12-关键经验与坑点)

---

## 1. 架构概述

```
┌─────────────────────────────────────────────────────┐
│  LiquidGlassTheme (渐变背景根节点)                    │
│  ┌─────────────────────────────────────────────────┐│
│  │  Box (Z=0) — layerBackdrop(liquidBackdrop)      ││
│  │  ┌───────────────────────────────────────────┐  ││
│  │  │  NavHost (页面内容)                        │  ││
│  │  │  ┌─────────────────────────────────────┐  │  ││
│  │  │  │  TimelinePage (渐变背景)             │  │  ││
│  │  │  └─────────────────────────────────────┘  │  ││
│  │  └───────────────────────────────────────────┘  ││
│  │                                                 ││
│  │  Box (Z=1a) — layerBackdrop(barBackdrop)        ││
│  │  └── iOSTabBar (backdropOnly=true, 青色图标)     ││
│  │                                                 ││
│  │  Box (Z=1b) — 正常交互的标签栏                   ││
│  │  └── iOSTabBar (正常颜色 + 手势)                 ││
│  │                                                 ││
│  │  Box (Z=2) — LiquidGlassLensOverlay             ││
│  │     ├── 图层1: pageBackdrop + lens() 折射        ││
│  │     └── 图层2: barBackdrop + lens() 折射         ││
│  └─────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────┘
```

### 核心依赖

- **Kyant0 Backdrop** (`io.github.kyant0:backdrop`) — `drawBackdrop`, `lens()`, `blur()`, `vibrancy()`
- **Compose Animation** — `Animatable<Float>`, `spring()` 弹性动画
- **Navigation Compose** — `NavHost`, `composable()` 路由

---

## 2. 文件清单

| 文件 | 路径 | 职责 |
|------|------|------|
| `AppRoot.kt` | `ui/AppRoot.kt` | Z-Stack 根布局，三层层叠，提供 LocalComposition |
| `LiquidGlassLens.kt` | `ui/liquidglass/LiquidGlassLens.kt` | 透镜控制器 + 透镜叠加层渲染 |
| `AppleComponents.kt` | `ui/apple/AppleComponents.kt` | iOSTabBar、手势处理、标签项渲染 |
| `LiquidGlassComponents.kt` | `ui/liquidglass/LiquidGlassComponents.kt` | 可复用的液态玻璃表面（Surface/Card/Chip/Bar） |
| `LiquidGlassBackdrop.kt` | `ui/liquidglass/LiquidGlassBackdrop.kt` | 背景幕定义（PhotosMosaic 渐变色） |
| `LiquidGlassTheme.kt` | `ui/liquidglass/LiquidGlassTheme.kt` | 主题：绘制渐变背景 + 提供 LocalBackdrop |
| `GlassConfig.kt` | `data/glass/GlassConfig.kt` | 可配置参数（模糊半径、透镜大小、色调等） |
| `TimelinePage.kt` | `ui/pages/TimelinePage.kt` | "图库"页面（添加了渐变背景修复） |

---

## 3. Z-Stack 布局（AppRoot）

`AppRoot.kt` 使用单 `Box` + Z 轴层叠替代了 Material3 `Scaffold`，解决 `bottomBar` 插槽拦截点击事件的问题。

### 层级结构

```
Box(fillMaxSize)
├── Z=0: Box(layerBackdrop(liquidBackdrop))
│   └── NavHost (页面内容)
├── Z=1a: Box(layerBackdrop(barBackdrop))            ← 隐藏的青色图标标签栏
│   └── iOSTabBar(backdropOnly=true, onSelect={})
├── Z=1b: Box(align=BottomCenter)
│   └── iOSTabBar(正常交互, 带 onSelect 导航)
└── Z=2: LiquidGlassLensOverlay(barBackdrop)
```

### CompositionLocal 提供

```kotlin
CompositionLocalProvider(
    LocalImageLoader provides imageLoader,
    LocalLiquidGlassLens provides lensController,
    LocalLiquidGlassScreenBackdrop provides liquidBackdrop,
    LocalGlassConfig provides glassConfig,
    LocalLensOverlayIconState provides lensOverlayIconState,
) { ... }
```

### 关键要点

- `layerBackdrop(liquidBackdrop)` 只捕获 Z=0 的 NavHost 子树内容
- Z=1a 的青色标签栏用于 `barBackdrop` 捕获，供透镜折射使用
- Z=1b 是用户实际看到和交互的标签栏
- Z=2 的透镜叠加层读取两个 Backdrop，合成页面内容 + 标签栏内容的折射效果
- **不要使用 Material3 Scaffold** 的 `bottomBar` 插槽，否则点击事件会被拦截

---

## 4. LiquidGlassLensController

`LiquidGlassLensController` 管理透镜的所有动画状态。

### 核心属性

```kotlin
class LiquidGlassLensController {
    // 透镜中心位置 (窗口坐标)
    val position: Animatable<Offset, AnimationVector2D>
    // 宽度拉伸比 (1.0=正圆, 1.5=胶囊形)
    val widthScale: Animatable<Float, AnimationVector1D>
    // 透镜整体透明度
    val alpha: Animatable<Float, AnimationVector1D>
    // 透镜缩放 (0.01→1 出现, 1→0.1 隐藏)
    val scale: Animatable<Float, AnimationVector1D>
    // 是否可见
    val isVisible: MutableState<Boolean>
    // 隐藏时胶囊淡入的透明度 (0→1)
    val capsuleAlpha: Animatable<Float, AnimationVector1D>

    // 标签栏的范围 (用于位置钳制)
    var bounds: Rect?
    // 透镜基准像素大小
    var baseSizePx: Float
    // 最大拉伸比
    var stretchMax: Float
}
```

### 动画曲线

| 动画 | 弹性刚度 | 阻尼比 | 说明 |
|------|---------|--------|------|
| 出现时缩放 (0.01→1) | 350 | 0.55 | 轻微过冲，弹性感 |
| 出现时透明度 (0→1) | 600 | 0.9 | 快速淡入，几乎无弹跳 |
| 隐藏时位置 (飞到目标标签) | 3000 | 1.2 | 过阻尼，快速无弹跳 |
| 隐藏时缩放 (1→0.1) | 3000 | 1.2 | 同上 |
| 隐藏时透明度 (1→0) | 3000 | 1.2 | 同上 |
| 隐藏时胶囊淡入 (0→1) | 600 | 0.55 | 轻微弹跳，与透镜收缩配合 |
| 宽度拉伸 | 600 | 0.9 | 平滑跟随手指速度 |

### 位置钳制

```kotlin
private fun clampToBar(p: Offset): Offset {
    val b = bounds ?: return p
    val y = b.top + (b.bottom - b.top) / 2f  // Y 固定在标签栏中线
    val x = p.x.coerceIn(b.left, b.right)     // X 限制在标签栏范围内
    return Offset(x, y)
}
```

### 关键方法

```kotlin
// 显示透镜 (从选中标签中心开始)
fun show(at: Offset, scope: CoroutineScope)

// 移动透镜 (跟随手指)
fun moveTo(at: Offset, scope: CoroutineScope, nowNanos: Long)

// 平滑移动到目标位置 (用于初始动画到按下位置)
fun animateToPosition(target: Offset, scope: CoroutineScope)

// 隐藏透镜 (带收缩 + 胶囊过渡)
fun hide(scope: CoroutineScope, morphTarget: Offset? = null)
```

---

## 5. LiquidGlassLensOverlay

透镜叠加层使用两个 `drawBackdrop` 图层合成折射效果。

### 渲染结构

```
Box(.offset + .size + .clip(圆角) + .graphicsLayer{scale, alpha})
├── Layer 1: drawBackdrop(pageBackdrop) + lens()     ← 页面内容折射
└── Layer 2: drawBackdrop(barBackdrop) + lens()      ← 标签栏内容折射
```

### 关键渲染参数

```kotlin
val sizePx = with(density) { lensCfg.lensSize.toPx() }    // 透镜直径
val widthPx = sizePx * controller.widthScale.value         // 拉伸后宽度
val refractionHeightPx = lensCfg.lensRefractionHeight      // 折射高度
val refractionAmountPx = lensCfg.lensRefractionAmount      // 折射量
```

### 暗框修复

透镜必须**始终保持在组合树中**（即使隐藏），否则 `drawBackdrop` 的 AGSL 着色器
初次渲染会使用未初始化的缓冲区，产生黑色矩形暗框。

```kotlin
// 错误做法：visible 时才组合
if (visible) Box(...)  // ← 首次显示会出现暗框

// 正确做法：始终组合，隐藏时通过 graphicsLayer alpha 控制
Box(
    modifier = .graphicsLayer {
        alpha = if (visible) controller.alpha.value else 0.003f  // 非零
    }
)
```

同时配合 `.clip(圆角)` 剪裁掉缓冲区中透镜形状之外的未初始化像素。

---

## 6. iOSTabBar 手势处理

手势使用自定义 `detectLongPressThenDrag` 修饰符，附加在标签栏的 `Box` 上。

### 手势流程

```
onLongPress(pressLocal)
  ├── pressStartRoute = 按下位置的标签路由
  ├── lensStart = 当前选中标签的中心 (窗口坐标)
  ├── lens.show(lensStart, scope)           // 透镜从选中标签出现
  └── if (按下的不是选中标签)
       └── lens.animateToPosition(pressWindow, scope)  // 流动到按下位置

onDrag(currentLocal)
  └── lens.moveTo(currentWindow, scope, nanoTime)  // 跟随手指

onDragEnd
  ├── target = hoveredRoute (透镜最近的标签)
  ├── original = currentSelectedRoute
  ├── if (target != original)
  │   └── onSelect(target)                  // 导航到新标签
  │   └── lensSelectionCommitted = true      // 标记已选择
  ├── morphTarget = target/selected 标签的中心
  └── lens.hide(scope, morphTarget)          // 飞向目标并收缩

onDragCancel
  ├── lensSelectionCommitted = false
  └── lens.hide(scope, null)                // 原地消失
```

### hoveredRoute 计算

基于透镜位置实时计算最近标签页：

```kotlin
val hoveredRoute by remember(lens, items) {
    derivedStateOf {
        if (!lens.isVisible.value || tabLayouts.isEmpty()) return@derivedStateOf null
        val lensX = lens.position.value.x
        tabLayouts.entries
            .minByOrNull { entry ->
                val cx = entry.value.origin.x + entry.value.size.width / 2f
                kotlin.math.abs(cx - lensX)
            }?.key
    }
}
```

---

## 7. iOSTabBarItem 图标颜色逻辑

这是整个交互中最精细的部分，需要处理多个状态的优先级。

### 优先级排序

```kotlin
val iconColor = when {
    forceCyan            -> highlightCyan  // 青色标签栏 (backdropOnly)
    selected && lensActive && isHovered -> highlightCyan  // 透镜在选中标签上
    lensActive           -> gray           // 透镜活跃期间变灰
    selected             -> highlightCyan  // 正常选中状态
    else                 -> gray           // 默认
}
```

### 各种场景的颜色行为

| 场景 | 原始选中标签 | 透镜悬停标签 | 其他标签 |
|------|------------|-------------|---------|
| 无透镜 (正常) | 青色 | - | 灰色 |
| 透镜在选中标签上 | 青色 | 灰色 | 灰色 |
| 透镜离开选中标签 | 灰色 | 灰色 | 灰色 |
| 松手选择新标签 (过渡中) | 灰色 | 青色 (isHovered=true) | 灰色 |
| 隐藏完成 | 灰色 | 青色 | 灰色 |

### 关键要点

- **`selected` 不应当优先于 `lensActive`**，否则透镜离开选中标签时青蓝色不消失
- **但 `isHovered` 可以使选中标签在透镜悬停时保持青色**
- **`lensSelectionCommitted`** 用于标记 onDragEnd 中已选择新标签，确保新标签在过渡动画期间就是青色（在标签项层面使用 `isHovered` 即可，不需要额外传参，因为透镜在隐藏动画中会飞向新标签，`hoveredRoute` 自然更新为新的目标标签）

---

## 8. 选中胶囊渲染

选中标签的毛玻璃背景胶囊，使用分离的 Box 层避免 `graphicsLayer` 的 alpha 影响图标和文字。

### 渲染结构

```kotlin
Box(width = 72.dp) {                    // 固定宽度容器，保持正圆形
    // 胶囊背景层 (分离的 Box)
    if (selected && (!lensActive || lensCtl.isVisible.value)) {
        val capsuleAlpha = if (lensActive) lensCtl.capsuleAlpha.value else 1f
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(ContinuousCapsule)
                .graphicsLayer { alpha = capsuleAlpha.coerceAtLeast(0.003f) }
                .drawBackdrop(
                    backdrop = screenBackdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        blur(px)
                        lens(px, px)       // 毛玻璃 + 折射
                    },
                    onDrawSurface = {
                        // 1. 青色渐变色调
                        // 2. 顶部高光条纹
                        // 3. 青色边缘光晕
                        // 4. 底部边缘暗化
                    }
                )
        )
    }
    // 图标 + 文字层 (在胶囊上方)
    Column(...) {
        Icon(...)
        Text(...)
    }
}
```

### 关键要点

- **必须使用 `72.dp` 固定宽度容器**，否则 `weight(1f)` 会让胶囊拉伸成椭圆
- **胶囊必须是独立的 Box**，不能与图标/文字共用同一个 `graphicsLayer`，否则 alpha 动画会使图标和文字一起淡出
- **`capsuleAlpha`** 在透镜隐藏时从 0 弹性动画到 1，实现"透镜收缩 + 胶囊淡入"的连续过渡
- 胶囊的 `lens()` 效果在收缩过渡期间不可见（被透镜覆盖），但由于它一直存在于胶囊的 `drawBackdrop` 中，在胶囊完全淡入后与标签栏背景一致

---

## 9. 两遍渲染的标签栏

为了实现透镜内显示青色图标，透镜外保持正常颜色，标签栏需要渲染两次。

### Z=1a: 隐藏的青色标签栏 (backdropOnly)

```kotlin
Box(Modifier.layerBackdrop(barBackdrop)) {
    iOSTabBar(
        items, selectedRoute,
        onSelect = {},             // 无交互
        backdropOnly = true,       // forceCyan=true, 所有图标青色
    )
}
```

- 所有图标都是金色 (`forceCyan = true`)
- 被 `layerBackdrop(barBackdrop)` 捕获
- 用户看不到这层（被 Z=1b 覆盖）

### Z=1b: 可见的交互标签栏

```kotlin
iOSTabBar(
    items, selectedRoute,
    onSelect = { item -> navController.navigate(...) },
)
```

- 正常颜色（仅选中标签青色）
- 完整手势交互
- 完全覆盖 Z=1a

---

## 10. GlassConfig 配置系统

所有液态玻璃参数通过 `GlassConfig` 数据类集中管理，支持 DataStore 持久化和实时调整。

### 配置层级

```kotlin
GlassConfig
├── TabBarGlassConfig    — 标签栏毛玻璃参数
├── StaticGlassConfig    — 静态玻璃表面参数 (卡片等)
├── LensGlassConfig      — 透镜放大镜参数
└── BackdropGlassConfig  — 背景渐变颜色
```

### LensGlassConfig 关键参数

```kotlin
data class LensGlassConfig(
    val lensSize: Dp = 100.dp,                    // 透镜容器直径
    val lensRefractionHeight: Dp = 10.dp,          // 折射高度
    val lensRefractionAmount: Dp = 14.dp,          // 折射量
    val lensChromaticAberration: Boolean = true,    // 色差
    val stretchMax: Float = 1.5f,                  // 宽度拉伸上限
    val iconScaleInside: Float = 1.18f,            // 透镜内图标缩放
    val iconTintAlpha: Float = 1.0f,               // 透镜内图标色调透明度
)
```

### 色调转换

```kotlin
private fun argbToColor(argb: Long): Color {
    val a = ((argb shr 24) and 0xFF).toInt()
    val r = ((argb shr 16) and 0xFF).toInt()
    val g = ((argb shr 8) and 0xFF).toInt()
    val b = (argb and 0xFF).toInt()
    return Color(red = r, green = g, blue = b, alpha = a)
}
```

> **注意**：不能直接用 `Color(argbLong)` 或 `Color(ulong)` 构造函数，它们期望的色彩空间元数据不同，会崩溃。

---

## 11. TimelinePage 背景修复

图库页面在无照片时背景完全透明，导致 `layerBackdrop` 捕获到未初始化的黑色像素，
透镜折射时出现视觉穿帮。

### 修复

```kotlin
val isDark = isSystemInDarkTheme()
val bgBrush = if (isDark) LiquidGlassBackdrop.PhotosMosaic.dark
              else LiquidGlassBackdrop.PhotosMosaic.light

Column(
    modifier = Modifier
        .fillMaxSize()
        .background(bgBrush)  // ← 覆盖透明背景
) { ... }
```

使用与 `LiquidGlassTheme` 根节点完全相同的渐变色，确保 `layerBackdrop` 在任何情况下
都能捕获到有意义的彩色内容。

---

## 12. 关键经验与坑点

### 1. layerBackdrop 子树透明问题

`layerBackdrop` 捕获的是子树渲染输出的像素。如果子树内容有透明区域，捕获到的将是
未初始化的缓冲区像素（通常为黑色），导致透镜折射时出现暗色穿帮。

**解决**：确保捕获子树内的所有页面都有不透明背景。

### 2. drawBackdrop + lens() 初次渲染暗框

Kyant 的 `drawBackdrop` + `lens()` AGSL 着色器在首次渲染时会使用未初始化的纹理缓冲区，
产生黑色矩形暗框。

**解决**：
- 透镜叠加层**始终保持在组合树中**，不按条件移除
- 隐藏时通过 `graphicsLayer { alpha = 0.003f }` 让 GPU 跳过合成，而不是 `alpha = 0f`
- 配合 `.clip(圆角形状)` 剪裁掉圆形透镜外的未初始化像素

### 3. SigningConfig 配置

Android SDK 34+ 要求 APK 必须有签名配置，否则无法安装。

```kotlin
 signingConfigs {
     create("debug") {
         storeFile = file("debug.keystore")
         storePassword = "android"
         keyAlias = "androiddebugkey"
         keyPassword = "android"
     }
 }
```

### 4. 弹性动画参数

| 目的 | stiffness | dampingRatio | 效果 |
|------|-----------|-------------|------|
| 弹性出现 | 300-400 | 0.5-0.6 | 轻微过冲，有弹性的手感 |
| 平滑淡入 | 500-700 | 0.8-1.0 | 快速到位，少量弹跳或无弹跳 |
| 快速收缩 | 2000-4000 | 1.0-1.5 | 过阻尼，无弹跳，视觉上消失 |
| 宽度跟随 | 500-700 | 0.8-1.0 | 平滑跟踪手指速度 |

### 5. 图标颜色优先级

状态判断顺序至关重要：
- `forceCyan` > `selected + isHovered` > `lensActive` > `selected` > default
- `lensActive` 必须优先于普通 `selected`，但 `selected + isHovered`（透镜在选中标签上时）优先于 `lensActive`

### 6. 胶囊形状

`matchParentSize()` 在 `weight(1f)` 容器内会拉伸到完整宽度，导致胶囊变成椭圆。

**解决**：在胶囊外包裹 `Box(width = 72.dp)` 固定宽度容器。

### 7. 导航路由 URI 编码

Navigation Compose 的 `viewer/{uri}` 路由中，Content URI（如 `content://media/...`）
包含 `://` 会破坏路由匹配。

**解决**：所有 `onOpenPhoto` 回调使用 `Uri.encode(uri.toString())` 编码，
在 `PhotoViewerPage` 中使用 `URLDecoder.decode(encoded, "UTF-8")` 解码。

### 8. 透镜返回胶囊过渡

松手后透镜需要飞回目标标签并收缩，同时胶囊淡入。两者必须同步以形成连续过渡。

在 `hide()` 中：
```kotlin
coroutineScope {
    launch { position.animateTo(morphTarget, spring(3000f, 1.2f)) }  // 快速飞到目标
    launch { scale.animateTo(0.1f, spring(3000f, 1.2f)) }            // 同步收缩
    launch { capsuleAlpha.animateTo(1f, spring(600f, 0.55f)) }       // 胶囊淡入 (稍慢有弹性)
    alpha.animateTo(0f, spring(3000f, 1.2f))                         // 透镜淡出
}
scale.snapTo(1f)     // 重置缩放
isVisible.value = false
```

胶囊的弹性淡入（0.55 阻尼）与透镜的过阻尼收缩（1.2 阻尼）形成对比，
视觉上透镜快速收紧进入胶囊，胶囊则有一个微弱的弹性扩散效果。

