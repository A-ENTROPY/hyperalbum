# OOM 修复 + 滑动快选重写设计

> **日期**: 2026-08-04
> **状态**: 已审查（修正 3 项事实错误 + 补充 1 遗漏 + 修复算法 + 强化测试 + 二轮 5 项小修）

## Goal

修复两个严重问题：
1. 双指缩放快速切换列数时触发 OOM 崩溃
2. 长按多选后滑动快选完全不可用（页面不滚动、无范围选择、二次取消失效）

## 根因回顾

### OOM

| 因素 | 说明 |
|------|------|
| 无固定 item 高度 | `aspectRatio(1f)` 图片加载前高度为 0，Lazy 无法正确计算可见项，导致过多 item 被 compose |
| Coil 2.6.0 无 maxBitmapSize | 大图可能解码全分辨率（24MB+），缩放时大量 item 重建叠加 |
| 缩放触发的批量重建 | 列数变化 → 所有 item key 不变但布局变 → 大量 AsyncImage 同时请求 |

### 滑动快选

| 因素 | 说明 |
|------|------|
| 无范围选择 | 当前每划过一项只 toggle 单项，不是从起点到当前项的区间操作 |
| dispatchRawDelta 不可靠 | 自动滚动需要手指持续移动才触发，停下就停，无法连续平滑滚动 |
| 无 LaunchedEffect 驱动 | 缺少独立于手势事件的持续滚动循环 |

### 额外发现：AsyncThumbnail 调用不一致

只有 `TimelinePage` 传了 `sizePx=cellPx`，其余 4 页（`AlbumDetail`、`Search`、`Trash`、`PrivacyVault`）使用无 size 的 3 参数重载，依赖 `BoxWithConstraints` 测量。`BoxWithConstraints` 在 item 高度固定后能正确测量，但应当统一传 `sizePx=cellPx` 以减少子组合开销。

## 设计

### 1. OOM 修复：固定 item 高度

**改动文件**：各页面 grid cell 的 `Modifier` 链

给每个 grid cell 的 `Modifier` 链显式设置高度：

```kotlin
Modifier
    .fillMaxWidth()
    .height(cellPx.toDp())  // 固定高度，让 Lazy 正确计算可见范围
    .aspectRatio(1f)
```

**影响页面**（5 页全部）：
- `TimelinePage.TimelineCell`
- `AlbumDetailPage` grid cell
- `SearchPage` grid cell
- `TrashPage` grid cell
- `PrivacyVaultPage` grid cell

**原理**：`LazyVerticalGrid` 的懒加载依赖 `contentSize` 计算可见范围。当 item 高度为 0 时 `contentSize = 0`，Lazy 认为所有 item 都可见，全部 compose。固定高度后 Lazy 能正确计算每个 item 的位置，只 compose 视口内 + 缓存区的项。

### 2. OOM 修复：统一 AsyncThumbnail sizePx

**改动文件**：`AlbumDetailPage`、`SearchPage`、`TrashPage`、`PrivacyVaultPage`

将 grid cell 的 `AsyncThumbnail` 调用从无 size 重载改为传 `sizePx=cellPx`：

```kotlin
// 改前：
AsyncThumbnail(model = item.uri, contentDescription = "...")

// 改后：
AsyncThumbnail(model = item.uri, sizePx = cellPx, contentDescription = "...")
```

### 3. 滑动快选重写：DragSelectGesture

**改动文件**：`app/.../gestures/DragSelectGesture.kt`（重写）

#### 3.1 架构

保留 `awaitEachGesture` + `awaitFirstDown` + `drag(pointerId)` 结构，新增：
- 范围选择（从起点到当前项的区间操作）
- 方向基准（SELECT / DESELECT）
- 自动滚动速度通过 `MutableState<Float>` 暴露给外部 `LaunchedEffect`

