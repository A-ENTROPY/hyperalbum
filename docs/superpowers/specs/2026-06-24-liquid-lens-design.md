# Liquid Lens — 液态透镜 (iOS 26 完整重写)

**日期**: 2026-06-24
**状态**: 设计已批准，待实现
**前置**: [2026-06-24-glass-control-panel-design.md](./2026-06-24-glass-control-panel-design.md)（已有的 4 spec + GlassConfigPanel，本次只在 LensGlassConfig 上叠加 9 个新字段）

## 目标

把当前的 `LiquidGlassLens`（一个普通 `lens()` 胶囊 + 1.5dp 白边）替换成 iOS 26 WWDC25 公布的 **"Liquid Lensing"** 效果：

1. **单球**：永远只有 **一个** 液态透镜，**没有尾巴、没有副水滴**。
2. **拖动形变**：手指拖动时，透镜在运动方向上被**表面张力拉长**、垂直方向**体积守恒**压缩。
3. **边界压缩**：透镜中心抵到底栏左右边缘时，被**压扁成竖椭圆**（水珠压在玻璃上的物理形变）。
4. **回弹手感**：透镜**贴手跟随**（无延迟），**形变用软弹簧**（拖动有"水"感）。
5. **Icon 反馈**：透镜覆盖到的 tab icon **scale 1.18x + tint 切到主色 + 文字加粗**（Apple 官方）。
6. **9 个可调参数**全部进 `GlassConfigPanel` 的 Lens 区，DataStore 持久化。

## 参考

- iOS 26 "Liquid Lensing" 视频（WWDC25 Session 256, 320）
- 项目内 6 张参考图（`H:\workspace-minimaxcode\参考图片\`）：
  - 静态、触左边界半圆、快速拖动大椭圆（带色散环）
- 6 张形变参考图（`H:\workspace-minimaxcode\参考图片\形变参考\`）：
  - 静态首页半裁、贯穿整条底栏的大椭圆、动态上的色散环

## 设计决策（已锁）

| 维度 | 决策 |
|------|------|
| 渲染方案 | **C — AGSL 自定义 shader**（Apple 原生质感，AGSL 单球 SDF + 折射 + 色散） |
| Icon 反馈 | **A — scale 1.18x + tint 主色 + 文字加粗** |
| 弹簧手感 | **A 贴手位置** (k=900, d=1.0) + **形变用软弹簧** (k=600, d=0.9) |
| 边界行为 | **A — 单侧压扁 + 垂直鼓起**（物理：水珠压在玻璃上） |

## 物理模型（核心公式）

### 1. 位置 — 硬跟随（无任何弹簧）
```kotlin
// snapTo 不是 animateTo — 透镜每一帧都精确等于目标位置
positionX.snapTo(target.x)
positionY.snapTo(target.y)
```
- 透镜中心 = 手指位置，**零延迟、零震荡**。
- "弹性"完全交给形变弹簧（§3），不让位置弹簧产生任何"水滞后"感。
- drag callback（`detectDragGestures.onDrag`）每帧触发，所以 snapTo 等价于"手指到哪、透镜到哪"。

### 2. 速度 — 时间归一化 + 时间常数 EMA
```kotlin
// 每一帧（withFrameNanos 调用）执行
val dt = (frameNanos - lastFrameNanos) / 1e9f                  // 秒
val vxRaw = (position.x - lastPosition.x) / dt                  // px/秒
val vyRaw = (position.y - lastPosition.y) / dt
// 时间常数 50ms 的 EMA：α = 1 - exp(-dt/τ)
val alpha = 1f - exp(-dt / 0.05f)
velocityX = vxRaw * alpha + velocityX * (1f - alpha)
velocityY = vyRaw * alpha + velocityY * (1f - alpha)
lastPosition = position
lastFrameNanos = frameNanos
```
- 单位是 **px/秒**，不依赖帧率。120Hz 屏和 60Hz 屏同手势产生相同速度。
- EMA 系数 `alpha` 自动适配帧率：60Hz 时 α ≈ 0.55，120Hz 时 α ≈ 0.30，30Hz 时 α ≈ 0.78。
- 时间常数 τ=50ms ≈ "手感记忆"窗口（人手感知速度变化的典型时长）。

### 3. 水平拉伸 — 速度驱动 + 基础面积守恒 + 阻力附加压扁

```kotlin
companion object {
    /** 横向最大拉伸倍数（静止时 1.0，快速滑动时最大 4.5） */
    const val STRETCH_MAX = 3.5f

    /** 阻力衰减系数（<1 模拟非完全刚性，0.5 表示压扁只能达到上限的 50%） */
    const val DRAG_DAMPING = 0.5f
}

