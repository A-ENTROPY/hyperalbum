package com.smartvision.gallery.ui.lan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.lan.smb.SmbDevice
import com.smartvision.gallery.lan.smb.SmbMediaDataSource
import com.smartvision.gallery.lan.smb.SmbMediaFile
import com.smartvision.gallery.lan.smb.SmbMediaType
import com.smartvision.gallery.lan.smb.SmbShareManager
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassScreenBackdrop
import com.smartvision.gallery.ui.liquidglass.LocalTopBarState
import com.smartvision.gallery.ui.liquidglass.TopBarConfig
import com.smartvision.gallery.ui.liquidglass.TopBarVariant
import com.smartvision.gallery.ui.liquidglass.drawGlassTint
import com.smartvision.gallery.ui.viewer.CapsuleActionIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.saket.telephoto.zoomable.OverzoomEffect
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.io.File

/**
 * SMB 全屏照片查看器——复刻 [com.smartvision.gallery.ui.viewer.PhotoViewerActivity]
 * 的局部 backdrop 液态玻璃模式。
 *
 * 架构（同 PhotoViewerActivity.kt:355-460）：
 *  - 局部 [viewerBackdrop] = [rememberLayerBackdrop]，经
 *    [CompositionLocalProvider] 提供 [LocalLiquidGlassScreenBackdrop]
 *  - Z=0: [Box.layerBackdrop] 捕获子树 = letterbox 模糊快照 +
 *    [HorizontalPager]（图片/视频/动图）
 *  - Z=1: chrome sibling（[SmbViewerTopBar] / [SmbViewerBottomBar]），
 *    [drawBackdrop] 折射 [LocalLiquidGlassScreenBackdrop] 真实照片像素
 *    （非 canvas 渐变色块）+ [CapsuleActionIcon] 胶囊按钮
 *
 * 在 AppRoot capture 子树内建**嵌套**局部 backdrop——LibraryOverlay 已验证
 * 嵌套 layerBackdrop 在 ColorOS 16 安全（无自采样，因 chrome 在捕获 Box 外）。
 *
 * 单击图片 toggle chrome：telephoto [ZoomableAsyncImage] 的 onClick 参数
 * （javap 验证签名），避免外层 Box clickable 与缩放手势冲突。
 *
 * ponytail: maxScale 限 12f（ZoomableAsyncImage 整图解码上限，防 OOM）。
 * 首页 40f 依赖 SubSamplingImage 瓦片解码 + SmbRandomAccessSource 工厂
 * （random-access SmbResource 包装），属大工程；升级路径：实现
 * `SubSamplingImageSource` 适配器后改用 SubSamplingZoomableImage + maxScale=40f。
 */