```
┌──────────────────────────────────────────────────────────┐
│                    DragSelectGesture                       │
│                                                            │
│  awaitEachGesture {                                        │
│    down = awaitFirstDown()                                 │
│    startIndex = indexAt(down.position)                     │
│    startWasSelected = isItemSelected(startIndex)           │
│    selectPass = !startWasSelected                           │
│                                                            │
│    decision = waitForDecision(                             │
│      长按超时 / 抬起 / 超过 slop / 被消费                   │
│    )                                                       │
│                                                            │
│    when (decision) {                                       │
│      Tap → onTapItem(startIndex)                           │
│      LongPress → {                                         │
│        onEnterSelectMode()                                 │
│        initialIdx = startIdx, currentIdx = startIdx        │
│        选中起点（如果未选中）                                │
│        drag(pointerId) { change →                          │
│          idx = indexAt(change.position)                    │
│          if (idx != currentIdx) 范围选择 + 更新速度         │
│          change.consume()                                   │
│        }                                                   │
│      }                                                     │
│    }                                                       │
│  }                                                         │
│                                                            │
│  外部 LaunchedEffect(snapshotFlow { autoScrollSpeed }) {   │
│    while (isActive) { scrollBy(speed); delay(10) }         │
│  }                                                         │
└──────────────────────────────────────────────────────────┘
```

#### 3.2 构造参数

```kotlin
class DragSelectGesture(
    private val gridState: LazyGridState,
    private val columns: () -> Int,  // 传给 GridGeometry 用于列命中计算
    private val isItemSelected: (Int) -> Boolean,
    private val onToggleItem: (Int) -> Unit,
    private val autoScrollSpeed: MutableState<Float>,  // 共享状态，外部 LaunchedEffect 驱动
    private val isSelectMode: () -> Boolean,
    private val onEnterSelectMode: () -> Unit,
    private val onTapItem: (Int) -> Unit,
    private val longPressTimeoutMillis: Long = 500L,  // 500ms 减少误触，小米/OPPO 相册约 400-500ms
)
```

移除 `onAutoScroll` 回调，改为 `autoScrollSpeed: MutableState<Float>`。

#### 3.3 手势流程

```
手指按下 → awaitFirstDown()
  ├─ waitForDecision(): 自检长按超时（System.nanoTime + 500ms）
  │   ├─ 抬起 → Tap → onTapItem
  │   ├─ 超过 slop → Drag（非多选模式时取消，多选模式时进入拖拽）
  │   ├─ 被消费/双指 → Cancelled
  │   └─ 超时 → LongPress
  │
  ├─ LongPress:
  │   1. onEnterSelectMode()
  │   2. initialIndex = startIndex, currentIndex = startIndex
  │   3. selectPass = shouldSelect(startWasSelected)
  │   4. 如果 selectPass → onToggleItem(startIndex) 选中起点
  │   5. drag(pointerId) { change → handleDrag(change) }
  │
  ├─ handleDrag(change):
  │   1. idx = indexAt(change.position)
  │   2. if (idx != currentIndex) → 范围选择
  │   3. emitAutoScroll(change.position.y)
  │   4. change.consume()
  │
  └─ 拖拽结束（drag{} 退出）:
     autoScrollSpeed.value = 0f
```

#### 3.4 范围选择算法

方向基准：起点未选中 → SELECT 模式（选中范围），起点已选中 → DESELECT（取消范围）。

`DragSelectGesture` 不持有选中状态，通过 `onRangeSelect` 回调通知页面更新。每次手指移动到新 item 时，从选中集合中移除上一轮范围，再添加本轮范围：

```kotlin
/**
 * 纯函数：范围选择核心逻辑。
 * @param currentSelected 当前选中集合
 * @param prevRange 上一轮范围（首次拖拽时为 initialIndex..initialIndex）
 * @param currRange 本轮新范围（始终 [initialIndex, idx] 闭区间，方向无关）
 * @param selectPass SELECT 模式（true）或 DESELECT 模式（false）
 * @param items flat list 用于解析 index → item id
 * @return 更新后的选中集合
 */
fun selectRange(
    currentSelected: Set<Long>,
    prevRange: IntRange,
    currRange: IntRange,
    selectPass: Boolean,
    items: List<Any>,
): Set<Long> {
    val newSelection = currentSelected.toMutableSet()
    // 清除上一轮范围
    for (i in prevRange) {
        val item = items.getOrNull(i) as? MediaItem ?: continue
        newSelection.remove(item.id)
    }
    // 添加本轮范围
    for (i in currRange) {
        val item = items.getOrNull(i) as? MediaItem ?: continue
        if (selectPass) newSelection.add(item.id)
    }
    return newSelection
}
```

