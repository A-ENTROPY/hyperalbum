package com.smartvision.gallery.ui.permission

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.util.AppLog
import com.smartvision.gallery.util.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for media-permission state. Replaces the old hand-
 * rolled 0/1/2 int + remember{} snapshots that reset on rotation and never
 * refreshed after the user returned from system settings.
 *
 * The launcher itself stays in the Composable (ActivityResultLauncher must be
 * created in a Composable scope), but all state derivation lives here so it
 * survives config changes and is re-derivable from [checkAndEmit] on every
 * onResume — fixing the "grant in settings → return → still shows guide" bug.
 */
sealed interface PermissionState {
    data object Loading : PermissionState
    data object Granted : PermissionState
    data class Denied(val canRequest: Boolean, val permanently: Boolean) : PermissionState
}

class PermissionViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<PermissionState>(PermissionState.Loading)
    val state: StateFlow<PermissionState> = _state.asStateFlow()

    /** Re-read system state. Call from onResume (and after any result). */
    fun checkAndEmit() {
        val ctx = getApplication<Application>()
        val next = if (PermissionHelper.hasStoragePermission(ctx)) {
            PermissionState.Granted
        } else {
            // No Activity here — rationale/permanent detection needs an Activity,
            // so we only know "not granted". The Activity-bound checkAndEmit
            // overload below refines canRequest/permanently. Default to
            // canRequest=true so the UI offers a request button.
            PermissionState.Denied(canRequest = true, permanently = false)
        }
        emitState(next)
    }

    /** Refine Denied state using the live Activity for rationale/permanent flags. */
    fun checkAndEmit(activity: android.app.Activity) {
        val ctx = getApplication<Application>()
        val next = if (PermissionHelper.hasStoragePermission(ctx)) {
            PermissionState.Granted
        } else {
            val permanently = PermissionHelper.isPermanentlyDenied(activity)
            val canRequest = !permanently
            AppLog.d(TAG, "checkAndEmit: canRequest=$canRequest permanently=$permanently")
            PermissionState.Denied(canRequest = canRequest, permanently = permanently)
        }
        emitState(next)
    }

    /**
     * Publish [next] and, on the Denied/Loading → Granted transition, re-kick the
     * media scan. Root cause of "permission granted but gallery empty forever":
     * the scan that ran at process start (SmartVisionApp.onCreate) fired BEFORE
     * the user answered the dialog, so queryAll() returned 0 items and the
     * coordinator skipped DB population. Nothing re-triggered a scan afterwards.
     * [com.smartvision.gallery.scanner.MediaScanCoordinator.scheduleIncrementalScan]
     * now skips+defers when permission is missing (no watermark write), so this
     * call on grant produces the full first scan instead of a poisoned delta.
     */
    private fun emitState(next: PermissionState) {
        val prev = _state.value
        _state.value = next
        if (next is PermissionState.Granted && prev !is PermissionState.Granted) {
            AppLog.i(TAG, "Permission transitioned to Granted — triggering media scan")
            runCatching {
                getApplication<SmartVisionApp>().scanCoordinator.scheduleIncrementalScan()
            }.onFailure { AppLog.w(TAG, "rescan after grant failed", it) }
        }
    }

    /** Call BEFORE launching the system dialog (after a rationale tap). */
    fun markRequesting() { _state.value = PermissionState.Loading }

    /** Consume the RequestMultiplePermissionsResult map. */
    fun onPermissionResult(activity: android.app.Activity, result: Map<String, Boolean>) {
        AppLog.d(TAG, "onPermissionResult: $result")
        checkAndEmit(activity) // system grant state is the source of truth
    }

    /** Intent to open this app's system settings page (for permanently-denied). */
    fun appSettingsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", getApplication<Application>().packageName, null)
        }

    private companion object {
        private const val TAG = "PermissionViewModel"
    }
}
