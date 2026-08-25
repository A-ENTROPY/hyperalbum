package com.smartvision.gallery.cache

import android.graphics.Bitmap
import android.net.Uri

/**
 * Two-level thumbnail cache façade: memory first, disk second. Public surface that
 * [com.smartvision.gallery.decoder.MediaLoader] talks to.
 */
class ThumbnailCacheFacade(private val coordinator: CacheCoordinator) {

    fun get(uri: Uri, w: Int, h: Int): Bitmap? =
        coordinator.memoryCache.get(uri, w, h) ?: coordinator.thumbnailDiskCache.get(uri, w, h)?.also {
            coordinator.memoryCache.put(uri, w, h, it)
        }

    fun put(uri: Uri, w: Int, h: Int, bitmap: Bitmap) {
        coordinator.memoryCache.put(uri, w, h, bitmap)
        coordinator.thumbnailDiskCache.put(uri, w, h, bitmap)
    }
}