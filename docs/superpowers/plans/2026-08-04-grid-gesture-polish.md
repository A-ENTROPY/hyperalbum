# 网格手势打磨实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Gallery Grid 4 个手势缺陷（列数达不到 30、双指误触乱跳、长按快选锚点偏移、灵敏度过高），对齐 Apple Photos 手感。

**Architecture:** 三个手势/几何单元分而治之——`PinchToZoomGesture` 改为以"按下时当前列数"为基准的增量连续映射（含死区过滤 + 双指平移不缩放）；`GridGeometry` 改用 `layoutInfo.visibleItemsInfo` 真实布局几何做命中测试（行二分索引缓存）；`DragSelectGesture` 常驻统一手势已前置完成，仅需保留方向基准与自动滚动。测试纯逻辑通过 companion 函数覆盖。

**Tech Stack:** Kotlin, Jetpack Compose (foundation 1.8.3), LazyVerticalGrid, kotlin.math (ln/log/sqrt), JUnit4.

**前置状态确认**（实现者需先核实）：
- `DragSelectGesture.kt` 已是常驻手势（含 `isSelectMode`/`onEnterSelectMode`/`onTapItem` 参数与长按检测），本轮不再改动其结构。
- 测试执行被 AGP 8.7.3 + Kotlin 2.2 预存 classpath 问题阻塞（全部测试无法运行）。**验证手段 = `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin` 编译通过 + 纯逻辑推演**。
- 当前分支 `feat/curated-ai`，基线 commit `263c830`。

---

### Task 1: PinchToZoomGesture — 增量缩放基准 + 连续映射 + 死区过滤

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/gestures/PinchToZoomGesture.kt`
- Test: `app/src/test/java/com/smartvision/gallery/ui/gestures/PinchToZoomGestureTest.kt`

- [ ] **Step 1: 更新测试为"当前基准 + 新灵敏度 0.5"**

重写 `PinchToZoomGestureTest.kt`：

```kotlin
package com.smartvision.gallery.ui.gestures

import com.smartvision.gallery.ui.gestures.PinchToZoomGesture.Companion.scaleToColumns
import org.junit.Test
import org.junit.Assert.assertEquals

class PinchToZoomGestureTest {

    @Test
    fun zero_scale_keeps_columns_at_start() {
        assertEquals(10, scaleToColumns(logScale = 0f, startColumns = 10, minColumns = 2, maxColumns = 30, sensitivity = 0.5f))
    }

    @Test
    fun positive_log_decreases_columns_from_start() {
        // 双指张开（放大）→ log10(2/1)≈0.301，灵敏度 0.5 → -0.6 → round 到 -1 列
        assertEquals(9, scaleToColumns(logScale = 0.301f, startColumns = 10, minColumns = 2, maxColumns = 30, sensitivity = 0.5f))
    }

    @Test
    fun negative_log_increases_columns_from_start() {
        // 双指捏合（缩小）→ 负 log → 列数增加
        assertEquals(11, scaleToColumns(logScale = -0.301f, startColumns = 10, minColumns = 2, maxColumns = 30, sensitivity = 0.5f))
    }

    @Test
    fun scale_clamped_to_2_30() {
        assertEquals(30, scaleToColumns(logScale = -5f, startColumns = 3, minColumns = 2, maxColumns = 30, sensitivity = 0.5f))
        assertEquals(2, scaleToColumns(logScale = 5f, startColumns = 3, minColumns = 2, maxColumns = 30, sensitivity = 0.5f))
    }