@Composable
fun SmbPhotoViewer(
    files: List<SmbMediaFile>,
    initialIndex: Int,
    device: SmbDevice,
    shareManager: SmbShareManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex) { files.size }

    var showChrome by remember { mutableStateOf(true) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    // null=idle, "downloading"/"done"/"error" 供下载按钮 Toast 反馈
    var downloadStatus by remember { mutableStateOf<String?>(null) }
    val currentIndex = pagerState.currentPage
    val currentFile = files[currentIndex]

    // 本组件在 AppRoot capture 子树内，不能读全局 LocalLiquidGlassScreenBackdrop
    // （自采样崩溃）。自建嵌套局部 backdrop，chrome 作捕获 Box 的 sibling 读取。
    val viewerBackdrop = rememberLayerBackdrop()

    // 抑制 AppRoot FloatingTopBarPill，避免双 bar + 玻璃类型不一致。
    // LanSharePage:isSmbPhotoViewer -> topBar.value 保持 viewer 发布（HIDDEN）。
    val topBar = LocalTopBarState.current
    LaunchedEffect(currentIndex) {
        topBar.value = TopBarConfig(
            title = currentFile.name,
            variant = TopBarVariant.HIDDEN,
            onBack = onBack,
        )
    }

    // 当前页 SmbResource（仅解析 URL 不联网，remember 安全）
    val currentSmbModel = remember(currentFile, device) {
        runCatching {
            shareManager.getCifsContext(device)
                .get(device.toSmbUrl(currentFile.path)) as jcifs.SmbResource
        }.getOrNull()
    }

    // letterbox 模糊快照（SMB 版，走 BitmapFactory 直解小图）
    val letterboxBitmap = rememberSmbBlurredBackdrop(currentSmbModel, targetPx = 256)

    CompositionLocalProvider(LocalLiquidGlassScreenBackdrop provides viewerBackdrop) {
        Box(modifier = modifier.fillMaxSize()) {
            // ---- Z=0: capture 子树 ----
            // letterbox 模糊背景 + HorizontalPager。layerBackdrop 把本子树捕获进
            // viewerBackdrop，Z=1 chrome 折射真实照片像素。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(viewerBackdrop)
            ) {
                // Letterbox 模糊背景：同 PhotoViewerActivity.kt:386-422。
                // Z=0 最底层，不在 glass-surface RenderEffect 链里，Modifier.blur 安全
                // （BlurredPhotoBackdrop.kt:35-36 警告的是 chrome panel 链内 blur）。
                Crossfade(
                    targetState = letterboxBitmap,
                    animationSpec = tween(durationMillis = 250),
                    label = "smbLetterbox",
                ) { backdrop ->
                    if (backdrop != null) {
                        androidx.compose.foundation.Image(
                            bitmap = backdrop.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(64.dp),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Spacer(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val file = files[page]
                    val smbModel = remember(file, device) {
                        runCatching {
                            shareManager.getCifsContext(device)
                                .get(device.toSmbUrl(file.path)) as jcifs.SmbResource
                        }.getOrNull()
                    }

                    when (file.type) {
                        SmbMediaType.VIDEO -> SmbVideoPage(
                            file = file,
                            device = device,
                            shareManager = shareManager,
                        )

                        SmbMediaType.GIF -> coil.compose.AsyncImage(
                            model = smbModel,
                            contentDescription = file.name,
                            modifier = Modifier.fillMaxSize(),
                        )

                        else -> {
                            val zoomState = rememberZoomableImageState(
                                rememberZoomableState(
                                    zoomSpec = ZoomSpec(
                                        maxZoomFactor = 12f,
                                        overzoomEffect = OverzoomEffect.RubberBanding,
                                    )
                                )
                            )
                            if (smbModel != null) {
                                ZoomableAsyncImage(
                                    state = zoomState,
                                    model = smbModel,
                                    contentDescription = file.name,
                                    modifier = Modifier.fillMaxSize(),
                                    // 单击 toggle chrome（telephoto onClick 参数，javap 验证）
                                    onClick = { if (file.type != SmbMediaType.VIDEO) showChrome = !showChrome },
                                    onDoubleClick = { state, centroid ->
                                        val current = state.contentTransformation.scale.scaleX
                                        val target = when {
                                            current < 2.5f -> 2.5f
                                            current < 4f -> 4f
                                            current < 8f -> 8f
                                            else -> 1f
                                        }
                                        if (target == 1f) state.resetZoom()
                                        else state.zoomTo(zoomFactor = target, centroid = centroid)
                                    },
                                )
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Outlined.Close,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.6f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- Z=1: chrome sibling（折射 viewerBackdrop 真实照片像素）----
            if (showChrome && currentFile.type != SmbMediaType.VIDEO) {
                SmbViewerTopBar(
                    title = currentFile.name,
                    onBack = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars),
                )
                SmbViewerBottomBar(
                    onDownload = {
                        scope.launch {
                            if (downloadStatus == "downloading") return@launch
                            downloadStatus = "downloading"
                            val ok = downloadToGallery(context, shareManager, device, currentFile)
                            downloadStatus = if (ok) "done" else "error"
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "已保存到相册" else "下载失败",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onDelete = { showDeleteConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }

            // 删除二次确认
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    title = { Text("删除照片") },
                    text = { Text("确定删除「${currentFile.name}」吗？此操作不可恢复。") },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            scope.launch {
                                if (shareManager.deleteFile(device, currentFile.path)) onBack()
                            }
                        }) {
                            Text("删除", color = Color(0xFFFF3B30))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                    },
                )
            }
        }
    }
}

/**
 * 下载 SMB 文件到系统相册（DCIM/SmartVision）。
 *
 * Android 10+ 走 MediaStore（scoped storage）：IS_PENDING=1 占位写入 → 流式拷贝 →
 * IS_PENDING=0 提交，用户相册立即可见（替换原 getExternalFilesDir 私有目录——
 * 那里用户看不到，等于"没有作用"）。
 */
private suspend fun downloadToGallery(
    context: Context,
    shareManager: SmbShareManager,
    device: SmbDevice,
    file: SmbMediaFile,
): Boolean = withContext(Dispatchers.IO) {
    val mime = file.mimeType.ifBlank { "image/*" }
    val fileName = file.name
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/SmartVision")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    shareManager.openInputStream(device, file.path).use { input -> input.copyTo(out) }
                } ?: run {
                    resolver.delete(uri, null, null)
                    return@withContext false
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        } else {
            // API < 29 兜底（老设备罕见；当前设备 API 35）：app 私有目录
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DCIM), "SmartVision").apply { mkdirs() }
            shareManager.copyToLocal(device, file.path, File(dir, fileName))
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * SMB viewer 顶部 chrome——复刻 [com.smartvision.gallery.ui.viewer.ViewerTopBarChrome]
 * 的 drawBackdrop 链。panelShape + vibrancy/blur/lens + drawGlassTint，
 * 读 [LocalLiquidGlassScreenBackdrop] 真实像素，[CapsuleActionIcon] 返回键。
 */
@Composable
private fun SmbViewerTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val glassBackdrop = LocalLiquidGlassScreenBackdrop.current
    val spec = LocalGlassConfig.current.control.toSpec()
    val panelShape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(8.dp))
    Box(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = glassBackdrop,
                    shape = { panelShape },
                    effects = {
                        if (spec.vibrancy) vibrancy()
                        blur(with(density) { spec.blurRadius.toPx() })
                        lens(
                            with(density) { spec.lensAmount.toPx() },
                            with(density) { spec.lensAmount.toPx() },
                        )
                    },
                    onDrawSurface = { drawGlassTint(spec) },
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleActionIcon(
                    icon = {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    contentDescription = "返回",
                    onClick = onBack,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * SMB viewer 底部 chrome——复刻 [com.smartvision.gallery.ui.viewer.ViewerBottomBarChrome]
 * 的 drawBackdrop 链。下载/删除两个 [CapsuleActionIcon]，无色彩填充。
 */
@Composable
private fun SmbViewerBottomBar(
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val glassBackdrop = LocalLiquidGlassScreenBackdrop.current
    val spec = LocalGlassConfig.current.control.toSpec()
    val panelShape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(8.dp))
    Box(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .drawBackdrop(
                    backdrop = glassBackdrop,
                    shape = { panelShape },
                    effects = {
                        if (spec.vibrancy) vibrancy()
                        blur(with(density) { spec.blurRadius.toPx() })
                        lens(
                            with(density) { spec.lensAmount.toPx() },
                            with(density) { spec.lensAmount.toPx() },
                        )
                    },
                    onDrawSurface = { drawGlassTint(spec) },
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsuleActionIcon(
                    icon = {
                        Icon(
                            Icons.Outlined.CloudDownload,
                            contentDescription = "下载",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    contentDescription = "下载",
                    onClick = onDownload,
                )
                CapsuleActionIcon(
                    icon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    contentDescription = "删除",
                    onClick = onDelete,
                )
            }
        }
    }
}

/**
 * SMB 视频页：ExoPlayer + [SmbMediaDataSource] 随机访问流。
 */
@Composable
private fun SmbVideoPage(
    file: SmbMediaFile,
    device: SmbDevice,
    shareManager: SmbShareManager,
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(file, device) {
        val dataSourceFactory = object : DataSource.Factory {
            override fun createDataSource(): DataSource =
                SmbMediaDataSource(device, path = file.path, shareManager)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)
        val player = ExoPlayer.Builder(context, mediaSourceFactory).build()
        exoPlayer = player
        val uri = Uri.parse(device.toSmbUrl(file.path))
        player.setMediaItem(MediaItem.Builder()
            .setUri(uri)
            .setMimeType(file.mimeType)
            .build())
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(file) {
        onDispose {
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                if (exoPlayer != null) setPlayer(exoPlayer)
                setShowBuffering(7)
                setControllerAutoShow(true)
            }
        },
        update = { view ->
            if (exoPlayer != null && view.player != exoPlayer) view.setPlayer(exoPlayer)
        },
    )
}
