package com.smartvision.gallery.ui.pages

import androidx.lifecycle.SavedStateHandle
import com.smartvision.gallery.data.repo.MediaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineViewModelTest {

    private val repo = mockk<MediaRepository>(relaxed = true)

    private fun newViewModel(savedStateHandle: SavedStateHandle = SavedStateHandle()): TimelineViewModel {
        // observeTimeline() 在 sections 的 stateIn 求值时被调用；relaxed mock 返回
        // null Flow 会 NPE，这里显式给 emptyFlow()。
        every { repo.observeTimeline() } returns emptyFlow()
        return TimelineViewModel(repo, savedStateHandle = savedStateHandle)
    }

    @Test
    fun toggle_item_selection_adds_and_removes_id() {
        val vm = newViewModel()
        vm.toggleItemSelection(42L)
        assertEquals(setOf(42L), vm.selectedIds.value)
        vm.toggleItemSelection(42L)
        assertTrue(vm.selectedIds.value.isEmpty())
    }

    @Test
    fun selection_survives_process_death_via_saved_state() {
        val handle = SavedStateHandle(mapOf("selectedIds" to setOf(1L, 2L)))
        val vm = newViewModel(savedStateHandle = handle)
        assertEquals(setOf(1L, 2L), vm.selectedIds.value)
    }

    @Test
    fun clear_selection_clears_saved_state() {
        val handle = SavedStateHandle()
        val vm = newViewModel(savedStateHandle = handle)
        vm.setSelection(setOf(1L, 2L, 3L))
        assertEquals(setOf(1L, 2L, 3L), vm.selectedIds.value)
        assertEquals(setOf(1L, 2L, 3L), handle.get<Set<Long>>("selectedIds"))
        vm.clearSelection()
        assertTrue(vm.selectedIds.value.isEmpty())
    }
}