    @Test
    fun sensitivity_05_reaches_30_with_large_pinch() {
        // 灵敏度 0.5：捏合到初始 1/10 距离（log10(0.1)=-1）→ +2 列
        assertEquals(5, scaleToColumns(logScale = -1f, startColumns = 3, minColumns = 2, maxColumns = 30, sensitivity = 0.5f))
        // 累计对数足够时可达 30 列
        assertEquals(30, scaleToColumns(logScale = -14f, startColumns = 3, minColumns = 2, maxColumns = 30, sensitivity = 0.5f))
    }
}
```

- [ ] **Step 2: 重写 `scaleToColumns` 与手势主体**

重写 `PinchToZoomGesture.kt` 全文：

```kotlin
package com.smartvision.gallery.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 双指缩放手势：连续缩放缩略图网格，列数 2~30。
 *
 * ## 增量缩放基准（Incremental Basis）
 *
 * 以"手指按下时的当前列数"为基准，而非构造时的固定默认值。松手后列数状态
 * 保持，下次手势从新基准继续，不会跳回默认——方向感始终一致。
 *
 * ## 连续映射
 *
 * 每帧列数 = startColumns - log10(d1/d0) / sensitivity，roundToInt 后钳制在
 * min..max。列数随对数比例连续变化，每跨一个整数列数才触发一次列变更。
 *
 * ## 死区过滤
 *
 * 只有两指间距变化超过死区（touchSlop）才更新缩放比例，消除手指轻微颤抖造成
 * 的微小列数抖动。两指平行移动（间距不变）不触发缩放。
 *
 * 语义对齐 Apple Photos：双指捏合（距离缩小）→ 列数增加；双指张开（距离放大）
 * → 列数减少。
 */
