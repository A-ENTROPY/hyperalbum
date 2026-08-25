# OOM 修复 + 滑动快选重写 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复双指缩放快速切换列数时的 OOM 崩溃和长按多选后滑动快选完全不可用的问题。

**Architecture:** 三个独立改动线：(1) 固定 grid cell 高度让 Lazy 正确回收；(2) DragSelectGesture 重写为范围选择模式 + autoScrollSpeed 状态驱动；(3) 5 个页面统一接线。纯函数 selectRange 可脱离 Compose 测试。

**Tech Stack:** Jetpack Compose (LazyVerticalGrid, awaitEachGesture, LaunchedEffect, snapshotFlow, drag), Coil 2.6.0

---

### Task 1: DragSelectGesture 重写 — 核心手势逻辑

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/gestures/DragSelectGesture.kt` (全量重写)
- Test: `app/src/test/java/com/smartvision/gallery/ui/gestures/DragSelectGestureTest.kt` (新增 selectRange 测试)

- [ ] **Step 1: 写 selectRange 纯函数测试**

```kotlin
// 在 DragSelectGestureTest.kt 末尾追加
import com.smartvision.gallery.ui.gestures.DragSelectGesture.Companion.selectRange
import org.junit.Test
import org.junit.Assert.assertEquals

class SelectRangeTest {

    @Test
    fun select_mode_selects_range() {
        val selected = selectRange(
            currentSelected = emptySet(),
            prevRange = 0..0,
            currRange = 2..5,
            selectPass = true,
        )
        assertEquals(setOf(2L, 3L, 4L, 5L), selected)
    }

    @Test
    fun deselect_mode_deselects_range() {
        val selected = selectRange(
            currentSelected = setOf(1L, 2L, 3L, 4L, 5L, 6L),
            prevRange = 0..0,
            currRange = 2..5,
            selectPass = false,
        )
        assertEquals(setOf(1L, 6L), selected)
    }

    @Test
    fun previous_range_cleared_before_new_range() {
        val selected = selectRange(
            currentSelected = setOf(2L, 3L, 4L, 5L),
            prevRange = 2..5,
            currRange = 2..8,
            selectPass = true,
        )
        assertEquals(setOf(2L, 3L, 4L, 5L, 6L, 7L, 8L), selected)
    }

    @Test
    fun reverse_direction_shrinks_selection() {
        val selected = selectRange(
            currentSelected = setOf(2L, 3L, 4L, 5L, 6L, 7L, 8L),
            prevRange = 2..8,
            currRange = 2..5,
            selectPass = true,
        )
        assertEquals(setOf(2L, 3L, 4L, 5L), selected)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd H:\workspace-minimaxcode\新建文件夹\超级相册 && .\gradlew testDebugUnitTest --tests "com.smartvision.gallery.ui.gestures.SelectRangeTest" 2>&1 | tail -20`
Expected: `Compilation error: selectRange not found` 或类似错误

- [ ] **Step 3: 重写 DragSelectGesture.kt**

完整替换为范围选择版本：

```kotlin
package com.smartvision.gallery.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed

class DragSelectGesture(
    private val gridState: LazyGridState,
    private val columns: () -> Int,
    private val isItemSelected: (Int) -> Boolean,
    private val onToggleItem: (Int) -> Unit,
    private val onRangeSelect: (prevRange: IntRange, currRange: IntRange, selectPass: Boolean) -> Unit,
    private val autoScrollSpeed: MutableState<Float>,
    private val isSelectMode: () -> Boolean,
    private val onEnterSelectMode: () -> Unit,
    private val onTapItem: (Int) -> Unit,
    private val longPressTimeoutMillis: Long = 500L,
) {
    suspend fun PointerInputScope.attach() {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown()
            val startIndex = indexAt(down.position)
            val startWasSelected = startIndex >= 0 && isItemSelected(startIndex)
            val selectPass = startIndex < 0 || shouldSelect(startWasSelected)

            val decision = waitForDecision(
                pointerId = down.id,
                downPosition = down.position,
                touchSlop = touchSlop,
                longPressEnabled = !isSelectMode(),
            )

            when (decision) {
                Decision.Tap -> {
                    if (startIndex >= 0) {
                        if (isSelectMode()) onToggleItem(startIndex)
                        else onTapItem(startIndex)
                    }
                }
                Decision.LongPress -> {
                    onEnterSelectMode()
                    if (startIndex >= 0) onToggleItem(startIndex)
                    dragSelect(down.id, selectPass, startIndex)
                }
                Decision.Drag -> {
                    if (isSelectMode()) dragSelect(down.id, selectPass, startIndex)
                }
                Decision.Cancelled -> Unit
            }
        }
    }

    private suspend fun AwaitPointerEventScope.dragSelect(
        pointerId: PointerId,
        selectPass: Boolean,
        startIndex: Int,
    ) {
        var initialIndex = startIndex
        var currentIndex = startIndex
        try {
            drag(pointerId) { change ->
                val idx = indexAt(change.position)
                if (idx < 0 || idx == currentIndex) return@drag

                val prevRange = if (initialIndex <= currentIndex) initialIndex..currentIndex
                    else currentIndex..initialIndex
                val currRange = if (initialIndex <= idx) initialIndex..idx
                    else idx..initialIndex

                onRangeSelect(prevRange, currRange, selectPass)
                currentIndex = idx
                emitAutoScroll(change.position.y)
                change.consume()
            }
        } finally {
            autoScrollSpeed.value = 0f
        }
    }

    private suspend fun AwaitPointerEventScope.waitForDecision(
        pointerId: PointerId,
        downPosition: Offset,
        touchSlop: Float,
        longPressEnabled: Boolean,
    ): Decision {
        val deadline = System.nanoTime() + longPressTimeoutMillis * 1_000_000L
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
            when {
                change.isConsumed -> return Decision.Cancelled
                event.changes.count { it.pressed } >= 2 -> return Decision.Cancelled
                change.changedToUpIgnoreConsumed() -> return Decision.Tap
                (change.position - downPosition).getDistance() > touchSlop ->
                    return Decision.Drag
                longPressEnabled && System.nanoTime() >= deadline ->
                    return Decision.LongPress
            }
        }
    }

