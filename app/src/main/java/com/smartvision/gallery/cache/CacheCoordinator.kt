package com.smartvision.gallery.cache

import android.content.Context
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Coordinator over the in-memory + disk caches. Hides cache wiring from the loader and
 * orchestrates background/foreground transitions.
 */
class CacheCoordinator(context: Context) {

    val memoryCache = BitmapMemoryCache()
    val thumbnailDiskCache = ThumbnailDiskCache(context)
    val thumbnailCache: ThumbnailCacheFacade = ThumbnailCacheFacade(this)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onAppForeground() {
        // Warm nothing explicitly — lazy load is fine; we just want to log if anything
        // weird happened while backgrounded.
        AppLog.d(TAG, "onAppForeground; memory entries: ${memoryCache.javaClass.simpleName}")
    }

    fun onAppBackground() {
        scope.launch {
            // Keep the disk cache but trim anything older than 30 days.
            thumbnailDiskCache.trim(THIRTY_DAYS_MS)
        }
    }

    fun clearAll() {
        memoryCache.clear()
        thumbnailDiskCache.clear()
        BitmapPool.clear()
    }

    companion object {
        private const val TAG = "CacheCoordinator"
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}