class PinchToZoomGesture(
    private val defaultColumns: () -> Int,
    private val minColumns: Int = 2,
    private val maxColumns: Int = 30,
    private val sensitivity: Float = 0.5f,
    private val onColumnChange: (Int) -> Unit,
    private val onGestureEnd: () -> Unit,
) {
    suspend fun PointerInputScope.attach() {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            var startColumns = defaultColumns()
            var initialDistance = 0f
            var lastDistance = 0f
            var isPinned = false
            var lastEmittedColumns = startColumns

            while (true) {
                val event = awaitPointerEvent()
                val changes = event.changes
                val pointers = changes.filter { it.pressed }

                when {
                    // 双指：开始/继续缩放
                    pointers.size >= 2 -> {
                        val p1 = pointers[0].position
                        val p2 = pointers[1].position
                        val dist = distance(p1, p2)
                        if (initialDistance <= 0f && dist > 0f) {
                            initialDistance = dist
                            lastDistance = dist
                            isPinned = false
                        } else if (dist > 0f && initialDistance > 0f) {
                            // 死区过滤：仅间距变化超过 touchSlop 才视为缩放
                            val deltaDist = dist - lastDistance
                            if (kotlin.math.abs(deltaDist) > touchSlop) {
                                lastDistance = dist
                                val logScale = ln(dist / initialDistance) / ln(10f) // log10
                                val target = scaleToColumns(
                                    logScale = logScale,
                                    startColumns = startColumns,
                                    minColumns = minColumns,
                                    maxColumns = maxColumns,
                                    sensitivity = sensitivity,
                                )
                                if (target != lastEmittedColumns) {
                                    lastEmittedColumns = target
                                    onColumnChange(target)
                                }
                            }
                        }
                        isPinned = true
                        changes.forEach { it.consume() }
                    }
                    // 双指变单指（一手指抬起）：结束本轮，等下一轮
                    else -> {
                        if (isPinned) {
                            onGestureEnd()
                            isPinned = false
                        }
                        initialDistance = 0f
                        lastDistance = 0f
                    }
                }

                if (event.changes.all { !it.pressed }) break
            }
            if (isPinned) onGestureEnd()
        }
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /**
         * 纯逻辑：把对数缩放比例映射到列数。供单元测试使用。
         *
         * @param logScale log10(d1/d0)，正数放大，负数缩小
         * @param startColumns 本次手势按下时的当前列数（增量基准）
         * @param minColumns / @param maxColumns 列数范围
         * @param sensitivity 缩放灵敏度，每单位 log10 比例对应的列数变化
         */
        fun scaleToColumns(
            logScale: Float,
            startColumns: Int,
            minColumns: Int,
            maxColumns: Int,
            sensitivity: Float = 0.5f,
        ): Int {
            if (logScale == 0f) return startColumns
            val delta = (logScale / sensitivity).roundToInt()
            return (startColumns - delta).coerceIn(minColumns, maxColumns)
        }
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugUnitTestKotlin
```
Expected: `BUILD SUCCESSFUL`（主代码未改，先验证测试签名不冲突）。

- [ ] **Step 4: 提交**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && git add app/src/main/java/com/smartvision/gallery/ui/gestures/PinchToZoomGesture.kt app/src/test/java/com/smartvision/gallery/ui/gestures/PinchToZoomGestureTest.kt && git commit -m "fix(gesture): incremental pinch-zoom with dead-zone filter, reachable 30 columns"
```

---

### Task 2: GridGeometry — 真实布局几何命中 + 行索引二分

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/gestures/GridGeometry.kt`
- Test: `app/src/test/java/com/smartvision/gallery/ui/gestures/GridGeometryTest.kt`

- [ ] **Step 1: 更新测试为真实几何输入**

重写 `GridGeometryTest.kt`（`CellInfo` 字段序 `(index, type, col, span, offsetY, offsetX, sizePx)`）：

```kotlin
package com.smartvision.gallery.ui.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.smartvision.gallery.ui.gestures.GridGeometry.CellInfo
import com.smartvision.gallery.ui.gestures.GridGeometry.CellType
import com.smartvision.gallery.ui.gestures.GridGeometry.computeMediaIndex
import org.junit.Assert.assertEquals
import org.junit.Test

private fun cell(
    index: Int,
    type: CellType,
    col: Int,
    top: Float,
    x: Float,
    w: Float,
    h: Float = w,
    span: Int = 1,
) = CellInfo(index, type, col, span, top, x, Size(w, h))

/** 3 列布局，每格真实 100x100，spacing=0：
 *  row0 y[0,100)   HEADER(0) 占满整行（x0 w300）
 *  row1 y[100,200) MEDIA 1/2/3（col 0/1/2，x 起始 0/100/200）
 *  row2 y[200,300) HEADER(4) 占满整行（x0 w300）
 *  row3 y[300,400) MEDIA 5/6/7（col 0/1/2）
 */
fun visibleInfos(): List<CellInfo> = listOf(
    cell(0, CellType.HEADER, 0, 0f, 0f, 300f, 100f, span = 3),
    cell(1, CellType.MEDIA, 0, 100f, 0f, 100f),
    cell(2, CellType.MEDIA, 1, 100f, 100f, 100f),
    cell(3, CellType.MEDIA, 2, 100f, 200f, 100f),
    cell(4, CellType.HEADER, 0, 200f, 0f, 300f, 100f, span = 3),
    cell(5, CellType.MEDIA, 0, 300f, 0f, 100f),
    cell(6, CellType.MEDIA, 1, 300f, 100f, 100f),
    cell(7, CellType.MEDIA, 2, 300f, 200f, 100f),
)

class GridGeometryTest {

    @Test
    fun finger_on_first_media_row_returns_index_1() {
        val index = computeMediaIndex(
            visibleInfos = visibleInfos(),
            touch = Offset(50f, 150f), // row1 col0 中心
        )
        assertEquals(1, index)
    }

    @Test
    fun finger_on_second_media_row_returns_flat_index_7() {
        val index = computeMediaIndex(
            visibleInfos = visibleInfos(),
            touch = Offset(250f, 350f), // row3 col2
        )
        assertEquals(7, index)
    }

    @Test
    fun touch_on_header_row_returns_minus_one() {
        val index = computeMediaIndex(
            visibleInfos = visibleInfos(),
            touch = Offset(150f, 250f), // row2 Header
        )
        assertEquals(-1, index)
    }

    @Test
    fun adaptive_uneven_cell_width_maps_to_actual_column() {
        // Adaptive 取整后每格真实宽度不同：col0 110px（x0），col1 90px（x110），col2 100px（x200）
        val infos = listOf(
            cell(0, CellType.MEDIA, 0, 0f, 0f, 110f),
            cell(1, CellType.MEDIA, 1, 0f, 110f, 90f),
            cell(2, CellType.MEDIA, 2, 0f, 200f, 100f),
        )
        // x=115 落在 col1 真实区间 [110,200)
        assertEquals(1, computeMediaIndex(visibleInfos = infos, touch = Offset(115f, 50f)))
        // x=210 落在 col2 真实区间 [200,300)
        assertEquals(2, computeMediaIndex(visibleInfos = infos, touch = Offset(210f, 50f)))
    }

    @Test
    fun empty_or_invalid_grid_returns_minus_one() {
        assertEquals(-1, computeMediaIndex(visibleInfos = emptyList(), touch = Offset(50f, 50f)))
    }
}
```

- [ ] **Step 2: 重写 `GridGeometry` 用真实几何 + 行二分**

重写 `GridGeometry.kt` 全文（字段序与测试的 `cell(...)` 辅助函数一致：`(index, type, col, span, offsetY, offsetX, sizePx)`）：

```kotlin
package com.smartvision.gallery.ui.gestures

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * 网格几何计算工具 — 把手指屏幕坐标映射到数据源中的 media 索引。
 *
 * 使用 `LazyGridState.layoutInfo.visibleItemsInfo` 的**真实布局几何**（每格的
 * 真实 offsetX/offsetY/size）做命中，不依赖理想 cellPx 公式——因为
 * `GridCells.Adaptive` 自适应取整后每格真实尺寸 ≠ 理想值，列数越多误差越大。
 *
 * Header 感知：数据源是扁平列表（[GridHeader] 占满整行、[GridMedia] 为一个数据项），
 * 滑动多选只操作 media。返回数据源的**绝对索引**（含 Header 占位）。
 */
object GridGeometry {

    /** 数据源中的 Header 哨兵项。列表项用它标识「日期头」。 */
    data class GridHeader(val title: String)

    /** 数据项哨兵（用于类型识别；实际业务类型由调用方持有）。 */
    data class GridMedia(val id: Long)

    enum class CellType { HEADER, MEDIA }

    /** 单个可见项的几何信息，从 `LazyGridState.layoutInfo` 提取。 */
    data class CellInfo(
        val index: Int,        // 数据源绝对索引
        val type: CellType,
        val col: Int,          // 所在列（直接来自 info.column）
        val span: Int,         // 占列数
        val offsetY: Float,    // 项在网格容器中的 Y 偏移（含 contentPadding），px
        val offsetX: Float,    // 项在网格容器中的 X 偏移，px
        val sizePx: Size,      // 项真实尺寸，px
    )

    /** 一行的几何范围（真实布局）：行起点列、真实 top/bottom、该行 media 候选 */
    private data class RowBounds(
        val top: Float,
        val bottom: Float,
        val rowItems: MutableList<CellInfo>,
    )

    /**
     * 计算手指位置对应的 media 数据索引。
     *
     * 行命中：按真实 offsetY + sizePx.height 区间划分，用二分定位手指所在行。
     * 列命中：遍历该行可见 media，判断 touch.x 落在 [offsetX, offsetX+sizePx.width)。
     *
     * @param visibleInfos 当前可见项几何（真实 offset/size，按 index 升序）
     * @param touch 手指相对网格原点的位置（与 item offset 同坐标系）
     * @return 手指所在的 Media 在数据源中的绝对索引；Header 上或无命中返回 -1
     */
    fun computeMediaIndex(
        visibleInfos: List<CellInfo>,
        touch: Offset,
    ): Int {
        if (visibleInfos.isEmpty()) return -1
        val y = touch.y
        val x = touch.x

        // 建行索引：col==0 项作为行起点；行含真实 top/bottom
        val rows = mutableListOf<RowBounds>()
        for (info in visibleInfos) {
            if (info.col == 0) {
                rows.add(RowBounds(info.offsetY, info.offsetY + info.sizePx.height, mutableListOf()))
            }
        }
        if (rows.isEmpty()) return -1

        // 按行区间归属，把 media 加入对应行候选
        for (info in visibleInfos) {
            if (info.type != CellType.MEDIA) continue
            for (row in rows) {
                if (info.offsetY >= row.top && info.offsetY < row.bottom) {
                    row.rowItems.add(info)
                    break
                }
            }
        }

        // 二分定位手指所在行（rows 按 top 升序）
        val rowIdx = rows.binarySearchBy(y) { it.top }
        val hitRow = when {
            rowIdx >= 0 -> rows[rowIdx]
            else -> {
                val insert = -rowIdx - 1
                rows.getOrNull(insert - 1)
            }
        } ?: return -1

        if (y < hitRow.top || y >= hitRow.bottom) return -1
        val rowItems = hitRow.rowItems
        if (rowItems.isEmpty()) return -1

        // 列命中：按真实 [offsetX, offsetX+width) 判断
        return rowItems.firstOrNull { x >= it.offsetX && x < it.offsetX + it.sizePx.width }?.index
            ?: rowItems.first().index
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugUnitTestKotlin
```
Expected: `BUILD SUCCESSFUL`（`DragSelectGesture` 尚引用旧签名，下一步 Task 3 修正；若失败可暂时跳过，等 Task 3 一起过）。

- [ ] **Step 4: 提交**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && git add app/src/main/java/com/smartvision/gallery/ui/gestures/GridGeometry.kt app/src/test/java/com/smartvision/gallery/ui/gestures/GridGeometryTest.kt && git commit -m "feat(grid): real-layout hit-test with row-index binary search"
```

---

### Task 3: DragSelectGesture — 适配新 GridGeometry 签名 + 坐标对齐

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/gestures/DragSelectGesture.kt`
- Test: `app/src/test/java/com/smartvision/gallery/ui/gestures/DragSelectGestureTest.kt`

- [ ] **Step 1: 更新 `indexAt` 传真实几何 + 坐标对齐**

`DragSelectGesture.kt` 的 `indexAt` 从理想 cellPx 改为传真实 `CellInfo`（含 `offsetX`/`sizePx`），签名对应 `computeMediaIndex(visibleInfos, touch)`，字段序与 Task 2 一致。替换 `indexAt` 及新增 `Size` import：

```kotlin
private fun indexAt(pos: Offset): Int =
    GridGeometry.computeMediaIndex(
        visibleInfos = gridState.layoutInfo.visibleItemsInfo.map { info ->
            GridGeometry.CellInfo(
                index = info.index,
                type = if (info.span == columns()) GridGeometry.CellType.HEADER else GridGeometry.CellType.MEDIA,
                col = info.column,
                span = info.span,
                offsetY = info.offset.y.toFloat(),
                offsetX = info.offset.x.toFloat(),
                sizePx = Size(
                    info.size.width.toFloat(),
                    info.size.height.toFloat(),
                ),
            )
        },
        touch = pos,
    )
```

> **注意**：文件顶部需补 `import androidx.compose.ui.geometry.Size`（现有 `Offset` 已 import）。`cellSizePx`/`spacingPx` 构造参数保留但内部不再使用（页面仍传它们，最小改动）。

删除 `DragSelectGesture` 的 `cellSizePx`/`spacingPx` 构造参数（不再需要）——但页面仍传它们。**决策：保留参数但内部不再使用**，或同步删页面调用。为最小改动，**保留 `cellSizePx`/`spacingPx` 参数（unused），只在 `indexAt` 换实现**；页面接线不动。

- [ ] **Step 2: 确认 DragSelectGestureTest 纯函数不变**

`DragSelectGestureTest.kt` 只测 `shouldSelect`/`autoScrollSpeed`，不涉及 `indexAt`，无需改动。读文件确认。

- [ ] **Step 3: 编译验证**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
```
Expected: `BUILD SUCCESSFUL`（GridGeometry 新签名与 DragSelect 新调用一致）。

- [ ] **Step 4: 提交**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && git add app/src/main/java/com/smartvision/gallery/ui/gestures/DragSelectGesture.kt && git commit -m "fix(grid): hit-test via real layout geometry in drag-select"
```

---

### Task 4: 页面接线 — 惰性列基准 + 保持选中

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/album/AlbumDetailPage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/search/SearchPage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/trash/TrashPage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/privacy/PrivacyVaultPage.kt`

- [ ] **Step 1: PinchToZoomGesture 构造改惰性列基准 + 去掉 clearSelection**

5 个页面的 `PinchToZoomGesture(defaultColumns = gridColumns, ...)` 改为 `defaultColumns = { gridColumns }`，且 `onColumnChange` 里去掉 `clearSelection`/`selectedIds = emptySet()`（保持选中态）：

```kotlin
// 以「…/ui/pages/TimelinePage.kt」为例，其余 4 页同构
val gesture = PinchToZoomGesture(
    defaultColumns = { gridColumns },
    onColumnChange = { newCols ->
        if (newCols != gridColumns) {
            gridColumns = newCols
            // 不再 clearSelection：缩放不清空已选
        }
    },
    onGestureEnd = { /* 弹簧吸附动画简化 */ },
)
```

对应 5 处调用点逐一替换。

- [ ] **Step 2: 编译验证**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && git add app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt app/src/main/java/com/smartvision/gallery/ui/album/AlbumDetailPage.kt app/src/main/java/com/smartvision/gallery/ui/search/SearchPage.kt app/src/main/java/com/smartvision/gallery/ui/trash/TrashPage.kt app/src/main/java/com/smartvision/gallery/ui/privacy/PrivacyVaultPage.kt && git commit -m "fix(pages): lazy column-basis for pinch-zoom, keep selection on zoom"
```

---

### Task 5: 全量编译 + 测试逻辑推演验证

**Files:**
- 无代码改动

- [ ] **Step 1: 全量编译**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin
```
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 2: 逻辑推演关键用例**

无自动化运行（预存 classpath 阻塞）。人工核对：
- `scaleToColumns(logScale=0, start=10) = 10` ✓
- `scaleToColumns(logScale=0.301, start=10, sens=0.5) = 10 - round(0.602) = 10 - 1 = 9` ✓
- `scaleToColumns(logScale=-1, start=3, sens=0.5) = 3 - round(-2) = 3 + 2 = 5` ✓
- `scaleToColumns(logScale=-14, start=3, sens=0.5) = 3 - round(-28) = 31 → clamp 30` ✓
- `GridGeometry` 真实几何：`touch(50,150)` 命中 row1(col0) 区间 [0,100)x[100,200) → index 1 ✓

- [ ] **Step 3: 提交（若有遗留改动）**

```bash
cd "H:\workspace-minimaxcode\新建文件夹\超级相册" && git status --short && git add -A && git commit -m "chore(grid): polish gesture plan complete" 2>/dev/null || echo "无遗留改动"
```

---

## 自审清单

**Spec 覆盖：**
- Bug 1/2/4（缩放方向、灵敏度、误触、30 列）→ Task 1 ✓
- Bug 3（锚点偏移）→ Task 2/3 ✓
- 页面接线（惰性基准 + 保持选中）→ Task 4 ✓
- R1 死区过滤 → Task 1（`|Δdist| > touchSlop`）✓
- R1 手势冲突隔离 → Task 1 双指 `consume` + DragSelect"双指=让位"互斥（已前置）✓
- R2 坐标系一致 → Task 3（touch 与 offset 同网格容器坐标，offsetY 用真实 info.offset）✓
- R2 行索引二分 → Task 2（`rows.binarySearchBy`）✓
- R3 优先级状态机 → DragSelect 已前置（多指>拖动>长按>单击）✓
- R3 惯性滚动 → `userScrollEnabled=false` + `dispatchRawDelta` 已前置 ✓

**占位符扫描：** 无 TBD/TODO。Task 2 Step 2 的 `GridGeometry` 已是完整实现（含 `offsetX` 字段、行二分、真实列区间判定），Task 3 只做字段序一致的调用。

**类型一致性：**
- `scaleToColumns(logScale, startColumns, min, max, sensitivity)` 在 Task 1 定义，Task 5 推演用同名参数 ✓
- `CellInfo(index, type, col, span, offsetY, offsetX, sizePx)` 在 Task 2 定义（测试辅助 `cell(...)` 同字段序），Task 3 的 `indexAt` 构造按同序传参 ✓
- `computeMediaIndex(visibleInfos, touch)` 新签名在 Task 2 定义，Task 3 调用 ✓
