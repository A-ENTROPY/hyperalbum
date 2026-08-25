package com.smartvision.gallery.ui.gestures

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 双指缩放手势：连续缩放缩略图网格，列数 2~10 自适应。
 *
 * 使用**预定义列数快照**（2→3→5→7→10）而非连续值，大幅减少
 * 缩放时的网格重组次数和 Coil 图片重新加载。
 *
 * 弃用 detectTransformGestures 的原因：它把缩放和拖拽混在一起，且两指
 * 初始间距的处理有跳变。本实现用 awaitEachGesture 自行计算两指中心点
 * 距离的对数比例 log10(d1/d0)，缩放灵敏度在线性域内平滑。
 *
 * 状态机：
 *  - 单指：不触发缩放
 *  - 双指：计算对数比例，驱动 onColumnChange(target)
 *  - 手指抬起/取消：结束，状态复位（onGestureEnd）
 *
 * 语义对齐 Apple Photos 相册：
 *  - 双指捏合（距离缩小，log10 < 0）→ 列数增加，格子变小
 *  - 双指张开（距离放大，log10 > 0）→ 列数减少，格子变大
 */
class PinchToZoomGesture(
    private val defaultColumns: () -> Int,
    private val minColumns: Int = 2,
    private val maxColumns: Int = 10,
    private val sensitivity: Float = 0.4f,
    private val onColumnChange: (Int) -> Unit,
    private val onGestureEnd: () -> Unit,
    private val isSelectMode: () -> Boolean = { false },
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
                if (isSelectMode()) {
                    // 选择模式：完全让位，不消费任何事件（双指缩放由主手势接管，
                    // 不再从 modifier 链上条件移除，避免链结构变化重启手势）。
                    if (event.changes.all { !it.pressed }) break
                    continue
                }
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
                                // 吸附到最近快照列数 + 防抖：仅 target 变化时发射
                                val snapped = snapToNearest(target)
                                if (snapped != lastEmittedColumns) {
                                    lastEmittedColumns = snapped
                                    onColumnChange(snapped)
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
        }
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    companion object {
        /**
         * 预定义列数快照 — 缩放到这些值之一，减少重组次数。
         * 连续 2..10：用户报告 7/8 被跳过、9 吸附到 10（快照缺 6/8/9 导致
         * minByOrNull 把 7→10、8→10、9→10）。连续档位让每列都可到达，
         * 吸附只做四舍五入不做跳档。
         */
        val COLUMN_SNAPSHOTS = (2..10).toList()

        /** 将连续列数吸附到最近的快照值。 */
        fun snapToNearest(cols: Int): Int {
            return COLUMN_SNAPSHOTS.minByOrNull { kotlin.math.abs(it - cols) } ?: cols
        }

        /**
         * 纯逻辑：把对数缩放比例映射到列数。供单元测试使用。
         *
         * **比例模型**：`target = startColumns × 10^(-logScale / sensitivity)`。
         * 因为 `GridCells.Adaptive` 的 cellPx 反比于列数，列数跨步在对数尺度下
         * 指数放大——线性 `round(logScale/sensitivity)` 会把整个 3→30 范围压成
         * 仅约 2 步（物理不可达）。比例模型让"捏合到距离一半 → 列数翻倍"，
         * 捏合到初始 1/10 距离即可从 3 列到 30 列。
         *
         * @param logScale log10(d1/d0)，正数放大，负数缩小
         * @param startColumns 手势开始时的基准列数
         * @param minColumns / @param maxColumns 列数范围
         * @param sensitivity 每 sensitivity 的 log10 距离变化 → 列数 ×10
         */
        fun scaleToColumns(
            logScale: Float,
            startColumns: Int,
            minColumns: Int,
            maxColumns: Int,
            sensitivity: Float = 0.4f,
        ): Int {
            val ratio = 10f.pow(-logScale / sensitivity)
            return (startColumns * ratio).roundToInt().coerceIn(minColumns, maxColumns)
        }
    }
}