// density-aware 速度上限
val maxVx = with(density) { 1800.dp.toPx() }                  // 横向
val maxVy = maxVx * 0.75f                                       // 纵向（实际拖动比横向慢）

val absVx = abs(velocityX).coerceAtMost(maxVx)                  // 0..maxVx
val absVy = abs(velocityY).coerceAtMost(maxVy)                  // 0..maxVy（关键：取 abs，不带符号）

// 基础形变：横向拉 N 倍 → 纵向 1/N 严格守恒（不可压缩流体）
val stretchX = 1f + (absVx / maxVx) * STRETCH_MAX
// 附加项：纵向运动时的气动阻力压扁（无论向上向下都压扁，方向无关）
// squashMax 是用户可调参数，DRAG_DAMPING 是固定阻尼系数
val verticalSquash = 1f - (absVy / maxVy) * (squashMax * DRAG_DAMPING)
val stretchY = (1f / stretchX) * verticalSquash
```

**物理说明**：
- **基础项** `1/stretchX`：横向拉 N 倍时纵向严格压 1/N，**面积守恒**（stretchX × (1/stretchX) = 1）。
- **附加项** `(1 - k·|vy|/MAX_VY)`：纵向高速运动时额外压扁（无论上/下），模拟气动阻力。这会**牺牲面积**，不是严格守恒——但视觉上更像真实液体（快速运动必然伴随能量耗散）。
- 关键修正：speedY 必须取 `abs()`。否则向上滑动时 stretchY 反而增大，与物理直觉矛盾。
- `squashMax` 用户可调（slider 0.0–0.6，默认 0.40），与固定 `DRAG_DAMPING = 0.5` 相乘 = `EFFECTIVE_SQUASH` 上限 0.20。

**软弹簧插值**：
```kotlin
scaleX.animateTo(stretchX, spring(stiffness = 600f, dampingRatio = 0.9f))
scaleY.animateTo(stretchY, spring(stiffness = 600f, dampingRatio = 0.9f))
```
- 拖动停下时形变**回弹**到 1.0（球形）。
- 软弹簧是"水感"唯一来源。

### 4. 边界压缩 — 物理水珠压墙
```kotlin
val wallLeft  = bounds.left + lensRadius
val wallRight = bounds.right - lensRadius
val proxL = ((position.x - wallLeft)  / lensRadius).coerceIn(0f, 1f)
val proxR = ((wallRight - position.x) / lensRadius).coerceIn(0f, 1f)
val wallProx = max(1f - proxL, 1f - proxR)              // 0=远离墙, 1=贴墙
val wallCompress = 1f - wallProx * WALL_COMPRESS_K      // WALL_COMPRESS_K = 0.45
val wallBulge    = 1f + wallProx * WALL_BULGE_K         // WALL_BULGE_K = 0.25
```
- 距左墙 0% → `wallProx = 1` → scaleX × 0.55，scaleY × 1.25（竖椭圆）。
- 距墙 50% → `wallProx = 0.5` → scaleX × 0.78，scaleY × 1.13。
- **最终 scale = stretch × wall**（两套独立系数相乘，软弹簧独立插值）。

### 5. AGSL 单球渲染 — 无尾巴、无副球

**重要：AGSL 中背景采样必须用 `backdrop.eval(coord)`，不能用 `texture(...)`（AGSL 没有 GLSL 风格的纹理采样函数）。**

**架构决策**：使用 **`graphicsLayer { renderEffect = RenderEffect.createRuntimeShaderEffect(...).asComposeRenderEffect() }`** 路径，不是 `drawWithCache + ShaderBrush`。原因：
- `ShaderBrush + drawRect` **不会自动填充** `backdrop` uniform（ShaderBrush 是生成型 brush，不读取 layer 内容）
- `RenderEffect.createRuntimeShaderEffect(shader, "backdrop")` 把 shader 应用到整个 graphicsLayer，`backdrop` 自动绑定为该 layer 的渲染内容
- 这是 AGSL 后处理 shader（扭曲背景内容）的标准路径

```glsl
uniform shader backdrop;                       // 屏幕内容（RenderEffect 自动注入 layer 内容）
uniform float2 u_resolution;                   // 屏幕像素尺寸
uniform float2 u_lensCenter;                   // 透镜中心 (px, 在 layer 坐标系内)
uniform float2 u_screenSize;                   // 球体渲染尺寸 (px, 含 scale)
uniform float  u_lensAmount;                   // 折射强度（0.0..0.3）
uniform float  u_chromaticAberration;          // 色散强度（0.0..0.05）
uniform float  u_highlightAlpha;               // 高光强度（0.0..1.0）

