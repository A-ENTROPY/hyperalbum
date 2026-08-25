package com.smartvision.gallery.ui.gestures

import com.smartvision.gallery.ui.gestures.PinchToZoomGesture.Companion.scaleToColumns
import org.junit.Test
import org.junit.Assert.assertEquals

class PinchToZoomGestureTest {

    @Test
    fun zero_scale_keeps_columns_at_start() {
        // 无缩放 → 保持基准
        assertEquals(10, scaleToColumns(logScale = 0f, startColumns = 10, minColumns = 2, maxColumns = 30, sensitivity = 0.4f))
    }

    @Test
    fun positive_log_decreases_columns() {
        // 双指张开（放大）→ log10(2/1)≈0.301，比例 10^(-0.301/0.4)=10^(-0.7525)≈0.177
        // 10×0.177=1.77 → round 2 列
        assertEquals(2, scaleToColumns(logScale = 0.301f, startColumns = 10, minColumns = 2, maxColumns = 30, sensitivity = 0.4f))
    }

    @Test
    fun negative_log_increases_columns() {
        // 双指捏合（缩小）→ 负 log → 列数增加
        // 10×10^(0.301/0.4)=10×10^0.7525≈10×5.65=56.5 → clamp 30
        assertEquals(30, scaleToColumns(logScale = -0.301f, startColumns = 10, minColumns = 2, maxColumns = 30, sensitivity = 0.4f))
    }

    @Test
    fun half_distance_doubles_columns() {
        // 捏合到初始一半距离：log10(0.5)≈-0.301，sensitivity=1.0 → ×10^0.301=×2
        assertEquals(20, scaleToColumns(logScale = -0.301f, startColumns = 10, minColumns = 2, maxColumns = 30, sensitivity = 1.0f))
    }

    @Test
    fun tenth_distance_reaches_30_from_3() {
        // 核心可达性：捏合到初始 1/10 距离（log10(0.1)=-1.0），sensitivity=0.4
        // → ×10^(1.0/0.4)=×10^2.5≈×316 → 3×316 从 3 列直达 30
        assertEquals(30, scaleToColumns(logScale = -1f, startColumns = 3, minColumns = 2, maxColumns = 30, sensitivity = 0.4f))
    }

    @Test
    fun scale_clamped_to_2_30() {
        assertEquals(30, scaleToColumns(logScale = -2f, startColumns = 3, minColumns = 2, maxColumns = 30, sensitivity = 0.4f))
        assertEquals(2, scaleToColumns(logScale = 2f, startColumns = 3, minColumns = 2, maxColumns = 30, sensitivity = 0.4f))
    }
}
