package com.smartvision.gallery.ui.lan

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.smartvision.gallery.lan.smb.*
import com.smartvision.gallery.ui.apple.iOSSegmentedControl
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import com.smartvision.gallery.ui.liquidglass.LocalSegmentedControlBackdropState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore

// ponytail: 全局并发限流 ceiling=4。SMB 缩略图走网络+解码，无限制并发会
// 同时 readBytes 多个大文件 → 内存峰值爆 + 主线程卡死。升级路径：改用 Coil
// SmbFetcher 流式 + 内存 LRU，此 Semaphore 即可移除。
private val smbThumbSemaphore = Semaphore(4)

/**
 * SMB 共享文件夹照片网格。
 *
 * 功能：
 * 1. 首次加载时显示扫描进度
 * 2. 扫描完成后显示分类标签（全部/照片/视频/动图）
 * 3. 点击缩略图进入全屏查看
 * 4. 下拉刷新
 */
@Composable
fun SmbMediaGrid(
    device: SmbDevice,
    initialPath: String,
    shareManager: SmbShareManager,
    albumIndex: SmbAlbumIndex,
    thumbnailCache: SmbThumbnailCache,
    onPhotoClick: (SmbMediaFile, List<SmbMediaFile>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as com.smartvision.gallery.SmartVisionApp

    // pill（AppRoot FloatingTopBarPill / CompactTitleBar）沉浸式悬浮：从屏幕顶
    // 12dp 起、44dp 高 → 底=56dp。快选 bar 顶 = pill 底 + 8dp 呼吸 = 64dp。
    // 全部坐标相对屏幕顶（同 TimelinePage：无 statusBars padding，grid 直通
    // Y=0，chrome 浮于图片上方采样真实像素）。若加 windowInsetsPadding(statusBars)
    // 会把 grid 顶推到 statusBars 下 → 图片到不了 pill 下方，pill 底下是空白
    // 而非图片，即"标题栏无法完全浮在图片上面"。故此处不用任何 insets。
    val barTop = 64.dp
    // 首行图片从 bar 底（capsule ~34dp）+ 6dp 呼吸之下开始
    val gridContentTop = barTop + 40.dp

    // 状态
    var mediaFiles by remember { mutableStateOf<List<SmbMediaFile>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var scanProgress by remember { mutableStateOf(ScanProgress()) }
    var selectedFilter by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 扫描（LaunchedEffect body 自身即 CoroutineScope，用于取消检查）
    LaunchedEffect(device, initialPath) {
        isScanning = true
        errorMessage = null
        try {
            mediaFiles = albumIndex.scan(device, initialPath, scope = this, onProgress = { progress ->
                scanProgress = progress
            })
        } catch (e: Exception) {
            errorMessage = "扫描失败: ${e.message}"
        }
        isScanning = false
    }

    // 过滤后的文件
    val filteredFiles = remember(mediaFiles, selectedFilter) {
        when (selectedFilter) {
            0 -> mediaFiles // 全部
            1 -> mediaFiles.filter { it.type == SmbMediaType.IMAGE }
            2 -> mediaFiles.filter { it.type == SmbMediaType.VIDEO }
            3 -> mediaFiles.filter { it.type == SmbMediaType.GIF }
            else -> mediaFiles
        }
    }

    // 分类统计
    val imageCount = mediaFiles.count { it.type == SmbMediaType.IMAGE }
    val videoCount = mediaFiles.count { it.type == SmbMediaType.VIDEO }
    val gifCount = mediaFiles.count { it.type == SmbMediaType.GIF }

    // 全屏分支自身占满窗口，需要自绘 insets。
    // 顶栏由 AppRoot FloatingTopBarPill 接管（Z=1 真实屏幕采样液态玻璃），
    // 内容下移让出 pill（底部 ~56dp + 呼吸间隙），与首页/主页面一致。

    // 嵌套局部 backdrop：捕获网格区域（照片缩略图）供上方 segmented control
    // 折射真实像素 → 字体逐像素改色（同 LibraryOverlay 嵌套 layerBackdrop 模式）。
    // segmented control 必须在本捕获 Box 之外（sibling），否则 drawBackdrop
    // 采样含自身的层 → ColorOS 16 RenderNode 自采样崩溃。
    val gridBackdrop = rememberLayerBackdrop()

    // backdropOnly twin：静态 cyan 文字被 hiddenSegBackdrop 捕获，写入全局
    // segBackdropState → AppRoot Z=2 SegmentedControlLensOverlay 折射 cyan 文字
    // 层 → 长按透镜扫过时字体实时像素级变色（同 LibraryOverlay 三件套）。
    // 若缺此 twin，segBackdropState 为空 → lens 只折射 pageBackdrop，字体不变色。
    val segBackdropState = LocalSegmentedControlBackdropState.current
    val hiddenSegBackdrop = rememberLayerBackdrop()
    // DisposableEffect：写入 + dispose 清理。若用 LaunchedEffect 只写不清，
    // 打开 SmbPhotoViewer 时本组件被移除 → hiddenSegBackdrop 的 GraphicsLayer
    // 释放，但 segBackdropState 仍指向它 → SegmentedControlLensOverlay（AppRoot
    // Z=2 常驻）每帧 drawBackdrop(已释放层) → RenderNode::prepareTreeImpl 无限
    // 递归 → RenderThread 栈溢出崩溃（549 帧 prepareTreeImpl，用户"打开照片闪退"）。
    // 用 === 比较确保只清自己写入的值，不清其他写入者（LibraryOverlay）的。
    DisposableEffect(hiddenSegBackdrop) {
        segBackdropState.value = hiddenSegBackdrop
        onDispose {
            if (segBackdropState.value === hiddenSegBackdrop) segBackdropState.value = null
        }
    }

    // 分类标签选项（twin 与可见控件共用，保持像素一致）
    val segmentOptions = remember(mediaFiles, imageCount, videoCount, gifCount) {
        listOf(
            "全部(${mediaFiles.size})",
            "照片($imageCount)",
            "视频($videoCount)",
            "动图($gifCount)",
        )
    }

    // 无 windowInsetsPadding(statusBars)：同 TimelinePage，grid 直通屏幕顶 Y=0，
    // 顶部 pill 浮于图片上方采样真实像素。加 insets padding 会形成 opaque 保留区
    // div，图片到不了 pill 下方 → 标题栏无法完全浮在图片上（用户反馈）。
    Box(
        modifier = modifier.fillMaxSize()
    ) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 扫描进度
        if (isScanning) {
            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 64.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "正在扫描...",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "已发现 ${scanProgress.foundCount} 个文件",
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93),
                        )
                    }
                }
            }
        }

        // 错误信息
        if (errorMessage != null) {
            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 64.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF3B30),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = errorMessage!!,
                        fontSize = 13.sp,
                        color = Color(0xFFFF3B30),
                    )
                }
            }
        }

        // 网格 + 分类标签叠加区。与 LibraryOverlay 同构：Z=0 网格被
        // gridBackdrop 捕获，twin/visible 浮于网格上方（align TopStart）
        // 采样 gridBackdrop 真实缩略图像素 → 逐像素变色。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // ---- Z=0：网格捕获区。layerBackdrop 把缩略图网格捕获进
            // gridBackdrop，上方的 twin/visible 折射此层真实像素。----
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(gridBackdrop)
            ) {
                // 空状态
                if (!isScanning && filteredFiles.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.Photo,
                                contentDescription = null,
                                tint = Color(0xFF8E8E93),
                                modifier = Modifier.size(48.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "没有找到媒体文件",
                                fontSize = 16.sp,
                                color = Color(0xFF8E8E93),
                            )
                        }
                    }
                }

                // 照片网格
                if (!isScanning && filteredFiles.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        // 图片直接到顶（Y=0），顶部 pill + 分类 bar 浮在图片上方
                        // 做液态玻璃折射（同 TimelinePage: 纯 contentPadding，无
                        // opaque div）。首行从两 chrome 之下开始（bar 底+呼吸）。
                        contentPadding = PaddingValues(top = gridContentTop, bottom = 8.dp, start = 3.dp, end = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filteredFiles, key = { it.path }) { file ->
                            SmbThumbnailCard(
                                file = file,
                                device = device,
                                thumbnailCache = thumbnailCache,
                                onClick = { onPhotoClick(file, filteredFiles) },
                            )
                        }
                    }
                }
            }

            // ---- 分类标签（扫描完成后显示），浮于网格上方 ----
            if (!isScanning && mediaFiles.isNotEmpty()) {
                // Z=1: backdropOnly twin —— 静态 cyan 文字被 hiddenSegBackdrop
                // 捕获，供 SegmentedControlLensOverlay 折射（lens 扫过时字体
                // 逐像素变色）。backdropOverride = gridBackdrop（真实网格缩略图
                // 像素）：本组件位于 AppRoot liquidBackdrop capture 子树内
                // （NavHost Z=0），缺省 backdrop 会回退
                // LocalLiquidGlassScreenBackdrop（=liquidBackdrop），twin 的
                // drawBackdrop 采样含自身的未完成 capture → RenderNode
                // prepareTreeImpl 无限递归 → 栈溢出（AppleComponents:654-657,
                // 705-713）。gridBackdrop 捕获网格 Box 子树，twin 是其 sibling
                // （不在捕获内）→ 无自采样，安全。twin 与网格区域重叠，折射
                // 真实缩略图 → hiddenSegBackdrop 捕获含真实像素的 frosted
                // track → lens 扫过时字体逐像素变色。----
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = barTop) // 屏幕顶 pill 底(56dp) + 8dp 呼吸 - statusBars
                        .layerBackdrop(hiddenSegBackdrop)
                ) {
                    iOSSegmentedControl(
                        options = segmentOptions,
                        selected = selectedFilter,
                        onSelect = {},
                        backdropOnly = true,
                        backdropOverride = gridBackdrop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // ---- Z=2: 可见交互 segmented control（折射 gridBackdrop
                // 真实像素，覆盖 twin，与 twin 同位置同尺寸）----
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = barTop)
                ) {
                    iOSSegmentedControl(
                        options = segmentOptions,
                        selected = selectedFilter,
                        onSelect = { selectedFilter = it },
                        backdropOverride = gridBackdrop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun SmbThumbnailCard(
    file: SmbMediaFile,
    device: SmbDevice,
    thumbnailCache: SmbThumbnailCache,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext

    // 尝试从缓存加载缩略图
    val cachedBitmap = remember(file.path, device) {
        thumbnailCache.get(device.host, device.shareName, file.path)
    }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (cachedBitmap != null) {
                // 缓存命中：直接显示
                Image(
                    bitmap = cachedBitmap.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 缓存未命中：通过 SMB 降采样加载（写回缓存，下次命中）
                CoilAsyncSmbThumb(
                    file = file,
                    device = device,
                    thumbnailCache = thumbnailCache,
                    contentDescription = file.name,
                )
            }

            // 视频/动图标记
            if (file.type == SmbMediaType.VIDEO || file.type == SmbMediaType.GIF) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (file.type == SmbMediaType.VIDEO) "视频" else "GIF",
                        fontSize = 10.sp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 通过 SMB 加载缩略图。
 *
 * 卡死根因修复（文件多时）：
 * 1. 全尺寸解码 → 48MP 原图 decodeByteArray = 192MB bitmap，并发叠加直接 OOM/主线程冻结。
 *    改为先读 bounds 再 inSampleSize 降采样到 ≤512px（与磁盘缓存一致）。
 * 2. 解码结果写回 [SmbThumbnailCache]，滚动离开再回来命中磁盘缓存，不重复网络读。
 * 3. [smbThumbSemaphore] 限制同时进行的 SMB 读取（SMB 连接池 maxBuffered=16，
 *    无限并发会耗尽连接 + 卡死 keepalive/其他操作）。
 */
@Composable
private fun CoilAsyncSmbThumb(
    file: SmbMediaFile,
    device: SmbDevice,
    thumbnailCache: SmbThumbnailCache,
    contentDescription: String?,
) {
    val context = LocalContext.current
    val shareManager = remember { SmbShareManager.getInstance(context) }

    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(file.path, device) {
        isLoading = true
        bitmap = try {
            loadSmbThumbnail(file, device, thumbnailCache, shareManager)
        } catch (e: Exception) {
            android.util.Log.w("SmbThumb", "thumb load failed: ${file.path}", e)
            null
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = Color(0xFF8E8E93),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/** 缩略图目标尺寸（与磁盘缓存 512x512 一致） */
private const val THUMB_TARGET = 512

/**
 * 降采样加载 SMB 缩略图（IO 线程，并发受限）并写回磁盘缓存。
 * 先 inJustDecodeBounds 读尺寸 → 算 inSampleSize → 降采样解码，
 * 避免全尺寸解码（48MP 原图 = 192MB bitmap）导致的 OOM/主线程冻结。
 */
private suspend fun loadSmbThumbnail(
    file: SmbMediaFile,
    device: SmbDevice,
    thumbnailCache: SmbThumbnailCache,
    shareManager: SmbShareManager,
): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
    smbThumbSemaphore.acquire()
    try {
        val ctx = shareManager.getCifsContext(device)
        val resource = ctx.get(device.toSmbUrl(file.path)) as jcifs.SmbResource
        val bytes = resource.openInputStream().use { it.readBytes() }
        if (bytes.isEmpty()) return@withContext null

        // 1) bounds：只读尺寸，不分配像素
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        // 2) 降采样：长边 ≤ THUMB_TARGET
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, THUMB_TARGET)
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (bmp != null) {
            thumbnailCache.put(device.host, device.shareName, file.path, bmp)
        }
        bmp
    } finally {
        smbThumbSemaphore.release()
    }
}

/** 计算 2 的幂 inSampleSize：使最长边 ≤ target */
private fun calcSampleSize(width: Int, height: Int, target: Int): Int {
    var sample = 1
    var largest = maxOf(width, height)
    while (largest / 2 >= target) {
        largest /= 2
        sample *= 2
    }
    return sample
}