half4 main(float2 fragCoord) {
    float aspect = u_screenSize.x / max(u_screenSize.y, 0.001);   // 椭圆度（shader 内计算，无需 uniform）

    // 椭球 SDF：用 aspect 把圆拉成椭圆
    float2 uv = (fragCoord - u_lensCenter) / (u_screenSize * 0.5);
    float sdf = length(float2(uv.x, uv.y / aspect)) - 1.0;

    // 椭圆面法线
    float2 normal = normalize(float2(uv.x, uv.y / (aspect * aspect)));

    // 边缘软切
    float rim = smoothstep(0.0, -0.04, sdf);

    // 折射采样：clip 屏幕坐标到 [0, u_resolution] 避免越界
    float2 baseCoord = clamp(fragCoord + normal * u_lensAmount * u_screenSize.x, float2(0.0), u_resolution);
    float2 redCoord  = clamp(fragCoord + normal * u_lensAmount * (1.0 + u_chromaticAberration) * u_screenSize.x, float2(0.0), u_resolution);
    float2 blueCoord = clamp(fragCoord + normal * u_lensAmount * (1.0 - u_chromaticAberration) * u_screenSize.x, float2(0.0), u_resolution);

    // 球外透明（让 SDF 边界外的像素天然空白，不画外置 clip）
    if (sdf > 0.0) return half4(0.0);

    // 色散采样
    half3 chroma = half3(
        backdrop.eval(redCoord).r,
        backdrop.eval(baseCoord).g,
        backdrop.eval(blueCoord).b
    );

    // 高光：pow 前必须 max(·, 0.0)；指数必须是浮点字面量 16.0
    float h = max(0.0, 1.0 - abs(sdf));
    float highlight = pow(h, 16.0);            // 4 次平方开销
    half3 highlighted = mix(chroma, half3(1.0), highlight * u_highlightAlpha * rim);

    return half4(mix(backdrop.eval(fragCoord).rgb, highlighted, rim), 1.0);
}
```

**关键约束**：
- **源码以 `uniform` 开头**，不含 `#version` / `precision`（AGSL 不支持 GLSL 预处理指令）
- **`pow()` 前必须 `max(·, 0.0)`**，即使指数是偶数也要包（防止某些 GPU 驱动返回 NaN）
- **指数用浮点字面量 `16.0`**，不是整数 `16`
- **只有这一个椭球**，不渲染副球、不画尾巴、不画拖出的小水滴
- **不需要外置 `clip`**：SDF 内部 `sdf > 0 → return half4(0.0)` 已经把球外渲成透明
- **不需要 `u_aspect`**，shader 内通过 `u_screenSize.x / u_screenSize.y` 计算，减少一次 `setFloatUniform`

#### 5.1 RuntimeShader 生命周期与线程约束

**关键架构决策**：
- **不能用** `drawWithCache { remember { RuntimeShader } }` —— `drawWithCache` 不是 `@Composable` 作用域，`remember {}` 在内编译失败
- **不能用** `ShaderBrush + drawRect` —— 不会自动填充 `backdrop` uniform
- **必须用** `graphicsLayer { renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect() }`
- **RenderEffect 必须挂在全屏 Box 上**，不能挂在内层 `Modifier.size(width, height)` 的 Box 上（否则 fragCoord 是局部 0..96 而 u_lensCenter 是绝对坐标，导致 uv 极大使 sdf 永远 > 0 → 全透明）

