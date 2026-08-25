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

        // 列命中：按真实 [offsetX, offsetX+width) 判断。
        // 手指落在列间 gap 或最后列右侧时回退到行内首个 media——有意的宽容行为
        // （对齐 Apple Photos：行内任意触摸都吸附到某格），仅行内生效。
        return rowItems.firstOrNull { x >= it.offsetX && x < it.offsetX + it.sizePx.width }?.index
            ?: rowItems.first().index
    }
}