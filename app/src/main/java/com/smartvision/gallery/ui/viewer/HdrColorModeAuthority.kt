package com.smartvision.gallery.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.delay

/**
 * Single source of truth for the viewer's [Activity.window.colorMode].
 *
 * Each Telephoto [me.saket.telephoto.subsamplingimage.SubSamplingImage] sets the window
 * color mode to HDR for its own image and restores the *previously observed* mode on
 * dispose. Inside a [androidx.compose.foundation.pager.HorizontalPager] adjacent pages are
 * composed/disposed while swiping, so when moving HDR→HDR the old page's restore can land
 * *after* the new page set HDR, leaving the window in SDR for a photo that wants HDR.
 *
 * Instead of trusting each image's capture-and-restore, this authority deterministically
 * sets the window color mode to HDR when the *current* photo is HDR and back to DEFAULT
 * otherwise. It is recomputed on every current-URI change, so it always converges to the
 * correct mode and cannot be clobbered by a stale per-image restore.
 *
 * The periodic reconciliation ([SELF_HEAL_INTERVAL_MS]) re-applies the desired mode so that
 * any restore performed by a telephoto effect during a fast HDR→HDR→SDR… HDR chain self-heals.
 */
@Composable
fun HdrColorModeAuthority(
    isCurrentPhotoHdr: Boolean,
    isSdkEligible: Boolean,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val activityState = rememberUpdatedState(activity)
    val hdrState = rememberUpdatedState(isCurrentPhotoHdr)
    val sdkState = rememberUpdatedState(isSdkEligible)

    if (activity == null || !isSdkEligible) return

    SideEffect {
        applyColorMode(activityState.value, hdrState.value)
    }

    // Self-heal: any telephoto per-image restore that races in after a page change
    // gets corrected on the next tick, so the mode always converges to the current photo.
    LaunchedEffect(activity, isCurrentPhotoHdr, isSdkEligible) {
        while (true) {
            delay(SELF_HEAL_INTERVAL_MS)
            applyColorMode(activityState.value, hdrState.value)
        }
    }

    DisposableEffect(activity) {
        onDispose {
            applyColorMode(activityState.value, desired = false)
        }
    }
}

/** Deterministically set the window color mode. Restores DEFAULT when the photo isn't HDR. */
private fun applyColorMode(activity: Activity?, desired: Boolean) {
    val target = activity ?: return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val mode = if (desired) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
    if (target.window.colorMode != mode) {
        AppLog.i(TAG, "colorMode ${target.window.colorMode} -> $mode (wantHdr=$desired)")
        target.window.colorMode = mode
    }
}

/** Unwrap [LocalContext] (usually a [android.view.ContextThemeWrapper]) back to the Activity. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** True when the platform can display Ultra HDR gainmap content. */
fun isHdrDisplayEligible(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

private const val SELF_HEAL_INTERVAL_MS = 500L
private const val TAG = "HdrColorMode"