```kotlin
@Composable
fun LiquidGlassLensOverlay(
    modifier: Modifier = Modifier,
    width: Dp = 48.dp * 2,
    height: Dp = 48.dp * 2,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        LegacyLensOverlay(modifier, width, height)
        return
    }

    val controller = LocalLiquidGlassLens.current
    val config = LocalGlassConfig.current
    val spec = config.lens
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
    val heightPx = with(density) { height.toPx() }

    // RuntimeShader 在 @Composable 作用域内创建（remember）
    val shader = remember(spec) {
        try {
            RuntimeShader(SHADER_SRC).also {
                it.setFloatUniform("u_resolution", 1f, 1f)   // 触发编译，尽早暴露 uniform 名不匹配
            }
        } catch (e: Exception) {
            Log.e("LiquidLens", "Shader compile failed", e)
            null
        }
    }
    if (shader == null) {
        LegacyLensOverlay(modifier, width, height)
        return
    }

    // 不可变快照：避免 main thread 写 + draw phase 读的数据竞争
    var snapshot by remember { mutableStateOf(LensSnapshot.ZERO) }
    LaunchedEffect(Unit) {
        var lastNanos = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            controller.updateVelocity((now - lastNanos) / 1_000_000_000f)
            snapshot = controller.toSnapshot(widthPx, heightPx)
            lastNanos = now
        }
    }

    // ★ RenderEffect 挂全屏 Box — 让 fragCoord 拥有完整屏幕坐标空间，backdrop 采样全屏背景
    // 透镜的位移和形变完全由 shader 内的 SDF 公式接管，不需要 translationX/scaleX
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                shader.setFloatUniform("u_resolution", size.width, size.height)
                shader.setFloatUniform("u_lensCenter", snapshot.cx, snapshot.cy)
                shader.setFloatUniform("u_screenSize", widthPx * snapshot.scaleX, heightPx * snapshot.scaleY)
                shader.setFloatUniform("u_lensAmount", spec.lensRefraction)
                shader.setFloatUniform("u_chromaticAberration", spec.chromaticAberration)
                shader.setFloatUniform("u_highlightAlpha", spec.highlightAlpha)

                renderEffect = remember(shader) {
                    RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
                        .asComposeRenderEffect()
                }
            }
    ) {
        // 内部空：所有视觉由 shader 完成。alpha 用 `if (snapshot.alpha > 0f) ... else invisible` 模式：
        // snapshot.alpha = 0 时直接让 Box 不参与绘制（shader 内 fragCoord 依然有效）
        if (snapshot.alpha > 0.01f) {
            // 空 Box — 让 RenderEffect 有 layer 可挂
        }
    }
}

data class LensSnapshot(
    val cx: Float, val cy: Float,
    val scaleX: Float, val scaleY: Float,
    val alpha: Float,
) {
    companion object { val ZERO = LensSnapshot(0f, 0f, 1f, 1f, 0f) }
}
```

**为什么不挂内层 Box**：
- RenderEffect 绑定在 `Modifier.size(96.dp)` 的 Box 上时，传入 AGSL 的 `fragCoord` 范围是 0..96，不是屏幕绝对坐标
- 而 `u_lensCenter` 传的是绝对屏幕坐标（如 540）
- `uv = (fragCoord - u_lensCenter) / u_screenSize` 算出来极其巨大（uv.x ≈ -5.6）
- `sdf = length(...) - 1.0` 永远 > 0，触发 `if (sdf > 0) return half4(0.0)` → **整个 96dp 区域全透明**
- backdrop 也只能采样到 96dp 局部区域，透镜拉伸到 3.5x 时背景信息不够产生严重失真

**为什么不画硬件阴影**：
- `graphicsLayer { shadowElevation }` 只对 Box 自身渲染区有阴影；全屏 Box 的阴影没有视觉意义
- shader 内置的 rim 边缘高光已经足够表达透镜边界
- 如果需要"落影"效果（如水珠投在底栏的影子），在 writing-plans 阶段可作为小 task 补充进 shader（在 sdf ∈ (0, 0.05) 区间返回 `half4(0, 0, 0, 0.2)`）

**线程安全总结**：

| 操作 | 线程 | 安全依据 |
|------|------|----------|
| `RuntimeShader(SHADER_SRC)` 构造 | Main（remember） | Android 13+ 仅做 CPU 端 AGSL→SPIR-V 编译 |
| `setFloatUniform()` | Draw phase | 仅更新 CPU 端 uniform buffer |
| `createRuntimeShaderEffect()` | Draw phase | 创建描述对象，GPU 在 RenderThread 执行 |
| `controller.updateVelocity(dt)` | Main（withFrameNanos） | 纯数据，不碰 shader |
| snapshot 写入 | Main（withFrameNanos） | 通过 `mutableStateOf` 触发 Compose 重组 |
| snapshot 读取 | Draw phase | Compose 状态系统保证可见性 |

