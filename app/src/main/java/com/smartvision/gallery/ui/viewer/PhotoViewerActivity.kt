package com.smartvision.gallery.ui.viewer

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.ui.editor.PhotoEditorPage
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.data.repo.MediaRepository
import com.smartvision.gallery.decoder.bridge.NativeBridge
import com.smartvision.gallery.decoder.format.FormatDetector
import com.smartvision.gallery.decoder.format.MediaFormat
import com.smartvision.gallery.export.ExportPipeline
import com.smartvision.gallery.ui.glass.GlassConfigViewModel
import com.smartvision.gallery.livephoto.LivePhoto
import com.smartvision.gallery.livephoto.LivePhotoDetector
import com.smartvision.gallery.livephoto.LivePhotoVideoPlayer
import com.smartvision.gallery.privacy.PrivacyVault
import com.smartvision.gallery.ui.liquidglass.LiquidGlassTheme
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassScreenBackdrop
import com.smartvision.gallery.ui.share.ShareSheet
import com.smartvision.gallery.ui.theme.SmartVisionTheme
import com.smartvision.gallery.ui.viewer.VideoPlaybackState
import com.smartvision.gallery.util.AppLog
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.subsamplingimage.SubSamplingImage
import me.saket.telephoto.subsamplingimage.SubSamplingImageErrorReporter
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.rememberSubSamplingImageState
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import com.smartvision.gallery.data.model.DecodedPayload

/**
 * Standalone photo/video viewer Activity — opens via Intent.
 *
 * Composition root that bypasses the main app's Liquid Glass render tree
 * to dodge a realme ColorOS 16 HWUI RenderEffect stack overflow.
 * All chrome is Material3.
 */
class PhotoViewerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriList: List<Uri> = intent.getStringArrayListExtra(EXTRA_URI_LIST)
            ?.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
            ?: emptyList()
        val singleUri: Uri? = intent.getStringExtra(EXTRA_URI)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: ""

        // External open/share ("open with Liquid Gallery"): the caller delivers the
        // image as intent.data (ACTION_VIEW) or an EXTRA_STREAM (ACTION_SEND/
        // SEND_MULTIPLE), not as our EXTRA_URI/EXTRA_URI_LIST extras. Without this
        // fallback onCreate hits allUris.isEmpty() → finish() → the system reports
        // "cannot open this photo" even though the viewer is fully functional.
        @Suppress("DEPRECATION", "DEPRECATED_IN_SDK_33")
        val viewData: List<Uri> = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            else -> emptyList()
        }

        val allUris: List<Uri> = when {
            uriList.isNotEmpty() -> uriList
            singleUri != null -> listOf(singleUri)
            viewData.isNotEmpty() -> viewData
            else -> emptyList()
        }
        if (allUris.isEmpty()) { finish(); return }

        // External VIEW/SEND intents carry a per-activity read grant on the
        // delivered URI — enough for the decode pipeline (openInputStream /
        // openFileDescriptor) which runs while this activity is alive. No
        // takePersistableUriPermission needed: we only read once, in-session.

        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
            .coerceIn(0, allUris.size - 1)

        setContent {
            SmartVisionTheme {
                val app = applicationContext as SmartVisionApp
                val glassVm = remember { GlassConfigViewModel(app.glassConfigRepository) }
                val glassConfig by glassVm.config.collectAsState()
                CompositionLocalProvider(LocalGlassConfig provides glassConfig) {
                    LiquidGlassTheme {
                        ViewerScreen(
                            uris = allUris,
                            startIndex = startIndex,
                            displayName = displayName,
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_URI_LIST = "extra_uri_list"
        const val EXTRA_URI = "extra_uri"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
        const val EXTRA_START_INDEX = "extra_start_index"

        /** Caller-supplied list of URIs to swipe through. */
        fun launchIntent(
            context: Context,
            uris: List<Uri>,
            startIndex: Int = 0,
        ): Intent = Intent(context, PhotoViewerActivity::class.java).apply {
            putStringArrayListExtra(EXTRA_URI_LIST, ArrayList(uris.map { it.toString() }))
            putExtra(EXTRA_START_INDEX, startIndex)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** Legacy single-URI launcher. */
        fun launchIntent(context: Context, uri: Uri, displayName: String = ""): Intent =
            Intent(context, PhotoViewerActivity::class.java).apply {
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerScreen(
    uris: List<Uri>,
    startIndex: Int,
    displayName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 删除照片后从列表中移除 → pager 页数随之收缩, 避免滑动到已删除槽位
    // 显示空白/已回收照片. 用 SnapshotStateList 让 pageCount 重组.
    val remainingUris = remember { uris.toMutableStateList() }
    val pagerState = rememberPagerState(initialPage = startIndex) { remainingUris.size }

    val visibilityState = rememberChromeVisibilityState()
    val chromeBottomHeightPx = remember { mutableIntStateOf(0) }
    // M1: use `remember` (not rememberSaveable) — currentIndex is overwritten by
    // snapshotFlow { pagerState.currentPage } on first emission, so saveable creates
    // two sources of truth that can disagree briefly on rotation.
    var currentIndex by remember { mutableStateOf(startIndex) }
    var showInfo by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showWallpaperConfirm by remember { mutableStateOf(false) }
    var showShareSheet by remember { mutableStateOf(false) }
    var slideshowConfig by remember { mutableStateOf<SlideshowConfig?>(null) }
    var slideshowDialogOpen by remember { mutableStateOf(false) }
    var currentFavorite by remember { mutableStateOf(false) }
    var editorMode by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }



    val currentUri by remember {
        derivedStateOf { remainingUris.getOrNull(currentIndex) }
    }

    // 视频状态 — iOS 风格统一 chrome 后，播放/静音/进度由 VideoControlBar 持有；
    // position/duration/isPlaying 由 ExoPlayer.Listener 回写到 mutableStateMapOf，
    // 跨 page 切换不丢失。currentVideoPlayer 为当前页 ExoPlayer 句柄，控制条直接操作。
    val videoState = remember { mutableStateMapOf<Uri, VideoPlaybackState>() }
    var currentVideoPlayer by remember { mutableStateOf<androidx.media3.exoplayer.ExoPlayer?>(null) }
    val currentMime = currentUri?.let { MediaItemAdapter.mimeType(context, it) }
    val isCurrentVideo = currentMime?.startsWith("video/") == true
    val currentVideoState = currentUri?.let { videoState[it] } ?: VideoPlaybackState()

    // Ultra HDR — single authority owns window.colorMode for the CURRENT photo.
    // Telephoto's per-image capture-and-restore races inside the pager: the old
    // page's onDispose can restore the pre-HDR mode AFTER the new page already set
    // HDR, leaving an HDR photo displayed in SDR. We probe the current photo's
    // gainmap directly and deterministically re-apply the mode on every photo
    // change, so HDR↔SDR switching always converges to the right mode.
    var currentHdr by remember { mutableStateOf(false) }
    LaunchedEffect(currentUri) {
        currentHdr = currentUri?.let { uri ->
            withContext(Dispatchers.IO) {
                (context.applicationContext as SmartVisionApp).mediaLoader.hasGainmap(uri)
            }
        } == true
    }
    HdrColorModeAuthority(
        isCurrentPhotoHdr = currentHdr,
        isSdkEligible = isHdrDisplayEligible(),
    )

    // Static photo snapshot for chrome panel background. Recomputes only when
    // the current URI changes (page swipe); the panel-level `Modifier.blur(28.dp)`
    // does the glass blur at draw time. See BlurredPhotoBackdrop.kt for why we
    // can't use live backdrop sampling here (ColorOS 16 RenderThread SIGSEGV).
    val photoBackdrop = rememberBlurredPhotoBackdrop(currentUri, targetPx = 48)
    // Larger snapshot for the screen-wide photo letterbox fill — 48 px upscaled
    // to a full screen leaves visible pixelation, so this one decodes at 256 px
    // (~4× bilinear upscale at 1080 dp = smooth blur look without any runtime
    // Modifier.blur, which still crashes ColorOS 16).
    val letterboxBackdrop = rememberBlurredPhotoBackdrop(currentUri, targetPx = 256)

    // Live Photo state (suspend call must be in LaunchedEffect, not remember)
    var currentLivePhoto by remember { mutableStateOf<LivePhoto?>(null) }
    // Live Photo press-and-hold playback — toggled by LivePhotoOverlay's
    // onPress / onRelease. When true, LivePhotoVideoPlayer is overlaid on
    // top of the static photo for the current page.
    var livePlaying by remember { mutableStateOf(false) }

    // Sync pagerState → currentIndex
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { currentIndex = it }
    }

    // Refresh favorite state when current page changes
    LaunchedEffect(currentUri) {
        currentFavorite = currentUri?.let { uri ->
            withContext(Dispatchers.IO) { MediaItemAdapter.queryImage(context, uri)?.isFavorite == true }
        } ?: false
    }

    // Detect live photo when current URI changes
    LaunchedEffect(currentUri) {
        currentLivePhoto = currentUri?.let { uri ->
            withContext(Dispatchers.IO) { LivePhotoDetector.findLivePhoto(context, uri) }
        }
        // Stop any in-flight Live Photo playback when the page changes — the
        // overlay is bound to the OLD page's URI and would otherwise keep
        // playing a video the user is no longer pressing.
        livePlaying = false
    }

    // Overlay suppression only — no auto-hide timer. Chrome stays visible until
    // the user explicitly taps to toggle it. When any dialog/sheet opens we
    // hide chrome so it doesn't interfere with the overlay.
    LaunchedEffect(
        showInfo,
        showDeleteConfirm,
        showShareSheet,
        slideshowDialogOpen,
    ) {
        if (showInfo || showDeleteConfirm || showShareSheet || slideshowDialogOpen) {
            visibilityState.hide()
        }
    }

    // Slideshow auto-advance. C1 fix: guard against stale currentIndex (e.g. after
    // a delete that shrank the URI list). H1 fix: clear slideshowConfig when a
    // non-loop slideshow reaches the end so the loop fully stops.
    LaunchedEffect(slideshowConfig) {
        val cfg = slideshowConfig ?: return@LaunchedEffect
        visibilityState.hide()
        while (isActive) {
            delay(cfg.intervalMs)
            if (currentIndex > remainingUris.size - 1) break
            val nextIndex = if (currentIndex >= remainingUris.size - 1) {
                if (cfg.loop) 0 else {
                    slideshowConfig = null
                    break
                }
            } else currentIndex + 1
            pagerState.animateScrollToPage(nextIndex)
        }
    }

    val pipeline = remember { ExportPipeline(context.applicationContext) }

    // Screen-level backdrop — captures the photo pager subtree so chrome bars
    // (siblings of this capture Box) can refractor real photo pixels through
    // their glass surface. This is the same pattern used by AppRoot.kt for
    // the bottom tab bar; chrome MUST be a sibling of the layerBackdrop Box,
    // never inside it, or we hit the ColorOS 16 HWUI render-tree cycle.
    val screenBackdrop = rememberLayerBackdrop()

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        CompositionLocalProvider(LocalLiquidGlassScreenBackdrop provides screenBackdrop) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                // Z=0 — photo content. layerBackdrop captures this subtree into
                // the graphics layer that chrome bars read via
                // LocalLiquidGlassScreenBackdrop.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(screenBackdrop)
                ) {
                    // Letterbox fill — a tiny blurred snapshot of the current photo,
                    // scaled to fill the whole screen (ContentScale.Crop). The pager
                    // draws each photo at its native aspect ratio on top; for the
                    // top/bottom (or left/right) bands where the photo doesn't reach
                    // edge-to-edge this snapshot provides textured pixels for the
                    // Kyant lens/blur physics to sample. Without this, the layer
                    // captures pure black in the letterbox regions and the glass
                    // card's "物理效果" appears dead/flat near the photo edges —
                    // matching iOS Photos which fills its letterbox with a blurred
                    // version of the photo. Does not modify blur/lens/vibrancy
                    // physics parameters — only their backdrop input.
                    // Crossfade the letterbox between the current and next photo's
                    // backdrop. Combined with rememberBlurredPhotoBackdrop keeping
                    // the old frame until the new one decodes, page switches fade
                    // old→new instead of flashing the dark gradient mid-decode.
                    Crossfade(
                        targetState = letterboxBackdrop ?: photoBackdrop,
                        animationSpec = tween(durationMillis = 250),
                        label = "letterbox",
                    ) { backdrop ->
                        if (backdrop != null) {
                            Image(
                                bitmap = backdrop.asImageBitmap(),
                                contentDescription = null,
                                // Big Gaussian blur on this image only — the letterbox
                                // is the lowest composable in the z-stack, NOT inside
                                // any glass-surface RenderEffect chain, so a single
                                // Modifier.blur here doesn't compound depth like the
                                // chrome panel's blurred-glass chain did on realme
                                // ColorOS 16. Effect: photo becomes a smooth color
                                // field that the Kyant lens/blur physics can refract,
                                // matching iOS Photos' heavily-blurred letterbox.
                                modifier = Modifier
                                    .fillMaxSize()
                                    .blur(64.dp),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFF1A1A1A),
                                                Color.Black,
                                            )
                                        )
                                    )
                            )
                        }
                    }
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        val uri = remainingUris[page]
                        val mime = remember(uri) { MediaItemAdapter.mimeType(context, uri) }
                        if (mime?.startsWith("video/") == true) {
                            com.smartvision.gallery.ui.viewer.VideoPlayerViewer(
                                uri = uri,
                                isCurrentPage = (uri == currentUri),
                                onSingleTap = { visibilityState.toggle() },
                                chromeVisible = visibilityState.visible,
                                onPlayerReady = { player ->
                                    // 仅记录当前页 player；page 切换时旧 player 由
                                    // DisposableEffect 释放，新 player 上抛时覆盖。
                                    if (uri == currentUri) currentVideoPlayer = player
                                },
                                onStateChanged = { state ->
                                    if (uri == currentUri) videoState[uri] = state
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            // Bug fix: pass onSingleTap so ZoomableImage's detectTapGestures
                            // handles single-tap (chrome toggle) without an outer Box that
                            // would sit on top of the chrome Column and consume IconButton taps.
                            ZoomableImage(
                                uri = uri,
                                onSingleTap = { visibilityState.toggle() },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }

                // Chrome — ALWAYS in the composition tree so the spring hide animation
                // can play. `progress` drives alpha; `visible` gates clickable() so taps
                // pass through to the photo when chrome is hidden. Sibling of the
                // capture Box above (not inside it) — critical to avoid the render
                // tree cycle that crashed ColorOS 16.
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        ViewerTopBarChrome(
                            title = if (remainingUris.size > 1) "${currentIndex + 1} / ${remainingUris.size}"
                                    else displayName,
                            progress = visibilityState.progress,
                            visible = visibilityState.visible,
                            onBack = onBack,
                            onSlideshowClick = { slideshowDialogOpen = true },
                            onSetWallpaperClick = { showWallpaperConfirm = true },
                            onHideToVaultClick = {
                                scope.launch {
                                    currentUri?.let { uri ->
                                        val ok = withContext(Dispatchers.IO) { hideToVault(context, uri) }
                                        snackbarHostState.showSnackbar(
                                            if (ok) "已隐藏到保险柜" else "隐藏失败"
                                        )
                                        if (ok) onBack()
                                    }
                                }
                            },
                            onShowLocationClick = {
                                // Show InfoPanel with inline map + address instead
                                // of opening an external map app directly.
                                showInfo = true
                            },
                            onAnyInteraction = { visibilityState.kickAutoHide() },
                            modifier = Modifier.fillMaxWidth(),
                            photoBitmap = photoBackdrop,
                        )
                    Spacer(modifier = Modifier.weight(1f))
                    // 视频页：在底栏 chrome 上方叠加液态玻璃控制条
                    if (isCurrentVideo && visibilityState.visible && currentVideoPlayer != null) {
                        val player = currentVideoPlayer!!
                        VideoControlBar(
                            isPlaying = currentVideoState.isPlaying,
                            isMuted = currentVideoState.isMuted,
                            positionMs = currentVideoState.positionMs,
                            durationMs = currentVideoState.durationMs,
                            onPlayPause = {
                                if (currentVideoState.isPlaying) player.pause() else player.play()
                            },
                            onSeek = { pos ->
                                player.seekTo(pos)
                                videoState[currentUri!!] = currentVideoState.copy(positionMs = pos)
                            },
                            onToggleMute = {
                                val newMuted = !currentVideoState.isMuted
                                player.volume = if (newMuted) 0f else 1f
                                videoState[currentUri!!] = currentVideoState.copy(isMuted = newMuted)
                            },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    ViewerBottomBarChrome(
                        isFavorite = currentFavorite,
                        progress = visibilityState.progress,
                        visible = visibilityState.visible,
                        onFavoriteClick = {
                            currentUri?.let { uri ->
                                scope.launch {
                                    val app = context.applicationContext as SmartVisionApp
                                    app.mediaRepository.setFavorite(uri, !currentFavorite)
                                    currentFavorite = !currentFavorite
                                }
                            }
                        },
                        onShareClick = { showShareSheet = true },
                        onEditClick = {
                            // 智能调用系统编辑器：
                            // 1. 先查询可用的 ACTION_EDIT 处理器
                            // 2. 如果有多个，让用户选择
                            // 3. 如果只有一个，直接启动
                            // 4. 如果没有，尝试已知的国产 ROM 系统编辑器
                            // 5. 都不行才回退到内置编辑器
                            currentUri?.let { uri ->
                                val mime = MediaItemAdapter.mimeType(context, uri) ?: "image/*"
                                val pm = context.packageManager

                                // 诊断：记录完整信息
                                AppLog.d("PhotoViewer", "=== EDIT DIAGNOSTIC ===")
                                AppLog.d("PhotoViewer", "URI: $uri")
                                AppLog.d("PhotoViewer", "MIME: $mime")
                                AppLog.d("PhotoViewer", "URI scheme: ${uri.scheme}")
                                AppLog.d("PhotoViewer", "URI authority: ${uri.authority}")

                                // 查询可用的编辑器
                                val editIntent = Intent(Intent.ACTION_EDIT).apply {
                                    setDataAndType(uri, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                val activities = pm.queryIntentActivities(editIntent, 0)

                                AppLog.d("PhotoViewer", "Found ${activities.size} editors via queryIntentActivities")
                                activities.forEachIndexed { index, resolveInfo ->
                                    AppLog.d("PhotoViewer", "Editor $index: ${resolveInfo.activityInfo.packageName}/${resolveInfo.activityInfo.name}")
                                }

                                if (activities.isNotEmpty()) {
                                    // 有可用的编辑器
                                    // 优先选择系统编辑器（com.coloros.gallery3d, com.miui.gallery 等）
                                    val systemEditor = activities.find { info ->
                                        val pkg = info.activityInfo.packageName
                                        pkg.contains("gallery") || pkg.contains("photo") || pkg.contains("coloros") ||
                                        pkg.contains("miui") || pkg.contains("huawei") || pkg.contains("vivo")
                                    }
                                    val targetEditor = systemEditor ?: activities.first()
                                    val targetPackage = targetEditor.activityInfo.packageName
                                    val targetActivity = targetEditor.activityInfo.name

                                    AppLog.d("PhotoViewer", "Launching editor: $targetPackage/$targetActivity")

                                    // 复制图片到 cache 目录，用 FileProvider 分享
                                    // 原因：Android Scoped Storage 限制，第三方 app 无法将 MediaStore URI 的写权限授予其他应用
                                    scope.launch {
                                        try {
                                            val cacheUri = withContext(Dispatchers.IO) {
                                                val ext = when {
                                                    mime.contains("png") -> "png"
                                                    mime.contains("webp") -> "webp"
                                                    mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                                                    else -> "jpg"
                                                }
                                                val cacheFile = java.io.File(context.cacheDir, "edit_${System.currentTimeMillis()}.$ext")
                                                context.contentResolver.openInputStream(uri)?.use { input ->
                                                    cacheFile.outputStream().use { output ->
                                                        input.copyTo(output)
                                                    }
                                                }
                                                androidx.core.content.FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    cacheFile
                                                )
                                            }

                                            AppLog.d("PhotoViewer", "Cache URI: $cacheUri")

                                            val directIntent = Intent(Intent.ACTION_EDIT).apply {
                                                setDataAndType(cacheUri, mime)
                                                setClassName(targetPackage, targetActivity)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }

                                            context.startActivity(directIntent)
                                            AppLog.d("PhotoViewer", "Editor launched successfully")
                                        } catch (e: Exception) {
                                            AppLog.w("PhotoViewer", "Failed to launch editor: ${e.javaClass.simpleName}: ${e.message}", e)
                                            editorMode = true
                                        }
                                    }
                                } else {
                                    // 没有标准 ACTION_EDIT 处理器，尝试已知的国产 ROM 系统编辑器
                                    val systemEditors = listOf(
                                        // ColorOS (OPPO/OnePlus)
                                        "com.coloros.gallery3d" to "com.coloros.photoeditor.EditActivity",
                                        "com.coloros.photoeditor" to "com.coloros.photoeditor.ui.EditActivity",
                                        // MIUI (小米/红米)
                                        "com.miui.gallery" to "com.miui.gallery.editor.photo.EditorActivity",
                                        "com.miui.mediaeditor" to "com.miui.mediaeditor.PhotoEditorActivity",
                                        // EMUI (华为/荣耀)
                                        "com.huawei.gallery" to "com.huawei.gallery.editor.PhotoEditorActivity",
                                        // OriginOS (vivo/iQOO)
                                        "com.vivo.gallery" to "com.vivo.gallery.editor.PhotoEditorActivity",
                                        // 通用 AOSP
                                        "com.android.gallery3d" to "com.android.gallery3d.app.EditActivity"
                                    )

                                    var launched = false
                                    for ((pkg, activity) in systemEditors) {
                                        val component = android.content.ComponentName(pkg, activity)
                                        val intent = Intent(Intent.ACTION_EDIT).apply {
                                            setDataAndType(uri, mime)
                                            setComponent(component)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        val result = runCatching {
                                            context.startActivity(intent)
                                            true
                                        }.getOrDefault(false)

                                        if (result) {
                                            AppLog.d("PhotoViewer", "Launched system editor: $pkg/$activity")
                                            launched = true
                                            break
                                        }
                                    }

                                    if (!launched) {
                                        AppLog.w("PhotoViewer", "No system editor found, falling back to built-in")
                                        editorMode = true
                                    }
                                }
                            }
                        },
                        onInfoClick = { showInfo = true },
                        onDeleteClick = { showDeleteConfirm = true },
                        onAnyInteraction = { visibilityState.kickAutoHide() },
                        onHeightMeasured = { chromeBottomHeightPx.intValue = it },
                        modifier = Modifier.fillMaxWidth(),
                        photoBitmap = photoBackdrop,
                    )
                }
            }

            // (删除保留两处 Live Photo 蓝色遗留控件：LivePhotoOverlay 底部圆形按压按钮，
            //  LiveBadgePill 右上角 LIVE Pill — 与液态玻璃风格冲突)

            // Live Photo motion clip — overlaid on the static photo when the
            // user is press-and-holding the LivePhotoOverlay. State-driven
            // (see LivePhotoVideoPlayer): playing = true starts playback,
            // false seeks back to frame 1. Tied to the CURRENT page's URI so
            // swiping away stops playback (see LaunchedEffect(currentUri)).
            if (currentLivePhoto != null && livePlaying && currentUri != null) {
                LivePhotoVideoPlayer(
                    uri = currentLivePhoto!!.videoUri,
                    playing = true,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Bottom sheets & dialogs
            // NOTE: tap-to-toggle chrome is now handled by ZoomableImage.onTap
            // (for image pages) and VideoPlayerViewer's own gesture detector
            // (for video pages). A previous full-screen tap-toggle Box here
            // sat above the chrome Column in z-order and consumed pointer
            // events, blocking IconButton taps, HorizontalPager swipe, and
            // ZoomableImage double-tap/pinch.
            if (showInfo && currentUri != null) {
                InfoPanel(
                    context = context,
                    uri = currentUri!!,
                    onDismiss = { showInfo = false }
                )
            }
            if (showDeleteConfirm) {
                DeleteConfirmDialog(
                    onConfirm = {
                        showDeleteConfirm = false
                        currentUri?.let { uri ->
                            // 复用项目回收站逻辑 — 仅写 DB flag is_trash=true，
                            // 原文件不动。彻底删除走回收站页面的"清空"按钮
                            // （那里才调 MediaStore.createDeleteRequest）。
                            scope.launch {
                                val app = context.applicationContext as SmartVisionApp
                                app.mediaRepository.setTrash(uri, true)
                                slideshowConfig = null
                                snackbarHostState.showSnackbar("已移至回收站")
                                // 从列表移除已删照片 → pager 页数收缩. 若删的是
                                // 最后一张, 回退; 否则留在原位 (下一张自动补位).
                                val idx = remainingUris.indexOf(uri)
                                if (idx >= 0) remainingUris.removeAt(idx)
                                if (remainingUris.isEmpty()) {
                                    onBack()
                                } else if (currentIndex > remainingUris.size - 1) {
                                    pagerState.animateScrollToPage(remainingUris.size - 1)
                                }
                            }
                        }
                    },
                    onDismiss = { showDeleteConfirm = false }
                )
            }
            if (showWallpaperConfirm) {
                com.smartvision.gallery.ui.liquidglass.LiquidGlassAlertDialog(
                    onDismiss = { showWallpaperConfirm = false },
                    title = "设为壁纸？",
                    message = "将当前照片设为系统壁纸。此操作会直接修改系统设置，请确认。",
                    confirmText = "设为壁纸",
                    dismissText = "取消",
                    titleColor = MaterialTheme.colorScheme.onSurface,
                    messageColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    dismissColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    confirmColor = MaterialTheme.colorScheme.onSurface,
                    onConfirm = {
                        showWallpaperConfirm = false
                        scope.launch {
                            val uri = currentUri ?: return@launch
                            val result = setAsWallpaper(context, uri)
                            snackbarHostState.showSnackbar(
                                if (result.success) "壁纸已设置"
                                else "设置失败：${result.error}"
                            )
                        }
                    },
                )
            }
            if (editorMode && currentUri != null) {
                PhotoEditorPage(
                    uri = currentUri!!,
                    onBack = { editorMode = false },
                    onSave = { editorMode = false }
                )
            }
            if (showShareSheet && currentUri != null) {
                val cur = currentUri!!
                val mime = MediaItemAdapter.mimeType(context, cur) ?: "image/*"
                ShareSheet(
                    items = listOf(
                        MediaItem(
                            id = 0L,
                            uri = cur,
                            displayName = displayName.ifEmpty { "photo" },
                            mimeType = mime,
                            format = MediaFormat.JPEG,
                            sizeBytes = 0L,
                            width = 0,
                            height = 0,
                            dateTakenMs = 0L,
                            dateModifiedMs = 0L,
                            isFavorite = false,
                                    isHidden = false,
                                    isInTrash = false,
                                    isLivePhoto = false,
                                )
                            ),
                            pipeline = pipeline,
                            onDismiss = { showShareSheet = false }
                        )
            }
            if (slideshowDialogOpen) {
                SlideshowDialog(
                    initial = slideshowConfig ?: SlideshowConfig(),
                    onConfirm = { cfg ->
                        slideshowConfig = cfg
                        slideshowDialogOpen = false
                    },
                    onDismiss = { slideshowDialogOpen = false }
                )
            }

            val density = LocalDensity.current
            val chromeBottomHeightDp = with(density) { chromeBottomHeightPx.intValue.toDp() }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = chromeBottomHeightDp + 12.dp),
            ) { Snackbar(it) }
        }
    }
    }
}

/**
 * Hide a media URI to the privacy vault.
 * Uses MediaStore to build a minimal MediaItem, then delegates to PrivacyVault.
 */
private suspend fun hideToVault(context: Context, uri: Uri): Boolean {
    return withContext(Dispatchers.IO) {
        try {
            val app = SmartVisionApp.from(context)
            // M2 fix: delegate to MediaItemAdapter.buildMediaItem to remove
            // duplicated MediaStore query pattern.
            val item = MediaItemAdapter.buildMediaItem(context, uri) ?: return@withContext false
            val privacyVault = PrivacyVault(app.mediaRepository, context)
            privacyVault.hide(item)
            true
        } catch (t: Throwable) {
            false
        }
    }
}

@OptIn(ExperimentalTelephotoApi::class)
@Composable
private fun ZoomableImage(
    uri: Uri,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as SmartVisionApp

    // Detect format upfront — AVIF/JXL need full-resolution decode path
    // to avoid blur when zoomed in. Other formats go through Coil's default
    // pipeline (ZoomableAsyncImage) which handles downsampling internally.
    var format by remember(uri) { mutableStateOf<MediaFormat?>(null) }
    LaunchedEffect(uri) {
        // JXL/AVIF fast-path: mimeType or ".jxl"/".avif" extension decides in
        // µs, skipping the 64-byte IO head probe (~1s on cold open, measured:
        // ZoomBranch format=null → JXL gap). JXL/AVIF both route to the same
        // native raw pipeline; a misdetect can only fail decode → placeholder,
        // never a crash, so extension+mime are safe gates here.
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val hint = uri.lastPathSegment
        format = when {
            mime == "image/jxl" || hint?.endsWith(".jxl", ignoreCase = true) == true ->
                MediaFormat.JXL
            mime == "image/avif" || hint?.endsWith(".avif", ignoreCase = true) == true ->
                MediaFormat.AVIF_STATIC
            else -> withContext(Dispatchers.IO) {
                FormatDetector.detect(context, uri, hint)
            }
        }
    }
    val isJxl = format == MediaFormat.JXL
    com.smartvision.gallery.util.AppLog.i(
        "ZoomBranch", "uri=$uri format=$format isJxl=$isJxl api=${Build.VERSION.SDK_INT}"
    )

    // Formats the system BitmapRegionDecoder tile-decodes natively → SubSamplingImage
    // path. Tiling decodes only the visible region per frame → native-resolution
    // zoom up to [maxScale] without holding the full bitmap.
    //
    // AVIF_STATIC is NOT here: the system BRD does NOT region-decode AVIF on most
    // OEMs (throws "Image format not supported" — see MediaLoader AVIF BRD probe
    // logs). So AVIF goes through its own native raw pipeline below (libavif →
    // SVRAW → RawImageRegionDecoder), mirroring the JXL raw path. AVIF_ANIMATED
    // has no single-frame region API either → that branch handles frames via Coil.
    val isTilingFormat = format == MediaFormat.JPEG ||
        format == MediaFormat.PNG ||
        format == MediaFormat.WEBP_STATIC ||
        format == MediaFormat.HEIC

    // 3-state double-tap targets (1× → 2.5× → 4× → 1×). Hysteresis 0.1 so a
    // gesture that lands fractionally above a threshold doesn't misclassify
    // the current stage (matches the prior hand-rolled behaviour).
    val midScale = 2.5f
    val highScale = 4f

    // Native-resolution zoom ceiling. Sub-sampled (tiled) rendering decodes
    // only the visible region at the current sample size, so a 48MP photo
    // supports 40× zoom without holding the full bitmap in memory.
    // Fallback whole-image decodes (AVIF/JXL) still cap at 4096 px to avoid OOM.
    val maxScale = 40f

    // Images larger than this get tiled rendering; smaller images decode whole.
    val tileThresholdSizePx = 800

    // Per-photo state. Telephoto auto-retains pan/zoom across state
    // restoration; keying on uri gives each pager page a fresh state so the
    // previous photo's zoom never bleeds into the new one.
    key(uri) {
        val zoomableState = rememberZoomableState(
            zoomSpec = ZoomSpec(
                maxZoomFactor = maxScale,
                overzoomEffect = OverzoomEffect.RubberBanding,
            )
        )

        // Source long-edge in pixels (bounds-only probe, ~1ms). Drives the
        // tiling decision for BitmapRegionDecoder formats. Not the viewport
        // size — see isTilingFormat branch comment for why.
        var srcLongEdge by remember(uri) { mutableStateOf<Int?>(null) }
        LaunchedEffect(uri) {
            srcLongEdge = probeSourceLongEdge(context, uri)
        }

        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            when {
                format == null -> CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.6f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(32.dp)
                )
                format == MediaFormat.UNKNOWN -> CorruptImagePlaceholder()
                // Large JPEG/PNG/WebP/HEIC: tiled sub-sampling for native-pixel
                // zoom up to 40× without OOM (decodes only the visible region).
                isTilingFormat -> {
                    // Tiling decision is based on the SOURCE image long-edge, not
                    // the Compose viewport size. The old viewport-based gate
                    // deadlocked: on first composition size==null → largeEnough
                    // ==false → Coil whole-image decode → onSizeChanged was only
                    // attached to the tiling (SubSampling) sub-branch, so size
                    // never updated → AVIF/JPEG stayed on the blurry 255px Coil
                    // path forever ("avif 打开图片是糊的").
                    val srcLargeEnough = (srcLongEdge ?: tileThresholdSizePx) >= tileThresholdSizePx
                    com.smartvision.gallery.util.AppLog.i(
                        "ZoomBranch", "tiling branch srcLongEdge=$srcLongEdge largeEnough=$srcLargeEnough fmt=$format"
                    )
                    if (srcLargeEnough) {
                        SubSamplingZoomableImage(
                            uri = uri,
                            format = format!!,
                            maxScale = maxScale,
                            midScale = midScale,
                            highScale = highScale,
                            onSingleTap = onSingleTap,
                            modifier = modifier,
                        )
                    } else {
                        // Small image — single whole decode is cheaper than tiling.
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(animationSpec = tween(durationMillis = 250)),
                        ) {
                            ZoomableAsyncImage(
                                state = rememberZoomableImageState(
                                    rememberZoomableState(
                                        zoomSpec = ZoomSpec(
                                            maxZoomFactor = maxScale,
                                            overzoomEffect = OverzoomEffect.RubberBanding,
                                        )
                                    )
                                ),
                                modifier = Modifier.fillMaxSize(),
                                model = uri,
                                contentDescription = null,
                                onClick = { onSingleTap() },
                                onDoubleClick = { state, centroid ->
                                    val current = state.contentTransformation.scale.scaleX
                                    val target = when {
                                        current < midScale - 0.1f -> midScale
                                        current < highScale - 0.1f -> highScale
                                        else -> 1f
                                    }
                                    if (target == 1f) state.resetZoom()
                                    else state.zoomTo(zoomFactor = target, centroid = centroid)
                                },
                            )
                        }
                    }
                }
                isJxl -> {
                    // JXL: probe 尺寸后分路径
                    //   ≤ 4096 长边 → 直接解码 Bitmap 显示（保留 JXL 原始特性）
                    //   > 4096 长边 → native libjxl 解码到 SVRAW raw 像素文件 +
                    //                   RawImageRegionDecoder 瓦片读取
                    // 4096 是 Canvas.getMaximumBitmapWidth() 典型值，
                    // 超过这个尺寸的 Bitmap 无法在 Canvas 上完整绘制。
                    // v52: 大图改走 raw 管线而非 JPEG 转码缓存 — JPEG 95 编码
                    // 16K 源是打开 20+ 秒的主因；raw 只落像素、零编码，与 AVIF
                    // 分支完全对齐。

                    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
                    var decodeError by remember(uri) { mutableStateOf(false) }
                    var rawFile by remember(uri) { mutableStateOf<File?>(null) }
                    var probing by remember(uri) { mutableStateOf(true) }

                    LaunchedEffect(uri) {
                        // 1. 快速探测源图尺寸（~1ms）
                        val longEdge = NativeBridge.jxlProbeLongEdge(uri)

                        if (longEdge != null && longEdge > 4096L) {
                            // 大 JXL → raw 像素文件 + SubSampling 瓦片
                            val file = withContext(Dispatchers.IO) {
                                runCatching {
                                    app.jxlFullResPrecacher.decodeToRaw(uri)
                                }.getOrNull()
                            }
                            if (file != null && file.exists() && file.length() > 20) {
                                rawFile = file
                            } else {
                                decodeError = true
                            }
                            probing = false
                        } else {
                            // 小 JXL → 直接解码到 Bitmap
                            probing = false
                            val payload = withContext(Dispatchers.IO) {
                                runCatching {
                                    app.mediaLoader.loadFullUri(uri, format, 4096)
                                }.getOrNull()
                            }
                            val bmp = (payload as? DecodedPayload.BitmapPayload)?.bitmap
                            if (bmp != null) {
                                bitmap = bmp
                            } else {
                                decodeError = true
                            }
                        }
                    }
                    when {
                        probing -> WaitingForDecode()
                        rawFile != null -> RawSubSamplingZoomableImage(
                            rawFile = rawFile!!,
                            maxScale = maxScale,
                            midScale = midScale,
                            highScale = highScale,
                            onSingleTap = onSingleTap,
                            modifier = modifier,
                        )
                        bitmap == null && !decodeError -> WaitingForDecode()
                        decodeError -> CorruptImagePlaceholder()
                        else -> {
                            val imageBitmap = bitmap!!.asImageBitmap()
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(animationSpec = tween(durationMillis = 250)),
                            ) {
                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .zoomable(
                                            state = zoomableState,
                                            onClick = { onSingleTap() },
                                            onDoubleClick = { state, centroid ->
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
                                            }
                                        ),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                        }
                    }
                }
                format == MediaFormat.AVIF_STATIC -> {
                    // AVIF has no system BitmapRegionDecoder support (throws
                    // "Image format not supported"), so Telephoto's SubSamplingImage
                    // cannot tile-decode it. Instead: native libavif decodes the
                    // whole AVIF once into an SVRAW raw file, and RawImageRegionDecoder
                    // tile-reads from that file → lossless 1:1 zoom with constant
                    // memory, exactly like the JXL raw pipeline.
                    var rawFile by remember(uri) { mutableStateOf<File?>(null) }
                    var decodeError by remember(uri) { mutableStateOf(false) }
                    var probing by remember(uri) { mutableStateOf(true) }

                    LaunchedEffect(uri) {
                        val file = withContext(Dispatchers.IO) {
                            runCatching {
                                app.avifRawPrecacher.decodeToRaw(uri)
                            }.getOrNull()
                        }
                        rawFile = file
                        probing = false
                        if (file == null) {
                            decodeError = true
                        }
                    }
                    when {
                        probing -> WaitingForDecode()
                        rawFile != null -> RawSubSamplingZoomableImage(
                            rawFile = rawFile!!,
                            maxScale = maxScale,
                            midScale = midScale,
                            highScale = highScale,
                            onSingleTap = onSingleTap,
                            modifier = modifier,
                        )
                        decodeError -> CorruptImagePlaceholder()
                    }
                }
                else -> {
                    com.smartvision.gallery.util.AppLog.i("ZoomBranch", "FALLBACK coil path fmt=$format uri=$uri")
                    AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(durationMillis = 250)),
                ) {
                    ZoomableAsyncImage(
                        state = rememberZoomableImageState(zoomableState),
                        modifier = Modifier.fillMaxSize(),
                        model = uri,
                        contentDescription = null,
                        onClick = { onSingleTap() },
                        onDoubleClick = { state, centroid ->
                            // Cycle 1× → 2.5× → 4× → 1×. resetZoom recenters to (0,0)
                            // and scale 1× so the round-trip from a zoomed+dragged state
                            // always lands the image centered, matching mainstream
                            // gallery behaviour (Apple Photos, MIUI, 小米).
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
                }
                }
            }
        }
    }
}

