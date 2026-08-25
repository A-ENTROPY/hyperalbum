package com.smartvision.gallery.ui.viewer

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.smartvision.gallery.decoder.format.FormatDetector
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.smartvision.gallery.livephoto.LivePhoto
import com.smartvision.gallery.livephoto.LivePhotoBadge
import com.smartvision.gallery.livephoto.LivePhotoDetector
import com.smartvision.gallery.livephoto.LivePhotoPressHold
import com.smartvision.gallery.livephoto.LivePhotoVideoPlayer
import com.smartvision.gallery.privacy.PrivacyVault
import com.smartvision.gallery.ui.components.FormatBadge
import com.smartvision.gallery.ui.liquidglass.LiquidGlassBar
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSurface
import kotlinx.coroutines.launch

/**
 * Photo Viewer — iOS Photos inspired.
 *
 *  * Minimal chrome (Vibrant glass)
 *  * Pinch-to-zoom (single-finger drag still pans)
 *  * Tap to toggle chrome
 *  * Top bar: back chevron (circular button) + dot indicators + "..." more
 *  * Bottom bar: previous / favorite / share / delete / next (5 iconic buttons)
 */
@Composable
fun PhotoViewerPage(
    uri: Uri,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    onNavigate: (Uri, Int) -> Unit = { _, _ -> },
) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val scope = rememberCoroutineScope()
    val vm: PhotoViewerViewModel = viewModel(factory = PhotoViewerViewModel.factory())

    val state by vm.uiState.collectAsState()
    val current = state.items
    val currentIndex = state.currentIndex
    val item = current.getOrNull(currentIndex)

    // Diagnostic logging — remove after crash is fixed
    androidx.compose.runtime.LaunchedEffect(current, currentIndex, item) {
        com.smartvision.gallery.util.AppLog.i("PhotoViewer",
            "items=${current.size} idx=$currentIndex uri=$uri item=${item?.uri}")
    }

    var chromeVisible by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var showInfo by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }

    // Live Photo state — set asynchronously when the still is loaded.
    var livePhoto by remember { mutableStateOf<LivePhoto?>(null) }
    var livePlaying by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        livePhoto = LivePhotoDetector.findLivePhoto(app, uri)
        livePlaying = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
            }
    ) {
        if (item == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            }
        } else {
            if (item.isVideo) {
                // Full video player — 旧入口（PhotoViewerPage）。新参数全部使用默认值。
                VideoPlayerViewer(
                    uri = item.uri,
                    isCurrentPage = true,
                    onSingleTap = {},
                    chromeVisible = false,
                    onPlayerReady = {},
                    onStateChanged = {},
                    modifier = Modifier.fillMaxSize(),
                    aspectRatio = item.aspectRatio,
                )
            } else {
                // The motion video, when "live playing" is on. We render it behind the
                // still so the still acts as a poster when paused at frame 0.
                livePhoto?.let { lp ->
                    if (livePlaying) {
                        LivePhotoVideoPlayer(
                            uri = lp.videoUri,
                            playing = true,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Static image. Visibility is dimmed when live is playing so the
                // motion video shows through.
                val stillAlpha = if (livePlaying) 0.0f else 1.0f
                ZoomableImage(
                    item = item,
                    scale = scale,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    alpha = stillAlpha,
                    onTransform = { s, x, y ->
                        scale = (s * scale).coerceIn(1f, 6f)
                        offsetX += x
                        offsetY += y
                    }
                )

                // LIVE badge — top-left, only when a Live Photo is detected and not playing.
                if (livePhoto != null && !livePlaying) {
                    LivePhotoBadge(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 16.dp, top = 72.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                TopChrome(item = item, onBack = onBack, onMore = { showInfo = !showInfo })
            }
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                BottomChrome(
                    item = item,
                    isLivePhoto = livePhoto != null,
                    isLivePlaying = livePlaying,
                    hasPrev = currentIndex > 0,
                    hasNext = currentIndex < current.lastIndex,
                    onPrev = {
                        val prevUri = current.getOrNull(currentIndex - 1)?.uri ?: return@BottomChrome
                        val prevIdx = currentIndex - 1
                        vm.setIndex(prevIdx)
                        onNavigate(prevUri, prevIdx)
                    },
                    onNext = {
                        val nextUri = current.getOrNull(currentIndex + 1)?.uri ?: return@BottomChrome
                        val nextIdx = currentIndex + 1
                        vm.setIndex(nextIdx)
                        onNavigate(nextUri, nextIdx)
                    },
                    onPressLive = { livePlaying = true },
                    onReleaseLive = { livePlaying = false },
                    onShare = { showShare = true },
                    onEdit = { onEdit() },
                    onDelete = { vm.delete(onSuccess = onBack) },
                    onFav = { scope.launch { app.mediaRepository.setFavorite(item.uri, !item.isFavorite) } },
                    onToggleHide = {
                        scope.launch {
                            // Standard PrivacyVault — keeps both nav paths
                            // (this page + PhotoViewerActivity) on the same
                            // API so behavior stays consistent.
                            val vault = PrivacyVault(app.mediaRepository, app)
                            if (item.isHidden) {
                                vault.unhide(item)
                            } else {
                                vault.hide(item)
                            }
                        }
                    }
                )
            }
            if (showInfo && chromeVisible) {
                InfoPanel(
                    item = item,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(320.dp)
                        .padding(16.dp)
                )
            }
            if (showShare) {
                com.smartvision.gallery.ui.share.ShareSheet(
                    items = listOf(item),
                    pipeline = com.smartvision.gallery.export.ExportPipeline(app),
                    onDismiss = { showShare = false }
                )
            }
        }
    }
}

/**
 * Pre-flight validity gate. Reads the URI's first 16 bytes off the main thread
 * and verifies the magic-byte signature is a recognizable image container.
 *
 * Why this exists: the native JPEG/HEIC/AVIF decoders are called by Coil /
 * AsyncImage *without* a JVM try/catch wrapping the actual decode, so a
 * corrupt file produces a native SIGSEGV (status=11) and the whole process
 * dies. The only way to keep a corrupt file from reaching the native decoder
 * is to validate it *before* the AsyncImage is composed.
 *
 * Race-free design: the check runs once per `item.uri` via `LaunchedEffect(key)`,
 * never in composition. The result feeds a small state machine:
 *   * `null`  → still checking → show a thin progress ring (no image yet, no crash)
 *   * `true`  → valid        → mount the AsyncImage
 *   * `false` → invalid      → show a graceful "file corrupted" placeholder
 *
 * Files that pass this gate are still *possibly* corrupt inside the body, but
 * those are rare in practice and outside the scope of this fix.
 */
@Composable
private fun ZoomableImage(
    item: MediaItem,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    alpha: Float = 1.0f,
    onTransform: (zoomFactor: Float, panX: Float, panY: Float) -> Unit
) {
    val context = LocalContext.current
    var validity by remember(item.uri) { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(item.uri) {
        validity = withContext(Dispatchers.IO) {
            FormatDetector.isRecognizable(context, item.uri)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    onTransform(zoom, pan.x, pan.y)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
                this.alpha = alpha
            },
        contentAlignment = Alignment.Center
    ) {
        when (validity) {
            null -> CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp)
            )
            false -> CorruptImagePlaceholder(item = item)
            true -> AsyncImage(
                model = item.uri,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun CorruptImagePlaceholder(item: MediaItem) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text(
            "⚠",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 48.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Text(
            "无法显示该图片",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            item.displayName,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}

@Composable
private fun TopChrome(item: MediaItem, onBack: () -> Unit, onMore: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Round back button
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.KeyboardArrowLeft, contentDescription = "返回", tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.displayName,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                com.smartvision.gallery.util.DateFormatters.full(item.dateTakenMs),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .pointerInput(Unit) { detectTapGestures(onTap = { onMore() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = "更多", tint = Color.White)
        }
    }
}

@Composable
private fun BottomChrome(
    item: MediaItem,
    isLivePhoto: Boolean,
    isLivePlaying: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onPressLive: () -> Unit,
    onReleaseLive: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFav: () -> Unit,
    onToggleHide: () -> Unit
) {
    LiquidGlassBar(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconChromeButton(Icons.Outlined.KeyboardArrowLeft, "上一张", enabled = hasPrev) { onPrev() }
            IconChromeButton(
                if (item.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                "收藏"
            ) { onFav() }
            if (isLivePhoto) {
                LivePhotoPressHold(
                    isActive = isLivePlaying,
                    onPress = onPressLive,
                    onRelease = onReleaseLive
                )
            } else {
                IconChromeButton(
                    if (item.isHidden) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    if (item.isHidden) "取消隐藏" else "隐藏"
                ) { onToggleHide() }
            }
            IconChromeButton(Icons.Outlined.Edit, "编辑") { onEdit() }
            IconChromeButton(Icons.Outlined.Delete, "删除") { onDelete() }
            IconChromeButton(Icons.Outlined.KeyboardArrowRight, "下一张", enabled = hasNext) { onNext() }
        }
    }
}

@Composable
private fun IconChromeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bgAlpha = if (enabled) 0.15f else 0.06f
    val iconAlpha = if (enabled) 1f else 0.35f
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = bgAlpha))
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onTap = { onClick() })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = desc, tint = Color.White.copy(alpha = iconAlpha), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun InfoPanel(item: MediaItem, modifier: Modifier = Modifier) {
    LiquidGlassSurface(
        modifier = modifier,
    ) {
        Column {
            Text(
                "文件信息",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp
            )
            Spacer(Modifier.height(12.dp))
            InfoRow("格式", item.format.displayName)
            InfoRow("尺寸", "${item.width} × ${item.height}")
            InfoRow("大小", "${item.sizeBytes / 1024} KB")
            InfoRow("拍摄时间", com.smartvision.gallery.util.DateFormatters.full(item.dateTakenMs))
            if (item.bucketName != null) InfoRow("相册", item.bucketName)
            if (item.latitude != null && item.longitude != null) {
                InfoRow("位置", "%.5f, %.5f".format(item.latitude, item.longitude))
            }
            if (item.aiTags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("AI 标签", color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(item.aiTags.joinToString(" · "), color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.width(96.dp),
            fontSize = 12.sp
        )
        Text(value, color = Color.White, fontSize = 14.sp)
    }
}