**降级路径**：
- API < 33 → `LegacyLensOverlay()`：用 `drawBackdrop + lens()` + 单轴近似
- RuntimeShader 编译失败 → 同上
- 健康状态：`lensShaderHealthy: Boolean` 暴露给 `GlassConfigPanel`，用户可见降级状态

### 6. Icon 反馈 — Apple 官方

**Hit-test 用椭球判定，不用圆形近似**（避免横向拉伸 3.5x 时纵向判定范围被错误放大）。

```kotlin
// 归一化椭圆距离（< 1 表示在椭圆内）
val baseR = with(density) { spec.lensRestRadius.toPx() }
val dx = (tab.center.x - position.x) / (baseR * scaleX)
val dy = (tab.center.y - position.y) / (baseR * scaleY)
val ellipseDist = sqrt(dx * dx + dy * dy)
val isInside = ellipseDist < 1f

// smoothstep 用 ellipseDist 直接做（在椭圆外缘 80%~100% 区间做软过渡）
val iconScale = lerp(1.0f, spec.iconScaleInside, smoothstep(0.8f, 1.0f, ellipseDist))
val iconTint   = if (isInside) primaryBlue else gray
val labelBold  = isInside
```

**Icon scale 走 Animatable + 软弹簧**（避免瞬时跳变）：
```kotlin
// 每个 tab 持有自己的 Animatable
val animIconScale = remember { Animatable(1f) }
LaunchedEffect(iconScale) {
    animIconScale.animateTo(iconScale, spring(stiffness = 800f, dampingRatio = 0.85f))
}
```

**Tint 切换和文字加粗保持瞬时**（这两个是状态语义，不是物理）。

**文字加粗防布局抖动**：用 `TextStyle.fontWeight` 而不是可变字体的 `fontVariationSettings`（避免引入额外字体加载），但通过 `Modifier.width(IntrinsicSize.Min)` 锁住 Text 宽度 + `BoxWithConstraints` 给整个 tab 固定宽度，加粗只影响文字字形，不撑大容器。

## 9 个可调参数（全部进 GlassConfigPanel 的 Lens 区）

| 参数 | 作用 | 范围 | 默认值 | 单位 |
|------|------|------|--------|------|
| `lensRestRadius` | 静止时球半径 | 32–72 dp | 48 | dp |
| `stretchMax` | 最大横向拉伸倍数 | 1.5–6.0 | 3.5 | x |
| `squashMax` | 纵向高速运动时附加压扁最大比例（再叠加 0.5 阻尼系数） | 0.0–0.6 | 0.40 | 比例 |
| `wallCompressK` | 触边横向最大压缩量 | 0.0–0.7 | 0.45 | 比例 |
| `wallBulgeK` | 触边垂直最大鼓起量 | 0.0–0.5 | 0.25 | 比例 |
| `chromaticAberration` | 边缘 RGB 通道分离强度 | 0.0–0.05 | 0.012 | 比例 |
| `lensRefraction` | 折射强度（屏幕像素位移） | 0.0–0.3 | 0.08 | 比例 |
| `iconScaleInside` | 透镜内 icon 放大倍数 | 1.0–1.3 | 1.18 | x |
| `iconTintAlpha` | 透镜内 icon 主色染色强度 | 0.0–1.0 | 1.0 | alpha |

存 DataStore，key 前缀 `glass_lens_`。
重启后保留。滑动 slider 时实时生效（StateFlow → CompositionLocal）。

## 拓扑变化

```
AppRoot
└─ Box(fillMaxSize)
   ├─ Box(.fillMaxSize .layerBackdrop(L)) { NavHost }   ← 抓帧（提供 backdrop shader）
   ├─ Box(.align(BottomCenter)) { iOSTabBar }            ← 上报 bar rect + pointerInput 驱动 lens
   │   ├─ Modifier.pointerInput { awaitEachGesture(...) }   ← 800ms 长按 → 触发 lens.show/moveTo/hide
   │   └─ iOSTabBarItem (each)                           ← 读 lensCenter + lensRadius 改 icon
   └─ LiquidGlassLensOverlay()                           ← 单 AGSL 椭球（重写，全屏 Box 挂 RenderEffect）
       ├─ remember { RuntimeShader(SHADER_SRC) }         ← @Composable 内创建，try-catch
       ├─ mutableStateOf<LensSnapshot>                    ← 不可变快照
       ├─ LaunchedEffect(Unit) { withFrameNanos 循环 }    ← 计算 dt → controller.updateVelocity → snapshot
       └─ Box(.fillMaxSize().graphicsLayer {              ← ★ 全屏挂 RenderEffect
              shader.setFloatUniform(...)
              renderEffect = createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()
          })
```