/**
 * Bounds-only probe of a media URI's pixel long-edge. Reads only the image
 * header (no pixel decode, ~1ms) via [BitmapFactory] inJustDecodeBounds, so it
 * works for JPEG/PNG/WebP/HEIC and — critically — AVIF_STATIC on API 31+
 * where the system Skia codec recognises the AVIF container in the bounds path.
 *
 * Returns null when the header can't be parsed; callers should treat null as
 * "large enough" so a big image never falls through to the blurry whole-decode.
 */
private suspend fun probeSourceLongEdge(context: Context, uri: Uri): Int? =
    withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val longEdge = maxOf(opts.outWidth, opts.outHeight)
            if (longEdge <= 0) null else longEdge
        } catch (t: Throwable) {
            com.smartvision.gallery.util.AppLog.w("ZoomBranch", "probeSourceLongEdge failed", t)
            null
        }
    }

/**
 * Tiled-rendering photo viewer for formats [android.graphics.BitmapRegionDecoder]
 * supports natively (JPEG / PNG / WebP / HEIC). Copies the URI into a cache file,
 * hands it to Telephoto's [SubSamplingImage], which decodes only the visible
 * region at the current sample size — so zooming to [maxScale]× stays crisp at
 * native pixels without ever holding the full bitmap in memory.
 */