    private fun emitAutoScroll(posY: Float) {
        val viewportHeight = gridState.layoutInfo.viewportSize.height
        if (viewportHeight <= 0) return
        autoScrollSpeed.value = autoScrollSpeed(
            posY = posY,
            viewportHeightPx = viewportHeight.toFloat(),
            edgePx = EDGE_PX,
            maxSpeedPx = MAX_SPEED_PX,
        )
    }

    private fun indexAt(pos: Offset): Int =
        GridGeometry.computeMediaIndex(
            visibleInfos = gridState.layoutInfo.visibleItemsInfo.map { info ->
                GridGeometry.CellInfo(
                    index = info.index,
                    type = if (info.span > 1) GridGeometry.CellType.HEADER else GridGeometry.CellType.MEDIA,
                    col = info.column,
                    span = info.span,
                    offsetY = info.offset.y.toFloat(),
                    offsetX = info.offset.x.toFloat(),
                    sizePx = with(info.size) { Size(width.toFloat(), height.toFloat()) },
                )
            },
            touch = pos,
        )

    private enum class Decision { Tap, LongPress, Drag, Cancelled }

    companion object {
        const val MAX_SPEED_PX = 1200f
        const val EDGE_PX = 110f

        fun shouldSelect(startWasSelected: Boolean): Boolean = !startWasSelected

        fun autoScrollSpeed(
            posY: Float,
            viewportHeightPx: Float,
            edgePx: Float,
            maxSpeedPx: Float,
        ): Float {
            if (edgePx <= 0f || maxSpeedPx <= 0f) return 0f
            val topDistance = posY
            val bottomDistance = viewportHeightPx - posY
            val halfEdge = edgePx / 2f
            return when {
                topDistance >= 0f && topDistance < edgePx -> {
                    val raw = if (topDistance <= halfEdge) maxSpeedPx
                    else maxSpeedPx * (edgePx - topDistance) / edgePx
                    -raw
                }
                bottomDistance >= 0f && bottomDistance < edgePx -> {
                    if (bottomDistance <= halfEdge) maxSpeedPx
                    else maxSpeedPx * (edgePx - bottomDistance) / edgePx
                }
                else -> 0f
            }.coerceIn(-maxSpeedPx, maxSpeedPx)
        }

        fun selectRange(
            currentSelected: Set<Long>,
            prevRange: IntRange,
            currRange: IntRange,
            selectPass: Boolean,
        ): Set<Long> {
            val newSelection = currentSelected.toMutableSet()
            for (i in prevRange) {
                newSelection.remove(i.toLong())
            }
            for (i in currRange) {
                if (selectPass) newSelection.add(i.toLong())
            }
            return newSelection
        }
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `cd H:\workspace-minimaxcode\新建文件夹\超级相册 && .\gradlew testDebugUnitTest --tests "com.smartvision.gallery.ui.gestures.SelectRangeTest" 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`，4/4 tests passed

- [ ] **Step 5: 提交**

```bash
cd H:\workspace-minimaxcode\新建文件夹\超级相册
git add app/src/main/java/com/smartvision/gallery/ui/gestures/DragSelectGesture.kt
git add app/src/test/java/com/smartvision/gallery/ui/gestures/DragSelectGestureTest.kt
git commit -m "refactor: rewrite DragSelectGesture with range selection + autoScrollSpeed state

- Replace onAutoScroll callback with MutableState<Float> autoScrollSpeed
- Add onRangeSelect callback for prevRange/currRange range-based selection
- Add selectRange pure function (companion) for testable range logic
- Add 4 unit tests for selectRange (select, deselect, extend, shrink)"
```

---

### Task 2: 5 页面统一 AsyncThumbnail sizePx + 固定 cell 高度

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/album/AlbumDetailPage.kt` (cell 加 height + AsyncThumbnail 传 sizePx)
- Modify: `app/src/main/java/com/smartvision/gallery/ui/search/SearchPage.kt` (同上)
- Modify: `app/src/main/java/com/smartvision/gallery/ui/trash/TrashPage.kt` (同上)
- Modify: `app/src/main/java/com/smartvision/gallery/ui/privacy/PrivacyVaultPage.kt` (同上)
- Modify: `app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt` (cell 加 height，AsyncThumbnail 已传 sizePx 无需改)

- [ ] **Step 1: 修改 TimelinePage TimelineCell — 加固定高度**

在 `TimelinePage.kt` 中找到 `TimelineCell` 的 `Modifier` 链，在 `.fillMaxWidth()` 后加 `.height(cellPx.toDp())`：

```kotlin
// 改前：
Modifier
    .fillMaxWidth()
    .aspectRatio(1f)

// 改后：
Modifier
    .fillMaxWidth()
    .height(cellPx.toDp())
    .aspectRatio(1f)
```

确保 `cellPx` 是 `Int`（px 值），需要导入 `androidx.compose.ui.unit.dp`。

- [ ] **Step 2: 修改 AlbumDetailPage — 加高度 + AsyncThumbnail sizePx**

找到 grid cell 的 `Modifier` 链，加 `.height(cellPx.toDp())`：

```kotlin
// 改前：
Modifier
    .fillMaxWidth()
    .aspectRatio(1f)

// 改后：
Modifier
    .fillMaxWidth()
    .height(cellPx.toDp())
    .aspectRatio(1f)
```

同时找到 `AsyncThumbnail` 调用，从无 size 重载改为传 `sizePx=cellPx`：

```kotlin
// 改前：
AsyncThumbnail(model = item.uri, contentDescription = "...")

// 改后：
AsyncThumbnail(model = item.uri, sizePx = cellPx, contentDescription = "...")
```

- [ ] **Step 3: 修改 SearchPage — 加高度 + AsyncThumbnail sizePx**

同上：`.fillMaxWidth()` 后加 `.height(cellPx.toDp())`，`AsyncThumbnail` 改传 `sizePx=cellPx`。

- [ ] **Step 4: 修改 TrashPage — 加高度 + AsyncThumbnail sizePx**

同上。同时清理未使用的 `combinedClickable` import（如果存在）。

- [ ] **Step 5: 修改 PrivacyVaultPage — 加高度 + AsyncThumbnail sizePx**

同上。同时清理未使用的 `combinedClickable` import（如果存在）。

- [ ] **Step 6: 提交**

```bash
cd H:\workspace-minimaxcode\新建文件夹\超级相册
git add app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt
git add app/src/main/java/com/smartvision/gallery/ui/album/AlbumDetailPage.kt
git add app/src/main/java/com/smartvision/gallery/ui/search/SearchPage.kt
git add app/src/main/java/com/smartvision/gallery/ui/trash/TrashPage.kt
git add app/src/main/java/com/smartvision/gallery/ui/privacy/PrivacyVaultPage.kt
git commit -m "fix: add fixed cell height and unify AsyncThumbnail sizePx across all 5 pages

- Add .height(cellPx.toDp()) to all grid cells for Lazy proper recycling
- Fix OOM: fixed height enables LazyVerticalGrid to calculate visible range
- Unify AsyncThumbnail calls: pass sizePx=cellPx in all 5 pages
- Remove unused combinedClickable imports"
```

---

### Task 3: 5 页面 autoScrollSpeed + LaunchedEffect + 新 DragSelectGesture 接线

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/album/AlbumDetailPage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/search/SearchPage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/trash/TrashPage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/privacy/PrivacyVaultPage.kt`

- [ ] **Step 1: 给 TimelinePage 加 autoScrollSpeed + LaunchedEffect + 更新 DragSelectGesture 接线**

```kotlin
// 在 Composable 函数中：
val autoScrollSpeed = remember { mutableFloatStateOf(0f) }

// 在 gridState 定义之后：
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

// 更新 DragSelectGesture 构造调用：
DragSelectGesture(
    gridState = gridState,
    columns = { gridColumns },
    isItemSelected = { idx -> selectedIds.contains(flatList.getOrNull(idx)?.id ?: -1L) },
    onToggleItem = { idx -> /* toggle item in selectedIds */ },
    onRangeSelect = { prevRange, currRange, selectPass ->
        selectedIds = selectRange(
            currentSelected = selectedIds,
            prevRange = prevRange,
            currRange = currRange,
            selectPass = selectPass,
        )
    },
    autoScrollSpeed = autoScrollSpeed,
    isSelectMode = { selectModeEnabled },
    onEnterSelectMode = { selectModeEnabled = true },
    onTapItem = { idx -> /* open photo */ },
)
```

需要新增 import：
```kotlin
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.snapshotFlow
```

- [ ] **Step 2: 给 AlbumDetailPage 加 autoScrollSpeed + LaunchedEffect + 更新接线**

同上模式。注意：`onRangeSelect` 回调中处理 `selectedIds` 更新，调 `selectRange` 纯函数。

- [ ] **Step 3: 给 SearchPage 加 autoScrollSpeed + LaunchedEffect + 更新接线**

同上。

- [ ] **Step 4: 给 TrashPage 加 autoScrollSpeed + LaunchedEffect + 更新接线**

同上。

- [ ] **Step 5: 给 PrivacyVaultPage 加 autoScrollSpeed + LaunchedEffect + 更新接线**

同上。

- [ ] **Step 6: 编译验证**

Run: `cd H:\workspace-minimaxcode\新建文件夹\超级相册 && .\gradlew assembleDebug 2>&1 | tail -30`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交**

```bash
cd H:\workspace-minimaxcode\新建文件夹\超级相册
git add app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt
git add app/src/main/java/com/smartvision/gallery/ui/album/AlbumDetailPage.kt
git add app/src/main/java/com/smartvision/gallery/ui/search/SearchPage.kt
git add app/src/main/java/com/smartvision/gallery/ui/trash/TrashPage.kt
git add app/src/main/java/com/smartvision/gallery/ui/privacy/PrivacyVaultPage.kt
git commit -m "feat: wire autoScrollSpeed and LaunchedEffect to all 5 pages

- Add autoScrollSpeed MutableState and LaunchedEffect(snapshotFlow) for continuous scroll
- Replace old DragSelectGesture constructor with new onRangeSelect + autoScrollSpeed
- Use selectRange pure function in onRangeSelect callback"
```

---

### Task 4: 运行全部单元测试 + 真机验证

**Files:**
- Run: `app/src/test/java/com/smartvision/gallery/ui/gestures/DragSelectGestureTest.kt`
- Run: `app/src/test/java/com/smartvision/gallery/ui/gestures/PinchToZoomGestureTest.kt`

- [ ] **Step 1: 运行全部单元测试**

Run: `cd H:\workspace-minimaxcode\新建文件夹\超级相册 && .\gradlew testDebugUnitTest 2>&1 | tail -20`
Expected: `BUILD SUCCESSFUL`，全部测试通过

- [ ] **Step 2: 真机验证清单**

部署到真机后验证：
- OOM 压力测试：快速在 2 列 ↔ 4 列之间切换 10 次，观察内存是否稳定
- 快选基本流程：长按进入多选 → 向下滑动 → 选中连续范围 → 检查准确性
- 二次取消：从已选中项开始长按 → 滑动 → 取消选中范围
- 方向切换：向上拖 → 向下拖 → 范围正确收缩
- 跨页自动滚动：滑到边缘 → 页面自动滚动 → 新 item 出现并被选中
- 点击：非多选模式点击打开照片，多选模式点击切换选中
- 双指缩放：正常缩放，无 OOM

- [ ] **Step 3: 提交**

```bash
cd H:\workspace-minimaxcode\新建文件夹\超级相册
git commit -m "test: add selectRange unit tests and verify all gestures

- 4 selectRange tests: select, deselect, extend, shrink
- Manual verification: OOM, drag select, deselect, auto-scroll, tap"
```