**触摸输入路径**（`iOSTabBar.pointerInput`）：
```kotlin
Modifier.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPress = withTimeoutOrNull(800) {
            waitForUpOrCancellation()    // 等到 800ms 后还没抬手 → 触发 lens
        }
        if (longPress == null) return@awaitEachGesture   // 没等够就抬手，不是长按
        val lens = LocalLiquidGlassLens.current
        lens.show(down.position, scope)
        // 进入拖动循环（不设超时，自然等待 UP 事件）
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            lens.moveTo(change.position, scope)
            if (event.changes.none { it.pressed }) break
        }
        lens.hide(scope)
    }
}
```
- 长按 800ms 后才触发 lens（区分点击和长按拖动）。400ms 偏短：用户按住不动超过 400ms 会被误判为"结束"，镜头突然回弹。
- 拖动循环**不设超时**，靠 `event.changes.none { it.pressed }` 自然等待 UP 事件。
- `systemGestureExclusion()` 加在 lens Box 上避免和系统边缘返回手势冲突。
- 只追踪第一指（`event.changes.firstOrNull()`），多指冲突时取最先按下的。

## 文件改动

### 改的文件

- `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LiquidGlassLens.kt` — **完全重写**：
  - `LiquidGlassLensController`：
    - 删 `scale: Animatable<Float>`（旧的 0→1 缩放进场），改 `scaleX: Animatable<Float>` + `scaleY: Animatable<Float>`（独立两轴形变）。
    - 删 `targetIcon` 字段。
    - 新增 `lastPosition: Offset`、`velocityX: Float`、`velocityY: Float`。
    - `show(at, scope)` → `position.snapTo` + 两轴 scale 各 animateTo(1f)。
    - `moveTo(at, scope)` → `position.snapTo(clamped)`（硬跟随，零延迟）→ `updateVelocity(dt)` 计算 `stretchX/Y + wall*` → 两轴 scale 各 animateTo（软弹簧插值）。
    - 新增 `updateVelocity()` 私有方法（每帧调用）。
    - `hide(scope)` → 两轴 scale 各 animateTo(0f)。
    - `bounds` 不变（iOSTabBar 注入）。
    - 新增 `lensCenter: Offset`（读 `position` 即可）+ `lensRadiusX: Float`（`baseRadius * scaleX`），给 icon hit-test 用。
  - `LiquidGlassLensOverlay`：
    - 不再调 `drawBackdrop + lens()`，改成 `graphicsLayer + RenderEffect`（AGSL 单椭球）。
    - **RenderEffect 挂全屏 `Box(.fillMaxSize())`** 而非 `Modifier.size(width, height)` 内层 Box（局部坐标 bug 修复）。
    - 不画 `translationX / scaleX / shadowElevation`：透镜位移和形变完全交给 shader 内的 SDF 公式；阴影用 shader 内的 rim 边缘高光替代。
    - `remember { RuntimeShader(SHADER_SRC) }` 在 @Composable 作用域内创建，try-catch 包住，失败返回 null → 走 `LegacyLensOverlay`。
    - `Build.VERSION.SDK_INT < TIRAMISU` 直接走 `LegacyLensOverlay`。
    - `renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "backdrop").asComposeRenderEffect()` 让 backdrop 由系统自动填充为该 layer 内容（全屏 Box → 采样全屏背景）。
    - `var snapshot by mutableStateOf(LensSnapshot.ZERO)` + LaunchedEffect 驱动 `controller.updateVelocity → controller.toSnapshot`。
    - shader uniform 在 `graphicsLayer { }` block 内设置（draw phase）。
    - `width` / `height` 参数仅用于计算 `widthPx` / `heightPx`（即 `u_screenSize` 的基础值），不再用于约束 Box 尺寸。

