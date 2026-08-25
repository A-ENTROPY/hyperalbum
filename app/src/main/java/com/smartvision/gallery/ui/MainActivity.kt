package com.smartvision.gallery.ui

import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.ui.permission.PermissionState
import com.smartvision.gallery.ui.permission.PermissionViewModel
import com.smartvision.gallery.ui.theme.SmartVisionTheme
import com.smartvision.gallery.util.AppLog
import com.smartvision.gallery.util.PermissionHelper
import kotlinx.coroutines.launch

/**
 * Single-activity host. Extends [FragmentActivity] for BiometricPrompt.
 *
 * Sets FLAG_SHOW_WALLPAPER so the system wallpaper renders behind the
 * translucent window — the foundation for iOS 26 Liquid Glass.
 *
 * Permission flow: media permission requested on first launch (no
 * MANAGE_EXTERNAL_STORAGE). A [PermissionViewModel] owns state and re-derives
 * it on every onResume, so granting in system settings and returning updates
 * the UI without a remember{} snapshot race.
 */
class MainActivity : FragmentActivity() {

    private val permissionVm by viewModels<PermissionViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Cold-start gap: the window is transparent (FLAG_SHOW_WALLPAPER for
        // Liquid Glass), so between the splash screen disappearing and the
        // first Compose frame the system wallpaper would bleed through. Keep
        // the splash up until the first frame actually renders (onFirstFrame
        // fires from AppRoot/TimelinePage) — no transparent flash.
        val firstFrameDrawn = booleanArrayOf(false)
        splash.setKeepOnScreenCondition { !firstFrameDrawn[0] }

        setContent {
            val state by permissionVm.state.collectAsState()

            // Re-derive on every resume: grant-in-settings-then-return refreshes.
            val activity = this
            DisposableEffect(Unit) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        permissionVm.checkAndEmit(activity)
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }

            // Request media permission immediately on first launch (Loading =
            // fresh process, no decision yet). Skips once a decision exists.
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { result -> permissionVm.onPermissionResult(activity, result) }

            // Auto-prompt once per install. Not gated on `state is Loading`:
            // ON_RESUME's checkAndEmit can beat the Compose composition and
            // flip Loading → Denied before a LaunchedEffect keyed on state
            // runs, so LaunchedEffect(Denied) never fires → no dialog on a
            // fresh install. Persist a "prompt shown" flag so a user who
            // denied is not re-prompted every cold start (they land on the
            // rationale guide page instead, with a manual request button).
            LaunchedEffect(Unit) {
                val app = activity.appGraph
                if (!app.prefs.getPermissionPromptShown()) {
                    app.prefs.setPermissionPromptShown()
                    if (state !is PermissionState.Granted) {
                        permissionVm.markRequesting()
                        runCatching { permissionLauncher.launch(PermissionHelper.firstLaunchPermissions()) }
                            .onFailure { AppLog.w("MainActivity", "auto-permission launch failed", it) }
                    }
                }
            }

            SmartVisionTheme {
                AppRoot(
                    permissionState = state,
                    onRequestPermission = {
                        permissionVm.markRequesting()
                        runCatching { permissionLauncher.launch(PermissionHelper.firstLaunchPermissions()) }
                            .onFailure { AppLog.w("MainActivity", "permission launch failed", it) }
                    },
                    onOpenAppSettings = {
                        runCatching { startActivity(permissionVm.appSettingsIntent()) }
                            .onFailure { AppLog.w("MainActivity", "settings intent failed", it) }
                    },
                    onFirstFrame = { firstFrameDrawn[0] = true },
                )
            }
        }

        AppLog.d("MainActivity", "onCreate sdk=${Build.VERSION.SDK_INT}")
    }
}

/** Convenience accessor for the app graph from a Compose context. */
val FragmentActivity.appGraph: SmartVisionApp
    get() = SmartVisionApp.from(this)