@OptIn(ExperimentalTelephotoApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun SubSamplingZoomableImage(
    uri: Uri,
    format: MediaFormat,
    maxScale: Float,
    midScale: Float,
    highScale: Float,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as SmartVisionApp

    var loadState by remember(uri) { mutableStateOf<SubSamplingLoadState>(SubSamplingLoadState.Loading) }

    LaunchedEffect(uri) {
        val result = app.mediaLoader.copyToCacheFile(uri, format)
        loadState = if (result == null) SubSamplingLoadState.Error else SubSamplingLoadState.Ready(result)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (val state = loadState) {
            is SubSamplingLoadState.Loading -> CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp)
            )
            is SubSamplingLoadState.Error -> CorruptImagePlaceholder()
            is SubSamplingLoadState.Ready -> {
                val cacheResult = state.result
                // Okio path from the cache file. SubSamplingImageSource.file uses
                // BitmapRegionDecoder tile decoding, which only works reliably on a
                // real file (not a re-served content Uri), hence the copy above.
                val source = remember(cacheResult.file) {
                    // contentUri() parses a file:// Uri into telephoto's FileImageSource,
                    // which tile-decodes via BitmapRegionDecoder. Going through the Uri
                    // avoids constructing okio.Path directly in our code.
                    SubSamplingImageSource.contentUri(
                        uri = android.net.Uri.fromFile(cacheResult.file),
                        preview = cacheResult.preview?.asImageBitmap(),
                    )
                }
                val zoomableState = rememberZoomableState(
                    zoomSpec = ZoomSpec(
                        maxZoomFactor = maxScale,
                        overzoomEffect = OverzoomEffect.RubberBanding,
                    )
                )
                // SubSamplingImage renders only the visible tiles at the current
                // sample size — native pixels at 40× zoom, no OOM. The zoomable
                // modifier owns pan/pinch/double-tap and contentTransformation.
                val imageState = rememberSubSamplingImageState(
                    imageSource = source,
                    zoomableState = zoomableState,
                )
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(durationMillis = 250)),
                ) {
                    SubSamplingImage(
                        state = imageState,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .zoomable(
                                state = zoomableState,
                                onClick = { onSingleTap() },
                                onDoubleClick = { state, centroid ->
                                    val current = state.contentTransformation.scale.scaleX
                                    val target = when {
                                        current < midScale - 0.1f -> midScale
                                        current < highScale - 0.1f -> highScale
                                        else -> 1f
                                    }
                                    if (target == 1f) state.resetZoom()
                                    else state.zoomTo(zoomFactor = target, centroid = centroid)
                                },
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Tiled-rendering photo viewer for raw RGBA pixel files produced by the
 * native JXL decoder (jxl_to_raw.cpp). Uses [RawImageSource] to provide
 * custom tile decoding from the raw pixel buffer, preserving original
 * JXL pixel data (including alpha) without any format conversion loss.
 * Sub-sampling at zoom → native pixels up to 1:1, no OOM.
 */
@OptIn(ExperimentalTelephotoApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RawSubSamplingZoomableImage(
    rawFile: File,
    maxScale: Float,
    midScale: Float,
    highScale: Float,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageSource = remember(rawFile) {
        RawImageSource(rawFile)
    }

    val zoomableState = rememberZoomableState(
        zoomSpec = ZoomSpec(
            maxZoomFactor = maxScale,
            overzoomEffect = OverzoomEffect.RubberBanding,
        )
    )

    // telephoto 0.18's default errorReporter is NoOpInRelease — decode failures
    // (IOException from RawImageRegionDecoder.readHeader/decodeRegion on a
    // truncated/poisoned raw) are swallowed silently → black screen, no log.
    // Plug in a reporter that surfaces the IOException so a render failure is
    // diagnosable instead of invisible.
    val errorReporter = remember {
        object : SubSamplingImageErrorReporter {
            override fun onImageLoadingFailed(
                ioException: IOException,
                source: SubSamplingImageSource,
            ) {
                AppLog.e(
                    "RawSubSampling",
                    "telephoto onImageLoadingFailed src=$source raw=${rawFile.absolutePath}",
                    ioException,
                )
            }
        }
    }

    val imageState = rememberSubSamplingImageState(
        imageSource = imageSource,
        zoomableState = zoomableState,
        errorReporter = errorReporter,
    )

    // v53: 首帧完成标记 — 测端到端打开耗时 (probe→decode→raw→首 tile 渲染).
    // AppLog 写文件不受 ColorOS logcat 配额影响, 时间线可从 applog.txt 读取.
    LaunchedEffect(imageState) {
        try {
            snapshotFlow { imageState.isImageDisplayed }
                .filter { it }
                .first()
            AppLog.i("RawSubSampling", "first frame displayed raw=${rawFile.absolutePath} bytes=${rawFile.length()}")
        } catch (t: Throwable) {
            AppLog.w("RawSubSampling", "first-frame watch failed", t)
        }
    }

    // telephoto exposes no error field on SubSamplingImageState (only
    // isImageDisplayed/isImageLoaded booleans), so a failed render leaves the
    // surface black forever with no path to CorruptImagePlaceholder. Watch
    // isImageDisplayed: if nothing has rendered within 15s of the state being
    // created, fall back to the corrupt placeholder so the user sees an
    // actionable error instead of a permanent black screen.
    var renderError by remember(imageState) { mutableStateOf(false) }
    LaunchedEffect(imageState) {
        renderError = false
        kotlinx.coroutines.delay(15_000)
        if (!imageState.isImageDisplayed) {
            AppLog.w("RawSubSampling", "isImageDisplayed still false after 15s — showing CorruptImagePlaceholder")
            renderError = true
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (renderError) {
            CorruptImagePlaceholder()
        } else {
            SubSamplingImage(
                state = imageState,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .zoomable(
                        state = zoomableState,
                        onClick = { onSingleTap() },
                        onDoubleClick = { state, centroid ->
                            val current = state.contentTransformation.scale.scaleX
                            val target = when {
                                current < midScale - 0.1f -> midScale
                                current < highScale - 0.1f -> highScale
                                else -> 1f
                            }
                            if (target == 1f) state.resetZoom()
                            else state.zoomTo(zoomFactor = target, centroid = centroid)
                        },
                    ),
            )
        }
    }
}

/** Load states for [SubSamplingZoomableImage]. */
private sealed interface SubSamplingLoadState {
    data object Loading : SubSamplingLoadState
    data object Error : SubSamplingLoadState
    data class Ready(val result: com.smartvision.gallery.decoder.CacheFileResult) : SubSamplingLoadState
}

/**
 * Given the current zoom scale and display width, compute the output long
 * edge the JXL viewer needs to render at native pixels. At 1× scale we want
 * roughly displayWidthPx; at 4× scale ~4×displayWidthPx. Clamped to [256, 4096]
 * — the Canvas-safe ceiling where native libjxl halving picks the right 2^n
 * stride for any source size.
 */
private fun computeZoomTarget(
    sourceLongEdge: Long,
    scale: Float,
    displayWidthPx: Int
): Int {
    // Want output long edge ≈ displayWidthPx × zoom scale. Hard ceiling is
    // 6144 — Canvas rejects bitmaps ≥ 48MP (201326592 bytes); 6144×6144
    // square is 37.7MP, 6144×4608 (4:3) 28.3MP — both safe. Sources ≤ 6K
    // render at 1:1 at 1× zoom on a 4K screen; bigger sources rely on
    // libjxl's 2^n stride halving (16K at 4× zoom → 6144 output = 2.7
    // source px/out px, visibly crisper than the old 4096 cap).
    val desired = (displayWidthPx * scale.coerceAtLeast(0.5f)).toInt()
    return desired.coerceIn(256, 6144)
}

@Composable
private fun WaitingForDecode() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        CircularProgressIndicator(
            color = Color.White.copy(alpha = 0.6f),
            strokeWidth = 2.dp,
            modifier = Modifier.size(32.dp)
        )
        Text(
            "首次打开,正在解码 JXL…",
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}

@Composable
private fun CorruptImagePlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Text("⚠", color = Color.White.copy(alpha = 0.55f), fontSize = 48.sp,
            modifier = Modifier.padding(bottom = 12.dp))
        Text("无法显示该图片", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

private data class WallpaperResult(val success: Boolean, val error: String = "")

private suspend fun setAsWallpaper(context: Context, uri: Uri): WallpaperResult = withContext(Dispatchers.IO) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val wm = WallpaperManager.getInstance(context)
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext WallpaperResult(false, "无法打开 URI")
            stream.use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                    ?: return@withContext WallpaperResult(false, "解码失败")
                wm.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            }
            WallpaperResult(true)
        } else {
            @Suppress("DEPRECATION")
            val intent = Intent("android.intent.action.SET_WALLPAPER")
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            WallpaperResult(true)
        }
    } catch (t: Throwable) {
        WallpaperResult(false, t.message ?: t.javaClass.simpleName)
    }
}