# 网格手势打磨：双指缩放 + 长按快选锚点修复设计

> **日期**: 2026-08-04
> **状态**: 已批准（用户确认 3 节设计 + 4 项决策）

## Goal

修复 Gallery Grid 主页的 4 个手势缺陷：列数达不到 30、双指缩放误触乱跳、长按快选锚点偏移、灵敏度过高。对齐 Apple Photos 交互手感。

## 背景与根因诊断

上一轮已实现 5 页面（Timeline/AlbumDetail/Search/Trash/PrivacyVault）的双指缩放 + 长按多选 + 滑动快选。真机验证后暴露 4 个 bug：

| Bug | 现象 | 根因 |
|-----|------|------|
| 1 | 最多 ~7 列，达不到 30 列 | `PinchToZoomGesture` 用 `colDelta=(logScale/0.15).toInt()`，列数变化与捏合距离成**对数**关系。到 30 列需 `log10(d1/d0)=-4.05` = 捏到初始距离 1/10000，物理不可达 |
| 2 | 双指"时而放大时而缩小"、误触 | 每轮手势从构造时固定的 `defaultColumns(=3)` 重算列数。缩到 10 列松手，再捏又跳回 3 列基准 → 方向感错乱；且不区分拖拽与缩放，双指平行移动也触发缩放 |
| 3 | 长按快选锚点偏移 | `GridGeometry` 命中用理想 `cellPx`（按列数公式算）推列，但 `GridCells.Adaptive` 自适应取整后每格真实尺寸 ≠ cellPx，列数越多误差越大；touch 与 item offset 坐标系未对齐 |
| 4 | 灵敏度过高 | `sensitivity=0.15` 过激，微小的 log 变化就跨多列 |

## 已确认决策

1. **缩放基准**：以"手指按下时的当前列数"为基准增量缩放，松手保持结果。缩到 10 列松手再捏，从 10 列继续，不跳回 3。
2. **缩放曲线**：连续映射（列数随对数比例连续变化，每跨整数列数触发列变更）。
3. **灵敏度**：降低（`sensitivity=0.5`），增大每跨一列所需捏合距离，累计对数仍撑满 2→30。
4. **命中测试**：改用 `LazyGridState.layoutInfo.visibleItemsInfo` 真实布局几何，不依赖理想 cellPx。
5. **列数范围**：2~30，默认 3。
6. **实现方案**：方案 A 增量修复（保留现有手势风格，集中改手势类 + GridGeometry）。

## 设计

### 1. PinchToZoomGesture — 双指缩放（Bug 1/2/4）

**改动文件**：`app/src/main/java/com/smartvision/gallery/ui/gestures/PinchToZoomGesture.kt`

- 构造参数 `defaultColumns: Int` 改为 `defaultColumns: () -> Int`（惰性读当前列数）。
- 手势按下时采样一次 `startColumns = defaultColumns()` 作为基准。
- 每帧：`logScale = log10(d1/d0)`，`target = (startColumns - logScale/sensitivity).roundToInt().coerceIn(min, max)`。
- 松手后 `gridColumns` 状态保持 → 下次手势从新基准继续。
- `sensitivity` 默认 `0.15 → 0.5`。
- **区分拖拽与缩放**：双指事件中只有两指间距变化 `|Δdist| > touchSlop` 才触发列变更；两指平行移动（间距不变）不缩放。
- companion 纯逻辑 `scaleToColumns(startColumns, logScale, sensitivity, min, max)` 供单测。

### 2. GridGeometry — 真实布局几何命中（Bug 3）

**改动文件**：`app/src/main/java/com/smartvision/gallery/ui/gestures/GridGeometry.kt`

- `CellInfo` 增加 `sizePx: Size`（`info.size`，真实宽高）；`col` 直接用 `info.column`。
- 行命中：按真实 `offsetY + sizePx.height` 区间。
- 列命中：遍历当前行可见 media，判断 `touch.x` 落在 `[offsetX, offsetX+sizePx.width)`。
- 坐标对齐：touch 与 item offset 同用网格容器坐标系（`gridOffsetY=0`）。
- 保留 `flatItems` 契约与 header 感知（header 不参与 media 计数，返回绝对 flat index）。
- `computeMediaIndex` 的 `cellSize/spacing` 参数移除，改由真实几何驱动。

