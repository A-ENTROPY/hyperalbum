package com.smartvision.gallery.ui.viewer

import com.smartvision.gallery.data.model.MediaItem

data class PhotoViewerUiState(
    val items: List<MediaItem> = emptyList(),
    val initialIndex: Int = 0,
    val currentIndex: Int = 0,
)
