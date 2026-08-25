package com.smartvision.gallery.ui.cloud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.cloud.CloudSyncCoordinator
import com.smartvision.gallery.cloud.FakeLocalCloud
import com.smartvision.gallery.cloud.RemoteMedia
import com.smartvision.gallery.cloud.SyncState
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSurface
import com.smartvision.gallery.ui.liquidglass.LocalTopBarState
import com.smartvision.gallery.ui.liquidglass.TopBarConfig
import com.smartvision.gallery.ui.liquidglass.TopBarVariant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cloud sync panel — iOS Settings style with progress + remote file list.
 *
 * V1.0 wires the user to [FakeLocalCloud] (no OAuth needed). Plug in a real
 * provider by swapping the `provider` field — the UI doesn't change.
 */
@Composable
fun CloudSyncPage(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val scope = rememberCoroutineScope()

    val provider = remember { FakeLocalCloud(app) }
    val coordinator = remember { CloudSyncCoordinator(app, provider) }
    val state by coordinator.state.collectAsState()
    val uploaded by coordinator.uploadedCount.collectAsState()
    var remoteFiles by remember { mutableStateOf<List<RemoteMedia>>(emptyList()) }

    suspend fun refreshList() {
        remoteFiles = provider.list()
    }

    LaunchedEffect(Unit) { refreshList() }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val topBar = LocalTopBarState.current
        LaunchedEffect(Unit) {
            topBar.value = TopBarConfig(
                title = "云同步",
                variant = TopBarVariant.COMPACT,
                onBack = onBack,
            )
        }
        Spacer(Modifier.height(56.dp))

        // Sync action bar
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("本地模拟云", fontWeight = FontWeight.SemiBold)
                    Text(
                        "V1.x 将接入 Google Photos / 阿里云盘 / 腾讯微云",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "${remoteFiles.size} 项已上传",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            val items = app.mediaRepository.observeTimeline().first()
                            coordinator.sync(items)
                            refreshList()
                        }
                    },
                    enabled = state !is SyncState.Uploading
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                    Text(" 同步")
                }
            }
        }

        // Progress bar
        when (val s = state) {
            is SyncState.Uploading -> {
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Text(
                    "上传中: ${s.name}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            is SyncState.Success -> {
                Text(
                    "同步完成 · $uploaded 项",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            is SyncState.Failed -> {
                Text(
                    "同步失败: ${s.error}",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            else -> Unit
        }

        if (remoteFiles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有上传的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(remoteFiles, key = { it.remoteId }) { remote ->
                    LiquidGlassSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(remote.name, fontSize = 14.sp)
                                Text(
                                    "${remote.sizeBytes / 1024} KB · ${formatDate(remote.uploadedAt)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDate(ts: Long): String {
    if (ts <= 0) return "—"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return fmt.format(Date(ts))
}