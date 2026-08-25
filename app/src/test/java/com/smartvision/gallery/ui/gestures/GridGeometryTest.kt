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

/** 3 列布局，每格真实 100x100，spacing=0 */
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
            touch = Offset(50f, 150f),
        )
        assertEquals(1, index)
    }

    @Test
    fun finger_on_second_media_row_returns_flat_index_7() {
        val index = computeMediaIndex(
            visibleInfos = visibleInfos(),
            touch = Offset(250f, 350f),
        )
        assertEquals(7, index)
    }

    @Test
    fun touch_on_header_row_returns_minus_one() {
        val index = computeMediaIndex(
            visibleInfos = visibleInfos(),
            touch = Offset(150f, 250f),
        )
        assertEquals(-1, index)
    }

    @Test
    fun adaptive_uneven_cell_width_maps_to_actual_column() {
        val infos = listOf(
            cell(0, CellType.MEDIA, 0, 0f, 0f, 110f),
            cell(1, CellType.MEDIA, 1, 0f, 110f, 90f),
            cell(2, CellType.MEDIA, 2, 0f, 200f, 100f),
        )
        assertEquals(1, computeMediaIndex(visibleInfos = infos, touch = Offset(115f, 50f)))
        assertEquals(2, computeMediaIndex(visibleInfos = infos, touch = Offset(210f, 50f)))
    }

    @Test
    fun empty_or_invalid_grid_returns_minus_one() {
        assertEquals(-1, computeMediaIndex(visibleInfos = emptyList(), touch = Offset(50f, 50f)))
    }
}