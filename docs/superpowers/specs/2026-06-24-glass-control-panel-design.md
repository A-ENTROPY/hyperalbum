# Liquid Glass 可调参面板 + 行为修正

**日期**: 2026-06-24
**状态**: 设计已批准，进入实现

## 目标

在已有的 iOS 26 Liquid Glass 实现上：
1. 修正若干行为 bug
2. 增加一个可手动调节玻璃效果所有参数的实时面板

## 设计决策（已批准）

| 决策 | 选择 |
|------|------|
| 面板位置 | A. 改造现有 Glass Playground 整页（推荐） |
| 参数范围 | 全部 4 个 Spec 都能调（tabBar / static / lens / backdrop） |
| 持久化 | DataStore 存盘，重启保留 |
| Magnifier 透度 | A. 晶球（基本透明，无 icon） |
| 选中态样式 | A. 选中=玻璃胶囊 |
| 架构 | B. 不用 Scaffold，root Box 手动布局底栏 pill |

## 拓扑

```
AppRoot
└─ LiquidGlassTheme
   └─ CompositionLocalProvider(LocalGlassConfig, LocalLiquidGlassScreenBackdrop)
      └─ Box(fillMaxSize) ─── 不用 Scaffold
         ├─ Box(.fillMaxSize .layerBackdrop(L)) { NavHost }   ← 抓帧
         ├─ Box(.align(Alignment.BottomCenter)) { iOSTabBar }  ← floating pill
         └─ LiquidGlassLensOverlay()   ← 顶层 magnifier
```

## 新文件

- `data/glass/GlassConfig.kt` — 4 个 Spec 子数据类 + GlassConfig 容器
- `data/glass/GlassConfigRepository.kt` — DataStore 包装
- `ui/glass/GlassConfigViewModel.kt` — `StateFlow<GlassConfig>`
- `ui/glass/GlassSpecSliders.kt` — 复用的单组滑块组件
- `ui/glass/GlassConfigPanel.kt` — 替代现有 `LiquidGlassPlayground`

## 改的文件

- `AppRoot.kt` — 删 Scaffold，root Box 手动布局；注入 `LocalGlassConfig`
- `AppleComponents.kt` — 选中态改用 `LiquidGlassCard`；`iOSTabBar` 报 `onGloballyPositioned` 给 lens
- `LiquidGlassLens.kt` — magnifier 改纯透（无 icon，无 blur，只有 lens 折射 + 1.5dp rim）；controller 加 `bounds: Rect?`，moveTo 内部 clamp
- `LiquidGlassSpec.kt` — 加 `fromConfig()` 转换函数

## Bug 修正

### Bug 1: 内容点击被 Scaffold 拦截
**原因**：Scaffold 的 content 槽有 Box，挡住下面的 NavHost。
**修正**：删 Scaffold。底栏手动 `align(Alignment.BottomCenter)` 浮在 NavHost 之上。NavHost 全屏可点，bar 只占底部 pill 区。

### Bug 2: magnifier 范围超出底栏
**原因**：`lens.moveTo()` 接受任意坐标。
**修正**：`LiquidGlassLensController.bounds: Rect?`，由 iOSTabBar 通过 `onGloballyPositioned` 注入；`moveTo` 内部 clamp：
- x 范围：`[bounds.left + lensR, bounds.right - lensR]`
- y 范围：固定 `bounds.center.y`

### Bug 3: magnifier 内有 icon
**原因**：`controller.targetIcon` 被渲染为 lens 中心 icon。
**修正**：删除 `targetIcon`/`targetIconTint` 字段，删除 Box 里 `Icon` 渲染分支。

### Bug 4: magnifier 是磨砂而非晶球
**原因**：`vibrancy() + blur(8dp)` + 白色 0.06。
**修正**：去掉 vibrancy 和 blur；只保留 `lens(80dp, 80dp, chromaticAberration=true)` + 1.5dp 白色 rim。`onDrawSurface` 只画 rim，不画白底。

### Bug 5: 选中态是紫色实色
**原因**：`bgColor = MaterialTheme.colorScheme.primary.copy(alpha=0.20f)`。
**修正**：换成 `LiquidGlassCard(spec = Static.copy(blurRadius=12dp, tintAlpha=0.22f))`。因 bar 已经是 L 的 sibling，card 也是 sibling，可读 L 不递归。

## DataStore schema

```kotlin
// key: glass_tabbar_blur_radius, glass_tabbar_corner_radius, ...
// 用 Preferences DataStore 即可
```

24 个键（4 spec × 6 字段）。每个 spec 子类用 JSON 序列化存 1 个键更简洁。