### 3. DragSelectGesture — 常驻统一手势（已在本轮前置完成）

- 常驻挂网格，item 不再挂 `combinedClickable`/`detectTapGestures`（其内部 clickable 消费 pointer 事件，挡住 move → 快选失效）。
- 非多选按下 → 自检长按（`System.nanoTime` 超时）→ 命中则 `onEnterSelectMode` + 选中按下项 → 无缝拖拽。
- 抬起 = 点击；超 slop = 取消；双指 = 让位捏合。
- 多选模式 `userScrollEnabled=false`（scrollable 停抢 move），`dispatchRawDelta` 自动滚动不受影响。

### 4. 页面接线（5 页统一模式）

各页 `LazyVerticalGrid`：
1. `PinchToZoomGesture(defaultColumns = { gridColumns }, ...)` — 惰性读当前列。
2. `DragSelectGesture` 常驻挂网格。
3. `onColumnChange`：`gridColumns = newCols`（不再 `clearSelection`，保持选中）。
4. Timeline 的 `onTapItem` 移到手势层，命中后 `onOpenPhoto`。

## 审查补充与强化（用户批准后追加）

以下 3 条为审查意见，已纳入实现计划作为强化约束：

### R1. 双指缩放：死区过滤 + 手势冲突隔离
- **死区过滤**：双指间距变化引入死区，`|Δdist| > 死区阈值`（建议复用 `touchSlop` 或其倍数）才更新 `logScale`，消除手指轻微颤抖造成的微小变动。
- **手势冲突隔离**：确保单指滚动与双指捏合精确隔离。双指落下早期即判定捏合意图并**在事件分发初期截获**（双指按压后即 `consume`），阻断单指滚动/拖拽手势抢占。与 `DragSelectGesture` 的"双指=让位捏合"判定配合，互斥成立。

### R2. 真实布局几何命中：坐标系一致 + 性能优化
- **坐标系一致性**：触摸点坐标与可见项 offset 必须同一坐标系（网格容器坐标系）。顶部 Header（如 Timeline 168dp contentPadding）与屏幕安全区不额外偏移，`gridOffsetY=0` 保持。命中测试在 indexAt() 内用真实 `info.offset` 与 `info.size` 计算，验证 Header 行不产生错位。
- **性能**：拖拽快选高频 `indexAt` 调用，采用**行索引缓存**：按 `info.column==0` 建立 `rows: List<RowBounds(indexStart, top, bottom, mediaColumns)>`，手指 Y 用二分查找（`rows.binarySearchBy { top }`）定位行，再遍历该行 media 按 X 区间判断。避免每帧全量扫描。

### R3. 常驻手势：优先级状态机 + 惯性滚动验证
- **手势优先级状态机**：`DragSelectGesture` 内部明确优先级：多指(≥2) > 拖动 > 长按 > 单击。按下后先判定指头数：双指 → 让位捏合（不消费）；单指 → 进入"等待长按/拖动"子状态，超时→长按进多选，超 slop→取消。防止多指操作死锁。
- **滚动顺滑度**：多选模式 `userScrollEnabled=false` + `dispatchRawDelta` 自动滚动。验证无卡顿、无跳动；若 `dispatchRawDelta` 顺滑度不足，评估改用 `gridState.scrollBy` 的等效增量（经确认 LazyGridState 无 scrollBy，保留 dispatchRawDelta 并核实帧率）。

## 测试计划

> 注：单元测试执行被 AGP 8.7.3 + Kotlin 2.2 预存的 unit test classpath 问题阻塞（项目全部测试均无法运行，build.gradle.kts 注释已确认）。以编译 + 逻辑验证代替。

- `PinchToZoomGestureTest`：更新为按当前基准、新灵敏度 0.5、新 `scaleToColumns` 签名；断言 `scale=-5 → 30`（可达）。
- `GridGeometryTest`：改用真实几何输入断言锚点命中（列边界/header 跳过/多列）。
- `DragSelectGestureTest`：`shouldSelect`/`autoScrollSpeed` 纯函数保持。
- 真机验证：30 列、双指方向、锚点零偏移。