`handleDrag` 内部只调用 `onRangeSelect` 回调，不直接操作集合：

```kotlin
// DragSelectGesture 内部
private var initialIndex = -1
private var currentIndex = -1  // 初始 -1，LongPress 时设为 startIndex

private fun handleDrag(change: PointerInputChange) {
    val idx = indexAt(change.position)
    if (idx < 0 || idx == currentIndex) return

    val prevRange = if (initialIndex <= currentIndex) initialIndex..currentIndex
        else currentIndex..initialIndex
    val currRange = if (initialIndex <= idx) initialIndex..idx
        else idx..initialIndex

    onRangeSelect(prevRange, currRange, selectPass)
    currentIndex = idx
    emitAutoScroll(change.position.y)
    change.consume()
}
```

页面回调：

```kotlin
onRangeSelect = { prevRange, currRange, selectPass ->
    selectedIds = selectRange(
        currentSelected = selectedIds,
        prevRange = prevRange,
        currRange = currRange,
        selectPass = selectPass,
        items = flatList,
    )
}
```

#### 3.5 自动滚动

```kotlin
// 在每个页面中：
val autoScrollSpeed = remember { mutableFloatStateOf(0f) }

// snapshotFlow + collectLatest 驱动连续滚动
LaunchedEffect(Unit) {
    snapshotFlow { autoScrollSpeed.floatValue }
        .filter { it != 0f }
        .collectLatest { speed ->
            while (isActive) {
                gridState.scrollBy(speed)
                delay(10)  // ~100fps 滚动更新
            }
        }
}
```

`scrollBy` 参数为 px/帧。`collectLatest` 在 speed 变化时自动取消旧协程、启动新协程，避免 `LaunchedEffect` key 变化带来的重启延迟。

`autoScrollSpeed` 的计算保留当前 `autoScrollSpeed` 纯函数（三段式加速），`DragSelectGesture` 在 `emitAutoScroll` 中写 `autoScrollSpeed.floatValue = speed`。

**自动滚动时的 indexAt 行为**：跨页自动滚动时，手指停在屏幕边缘不动，grid 滚动导致 `visibleItemsInfo` 更新。`indexAt` 使用屏幕坐标 hit-test，grid 滚动后同一屏幕坐标对应不同的 item，因此新 item 会被正确命中并选中。这是期望行为，无需额外处理。

#### 3.6 点击处理

保留当前方案：`waitForDecision` 检测到抬起 → `Decision.Tap` → `onTapItem(startIndex)`。非多选模式 → 打开照片，多选模式 → toggle 选中状态。

#### 3.7 GridGeometry 保留

保留 `GridGeometry.computeMediaIndex`（RowBounds 二分查找在大量 item 时性能更优），不替换为 `visibleItemsInfo.find`。

### 4. 页面接线修改

#### 4.1 所有页面统一修改

每个使用 `DragSelectGesture` 的页面需要：

```kotlin
val autoScrollSpeed = remember { mutableFloatStateOf(0f) }

LaunchedEffect(Unit) {
    snapshotFlow { autoScrollSpeed.floatValue }
        .filter { it != 0f }
        .collectLatest { speed ->
            while (isActive) {
                gridState.scrollBy(speed)
                delay(10)
            }
        }
}

// grid modifier 中：
DragSelectGesture(
    gridState = gridState,
    columns = { gridColumns },
    isItemSelected = { idx -> ... },
    onToggleItem = { idx -> ... },
    autoScrollSpeed = autoScrollSpeed,
    isSelectMode = { selectModeEnabled },
    onEnterSelectMode = { selectModeEnabled = true },
    onTapItem = { idx -> ... },
)
```

