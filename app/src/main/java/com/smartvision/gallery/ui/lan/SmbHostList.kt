package com.smartvision.gallery.ui.lan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.lan.smb.SmbDevice
import com.smartvision.gallery.lan.smb.SmbEntry
import com.smartvision.gallery.lan.smb.SmbShareManager
import com.smartvision.gallery.ui.apple.iOSListRow
import com.smartvision.gallery.ui.apple.iOSListSection
import com.smartvision.gallery.ui.apple.iOSRowTrailing
import kotlinx.coroutines.launch

/**
 * 网络位置列表。
 *
 * 显示已添加的 SMB 网络位置，每个位置可展开查看共享文件夹内容。
 * 使用 LiquidGlassCard + iOSListRow 风格。
 */
@Composable
fun SmbHostList(
    devices: List<SmbDevice>,
    shareManager: SmbShareManager,
    onAddClick: () -> Unit,
    onDeviceClick: (SmbDevice, String) -> Unit, // device, initialPath
    onRemoveDevice: (SmbDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    iOSListSection(
        header = "网络位置",
        footer = "通过 SMB 协议访问 Windows 共享文件夹，需要 Windows 上已配置共享且网络可达",
        content = {
            // 添加按钮
            iOSListRow(
                title = "添加网络位置",
                leading = Icons.Outlined.Add,
                leadingTint = Color(0xFF007AFF),
                trailing = iOSRowTrailing.Chevron,
                onClick = onAddClick,
            )
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(start = 64.dp),
                thickness = 0.5.dp,
                color = Color.White.copy(alpha = 0.10f),
            )

            if (devices.isEmpty()) {
                Text(
                    text = "暂无网络位置，点击上方添加",
                    fontSize = 14.sp,
                    color = Color(0xFF8E8E93),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                devices.forEachIndexed { index, device ->
                    DeviceItem(
                        device = device,
                        shareManager = shareManager,
                        onDeviceClick = onDeviceClick,
                        onRemove = { onRemoveDevice(device) },
                    )
                    if (index < devices.size - 1) {
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(start = 64.dp),
                            thickness = 0.5.dp,
                            color = Color.White.copy(alpha = 0.10f),
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun DeviceItem(
    device: SmbDevice,
    shareManager: SmbShareManager,
    onDeviceClick: (SmbDevice, String) -> Unit,
    onRemove: () -> Unit,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var subDirectories by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDeviceClick(device, "") }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Computer,
                contentDescription = null,
                tint = Color(0xFF007AFF),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val title = device.displayName.ifBlank { device.host }
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                // 副标题避免与主标题重复（displayName 默认=host 时显示共享名）
                Text(
                    text = if (title == device.host) "/${device.shareName}" else device.host,
                    fontSize = 13.sp,
                    color = Color(0xFF8E8E93),
                )
            }
            IconButton(onClick = { isExpanded = !isExpanded }) {
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess
                        else Icons.Outlined.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = Color(0xFF8E8E93),
                )
            }
        }

        // 展开的共享文件夹列表
        if (isExpanded) {
            LaunchedEffect(device) {
                isLoading = true
                subDirectories = shareManager.listFiles(device)
                isLoading = false
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            } else {
                subDirectories
                    .filter { it.isDirectory }
                    .forEach { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceClick(device, dir.path) }
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = Color(0xFFFFCC00),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = dir.name,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
            }
        }
    }
}

/**
 * 空状态提示：无网络位置时显示。
 */
@Composable
fun SmbEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = Color(0xFF8E8E93),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "暂无网络位置",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF8E8E93),
        )
        Text(
            text = "添加 Windows 共享文件夹后即可浏览",
            fontSize = 13.sp,
            color = Color(0xFF8E8E93),
        )
    }
}