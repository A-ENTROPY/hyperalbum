package com.smartvision.gallery.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.smartvision.gallery.data.model.Album
import com.smartvision.gallery.data.model.AlbumKind
import com.smartvision.gallery.data.repo.MediaRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for [AlbumsPage] — the standard Android gallery "Albums" tab.
 *
 * Filters the smart-albums stream down to bucket albums (device folders like
 * DCIM/Camera, Screenshots, Downloads). Sorted by recency so the most
 * recently used camera roll sits at the top.
 *
 * This is intentionally a separate ViewModel from [AlbumListViewModel] —
 * the old AlbumListPage is the iOS-style "精选" surface (curated content),
 * while this AlbumsPage is the standard Android gallery "Albums" surface
 * (device folders grid). They serve different purposes.
 */
class AlbumsViewModel(repository: MediaRepository) : ViewModel() {

    val albums: StateFlow<List<Album>> = repository.observeSmartAlbums()
        .map { list -> list.filter { it.kind == AlbumKind.BUCKET } }
        .map { list ->
            // Sort: most recently updated first. Camera Roll is almost always
            // the bucket with the newest media; this puts it at the top.
            list.sortedByDescending { it.latestDateMs }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    companion object {
        fun factory(repository: MediaRepository) = viewModelFactory {
            initializer { AlbumsViewModel(repository) }
        }
    }
}