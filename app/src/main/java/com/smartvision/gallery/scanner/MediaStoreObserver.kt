package com.smartvision.gallery.scanner

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-lifetime MediaStore observer.
 *
 * Background: Liquid Gallery previously only scanned on cold start. Users who
 * added a new photo via the camera or another app had to kill+relaunch the
 * gallery to see it. Android fires content-change notifications on the
 * `MediaStore.Images` and `MediaStore.Video` URIs whenever something is
 * inserted/deleted/modified — we just have to subscribe.
 *
 * On any change, debounce into [MediaScanCoordinator.onMediaChanged] (which
 * already coalesces rapid scans). The observer is registered exactly once
 * from [com.smartvision.gallery.SmartVisionApp.onCreate] for the lifetime of
 * the process.
 *
 * `notifyForDescendants = true` means we get notified for every URI under the
 * collection (each `content://media/external/images/media/{id}`), which is
 * what we want — the change handler debounces anyway, so per-item noise is
 * filtered out.
 */
class MediaStoreObserver(
    private val context: Context,
    private val scanCoordinator: MediaScanCoordinator,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            AppLog.i(TAG, "MediaStore change: self=$selfChange uri=$uri")
            scope.launch { scanCoordinator.onMediaChanged() }
        }
    }

    fun register() {
        context.contentResolver.registerContentObserver(imagesUri(), true, observer)
        context.contentResolver.registerContentObserver(videosUri(), true, observer)
        AppLog.i(TAG, "MediaStore observer registered (images + videos)")
    }

    fun unregister() {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        AppLog.i(TAG, "MediaStore observer unregistered")
    }

    private fun imagesUri(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

    private fun videosUri(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private companion object {
        const val TAG = "MediaStoreObserver"
    }
}