`onAutoScroll` 回调移除，改为 `autoScrollSpeed` 状态。

#### 4.2 各页面具体改动

| 页面 | 改动 |
|------|------|
| `TimelinePage.kt` | 加 `autoScrollSpeed` + `LaunchedEffect`；`TimelineCell` 加 `.height(cellPx.toDp())`；`AsyncThumbnail` 已传 `sizePx=cellPx`（无需改） |
| `AlbumDetailPage.kt` | 同上 + `AsyncThumbnail` 改传 `sizePx=cellPx` |
| `SearchPage.kt` | 同上 + `AsyncThumbnail` 改传 `sizePx=cellPx` |
| `TrashPage.kt` | 同上 + `AsyncThumbnail` 改传 `sizePx=cellPx` |
| `PrivacyVaultPage.kt` | 同上 + `AsyncThumbnail` 改传 `sizePx=cellPx` |

### 5. 测试计划

#### 5.1 纯函数测试（已有）

- `autoScrollSpeed` 三段式加速逻辑
- `shouldSelect` 方向基准逻辑

#### 5.2 范围选择算法测试（新增）

```kotlin
@ test
fun select_mode_selects_range() {
    // 起点未选中（SELECT 模式），选中 [2..5]
    val selected = selectRange(
        currentSelected = emptySet(),
        prevRange = 0..0,   // 初始
        currRange = 2..5,
        selectPass = true,
    )
    assertEquals(setOf(2, 3, 4, 5), selected)
}

@ test
fun deselect_mode_deselects_range() {
    // 起点已选中（DESELECT 模式），取消 [2..5]
    val selected = selectRange(
        currentSelected = setOf(1, 2, 3, 4, 5, 6),
        prevRange = 0..0,
        currRange = 2..5,
        selectPass = false,
    )
    assertEquals(setOf(1, 6), selected)
}

@ test
fun previous_range_cleared_before_new_range() {
    // 从 [2..5] 滑到 [2..8]，旧的 2..5 清除，新的 2..8 选中
    val selected = selectRange(
        currentSelected = setOf(2, 3, 4, 5),
        prevRange = 2..5,
        currRange = 2..8,
        selectPass = true,
    )
    assertEquals(setOf(2, 3, 4, 5, 6, 7, 8), selected)
}

@ test
fun reverse_direction_shrinks_selection() {
    // 从 [2..8] 滑回 [2..5]，旧的 2..8 清除，新的 2..5 选中
    val selected = selectRange(
        currentSelected = setOf(2, 3, 4, 5, 6, 7, 8),
        prevRange = 2..8,
        currRange = 2..5,
        selectPass = true,
    )
    assertEquals(setOf(2, 3, 4, 5), selected)
}
```

#### 5.3 真机验证

- OOM 压力测试：快速在 2 列 ↔ 4 列之间切换 10 次，观察内存是否稳定
- 快选基本流程：长按进入多选 → 向下滑动 → 选中连续范围 → 检查准确性
- 二次取消：从已选中项开始长按 → 滑动 → 取消选中范围
- 方向切换：向上拖 → 向下拖 → 范围正确收缩
- 跨页自动滚动：滑到边缘 → 页面自动滚动 → 新 item 出现并被选中
- 点击：非多选模式点击打开照片，多选模式点击切换选中

## 变更文件清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `app/.../gestures/DragSelectGesture.kt` | 重写 | 范围选择 + autoScrollSpeed 状态代替回调 |
| `app/.../pages/TimelinePage.kt` | 修改 | 加 autoScrollSpeed + LaunchedEffect；cell 加 height |
| `app/.../album/AlbumDetailPage.kt` | 修改 | 加 autoScrollSpeed + LaunchedEffect；cell 加 height；AsyncThumbnail 传 sizePx |
| `app/.../search/SearchPage.kt` | 修改 | 同上 |
| `app/.../trash/TrashPage.kt` | 修改 | 同上 |
| `app/.../privacy/PrivacyVaultPage.kt` | 修改 | 同上 |