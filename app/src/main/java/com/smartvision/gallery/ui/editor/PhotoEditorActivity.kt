package com.smartvision.gallery.ui.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.ui.glass.GlassConfigViewModel
import com.smartvision.gallery.ui.liquidglass.LiquidGlassTheme
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.ui.theme.SmartVisionTheme

/**
 * Standalone photo editor Activity — wraps [PhotoEditorPage] so the
 * Intent-launched [com.smartvision.gallery.ui.viewer.PhotoViewerActivity]
 * can hop into the editor without going through the nav graph (which the
 * standalone viewer intentionally bypasses to dodge the ColorOS 16
 * RenderEffect stack overflow).
 *
 * Mirrors [com.smartvision.gallery.ui.viewer.PhotoViewerActivity]'s
 * `LocalGlassConfig` provisioning so the editor's Liquid Glass surfaces
 * also respond to the playground sliders.
 */
class PhotoEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriStr = intent.getStringExtra(EXTRA_URI) ?: run { finish(); return }
        val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: run { finish(); return }

        setContent {
            SmartVisionTheme {
                val app = applicationContext as SmartVisionApp
                val glassVm = remember { GlassConfigViewModel(app.glassConfigRepository) }
                val glassConfig by glassVm.config.collectAsState()
                CompositionLocalProvider(LocalGlassConfig provides glassConfig) {
                    LiquidGlassTheme {
                        PhotoEditorPage(
                            uri = uri,
                            onBack = { finish() },
                            onSave = { savedUri ->
                                // Notify the viewer that the photo was edited so it
                                // can re-render the page. We just return OK and let
                                // the viewer re-query on resume.
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_SAVED_URI, savedUri.toString()))
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_SAVED_URI = "extra_saved_uri"

        fun launchIntent(context: Context, uri: Uri): Intent =
            Intent(context, PhotoEditorActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
