package com.smartvision.gallery.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.ui.apple.iOSListRow
import com.smartvision.gallery.ui.apple.iOSListSection
import com.smartvision.gallery.ui.apple.iOSRowTrailing
import com.smartvision.gallery.ui.liquidglass.LocalTopBarState
import com.smartvision.gallery.ui.liquidglass.TopBarConfig
import com.smartvision.gallery.ui.liquidglass.TopBarVariant
import com.smartvision.gallery.util.AppLog
import com.smartvision.gallery.util.CloudProvider
import kotlinx.coroutines.launch

/**
 * Cloud provider picker. Persists the user's choice to [com.smartvision.gallery.util.AppPrefs]
 * so the next session remembers it. V1.x only fully supports [CloudProvider.NONE] and
 * [CloudProvider.LOCAL_FAKE] (the others are placeholder entries that show the planned
 * roadmap without crashing).
 */
@Composable
fun CloudProviderPickerPage(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val current by prefs.cloudProvider.collectAsState(initial = CloudProvider.NONE)

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val topBar = LocalTopBarState.current
        LaunchedEffect(Unit) {
            topBar.value = TopBarConfig(
                title = "云服务商",
                variant = TopBarVariant.COMPACT,
                onBack = onBack,
            )
        }
        Spacer(Modifier.height(56.dp))

        iOSListSection(
            header = "可用服务",
            footer = "V1.x 暂仅支持本地模拟云（用于演示完整流程），其它服务将在 V1.1 接入。",
            content = {
                CloudProvider.entries.forEach { provider ->
                    val isSelected = provider == current
                    iOSListRow(
                        title = provider.displayName,
                        subtitle = when (provider) {
                            CloudProvider.NONE -> "不上传；只使用本机相册"
                            CloudProvider.LOCAL_FAKE -> "使用 App 内部存储模拟云端（推荐试用）"
                            CloudProvider.GOOGLE_PHOTOS -> "需要 Google 账号授权 (V1.1)"
                            CloudProvider.ALIYUN_DRIVE -> "需要阿里云盘 OAuth (V1.1)"
                            CloudProvider.TENCENT_WECLOUD -> "需要腾讯微云 OAuth (V1.1)"
                        },
                        leading = Icons.Outlined.Cloud,
                        leadingTint = provider.tintColor(),
                        trailing = if (isSelected) {
                            iOSRowTrailing.Switch(true) {
                                // Already selected — no-op.
                            }
                        } else {
                            iOSRowTrailing.Chevron
                        },
                        onClick = {
                            scope.launch {
                                prefs.setCloudProvider(provider)
                                AppLog.d("CloudProviderPicker", "set=${provider.id}")
                            }
                        }
                    )
                }
            }
        )
    }
}

private fun CloudProvider.tintColor(): androidx.compose.ui.graphics.Color = when (this) {
    CloudProvider.NONE -> androidx.compose.ui.graphics.Color(0xFF8E8E93)
    CloudProvider.LOCAL_FAKE -> androidx.compose.ui.graphics.Color(0xFF34C759)
    CloudProvider.GOOGLE_PHOTOS -> androidx.compose.ui.graphics.Color(0xFF4285F4)
    CloudProvider.ALIYUN_DRIVE -> androidx.compose.ui.graphics.Color(0xFFFF6A00)
    CloudProvider.TENCENT_WECLOUD -> androidx.compose.ui.graphics.Color(0xFF007BFF)
}