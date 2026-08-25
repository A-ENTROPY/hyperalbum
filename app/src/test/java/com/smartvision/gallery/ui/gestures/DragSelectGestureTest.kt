package com.smartvision.gallery.ui.gestures

import com.smartvision.gallery.ui.gestures.DragSelectGesture.Companion.autoScrollSpeed
import com.smartvision.gallery.ui.gestures.DragSelectGesture.Companion.selectRange
import com.smartvision.gallery.ui.gestures.DragSelectGesture.Companion.shouldSelect
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class DragSelectGestureTest {

    // ─── shouldSelect: direction-basis ───────────────────────────────────────

    @Test
    fun starting_unselected_drag_is_select_pass() {
        // 起点未选中 → SELECT 模式：shouldSelect = true
        assertTrue(shouldSelect(startWasSelected = false))
    }

    @Test
    fun starting_selected_drag_is_deselect_pass() {
        // 起点已选中 → DESELECT 模式：shouldSelect = false
        assertFalse(shouldSelect(startWasSelected = true))
    }

    // ─── autoScrollSpeed: 3-tier acceleration ────────────────────────────────

    @Test
    fun mid_screen_no_scroll() {
        // 视口高 1000px，手指在中间（500px），距上下边缘均 > 110px → 不滚动
        assertEquals(0f, autoScrollSpeed(500f, 1000f, 110f, 1200f), 0.001f)
        assertEquals(0f, autoScrollSpeed(200f, 1000f, 110f, 1200f), 0.001f)
        assertEquals(0f, autoScrollSpeed(800f, 1000f, 110f, 1200f), 0.001f)
    }

    @Test
    fun top_edge_full_speed() {
        // 顶部边缘内（≤ 55px）→ 全速向上滚动（负数）
        assertEquals(-1200f, autoScrollSpeed(0f, 1000f, 110f, 1200f), 0.001f)
        assertEquals(-1200f, autoScrollSpeed(40f, 1000f, 110f, 1200f), 0.001f)
        assertEquals(-1200f, autoScrollSpeed(55f, 1000f, 110f, 1200f), 0.001f)
    }

    @Test
    fun top_edge_partial_speed() {
        // 顶部边缘内（55 ~ 110px）→ 线性减速（负数，向上滚动）
        // 距离 edge 还有 27.5px → -1200 * (27.5 / 110) = -300
        assertEquals(-300f, autoScrollSpeed(82.5f, 1000f, 110f, 1200f), 0.001f)
        // 距离 edge 还有 5.5px → -1200 * (5.5 / 110) = -60
        assertEquals(-60f, autoScrollSpeed(104.5f, 1000f, 110f, 1200f), 0.001f)
    }

    @Test
    fun bottom_edge_full_speed() {
        // 底部边缘内（≤ 55px）→ 全速向下滚动（正数）
        assertEquals(1200f, autoScrollSpeed(1000f, 1000f, 110f, 1200f), 0.001f)
        assertEquals(1200f, autoScrollSpeed(960f, 1000f, 110f, 1200f), 0.001f)
        assertEquals(1200f, autoScrollSpeed(945f, 1000f, 110f, 1200f), 0.001f)
    }

    @Test
    fun bottom_edge_partial_speed() {
        // 底部边缘内（55 ~ 110px）→ 线性减速（正数，向下滚动）
        assertEquals(300f, autoScrollSpeed(917.5f, 1000f, 110f, 1200f), 0.001f)
        assertEquals(60f, autoScrollSpeed(895.5f, 1000f, 110f, 1200f), 0.001f)
    }

    @Test
    fun speed_clamped_at_max() {
        // 即使计算值超过 maxSpeedPx，也钳制在 ±maxSpeedPx
        assertEquals(-1200f, autoScrollSpeed(0f, 1000f, 110f, 1200f), 0.001f)
        assertEquals(1200f, autoScrollSpeed(1000f, 1000f, 110f, 1200f), 0.001f)
    }

    @Test
    fun zero_or_negative_edge_or_max_returns_zero() {
        assertEquals(0f, autoScrollSpeed(0f, 1000f, 0f, 1200f), 0.001f)
        assertEquals(0f, autoScrollSpeed(0f, 1000f, -1f, 1200f), 0.001f)
        assertEquals(0f, autoScrollSpeed(0f, 1000f, 110f, 0f), 0.001f)
        assertEquals(0f, autoScrollSpeed(0f, 1000f, 110f, -1f), 0.001f)
    }
}

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
            prevRange = 2..2,
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