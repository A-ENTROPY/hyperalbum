package com.smartvision.gallery.livephoto

import android.media.MediaPlayer
import android.net.Uri
import android.view.View
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable that wraps Android's built-in `VideoView` to play a Live Photo's
 * motion clip. Designed to be controlled externally: callers hold a [Boolean]
 * state for "is live playing" and pass it via [playing]. The component
 * automatically mutes, hides controls, scales to fill, and loops the clip so
 * press-and-hold feels seamless.
 *
 *  * [playing] = true  → start playback from the beginning
 *  * [playing] = false → seek to frame 1 (the static-equivalent image)
 *
 * We use the legacy `VideoView` instead of pulling in ExoPlayer because Live
 * Photo clips are tiny (1–3 s) and the platform decoder handles them fine.
 */
@Composable
fun LivePhotoVideoPlayer(
    uri: Uri,
    playing: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoView = remember { VideoView(context) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { videoView.stopPlayback() }
        }
    }

    LaunchedEffect(uri, playing) {
        if (playing) {
            videoView.setVideoURI(uri)
            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                mp.setVolume(0f, 0f) // Live Photos play muted in iOS too.
                mp.start()
            }
            videoView.setOnErrorListener { _, what, extra ->
                android.util.Log.w("LivePhoto", "VideoView error what=$what extra=$extra")
                true
            }
        } else {
            // Reset to frame 1 (the "still" image equivalent). VideoView does this when paused
            // at position 0.
            runCatching {
                videoView.seekTo(0)
                videoView.pause()
            }
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setOnPreparedListener { mp ->
                        mp.isLooping = true
                        mp.setVolume(0f, 0f)
                    }
                }.also { videoView ->
                    videoView.setVideoURI(uri)
                }
            }
        )
    }
}