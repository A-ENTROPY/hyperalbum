package com.smartvision.gallery.ui.lan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.lan.LanPhotoDevice
import com.smartvision.gallery.lan.LanShareManager
import com.smartvision.gallery.lan.RemotePhoto
import com.smartvision.gallery.lan.smb.SmbAlbumIndex
import com.smartvision.gallery.lan.smb.SmbCredentialStore
import com.smartvision.gallery.lan.smb.SmbDevice
import com.smartvision.gallery.lan.smb.SmbMediaFile
import com.smartvision.gallery.lan.smb.SmbShareManager
import com.smartvision.gallery.lan.smb.SmbThumbnailCache
import com.smartvision.gallery.ui.apple.iOSListSection
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import com.smartvision.gallery.ui.liquidglass.LocalTopBarState
import com.smartvision.gallery.ui.liquidglass.TopBarConfig
import com.smartvision.gallery.ui.liquidglass.TopBarVariant
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.launch

/**
 * 局域网共享页面。
 *
 * 功能：
 * 1. 本机服务器状态（IP:端口）
 * 2. 网络位置 (SMB) — 访问 Windows 共享文件夹
 * 3. 发现并列出局域网其他设备
 * 4. 选择设备后浏览其照片
 * 5. 下载远程照片到本机
 */
