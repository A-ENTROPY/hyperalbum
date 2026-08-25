package com.smartvision.gallery.ui.lan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.lan.smb.SmbCredentials
import com.smartvision.gallery.lan.smb.SmbDevice
import com.smartvision.gallery.lan.smb.SmbShareManager
import com.smartvision.gallery.ui.apple.iOSButton
import com.smartvision.gallery.ui.apple.iOSButtonStyle
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import kotlinx.coroutines.launch

/**
 * 添加网络位置对话框。
 *
 * 标准 SMB 浏览流程：只填主机 + 凭据 → 点"浏览共享"枚举主机上的共享 → 点选一个 → 连接。
 * 共享名不再手填（与 iOS Files / Solid Explorer 一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSmbHostDialog(
    shareManager: SmbShareManager,
    onDismiss: () -> Unit,
    onConnect: (SmbDevice, onResult: (Boolean) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    // 枚举到的共享列表；null=未浏览
    var shares by remember { mutableStateOf<List<String>?>(null) }
    var selectedShare by remember { mutableStateOf("") }
    var isBrowsing by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 构造临时 device（shareName 空），仅用于枚举共享
    fun hostDevice() = SmbDevice(
        host = host.trim(),
        shareName = "",
        credentials = if (username.isNotBlank()) {
            SmbCredentials(username.trim(), password)
        } else null,
    )

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = Color.Transparent,
    ) {
        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(20.dp),
        ) {
            // LiquidGlassCard 内部是 Box（多子内容会原位叠放），多行内容必须用 Column 承载
            Column {
                Text(
                    text = "添加网络位置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))

                // 主机地址
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; errorMessage = null },
                    label = { Text("IP 地址 / 主机名") },
                    placeholder = { Text("192.168.1.100") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))

                // 用户名（Windows 本机账号）
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMessage = null },
                    label = { Text("用户名（Windows 本机账号，11/10 默认需填写）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // 密码
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))

                // 浏览共享按钮
                iOSButton(
                    text = if (isBrowsing) "正在浏览共享..." else "浏览共享",
                    style = iOSButtonStyle.Secondary,
                    onClick = {
                        if (isBrowsing) return@iOSButton
                        if (host.isBlank()) {
                            errorMessage = "请填写主机地址"
                            return@iOSButton
                        }
                        isBrowsing = true
                        errorMessage = null
                        selectedShare = ""
                        shares = null
                        scope.launch {
                            try {
                                shares = shareManager.listShares(hostDevice())
                            } catch (e: Exception) {
                                errorMessage = "浏览失败：${e.message}"
                            }
                            isBrowsing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // 共享列表（浏览成功后）
                if (shares != null) {
                    Spacer(Modifier.height(12.dp))
                    val shareList = shares!!
                    if (shareList.isEmpty()) {
                        Text(
                            text = "未发现可访问的共享文件夹",
                            fontSize = 14.sp,
                            color = Color(0xFF8E8E93),
                        )
                    } else {
                        Text(
                            text = "选择共享文件夹：",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(8.dp))
                        // 列表高度受限 + 内部滚动，保证"连接"按钮始终可见
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            shareList.forEach { name ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedShare = name; errorMessage = null }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Folder,
                                        contentDescription = null,
                                        tint = if (selectedShare == name) Color(0xFF007AFF) else Color(0xFF8E8E93),
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = name,
                                        fontSize = 15.sp,
                                        color = if (selectedShare == name) Color(0xFF007AFF) else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (selectedShare == name) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }
                }

                // 错误信息
                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = Color(0xFFFF3B30),
                        fontSize = 13.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 连接按钮
                iOSButton(
                    text = if (isConnecting) "连接中..." else "连接",
                    onClick = {
                        if (isConnecting) return@iOSButton
                        if (host.isBlank()) {
                            errorMessage = "请填写主机地址"
                            return@iOSButton
                        }
                        if (selectedShare.isBlank()) {
                            errorMessage = "请先浏览并选择一个共享文件夹"
                            return@iOSButton
                        }
                        isConnecting = true
                        errorMessage = null
                        val device = SmbDevice(
                            host = host.trim(),
                            shareName = selectedShare.trim(),
                            displayName = selectedShare.trim(),
                            credentials = if (username.isNotBlank()) {
                                SmbCredentials(username.trim(), password)
                            } else null,
                        )
                        onConnect(device) { success ->
                            isConnecting = false
                            if (!success) {
                                errorMessage = "连接被拒：请核对用户名、密码是否与 Windows 本机账户一致"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(16.dp)) // 底部安全区
    }
}
