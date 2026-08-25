package com.smartvision.gallery.ui.viewer

import android.net.Uri
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

/** 视频播放状态快照 — 由 [VideoPlayerViewer] 通过 onStateChanged 上抛到 Activity。 */
data class VideoPlaybackState(
    val isPlaying: Boolean = false,
    val isMuted: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * iOS 风格嵌入式视频播放器，支持双击放大、双指缩放、平移拖动。
 *
 * 与照片页 ([ZoomableImage]) 完全对齐的 telephoto zoomable 行为：
 * - 单击 → [onSingleTap]（上层 toggle chrome）
 * - 双击 → 1× ↔ 2.5× ↔ 4× 循环切换
 * - 双指缩放、平移由 telephoto Modifier.zoomable 接管，maxZoomFactor=6
 * - HorizontalPager 翻页时每个 uri 各自 key，保留独立 zoomable state
 *
 * 视频渲染用 TextureView（而非 PlayerView 默认的 SurfaceView），因为：
 * - TextureView 走 View 的 hardware-accelerated 通路，能正确响应 Compose
 *   graphicsLayer 的 scale/translation 变换；SurfaceView 在独立 window
 *   渲染会绕过 Compose 合成，缩放变换无法作用。
 * - **关键**：TextureView 自身不做 aspect-preserving 变换，默认把视频拉伸填满
 *   整个 TextureView 边界。这正是用户反馈的"视频拉伸"根因。修复方式是把
 *   TextureView 包到 [AspectRatioFrameLayout]（`RESIZE_MODE_FIT`）里，让
 *   AspectRatioFrameLayout 负责按视频原始宽高比 letterbox 视频，并随 zoom
 *   一起被 graphicsLayer 缩放。
 *
 * 控制条由 [PhotoViewerActivity] 的 [VideoControlBar] 渲染。
 *
 * **OOM / 掉帧修复：**
 * - [isCurrentPage] 控制：非当前页自动 stop + clearMediaItems 释放解码器内存，
 *   切回时重新 prepare，避免 HorizontalPager 预合成导致多个 ExoPlayer 同时存活
 * - 使用 ExoPlayer 默认 LoadControl（有 size-based 硬上限，防 OOM）
 */
@OptIn(ExperimentalTelephotoApi::class)
@UnstableApi
@Composable
fun VideoPlayerViewer(
    uri: Uri,
    isCurrentPage: Boolean,
    onSingleTap: () -> Unit,
    chromeVisible: Boolean,
    onPlayerReady: (ExoPlayer) -> Unit,
    onStateChanged: (VideoPlaybackState) -> Unit,
    modifier: Modifier = Modifier,
    initialMuted: Boolean = true,
    aspectRatio: Float = 16f / 9f,
) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var centerButtonVisible by remember { mutableStateOf(true) }
    // 视频真实解码后的宽高比 — 优先于 MediaItem.aspectRatio（MediaStore 元数据可能过时）。
    // null 表示尚未拿到真实值，使用 MediaItem.aspectRatio 作为初始。
    var realVideoAspect by remember { mutableFloatStateOf(-1f) }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (initialMuted) 0f else 1f
                playWhenReady = true
            }
    }

    // 当前页状态管理：非当前页释放解码器内存，避免 OOM
    LaunchedEffect(uri, isCurrentPage) {
        if (isCurrentPage) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                // 曾被释放过，重新 prepare
                val mediaItem = MediaItem.Builder().setUri(uri).build()
                exoPlayer.setMediaItem(mediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
            onPlayerReady(exoPlayer)
        } else {
            // 非当前页：停止播放 + 释放解码器，仅保留 ExoPlayer 对象
            if (exoPlayer.playbackState != Player.STATE_IDLE) {
                isPlaying = false
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    DisposableEffect(uri) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val duration = exoPlayer.duration.coerceAtLeast(0L)
                    onStateChanged(
                        VideoPlaybackState(
                            isPlaying = isPlaying,
                            isMuted = exoPlayer.volume == 0f,
                            positionMs = exoPlayer.currentPosition,
                            durationMs = duration,
                        )
                    )
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                centerButtonVisible = !playing && !chromeVisible
                onStateChanged(
                    VideoPlaybackState(
                        isPlaying = playing,
                        isMuted = exoPlayer.volume == 0f,
                        positionMs = exoPlayer.currentPosition,
                        durationMs = exoPlayer.duration.coerceAtLeast(0L),
                    )
                )
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    realVideoAspect = videoSize.width.toFloat() / videoSize.height
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // 位置轮询 — 仅在播放时更新
    LaunchedEffect(uri, isPlaying) {
        while (isPlaying) {
            onStateChanged(
                VideoPlaybackState(
                    isPlaying = true,
                    isMuted = exoPlayer.volume == 0f,
                    positionMs = exoPlayer.currentPosition,
                    durationMs = exoPlayer.duration.coerceAtLeast(0L),
                )
            )
            delay(250L)
        }
    }

    LaunchedEffect(chromeVisible, isPlaying) {
        centerButtonVisible = !isPlaying && !chromeVisible
    }

    // 与照片页完全对齐的 zoomable 配置：maxZoomFactor=6、RubberBanding、
    // key(uri) 让每个 Pager page 一份独立 zoom state，翻页互不串扰。
    Box(modifier = modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        key(uri) {
            val zoomableState = rememberZoomableState(
                zoomSpec = ZoomSpec(
                    maxZoomFactor = 6f,
                    overzoomEffect = OverzoomEffect.RubberBanding,
                )
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(
                        state = zoomableState,
                        onClick = { onSingleTap() },
                        onDoubleClick = { state, centroid ->
                            // 1× ↔ 2.5× ↔ 4× 循环（与照片页一致）
                            val midScale = 2.5f
                            val highScale = 4f
                            val current = state.contentTransformation.scale.scaleX
                            val target = when {
                                current < midScale - 0.1f -> midScale
                                current < highScale - 0.1f -> highScale
                                else -> 1f
                            }
                            if (target == 1f) {
                                state.resetZoom()
                            } else {
                                state.zoomTo(zoomFactor = target, centroid = centroid)
                            }
                        },
                    )
            ) {
                // 根因修复：用 androidx.media3.ui.AspectRatioFrameLayout 包裹 TextureView，
                // 设置 RESIZE_MODE_FIT 让其按视频原始宽高比 letterbox（保留黑边而不是拉伸）。
                // Modifier.aspectRatio() 只能控制外层 Compose layout 尺寸，无法改变
                // TextureView 内部 SurfaceTexture → View 的 transform matrix，所以之前
                // 的修复无效。这里改用 media3 自家的 AspectRatioFrameLayout，它会在
                // onMeasure 里按 ratio 调整 child (TextureView) 的实际尺寸。
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        AspectRatioFrameLayout(ctx).apply {
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            // MediaItem 里的宽高比（width/height）作为初始 ratio，
                            // 视频真实解码后由 onVideoSizeChanged 回调刷新。
                            setAspectRatio(aspectRatio)
                        }.also { frameLayout ->
                            val textureView = TextureView(ctx)
                            frameLayout.addView(
                                textureView,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )
                            exoPlayer.setVideoTextureView(textureView)
                        }
                    },
                    update = { frameLayout ->
                        // 重组时确保 RESIZE_MODE 仍是 FIT（避免被父组件意外改回 FILL）；
                        // ratio 优先使用 ExoPlayer 解码后的真实宽高比，否则用 MediaItem 的。
                        frameLayout.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
                        val effectiveRatio = if (realVideoAspect > 0f) realVideoAspect else aspectRatio
                        frameLayout.setAspectRatio(effectiveRatio)
                    }
                )

                AnimatedVisibility(
                    visible = centerButtonVisible,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f),
                    exit = fadeOut() + scaleOut(targetScale = 0.85f),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.50f))
                            .clickable { exoPlayer.play() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = "播放",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}