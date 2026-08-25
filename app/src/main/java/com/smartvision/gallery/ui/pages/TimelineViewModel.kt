package com.smartvision.gallery.ui.pages

import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.data.repo.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

@Stable
data class TimelineSection(
    val header: String,
    val bucketStartMs: Long,
    val items: List<MediaItem>
)

class TimelineViewModel(
    private val repository: MediaRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    val sections: StateFlow<List<TimelineSection>> =
        repository.observeTimeline().map { items -> bucketByDay(items) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _displayOptions = MutableStateFlow(DisplayOptions())
    val displayOptions: StateFlow<DisplayOptions> = _displayOptions

    // ---- 选择状态：SavedStateHandle 托管，进程死亡后 ID 仍有效 ----
    private val _selectedIds = MutableStateFlow(
        savedStateHandle.get<Set<Long>>(KEY_SELECTED_IDS) ?: emptySet()
    )
    val selectedIds: StateFlow<Set<Long>> = _selectedIds

    fun toggleItemSelection(id: Long) {
        val next = if (id in _selectedIds.value) _selectedIds.value - id
            else _selectedIds.value + id
        _selectedIds.value = next
        savedStateHandle[KEY_SELECTED_IDS] = next
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
        savedStateHandle[KEY_SELECTED_IDS] = emptySet<Long>()
    }

    fun setSelection(ids: Set<Long>) {
        _selectedIds.value = ids
        savedStateHandle[KEY_SELECTED_IDS] = ids
    }

    fun setQuery(q: String) { _query.value = q }
    fun toggleGridSize() {
        _displayOptions.value = _displayOptions.value.copy(
            gridColumns = if (_displayOptions.value.gridColumns == 3) 4 else 3
        )
    }

    fun toggleFavorite(item: MediaItem) {
        viewModelScope.launch { repository.setFavorite(item.uri, !item.isFavorite) }
    }

    fun moveToTrash(item: MediaItem) {
        viewModelScope.launch { repository.setTrash(item.uri, true) }
    }

    private fun bucketByDay(items: List<MediaItem>): List<TimelineSection> {
        if (items.isEmpty()) return emptyList()
        val cal = Calendar.getInstance()
        val groups = items.groupBy {
            cal.timeInMillis = it.dateTakenMs
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }.toSortedMap(reverseOrder())
        return groups.map { (startMs, list) ->
            TimelineSection(
                header = headerFor(startMs),
                bucketStartMs = startMs,
                items = list
            )
        }
    }

    private fun headerFor(dayStartMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dayStartMs }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val diffDays = ((today.timeInMillis - dayStartMs) / DAY_MS).toInt()
        return when (diffDays) {
            0 -> "今天"
            1 -> "昨天"
            in 2..6 -> "${diffDays} 天前"
            else -> com.smartvision.gallery.util.DateFormatters.dayHeader(dayStartMs)
        }
    }

    private companion object {
        const val KEY_SELECTED_IDS = "selectedIds"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

data class DisplayOptions(
    val gridColumns: Int = 3,
    val showFormatBadges: Boolean = true
)

@Suppress("UNCHECKED_CAST")
class TimelineViewModelFactory(private val repository: MediaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TimelineViewModel(repository) as T
}