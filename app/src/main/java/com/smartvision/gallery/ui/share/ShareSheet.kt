package com.smartvision.gallery.ui.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.export.ExportPipeline
import com.smartvision.gallery.ui.components.AsyncThumbnail
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.ui.liquidglass.LiquidGlassBottomSheet
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSpec
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.ui.liquidglass.drawGlassTint
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Format-aware share sheet — wrapped in [LiquidGlassBottomSheet] for the same
 * physical glass as [InfoPanel] / [SlideshowConfig]. Caller MUST NOT add a
 * dim/scrim Box underneath — the bottom sheet provides its own scrim.
 */
@Composable
fun ShareSheet(
    items: List<MediaItem>,
    pipeline: ExportPipeline,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var useCase by remember { mutableStateOf(ShareUseCase.SHARE_GENERIC) }
    var sharing by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<String?>(null) }
    val preferNextGen by SmartVisionApp.from(context).prefs.preferNextGen.collectAsState(initial = false)

    val suggestions = remember(items, useCase, preferNextGen) {
        items.map { pipeline.suggestFormat(it, useCase.toPipelineUseCase(), preferNextGen) }.distinct()
    }

    val staticSpec = LocalGlassConfig.current.staticGlass.toSpec()
    val controlSpec = LocalGlassConfig.current.control.toSpec()

    LiquidGlassBottomSheet(onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row: title + close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "分享 ${items.size} 项媒体",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Text(
                "目标场景",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(ShareUseCase.entries) { uc ->
                    InlineGlassButton(
                        spec = if (useCase == uc) {
                            staticSpec.copy(tintAlpha = 0.35f, highlightAlpha = 0.55f)
                        } else staticSpec.copy(tintAlpha = 0.10f, highlightAlpha = 0.30f),
                        onClick = { useCase = uc }
                    ) {
                        Text(uc.displayName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Text(
                "推荐格式",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(suggestions) { format ->
                    InlineGlassButton(
                        spec = controlSpec.copy(tintAlpha = 0.15f, highlightAlpha = 0.40f),
                        onClick = {
                            if (sharing) return@InlineGlassButton
                            sharing = true
                            lastError = null
                            scope.launch {
                                val result = runCatching {
                                    shareItems(context, items, format)
                                }
                                sharing = false
                                result.onFailure {
                                    lastError = it.message ?: "分享失败"
                                    AppLog.e("ShareSheet", "share failed", it)
                                }
                                result.onSuccess { onDismiss() }
                            }
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Text(format.displayName, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            lastError?.let { msg ->
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (items.isNotEmpty()) {
                Text(
                    "预览",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(items) { item ->
                        AsyncThumbnail(
                            model = item,
                            contentDescription = item.displayName,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    }
                }
            }

            // Close button at bottom for thumb-reachable dismiss
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                InlineGlassButton(
                    spec = controlSpec.copy(tintAlpha = 0.10f, highlightAlpha = 0.30f),
                    onClick = onDismiss
                ) {
                    Text(
                        "关闭",
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Inline glass button — uses ONLY [drawGlassTint] (no backdrop sampling) so
 * it never sees "through" the parent bottom sheet. The button reads as a
 * tinted+highlighted glass capsule on top of whatever the sheet already
 * rendered, which is the correct visual when nested inside another glass
 * surface (LiquidGlassBottomSheet). Backdrop sampling inside a sheet would
 * either capture the static canvas gradient (a hard "底色" block) or
 * capture the screen-level layer that already contains the chip itself
 * (visual recursion → "穿透" the sheet to the photo below).
 */
@Composable
private fun InlineGlassButton(
    spec: LiquidGlassSpec,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = if (pressed) 0.94f else 1.0f
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .drawWithContent {
                drawContent()
                drawGlassTint(spec)
            }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(modifier = Modifier.scale(scale)) { content() }
    }
}

/**
 * Build a real `ACTION_SEND[_MULTIPLE]` intent and launch the system chooser.
 *
 * Strategy: prefer the source MediaStore `content://media/...` URI directly.
 * Since Android Q+, receiving apps that participate in the share sheet can
 * read these URIs once we add `FLAG_GRANT_READ_URI_PERMISSION`. This avoids
 * the cache-copy round trip entirely and preserves original quality/metadata.
 *
 * Fallback: if a direct URI grant fails (some receivers don't accept
 * MediaStore URIs from other apps), copy bytes to `cacheDir/share/<random>/`
 * and expose through our FileProvider — the original approach.
 */
private suspend fun shareItems(
    context: Context,
    items: List<MediaItem>,
    format: com.smartvision.gallery.decoder.format.MediaFormat
) = withContext(Dispatchers.IO) {
    if (items.isEmpty()) return@withContext
    val authority = "${context.packageName}.fileprovider"
    // Source URIs are content://media/... — readable by any app once
    // FLAG_GRANT_READ_URI_PERMISSION is set on the share intent.
    val uris: List<Uri> = items.map { it.uri }
    val baseIntent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = items[0].mimeType ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uris[0])
            clipData = ClipData.newUri(context.contentResolver, items[0].displayName, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = items.first().mimeType ?: "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            // Anchor the clip data on the first URI so FLAG_GRANT_READ_URI_PERMISSION
            // is correctly applied to the whole list.
            clipData = ClipData.newUri(context.contentResolver, items[0].displayName, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val chooser = Intent.createChooser(baseIntent, "分享 · ${format.displayName}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }.onFailure {
        // Fallback: cache-copy through FileProvider for receivers that
        // can't read MediaStore URIs cross-app.
        val prepared = items.map { item ->
            val copy = copyToShareCache(context, item)
            copy to FileProvider.getUriForFile(context, authority, copy)
        }
        val fallbackUris: List<Uri> = prepared.map { it.second }
        val fallbackIntent = if (fallbackUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = items[0].mimeType ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, fallbackUris[0])
                clipData = ClipData.newUri(context.contentResolver, items[0].displayName, fallbackUris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = items.first().mimeType ?: "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(fallbackUris))
                clipData = ClipData.newUri(context.contentResolver, items[0].displayName, fallbackUris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        context.startActivity(
            Intent.createChooser(fallbackIntent, "分享 · ${format.displayName}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Copy the source item's bytes into `cacheDir/share/<random>/<name>` for sharing. */
private fun copyToShareCache(context: Context, item: MediaItem): File {
    val shareRoot = File(context.cacheDir, "share").apply { mkdirs() }
    val dir = File(shareRoot, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
    val safeName = item.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val out = File(dir, safeName)
    context.contentResolver.openInputStream(item.uri)?.use { input ->
        out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
    } ?: error("Cannot open ${item.uri}")
    return out
}

private enum class ShareUseCase(val displayName: String) {
    SHARE_GENERIC("通用分享"),
    SHARE_ON_SOCIAL("社交平台"),
    ARCHIVE("长期归档"),
    EDIT_FRIENDLY("后续编辑");

    fun toPipelineUseCase(): ExportPipeline.UseCase = when (this) {
        SHARE_GENERIC -> ExportPipeline.UseCase.SHARE_GENERIC
        SHARE_ON_SOCIAL -> ExportPipeline.UseCase.SHARE_ON_SOCIAL
        ARCHIVE -> ExportPipeline.UseCase.ARCHIVE
        EDIT_FRIENDLY -> ExportPipeline.UseCase.EDIT_FRIENDLY
    }
}