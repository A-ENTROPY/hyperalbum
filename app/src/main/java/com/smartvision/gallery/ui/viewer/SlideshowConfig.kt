package com.smartvision.gallery.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.ui.liquidglass.LiquidGlassAlertDialog

data class SlideshowConfig(
    val intervalMs: Long = 5_000L,
    val loop: Boolean = true,
)

@Composable
fun SlideshowDialog(
    initial: SlideshowConfig,
    onConfirm: (SlideshowConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var interval by remember { mutableStateOf(initial.intervalMs) }
    var loop by remember { mutableStateOf(initial.loop) }

    LiquidGlassAlertDialog(
        onDismiss = onDismiss,
        title = "幻灯片设置",
        confirmText = "开始",
        dismissText = "取消",
        onConfirm = { onConfirm(SlideshowConfig(interval, loop)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("切换间隔", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
                val intervals = listOf(3_000L to "3 秒", 5_000L to "5 秒", 10_000L to "10 秒")
                intervals.forEach { (ms, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = interval == ms, onClick = { interval = ms })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = interval == ms,
                            onClick = { interval = ms },
                            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.onSurface,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            )
                        )
                        Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("循环播放", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(checked = loop, onCheckedChange = { loop = it })
                }
            }
        }
    )
}