@Composable
fun LanSharePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SmartVisionApp
    val scope = rememberCoroutineScope()
    val lanManager = remember { LanShareManager.getInstance(app) }

    // 状态：本机服务器
    var isServerRunning by remember { mutableStateOf(false) }
    var serverIp by remember { mutableStateOf("") }
    var serverPort by remember { mutableStateOf(0) }
    val discoveredDevices = remember { mutableStateListOf<LanPhotoDevice>() }
    var selectedDevice by remember { mutableStateOf<LanPhotoDevice?>(null) }
    var remotePhotos by remember { mutableStateOf<List<RemotePhoto>>(emptyList()) }
    var isLoadingPhotos by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }

    // 状态：SMB 网络位置
    val smbDevices = remember { mutableStateListOf<SmbDevice>() }
    val shareManager = remember { SmbShareManager.getInstance(context) }
    val albumIndex = remember { SmbAlbumIndex(shareManager) }
    val thumbnailCache = remember { SmbThumbnailCache(context) }
    // 凭据加密持久化；keystore 不可用时降级为仅内存（runCatching 保证不崩）
    val credentialStore = remember {
        runCatching { SmbCredentialStore(context) }.getOrNull()
    }
    // 启动时从加密存储恢复已保存的网络位置
    LaunchedEffect(Unit) {
        smbDevices.addAll(credentialStore?.loadDevices() ?: emptyList())
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var isSmbMediaGrid by remember { mutableStateOf(false) }
    var isSmbPhotoViewer by remember { mutableStateOf(false) }
    var selectedSmbDevice by remember { mutableStateOf<SmbDevice?>(null) }
    var selectedSmbPath by remember { mutableStateOf("") }
    var smbPhotoFiles by remember { mutableStateOf<List<SmbMediaFile>>(emptyList()) }
    var smbPhotoIndex by remember { mutableStateOf(0) }

    // TopBar：与首页共用 FloatingTopBarPill（Z=1 真实屏幕采样液态玻璃）。
    // grid/viewer 是页内 state 分支，顶栏标题与返回行为须随层级切换，
    // 否则 back 会 popBackStack 整页退出。viewer 打开时标题交给
    // SmbPhotoViewer 自己发布（随滑动照片更新文件名）。
    val topBar = LocalTopBarState.current
    LaunchedEffect(isSmbMediaGrid, isSmbPhotoViewer, selectedSmbDevice) {
        topBar.value = when {
            isSmbPhotoViewer -> topBar.value // 保持 viewer 发布的文件名标题
            isSmbMediaGrid -> TopBarConfig(
                title = selectedSmbDevice?.displayName
                    ?: selectedSmbDevice?.shareName
                    ?: "局域网共享",
                variant = TopBarVariant.COMPACT,
                onBack = { isSmbMediaGrid = false },
            )
            else -> TopBarConfig(
                title = "局域网共享",
                variant = TopBarVariant.COMPACT,
                onBack = onBack,
            )
        }
    }

    // 启动时刷新设备列表
    LaunchedEffect(isServerRunning) {
        if (isServerRunning) {
            serverIp = lanManager.getLocalIpAddress()
            serverPort = lanManager.serverPort
        }
    }

    // 周期性刷新设备列表
    LaunchedEffect(isServerRunning) {
        while (isServerRunning) {
            discoveredDevices.clear()
            discoveredDevices.addAll(lanManager.getDiscoveredDevices())
            kotlinx.coroutines.delay(3_000)
        }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            lanManager.stop()
        }
    }

    // 手势/系统返回：viewer 是页内 state 分支而非独立 nav destination，
    // 不拦截会直接 popBackStack(LAN_SHARE) 跳到设置页（手势退出 bug）。
    // 注意：viewer 打开时 isSmbMediaGrid 仍为 true（viewer 叠在 grid 上），
    // 两个 handler 都 enabled 时后者胜会关错层级，故 grid handler 排除 viewer 态。
    BackHandler(enabled = isSmbMediaGrid && !isSmbPhotoViewer) {
        isSmbMediaGrid = false
    }
    BackHandler(enabled = isSmbPhotoViewer) {
        isSmbPhotoViewer = false
    }

    // SMB 全屏查看器
    if (isSmbPhotoViewer && smbPhotoFiles.isNotEmpty() && selectedSmbDevice != null) {
        SmbPhotoViewer(
            files = smbPhotoFiles,
            initialIndex = smbPhotoIndex,
            device = selectedSmbDevice!!,
            shareManager = shareManager,
            onBack = { isSmbPhotoViewer = false },
        )
        return
    }

    // SMB 照片网格
    if (isSmbMediaGrid && selectedSmbDevice != null) {
        SmbMediaGrid(
            device = selectedSmbDevice!!,
            initialPath = selectedSmbPath,
            shareManager = shareManager,
            albumIndex = albumIndex,
            thumbnailCache = thumbnailCache,
            onPhotoClick = { file, allFiles ->
                isSmbPhotoViewer = true
                smbPhotoIndex = allFiles.indexOf(file)
                smbPhotoFiles = allFiles
            },
            onBack = { isSmbMediaGrid = false },
        )
        return
    }

    // 主页面
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // 上内边距对齐 AlbumListPage 等详情页（TopBar 高度）；底部留给 TabBar
            contentPadding = PaddingValues(top = 96.dp, bottom = 140.dp),
        ) {
            // ---- 服务器控制 ----
            item {
                ServerControlCard(
                    isRunning = isServerRunning,
                    ip = serverIp,
                    port = serverPort,
                    onStart = {
                        lanManager.start()
                        isServerRunning = true
                        serverIp = lanManager.getLocalIpAddress()
                        serverPort = lanManager.serverPort
                        AppLog.i("LanShare", "Server started: $serverIp:$serverPort")
                    },
                    onStop = {
                        lanManager.stop()
                        isServerRunning = false
                        discoveredDevices.clear()
                        selectedDevice = null
                        remotePhotos = emptyList()
                        AppLog.i("LanShare", "Server stopped")
                    }
                )
            }

            // ---- 网络位置 (SMB) ----
            item {
                SmbHostList(
                    devices = smbDevices.toList(),
                    shareManager = shareManager,
                    onAddClick = {
                        showAddDialog = true
                    },
                    onDeviceClick = { device, path ->
                        selectedSmbDevice = device
                        selectedSmbPath = path
                        isSmbMediaGrid = true
                    },
                    onRemoveDevice = { device ->
                        smbDevices.remove(device)
                        credentialStore?.saveDevices(smbDevices.toList())
                    },
                )
            }

            // ---- 添加网络位置对话框 ----
            if (showAddDialog) {
                item {
                    // 对话框通过 ModalBottomSheet 显示，不在此处渲染
                }
            }

            // ---- 已发现设备 ----
            if (isServerRunning) {
                item {
                    DeviceListCard(
                        devices = discoveredDevices.toList(),
                        selectedDevice = selectedDevice,
                        onDeviceSelect = { device ->
                            selectedDevice = device
                            isLoadingPhotos = true
                            scope.launch {
                                remotePhotos = lanManager.client.fetchPhotos(
                                    device.host, device.port
                                )
                                isLoadingPhotos = false
                            }
                        }
                    )
                }
            }

            // ---- 远程照片网格 ----
            if (selectedDevice != null) {
                item {
                    Text(
                        text = "${selectedDevice!!.deviceName} 的照片",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                if (isLoadingPhotos) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else {
                    val chunked = remotePhotos.chunked(3)
                    items(chunked) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (i in 0 until row.size) {
                                val photo = row[i]
                                Box(Modifier.weight(1f)) {
                                    RemotePhotoCard(
                                        photo = photo,
                                        device = selectedDevice!!,
                                        isDownloading = isDownloading,
                                        onDownload = {
                                            isDownloading = true
                                            scope.launch {
                                                val fileName = "${System.currentTimeMillis()}_${photo.displayName}"
                                                val destDir = java.io.File(
                                                    context.getExternalFilesDir(null), "lan_downloads"
                                                ).apply { mkdirs() }
                                                val dest = java.io.File(destDir, fileName)
                                                val ok = lanManager.client.downloadPhoto(
                                                    selectedDevice!!.host,
                                                    selectedDevice!!.port,
                                                    photo.id,
                                                    dest.absolutePath,
                                                )
                                                if (ok) {
                                                    AppLog.i("LanShare", "Downloaded: ${dest.absolutePath}")
                                                    // 通知 MediaStore 扫描新文件
                                                    context.sendBroadcast(
                                                        android.content.Intent(
                                                            android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                                                            android.net.Uri.fromFile(dest)
                                                        )
                                                    )
                                                }
                                                isDownloading = false
                                            }
                                        }
                                    )
                                }
                            }
                            // 补齐空位
                            for (i in 0 until (3 - row.size)) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加网络位置对话框（作为 ModalBottomSheet 显示在顶层）
    if (showAddDialog) {
        AddSmbHostDialog(
            shareManager = shareManager,
            onDismiss = { showAddDialog = false },
            onConnect = { device, onResult ->
                scope.launch {
                    try {
                        // 连接验证：列出根目录，成功才保存
                        shareManager.listFiles(device)
                        smbDevices.add(device)
                        credentialStore?.saveDevices(smbDevices.toList())
                        showAddDialog = false
                        AppLog.i("SmbShare", "Connected to ${device.host}/${device.shareName}")
                        onResult(true)
                    } catch (e: Exception) {
                        AppLog.w("SmbShare", "Connection failed: ${e.message}")
                        onResult(false)
                    }
                }
            },
        )
    }
}

// ---- 服务器控制卡片 ----
@Composable
private fun ServerControlCard(
    isRunning: Boolean,
    ip: String,
    port: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Wifi,
                contentDescription = null,
                tint = if (isRunning) Color(0xFF34C759) else Color(0xFF8E8E93),
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "本机服务器",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (isRunning) {
                    Text(
                        text = "运行中 · http://$ip:$port",
                        fontSize = 13.sp,
                        color = Color(0xFF34C759),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "未启动",
                        fontSize = 13.sp,
                        color = Color(0xFF8E8E93),
                    )
                }
            }
            Button(
                onClick = if (isRunning) onStop else onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) Color(0xFFFF3B30) else Color(0xFF34C759)
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = if (isRunning) "停止" else "启动",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

// ---- 设备列表卡片 ----
@Composable
private fun DeviceListCard(
    devices: List<LanPhotoDevice>,
    selectedDevice: LanPhotoDevice?,
    onDeviceSelect: (LanPhotoDevice) -> Unit,
) {
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Column {
            Text(
                text = "发现的设备 (${devices.size})",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        if (devices.isEmpty()) {
            Text(
                text = "正在搜索同一 Wi-Fi 下的设备...",
                fontSize = 14.sp,
                color = Color(0xFF8E8E93),
            )
        } else {
            devices.forEach { device ->
                val isSelected = device == selectedDevice
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onDeviceSelect(device) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else Color(0xFF8E8E93),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = device.deviceName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${device.host}:${device.port}",
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93),
                        )
                    }
                    Icon(
                        imageVector = Icons.Outlined.Devices,
                        contentDescription = null,
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            }
        }
    }
}

// ---- 远程照片卡片 ----
@Composable
private fun RemotePhotoCard(
    photo: RemotePhoto,
    device: LanPhotoDevice,
    isDownloading: Boolean,
    onDownload: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Cloud,
                    contentDescription = null,
                    tint = Color(0xFF8E8E93),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = photo.displayName,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${photo.width}x${photo.height}",
                    fontSize = 9.sp,
                    color = Color(0xFF8E8E93),
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onDownload,
                    enabled = !isDownloading,
                    modifier = Modifier.size(width = 80.dp, height = 28.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF007AFF)
                    )
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.CloudDownload,
                            contentDescription = "下载",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}