- `app/src/main/java/com/smartvision/gallery/data/glass/GlassConfig.kt`：
  - `LensGlassConfig` 加 9 个新字段（见上表）。
  - 删 `lensAmount` / `tintArgb` / `tintAlpha` / `vibrancy` / `blurRadius` 等旧字段（不再被 shader 读）。
  - 保留 `cornerRadius` / `shadowElevation` / `highlightAlpha`（shadow + rim 仍用）。

- `app/src/main/java/com/smartvision/gallery/ui/glass/GlassConfigPanel.kt`：
  - 在 `LensSection` 末尾追加 9 个 `SliderRow`。
  - 顺序：`lensRestRadius` → `stretchMax` → `squashMax` → `wallCompressK` → `wallBulgeK` → `chromaticAberration` → `lensRefraction` → `iconScaleInside` → `iconTintAlpha`。

- `app/src/main/java/com/smartvision/gallery/data/glass/GlassConfigRepository.kt`：
  - `LensGlassConfig.toJson()` / `fromJson()` 加 9 个新字段。
  - 加 9 个新的 `stringPreferencesKey` 常量（命名 `glass_lens_<field>`）。

- `app/src/main/java/com/smartvision/gallery/ui/apple/AppleComponents.kt`：
  - `iOSTabBarItem` 加 3 个可组合参数：`iconScale: Float = 1f`、`iconTint: Color? = null`、`labelBold: Boolean = false`。
  - `Icon` 用 `Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }` 包裹。
  - `Text` 用 `fontWeight = if (labelBold) FontWeight.Bold else FontWeight.Normal`。
  - tint 逻辑：`iconTint?.let { Icon tint = it } else { 默认主题色 }`。
  - `iOSTabBar` 内一次性算出每个 tab 的 `distToLens` / `isInside` 并传下去（用 `LocalLiquidGlassLens` 提供的 `lensCenter` + `lensRadiusX`）。

### 不改的文件

- `BackdropCapture.kt` / `LiquidGlassBackdrop.kt` / `LiquidGlassTheme.kt` — 屏幕层抓帧继续工作，lens shader 读同一个 `screenTexture`。
- `AppRoot.kt` — 拓扑不变。
- `LiquidGlassSpec.kt` — 旧 spec 工厂函数保留（其他组件还在用）。

## 性能 / 兼容性

- **AGSL 最低 API**：33（Android 13）。低于 33 走降级：`LegacyLensOverlay` 用 `drawBackdrop + lens()` + 单轴近似 `scaleX = scaleY = 1f + (stretchX - 1f) * 0.3f`（损失面积守恒但保留部分形变）。
- **RuntimeShader 最低 API**：33，同上。
- **每帧 shader 调用**：1 次 fragment（覆盖 lens 区域 ~200x200 px），60fps 下 GPU 占用 < 1ms。
- **不阻塞主线程**：AGSL 编译在 `remember { RuntimeShader(...) }` 第一次组合时完成（CPU 端 AGSL→SPIR-V），之后只是 uniform 更新。
- **density-aware 速度阈值**：MAX_VX 用 `with(density) { 1800.dp.toPx() }`（约 4725 px/秒 @ 2.625x 密度），跨设备形变阈值一致。
- **速度 clamp**：`absVx.coerceAtMost(maxVx)` + `absVy.coerceAtMost(maxVy)` 防止极速 flick 超出预期。

## 风险与回退

| 风险 | 缓解 |
|------|------|
| RuntimeShader 在某些 GPU 上编译失败 | `try-catch` 在 `remember { RuntimeShader(SHADER_SRC) }` 外 → 失败返回 null → `LegacyLensOverlay` |
| RuntimeShader 静默失败（输出黑色） | 第一次 `onDrawWithContent` 采样中心点判断输出是否合理，超出范围则降级 |
| `iconScaleInside = 1.3` 时 icon 撑爆底栏 | slider 上限锁 1.3，UI 层再 clamp `min(tab.width * 0.9, iconScaleInside * baseSize)` |
| `wallCompressK + stretchMax` 同时极大导致 scale 突变 | 两套 scale 各自软弹簧独立插值（k=600, d=0.9），串行不会突变 |
| API < 33 设备 | `Build.VERSION.SDK_INT` 检查，直接走 `LegacyLensOverlay`，不 crash |
| backdrop shader 自动填充不生效（个别 GPU 驱动） | 检查 `RuntimeShader` 编译后的输出；降级路径已就绪 |

