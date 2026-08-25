package com.smartvision.gallery.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed

/**
 * 网格手势统一处理器：点击打开 / 长按进多选 / 多选下滑动快选。
 *
 * **常驻（Resident）**：手势始终挂在网格上（item 自身不再挂任何 click 修饰符），
 * 因为 item 的 clickable/combinedClickable 会消费 pointer 事件，导致网格级手势
 * 收不到 move，滑动快选永远失效。
 *
 * ## 交互模型（对齐 OPPO ColorOS / 小米 MIUI / Google Photos）
 *
 * - **非多选模式**：按下等长按。长按命中 → [onEnterSelectMode] 进入多选 +
 *   [onToggleItem] 选中按下项 → 无缝进入拖拽段；手指提前抬起 → 点击（[onTapItem]）。
 * - **多选模式**：直接等拖拽或点击。
 *
 * ## 方向基准（Direction Basis）
 *
 * 对标系统相册：按下时记录起始 Item 的选中状态，本次拖拽全程以此为准。
 * - 起点**未选中** → SELECT 模式：往下划动则选中经过项。
 * - 起点**已选中** → DESELECT 模式：往下划动则取消经过项。
 *
 * 范围语义：拖拽范围 = 起始项..当前命中项。**回缩取消**是范围模型的自然结果
 * （范围缩小时被移出范围的项自动解除选中），天然符合"外扩选中/回缩取消"。
 *
 * ## 自动滚动持续命中（关键）
 *
 * 快选的本体：手指压住屏幕上下边缘不动，列表持续滚动，途经的新项必须被选中。
 * 实现分工：
 *  - 本类：[onDragHit] 记录当前手指位置 [lastDragPosition] 与拖拽上下文，
 *    并在手指 move 时立即命中推进选择范围（主路径）。
 *  - 页面 `LaunchedEffect`：观察 [autoScrollSpeed]（非零时）做 `scrollBy` 滚动，
 *    每 tick 调 [onAutoScrollTick] 用当前手指位置重新命中，滑入的新项被选中。
 *
 * 两条路径共用 [advanceRange] 推进逻辑，互不冲突。
 */
