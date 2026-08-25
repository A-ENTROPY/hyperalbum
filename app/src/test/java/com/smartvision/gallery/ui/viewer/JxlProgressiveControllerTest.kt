package com.smartvision.gallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class JxlProgressiveControllerTest {

    @Test
    fun `computeStrideForTarget picks smallest 2^n that brings source below target`() {
        // 8K source (8192) → target 4096 → stride 2 (output 4096)
        assertEquals(2, JxlProgressiveController.computeStrideForTarget(8192L, 4096))
        // 4K source (4096) → target 4096 → stride 1 (full resolution)
        assertEquals(1, JxlProgressiveController.computeStrideForTarget(4096L, 4096))
        // 16K source (16384) → target 4096 → stride 4 (output 4096)
        assertEquals(4, JxlProgressiveController.computeStrideForTarget(16384L, 4096))
        // 2K source (2048) → target 4096 → stride 1 (output 2048, already under)
        assertEquals(1, JxlProgressiveController.computeStrideForTarget(2048L, 4096))
    }

    @Test
    fun `computeInitialTargetPx is roughly 1 eighth of source, floored at 256`() {
        assertEquals(1024, JxlProgressiveController.computeInitialTargetPx(8192L))
        assertEquals(512, JxlProgressiveController.computeInitialTargetPx(4096L))
        assertEquals(256, JxlProgressiveController.computeInitialTargetPx(256L))   // floor
        assertEquals(256, JxlProgressiveController.computeInitialTargetPx(128L))   // floor
    }

    @Test
    fun `shouldReload short-circuits when target maps to same stride as current`() {
        // 8K source, currently loaded at stride 2 (output 4096)
        val sourceLongEdge = 8192L
        val currentStride = 2
        // New zoom implies target 8000 — stride 2 still wins (not 1, would be 8K).
        assertEquals(false,
            JxlProgressiveController.shouldReload(sourceLongEdge, currentStride, targetLongEdgePx = 8000))
        // New zoom implies target 4096 — stride 2 wins, same.
        assertEquals(false,
            JxlProgressiveController.shouldReload(sourceLongEdge, currentStride, targetLongEdgePx = 4096))
        // New zoom implies target 1024 — stride 8 wins, different → reload.
        assertEquals(true,
            JxlProgressiveController.shouldReload(sourceLongEdge, currentStride, targetLongEdgePx = 1024))
    }
}