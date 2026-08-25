package com.smartvision.gallery.ui.viewer

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.repo.MediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PhotoViewerViewModel(
    private val repository: MediaRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialIndex: Int =
        savedStateHandle.get<Int>("initialIndex") ?: 0

    private val indexFlow = MutableStateFlow(initialIndex)

    val uiState: StateFlow<PhotoViewerUiState> = combine(
        repository.observeTimeline(),
        indexFlow
    ) { items, idx ->
        PhotoViewerUiState(
            items = items,
            initialIndex = initialIndex,
            currentIndex = idx.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PhotoViewerUiState(initialIndex = initialIndex),
    )

    fun next() { indexFlow.update { it + 1 } }
    fun prev() { indexFlow.update { it - 1 } }
    fun setIndex(i: Int) { indexFlow.value = i }

    fun delete(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val current = uiState.value.items.getOrNull(uiState.value.currentIndex)
                ?: return@launch
            repository.setTrash(current.uri, true)
            onSuccess()
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val savedStateHandle = createSavedStateHandle()
                val app = this[androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as SmartVisionApp
                PhotoViewerViewModel(
                    repository = app.mediaRepository,
                    savedStateHandle = savedStateHandle,
                )
            }
        }
    }
}