## 自检

- [x] 6 张参考图 + 6 张形变参考图的视觉特征全覆盖
- [x] 4 个决策都有理由（不是任选）
- [x] 公式数值给出具体默认值 + 可调范围
- [x] 9 个参数全部进 GlassConfigPanel
- [x] 边界、tab、屏幕、shader 各自的职责清晰无交叉
- [x] 降级路径明确（API < 33）
- [x] 不改 AppRoot 拓扑（用户上一轮刚确认）
- [x] B1 修复：AGSL 用 `backdrop.eval(coord)` 而非 `texture()`，补全 uniform 表
- [x] B2 修复：物理严格面积守恒（`stretchY = 1/stretchX`） + 叠加纵向附加压扁
- [x] B3 修复：位置用 `snapTo`（无弹簧），形变用软弹簧（k=600, d=0.9）
- [x] B4 修复：速度单位 px/秒，EMA 系数基于 dt 实时计算
- [x] B5 修复：RuntimeShader 在 `@Composable` 内创建（不在 drawWithCache），graphicsLayer + RenderEffect 路径
- [x] I1 修复：Icon hit-test 用椭圆归一化距离，不用圆形近似
- [x] I2 修复：Icon scale 用 Animatable + 软弹簧平滑
- [x] I3 修复：文字加粗用 fontWeight + Modifier.width(IntrinsicSize.Min) 防布局抖动
- [x] I4 修复：去掉 clip，AGSL SDF 内部 `sdf > 0 → return 0` 自然透明
- [x] I5 修复：高光用 `mix(chroma, white, highlight * α * rim)`，不用 `+`
- [x] I6 修复：高光指数从 32 降到 16（4 次平方）+ pow 前 max(·, 0.0) + 浮点字面量 16.0
- [x] I7 修复：补全触摸输入路径（800ms 长按 + 拖动循环不设超时，靠 UP 事件自然结束）
- [x] I8 修复：iconScaleInside slider 上限锁 1.3（更保守）
- [x] I9 修复：降级路径明确（单轴近似 + RuntimeShader 编译失败返回 null + API < 33 走 Legacy）
- [x] **§3 速度 density-aware**：MAX_VX = `with(density) { 1800.dp.toPx() }`（跨设备阈值一致）
- [x] **§3 speedY 取 abs**：用 `absVy.coerceAtMost(maxVy)`，避免向上滑动 stretchY 异常增大
- [x] **§3 阻力常量具名**：`DRAG_DAMPING = 0.5` 固定，`squashMax` 用户可调（0.0–0.6，默认 0.40），有效压扁上限 = `squashMax × DRAG_DAMPING = 0.20`
- [x] **§5.1 RenderEffect 挂全屏 Box**：不能挂内层 `Modifier.size(width, height)`（否则 fragCoord 是局部 0..96，u_lensCenter 是绝对坐标，uv 极大使 sdf > 0 全透明）
- [x] **§5.1 全屏 Box 后去掉 translationX/scaleX/shadowElevation**：透镜位移形变完全由 shader SDF 公式接管；阴影用 shader rim 替代
- [x] **§5 删 u_aspect**：shader 内通过 `u_screenSize.x / u_screenSize.y` 计算
- [x] **§5 pow 加 max**：`float h = max(0.0, 1.0 - abs(sdf)); pow(h, 16.0)`
- [x] **§5 AGSL 源码以 uniform 开头**：不含 `#version` / `precision`
- [x] **§5.1 用 RenderEffect 路径**：不再 ShaderBrush + drawRect，graphicsLayer + RenderEffect.createRuntimeShaderEffect
- [x] **§5.1 不可变 snapshot**：通过 `var snapshot by mutableStateOf(LensSnapshot.ZERO)` 避免 main thread 写 + draw phase 读的数据竞争
- [x] **§5.1 SDK check**：`Build.VERSION.SDK_INT < TIRAMISU` 直接走 LegacyLensOverlay
- [x] **§5.1 try-catch RuntimeShader 构造**：编译失败返回 null → LegacyLensOverlay
- [x] **§5.1 shader uniform 在 graphicsLayer block 设置**（draw phase），不在线程不安全的 LaunchedEffect 里

## 下一步

进入 writing-plans 阶段，把上述 spec 拆成可执行的实现任务（按文件拆分，每个文件一个 task，每个 task 写完直接 build）。
