package com.smartvision.gallery.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.ui.apple.iOSListRow
import com.smartvision.gallery.ui.apple.iOSListSection
import com.smartvision.gallery.ui.apple.iOSRowTrailing
import com.smartvision.gallery.ui.components.PerfMode
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import com.smartvision.gallery.ui.liquidglass.LocalTopBarState
import com.smartvision.gallery.ui.liquidglass.TopBarConfig
import com.smartvision.gallery.ui.liquidglass.TopBarVariant
import kotlinx.coroutines.launch

/**
 * Settings page — iOS Settings inspired.
 *
 * Visual hierarchy:
 *   Profile hero card (gradient avatar + identity)
 *   → "显示与外观" / "解码" / "隐私与数据" / "云同步" / "关于"
 *   Each section: rounded glass card with hairline dividers between rows.
 *
 * All toggles persist to [com.smartvision.gallery.util.AppPrefs] via DataStore.
 */
@Composable
fun SettingsPage(
    onOpenPrivacy: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenGlassPlayground: () -> Unit = {},
    onOpenLanShare: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val prefs = app.prefs
    val scope = rememberCoroutineScope()

    val perfMode by prefs.performanceMode.collectAsState(initial = false)
    val hwAccel by prefs.hardwareAccel.collectAsState(initial = true)
    val preferNextGen by prefs.preferNextGen.collectAsState(initial = false)
    LaunchedEffect(perfMode) { PerfMode.lowResThumbnails = perfMode }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        val topBar = LocalTopBarState.current
        LaunchedEffect(Unit) {
            topBar.value = TopBarConfig(
                title = "设置",
                variant = TopBarVariant.LARGE_TITLE,
            )
        }
        // LARGE_TITLE variant paints a 34sp title at the top + the topbar
        // safe-area inset. Spacer must clear both so the page content
        // doesn't slide under (and get occluded by) the title.
        Spacer(Modifier.height(120.dp))

        ProfileHero()

        Spacer(Modifier.height(8.dp))

        // -- 显示与外观 --
        iOSListSection(
            header = "显示与外观",
            content = {
                iOSListRow(
                    title = "性能模式",
                    subtitle = "低端设备降低预览分辨率",
                    leading = Icons.Outlined.Tune,
                    leadingTint = Color(0xFF34C759),
                    trailing = iOSRowTrailing.Switch(perfMode) { checked ->
                        PerfMode.lowResThumbnails = checked
                        scope.launch { prefs.setPerformanceMode(checked) }
                    }
                )
                RowHairline()
                iOSListRow(
                    title = "液态玻璃 Playground",
                    subtitle = "实时调参 8 个子规格",
                    leading = Icons.Outlined.AutoAwesome,
                    leadingTint = Color(0xFFFF2D55),
                    trailing = iOSRowTrailing.Chevron,
                    onClick = onOpenGlassPlayground
                )
            }
        )

        // -- 解码 --
        iOSListSection(
            header = "解码",
            content = {
                iOSListRow(
                    title = "硬件加速",
                    subtitle = "AVIF / JXL 视频路径启用硬件加速",
                    leading = Icons.Outlined.Image,
                    leadingTint = Color(0xFFFF9500),
                    trailing = iOSRowTrailing.Switch(hwAccel) { checked ->
                        scope.launch { prefs.setHardwareAccel(checked) }
                    }
                )
                RowHairline()
                iOSListRow(
                    title = "优先次世代格式",
                    subtitle = "导出时优先 AVIF / JXL",
                    leading = Icons.Outlined.Image,
                    leadingTint = Color(0xFF7B61FF),
                    trailing = iOSRowTrailing.Switch(preferNextGen) { checked ->
                        scope.launch { prefs.setPreferNextGen(checked) }
                    }
                )
            }
        )

        // -- 隐私与数据 --
        iOSListSection(
            header = "隐私与数据",
            content = {
                iOSListRow(
                    title = "隐私空间",
                    subtitle = "需要生物识别解锁",
                    leading = Icons.Outlined.Lock,
                    leadingTint = Color(0xFF34C759),
                    trailing = iOSRowTrailing.Chevron,
                    onClick = onOpenPrivacy
                )
                RowHairline()
                iOSListRow(
                    title = "回收站",
                    subtitle = "30 天后自动清理",
                    leading = Icons.Outlined.Tune,
                    leadingTint = Color(0xFF8E8E93),
                    trailing = iOSRowTrailing.Chevron,
                    onClick = onOpenTrash
                )
            }
        )

        // -- 本地 AI 分析 --
        iOSListSection(
            header = "本地 AI 分析",
            footer = "所有计算在本地完成，照片不会被上传。三模型组合：通用场景识别 + 动漫角色识别 + 域判别。",
            content = {
                val aiPrefs = app.aiPreferences
                val enabled by aiPrefs.aiEnabled.collectAsState(initial = true)
                val processed by aiPrefs.aiProcessed.collectAsState(initial = 0L)
                val total by aiPrefs.aiTotal.collectAsState(initial = 0L)
                val fgEnabled by aiPrefs.foregroundAiEnabled.collectAsState(initial = false)
                val batchSize by aiPrefs.batchSize.collectAsState(initial = 25)
                val cooldownMs by aiPrefs.cooldownMs.collectAsState(initial = 30_000L)
                val subtitle = when {
                    total <= 0L -> "将模型文件 vendor 到 assets/ 后启用"
                    processed >= total -> "分析完成 · 已处理 ${processed} 张"
                    else -> "分析中：${processed} / ${total}"
                }
                iOSListRow(
                    title = "本地 AI 分类",
                    subtitle = subtitle,
                    leading = Icons.Outlined.AutoAwesome,
                    leadingTint = Color(0xFFFF9500),
                    trailing = iOSRowTrailing.Switch(
                        checked = enabled,
                        onChange = { newValue ->
                            scope.launch {
                                aiPrefs.setEnabled(newValue)
                                // 关闭时立即取消当前 worker，提供即时反馈
                                if (!newValue) {
                                    try {
                                        androidx.work.WorkManager.getInstance(app)
                                            .cancelUniqueWork(com.smartvision.gallery.scanner.AiTaggingWorker.WORK_NAME)
                                    } catch (_: Throwable) { }
                                }
                            }
                        }
                    )
                )
                RowHairline()
                iOSListRow(
                    title = "前台运行",
                    subtitle = "关闭后仅在后台处理（推荐）",
                    leading = Icons.Outlined.AutoAwesome,
                    leadingTint = Color(0xFFFF9500),
                    trailing = iOSRowTrailing.Switch(
                        checked = fgEnabled,
                        onChange = { newValue ->
                            scope.launch { aiPrefs.setForegroundEnabled(newValue) }
                        }
                    )
                )
                RowHairline()
                // 单批数量调节
                SettingsSliderRow(
                    title = "单批数量",
                    value = batchSize.toFloat(),
                    onValueChange = { scope.launch { aiPrefs.setBatchSize(it.toInt()) } },
                    valueRange = 5f..100f,
                    steps = 18,
                    formatValue = { "${it.toInt()} 张" }
                )
                RowHairline()
                // 冷却时长调节
                SettingsSliderRow(
                    title = "冷却时长",
                    value = (cooldownMs / 1000f).coerceIn(5f, 300f),
                    onValueChange = { scope.launch { aiPrefs.setCooldownMs((it * 1000).toLong()) } },
                    valueRange = 5f..300f,
                    steps = 58,
                    formatValue = { "${it.toInt()} 秒" }
                )
            }
        )

        // -- 局域网共享 --
        iOSListSection(
            header = "局域网共享",
            footer = "通过局域网在设备间传输照片，无需云端中转。所有数据只经过本地网络。",
            content = {
                iOSListRow(
                    title = "局域网共享",
                    subtitle = "在同一 Wi-Fi 下的设备间浏览和传输照片",
                    leading = Icons.Outlined.Cloud,
                    leadingTint = Color(0xFF34C759),
                    trailing = iOSRowTrailing.Chevron,
                    onClick = onOpenLanShare,
                )
            }
        )

        // -- 关于 --
        iOSListSection(
            header = "关于",
            content = {
                iOSListRow(
                    title = "Liquid Gallery",
                    subtitle = "版本 V1.0.0 · 全格式智能相册",
                    leading = Icons.Outlined.Info,
                    leadingTint = Color(0xFF1F6FEB),
                    trailing = iOSRowTrailing.None
                )
            }
        )

        // 底部余量：需同时清过底部悬浮 TabBar（iOS 26 胶囊 ~84dp）与
        // 手势导航条 inset，否则最后一个 section（关于）会被盖住。
        Spacer(
            Modifier
                .height(140.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}

/**
 * Identity card pinned at the top of the settings page — a frosted-glass
 * surface with a glass avatar bubble. No colored gradients; the
 * `LiquidGlassCard` body samples the wallpaper through it for color so
 * the panel stays consistent with every other glass panel.
 */
@Composable
private fun ProfileHero() {
    LiquidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiquidGlassCard(
                modifier = Modifier.size(56.dp),
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "L",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Liquid Gallery",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                )
                Text(
                    text = "全格式智能相册 · iOS 26 Liquid Glass",
                    fontSize = 13.sp,
                    color = Color(0xFF666666),
                )
            }
            Text(
                text = "V1.0.0",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF666666),
            )
        }
    }
}

/**
 * 设置页中带 slider 的自定义行，匹配 iOSListRow 视觉风格。
 * 在 iOSListSection 的 content 内使用，外面用 RowHairline() 分隔。
 */
@Composable
private fun SettingsSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    formatValue: (Float) -> String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (-0.1).sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = formatValue(value),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
        // 底部分隔线由调用方通过 RowHairline() 提供
    }
}

/**
 * Hairline divider used between rows inside a single `iOSListSection`.
 * Padded so the line lands at the same x-edge as the icon column, matching
 * the iOS Settings appearance.
 */
@Composable
private fun RowHairline() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        thickness = 0.5.dp,
        color = Color.White.copy(alpha = 0.10f),
    )
}