class DragSelectGesture(
    private val gridState: LazyGridState,
    private val isItemSelected: (Int) -> Boolean,
    private val onToggleItem: (Int) -> Unit,
    private val onRangeSelect: (prevRange: IntRange, currRange: IntRange, selectPass: Boolean) -> Unit,
    private val autoScrollSpeed: MutableState<Float>,
    private val isSelectMode: () -> Boolean,
    private val onEnterSelectMode: () -> Unit,
    private val onTapItem: (Int) -> Unit,
    private val longPressTimeoutMillis: Long = 500L,
) {
    // ── 拖拽会话状态（供页面 autoScroll 循环读取/推进） ──
    private var active = false
    private var initialIndex = -1
    private var selectPass = true
    private var lastHitIndex = -1
    private var lastDragPosition = Offset.Unspecified

    /** 手指当前位置（px，viewport 局部坐标）。供页面 autoScroll 循环命中。 */
    fun currentDragPosition(): Offset = lastDragPosition

    /** 拖拽范围当前推进到的索引（含 header 的绝对索引），无拖拽时 -1。 */
    fun currentHitIndex(): Int = lastHitIndex

    /** 页面 autoScroll 循环每 tick 调用：滚动后重新命中并推进选择范围。 */
    fun onAutoScrollTick() {
        val pos = lastDragPosition
        if (!active || pos == Offset.Unspecified) return
        val idx = indexAt(pos)
        if (idx >= 0 && idx != lastHitIndex) {
            advanceRange(idx)
        }
    }

    suspend fun PointerInputScope.attach() {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            resetSession()

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
                    beginDrag(startIndex, selectPass)
                    dragSelect(down.id)
                }
                Decision.Drag -> {
                    if (isSelectMode()) {
                        beginDrag(startIndex, selectPass)
                        dragSelect(down.id)
                    }
                }
                Decision.Cancelled -> Unit
            }
            resetSession()
        }
    }

    private fun resetSession() {
        active = false
        initialIndex = -1
        selectPass = true
        lastHitIndex = -1
        lastDragPosition = Offset.Unspecified
    }

    private fun beginDrag(startIndex: Int, selectPass: Boolean) {
        active = true
        initialIndex = startIndex
        this.selectPass = selectPass
        lastHitIndex = startIndex
    }

    private suspend fun AwaitPointerEventScope.dragSelect(
        pointerId: PointerId,
    ) {
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
                if (!change.pressed) break
                if (event.changes.count { it.pressed } >= 2) break // 第二根手指 → 让位缩放
                if (change.isConsumed) break

                lastDragPosition = change.position

                // 更新自动滚动速度：手指进入上下边缘 → 页面 LaunchedEffect 开始 scrollBy。
                autoScrollSpeed.value = DragSelectGesture.autoScrollSpeed(
                    posY = lastDragPosition.y,
                    viewportHeightPx = gridState.layoutInfo.viewportSize.height.toFloat(),
                    edgePx = EDGE_PX,
                    maxSpeedPx = MAX_SPEED_PX,
                )

                // 手指 move → 立即命中推进（主路径）
                val idx = indexAt(change.position)
                if (idx >= 0 && idx != lastHitIndex) {
                    advanceRange(idx)
                }
                change.consume()
            }
        } finally {
            autoScrollSpeed.value = 0f
        }
    }

    /** 范围推进统一入口：从 initial 到 target 的绝对范围，prev→curr 增量交给 onRangeSelect。 */
    private fun advanceRange(targetIndex: Int) {
        val prev = if (initialIndex <= lastHitIndex) initialIndex..lastHitIndex
            else lastHitIndex..initialIndex
        val curr = if (initialIndex <= targetIndex) initialIndex..targetIndex
            else targetIndex..initialIndex
        onRangeSelect(prev, curr, selectPass)
        lastHitIndex = targetIndex
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

    /** 命中测试：坐标对齐容器坐标系（viewportStartOffset 修正）后查询可见项。 */
    fun indexAt(pos: Offset): Int {
        val adjustedPos = pos + Offset(0f, gridState.layoutInfo.viewportStartOffset.toFloat())
        return GridGeometry.computeMediaIndex(
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
            touch = adjustedPos,
        )
    }

    private enum class Decision { Tap, LongPress, Drag, Cancelled }

    companion object {
        /** 最大自动滚动速度（px/帧，约 4000px/s @60fps）。 */
        const val MAX_SPEED_PX = 4000f

        /** 触发自动滚动的边缘阈值（px），约 80dp @2.75 density。 */
        const val EDGE_PX = 220f

        /**
         * 拖拽方向基准：判断本次拖拽是 SELECT 还是 DESELECT 模式。
         *
         * @param startWasSelected 手指按下时起始 Item 是否已选中。
         * @return `true` = SELECT 模式（拖拽划过即选中），`false` = DESELECT 模式（拖拽划过即取消选中）。
         */
        fun shouldSelect(startWasSelected: Boolean): Boolean = !startWasSelected

        /**
         * 计算自动滚动速度（纯函数）。
         *
         * 三段式加速：
         * - 距边缘 > [edgePx] → 不滚动（0）
         * - 距边缘 ≤ [edgePx] → 速度与距离成反比，线性递增至 [maxSpeedPx]
         * - 距边缘 ≤ [edgePx] / 2 → 全速 [maxSpeedPx]
         * - 结果钳制在 ±[maxSpeedPx] 内
         */
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
                // 上边缘附近 → 向上滚动（负数）
                topDistance >= 0f && topDistance < edgePx -> {
                    val raw = if (topDistance <= halfEdge) maxSpeedPx
                    else maxSpeedPx * (edgePx - topDistance) / edgePx
                    -raw
                }
                // 下边缘附近 → 向下滚动（正数）
                bottomDistance >= 0f && bottomDistance < edgePx -> {
                    if (bottomDistance <= halfEdge) maxSpeedPx
                    else maxSpeedPx * (edgePx - bottomDistance) / edgePx
                }
                else -> 0f
            }.coerceIn(-maxSpeedPx, maxSpeedPx)
        }

        /**
         * 范围选择纯函数：撤销前一次范围，应用新范围。
         *
         * @param currentSelected 当前已选中的 Item ID 集合。
         * @param prevRange 上一次拖拽经过的范围（需要撤销）。
         * @param currRange 本次拖拽到达的新范围。
         * @param selectPass `true` = SELECT 模式（拖拽划过即选中），`false` = DESELECT 模式（拖拽划过即取消选中）。
         * @return 更新后的选中集合。
         */
        fun selectRange(
            currentSelected: Set<Long>,
            prevRange: IntRange,
            currRange: IntRange,
            selectPass: Boolean,
        ): Set<Long> {
            val newSelection = currentSelected.toMutableSet()
            // Undo previous range (mode-aware: SELECT removed what we added; DESELECT restored what we removed)
            for (i in prevRange) {
                if (selectPass) newSelection.remove(i.toLong())
                else newSelection.add(i.toLong())
            }
            // Apply current range
            for (i in currRange) {
                if (selectPass) newSelection.add(i.toLong())
                else newSelection.remove(i.toLong())
            }
            return newSelection
        }
    }
}
