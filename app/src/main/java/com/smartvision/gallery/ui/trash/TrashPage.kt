package com.smartvision.gallery.ui.trash

import android.app.Activity
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.TrashEntry
import com.smartvision.gallery.ui.components.AsyncThumbnail
import com.smartvision.gallery.ui.components.GridSelectionBadge
import com.smartvision.gallery.ui.components.rememberGridCellSizePx
import com.smartvision.gallery.ui.components.rememberGridThumbSizePx
import com.smartvision.gallery.ui.gestures.DragSelectGesture
import com.smartvision.gallery.ui.gestures.PinchToZoomGesture
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSurface
import com.smartvision.gallery.ui.liquidglass.LocalTopBarState
import com.smartvision.gallery.ui.liquidglass.TopBarConfig
import com.smartvision.gallery.ui.liquidglass.TopBarVariant
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 回收站 — full iOS 26 "Recently Deleted" redesign.
 *
 * Visual + behavioral parity:
 *  - Large title with back chevron, right-side "选择" toggle.
 *  - Info banner explaining the 30-day auto-purge policy.
 *  - Liquid Glass capsule row for bulk restore / delete.
 *  - 3-column grid; each cell shows a day-countdown pill (e.g. "还剩 29 天").
 *  - Selection mode: top bar shows count + Select All, bottom toolbar with
 *    restore / delete actions, per-cell checkmark overlays.
 *  - Empty state: glass icon + friendly copy + retention reminder.
 *  - 30-day auto-purge runs once when the page opens (no background worker needed).
 */
@Composable
fun TrashPage(
    onBack: () -> Unit,
    onOpenPhoto: (List<android.net.Uri>, Int) -> Unit
) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val entries by app.mediaRepository.observeTrashEntries()
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Single source of truth for the destructive confirm dialog.
    var confirmClearAll by remember { mutableStateOf(false) }

    // Selection mode state. selectedIds holds the active selection keyed by
    // item.id (Long), NOT Uri — media Uri can churn on re-scan while id is
    // stable, and the shared gestures/indexing contract selects by Long id.
    var selecting by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    BackHandler(enabled = selecting) {
        selecting = false
        selectedIds = emptySet()
    }

    // Grid chrome: pinch-to-zoom column count (2..7, default 3), and the shared
    // LazyGridState needed by DragSelectGesture for auto-scroll via rawDelta.
    var gridColumns by rememberSaveable { mutableIntStateOf(3) }
    val cellPx = rememberGridCellSizePx(columnCount = gridColumns)
    val thumbSizePx = rememberGridThumbSizePx(columnCount = gridColumns)

    // Helper: resolve selected Long ids back to their Live MediaItem Uris at
    // action time. Entries can churn on emission so we snapshot from the latest.
    fun selectedUris(): List<Uri> {
        val idSet = selectedIds
        if (idSet.isEmpty()) return emptyList()
        return entries.filter { it.item.id in idSet }.map { it.item.uri }
    }

    // System delete pipeline (API 30+). The MediaStore confirmation dialog runs
    // out-of-process, so we stash the URIs before launching and consume them in
    // the launcher callback once the user confirms.
    var pendingDeleteUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val uris = pendingDeleteUris
        pendingDeleteUris = emptyList()
        if (result.resultCode == Activity.RESULT_OK && uris.isNotEmpty()) {
            scope.launch {
                app.mediaRepository.deleteBatch(uris)
                app.cacheCoordinator.clearAll()
            }
        }
    }

    // Fire a system delete. API 30+ shows the MediaStore "Move to trash?" sheet;
    // pre-R devices have no public API, so we delete the DB rows directly and
    // the files become orphans (acceptable: gallery scan will eventually pick
    // them up under their parent bucket).
    val requestSystemDelete: (List<Uri>) -> Unit = remember {
        { uris ->
            if (uris.isEmpty()) return@remember
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pendingDeleteUris = uris
                val intentSender = MediaStore.createDeleteRequest(
                    app.contentResolver, uris
                ).intentSender
                deleteLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            } else {
                scope.launch {
                    app.mediaRepository.deleteBatch(uris)
                    app.cacheCoordinator.clearAll()
                }
            }
        }
    }

    // 30-day retention: eagerly purge items past their retention on page entry.
    // purgeOldTrash() is a no-op if nothing is past the threshold.
    LaunchedEffect(Unit) {
        runCatching {
            val cutoff = System.currentTimeMillis() -
                TimeUnit.DAYS.toMillis(TrashEntry.TRASH_RETENTION_DAYS)
            val purged = app.mediaRepository.purgeOldTrash(cutoff)
            if (purged > 0) {
                android.util.Log.i("TrashPage", "Auto-purged $purged items past retention")
            }
        }
    }

    // Exit selection mode automatically when the page empties out.
    LaunchedEffect(entries.isEmpty()) {
        if (entries.isEmpty()) {
            selecting = false
            selectedIds = emptySet()
        }
    }

    val topBar = LocalTopBarState.current
    LaunchedEffect(selecting, selectedIds.size, entries.size) {
        topBar.value = if (selecting) {
            TopBarConfig(
                title = if (selectedIds.isEmpty()) "选择项目" else "已选 ${selectedIds.size} 项",
                variant = TopBarVariant.COMPACT,
                onBack = {
                    selecting = false
                    selectedIds = emptySet()
                    onBack()
                }
            )
        } else {
            TopBarConfig(
                title = "回收站",
                variant = TopBarVariant.LARGE_TITLE,
                onBack = onBack
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top spacer for the AppRoot-level chrome (status bar + top bar).
            Spacer(Modifier.height(96.dp))

            if (entries.isEmpty()) {
                TrashEmptyState(modifier = Modifier.fillMaxSize())
            } else {
                // Info banner — explains 30-day retention policy.
                RetentionInfoBanner(
                    itemCount = entries.size,
                    oldestDaysLeft = entries.minOfOrNull { it.daysRemaining() } ?: 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                // Bulk-action bar — visible only in normal mode (selection mode
                // hides it because the bottom toolbar takes over).
                AnimatedVisibility(
                    visible = !selecting,
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Column {
                        BulkActionBar(
                            onRestoreAll = {
                                scope.launch {
                                    app.mediaRepository.setTrashBatch(
                                        entries.map { it.item.uri }, false
                                    )
                                }
                            },
                            onDeleteAll = { confirmClearAll = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        // Fallback entry into selection mode — long-press on
                        // thumbnails was unreliable on this Compose version,
                        // so we surface an explicit "选择" button as well.
                        TextButton(
                            onClick = { selecting = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SelectAll,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "选择照片",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                val density = LocalDensity.current
                val gridState = rememberLazyGridState()
                val autoScrollSpeed = remember { mutableStateOf(0f) }
                // 自动滚动：速度 0 时不轮询 (10ms 空转白耗 CPU/电量)。
                LaunchedEffect(autoScrollSpeed.value) {
                    val speed = autoScrollSpeed.value
                    if (speed == 0f) return@LaunchedEffect
                    while (isActive) {
                        gridState.scrollBy(speed * 0.01f)
                        delay(10)
                    }
                }
                // Flat media list for drag-select (no headers on this page → the
                // grid's item index == flat index). Shared with the grid below so
                // both pointer-input gesture lambdas resolve the same identity.
                val flatEntries: List<TrashEntry> = entries
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(
                        start = 8.dp, end = 8.dp,
                        top = 8.dp,
                        bottom = if (selecting) 120.dp else 24.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    // 多选模式禁用 scrollable 指针抢占：拖拽快选需独占 move；自动滚动不受影响。
                    userScrollEnabled = !selecting,
                    modifier = Modifier
                        .fillMaxSize()
                        // Pinch-to-zoom when NOT in select mode
                        .then(
                            if (selecting) Modifier
                            else Modifier.pointerInput(Unit) {
                                val gesture = PinchToZoomGesture(
                                    defaultColumns = { gridColumns },
                                    onColumnChange = { newCols ->
                                        if (newCols != gridColumns) gridColumns = newCols
                                    },
                                    onGestureEnd = { },
                                )
                                with(gesture) { attach() }
                            }
                        )
                        // 统一网格手势：点击打开 / 长按进多选 / 多选下滑动快选。
                        // 常驻，item 不再挂 detectTapGestures（其消费 pointer 事件，
                        // 会挡住本手势的 move → 快选失效）。
                        .then(
                            Modifier.pointerInput(flatEntries, gridColumns) {
                                val gesture = DragSelectGesture(
                                    gridState = gridState,
                                    isItemSelected = { idx ->
                                        flatEntries.getOrNull(idx)?.item?.id
                                            ?.let { it in selectedIds } ?: false
                                    },
                                    onToggleItem = { idx ->
                                        flatEntries.getOrNull(idx)?.let { entry ->
                                            val id = entry.item.id
                                            selectedIds = if (id in selectedIds) selectedIds - id
                                                else selectedIds + id
                                        }
                                    },
                                    onRangeSelect = { prevRange, currRange, selectPass ->
                                        selectedIds = DragSelectGesture.selectRange(
                                            currentSelected = selectedIds,
                                            prevRange = prevRange,
                                            currRange = currRange,
                                            selectPass = selectPass,
                                        )
                                    },
                                    autoScrollSpeed = autoScrollSpeed,
                                    isSelectMode = { selecting },
                                    onEnterSelectMode = { selecting = true },
                                    onTapItem = { idx ->
                                        flatEntries.getOrNull(idx)?.let { entry ->
                                            val id = entry.item.id
                                            if (selecting) {
                                                selectedIds = if (id in selectedIds) selectedIds - id
                                                    else selectedIds + id
                                            } else {
                                                val list = entries.map { it.item.uri }
                                                val openIdx = list.indexOf(entry.item.uri).coerceAtLeast(0)
                                                onOpenPhoto(list, openIdx)
                                            }
                                        }
                                    },
                                )
                                with(gesture) { attach() }
                            }
                        )
                ) {
                    items(entries, key = { it.item.id }) { entry ->
                        val id = entry.item.id
                        val isSelected = id in selectedIds
                        TrashThumbnail(
                            entry = entry,
                            selected = isSelected,
                            selecting = selecting,
                            cellPx = cellPx,
                            thumbSizePx = thumbSizePx,
                        )
                    }
                }
            }
        }

        // Bottom toolbar in selection mode — Liquid Glass capsule with restore + delete.
        AnimatedVisibility(
            visible = selecting,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SelectionToolbar(
                count = selectedIds.size,
                allSelected = selectedIds.size == entries.size && entries.isNotEmpty(),
                onSelectAllToggle = {
                    selectedIds = if (selectedIds.size == entries.size) {
                        emptySet()
                    } else {
                        entries.map { it.item.id }.toSet()
                    }
                },
                onRestore = {
                    val uris = selectedUris()
                    selectedIds = emptySet()
                    selecting = false
                    scope.launch { app.mediaRepository.setTrashBatch(uris, false) }
                },
                onDelete = {
                    val uris = selectedUris()
                    selectedIds = emptySet()
                    selecting = false
                    requestSystemDelete(uris)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }
    }

    // Clear-all confirmation dialog.
    if (confirmClearAll) {
        Dialog(onDismissRequest = { confirmClearAll = false }) {
            LiquidGlassSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        "清空回收站？",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "将永久删除 ${entries.size} 张照片，此操作不可撤销。",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { confirmClearAll = false }) {
                            Text("取消", fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val uris = entries.map { it.item.uri }
                            confirmClearAll = false
                            requestSystemDelete(uris)
                        }) {
                            Text(
                                "永久删除",
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Sub-composables
// -----------------------------------------------------------------------------

/**
 * Info banner — Liquid Glass card with a clock icon and 30-day retention copy.
 * Shows the most-urgent item's countdown so the user knows how soon the bulk
 * purge will hit.
 */
@Composable
private fun RetentionInfoBanner(
    itemCount: Int,
    oldestDaysLeft: Int,
    modifier: Modifier = Modifier
) {
    LiquidGlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "30 天后自动永久删除",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "共 $itemCount 项，最早将于 $oldestDaysLeft 天后清除",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Bulk action bar — two Liquid Glass capsules side by side.
 * Used in normal mode (selection hidden).
 */
@Composable
private fun BulkActionBar(
    onRestoreAll: () -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TrashCapsuleButton(
            text = "全部恢复",
            icon = Icons.Outlined.Restore,
            tint = MaterialTheme.colorScheme.primary,
            onClick = onRestoreAll,
            modifier = Modifier.weight(1f)
        )
        TrashCapsuleButton(
            text = "全部清空",
            icon = Icons.Outlined.DeleteForever,
            tint = MaterialTheme.colorScheme.error,
            onClick = onDeleteAll,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Single Liquid Glass capsule button. Bigger hit area than TextButton,
 * explicit tint background, used for both bulk and selection-mode actions.
 */
@Composable
private fun TrashCapsuleButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = tint.copy(alpha = 0.14f),
        contentColor = tint,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint
            )
        }
    }
}

/**
 * Bottom toolbar for selection mode — three capsules: 全选/全不选 + 恢复 + 删除.
 * Disabled when nothing is selected; all-clear when everything is.
 */
@Composable
private fun SelectionToolbar(
    count: Int,
    allSelected: Boolean,
    onSelectAllToggle: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = count > 0
    LiquidGlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrashCapsuleButton(
                text = if (allSelected) "全不选" else "全选",
                icon = Icons.Outlined.SelectAll,
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onSelectAllToggle,
                modifier = Modifier.weight(1f)
            )
            TrashCapsuleButton(
                text = if (enabled) "恢复 ($count)" else "恢复",
                icon = Icons.Outlined.Restore,
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                onClick = onRestore,
                modifier = Modifier.weight(1f)
            )
            TrashCapsuleButton(
                text = if (enabled) "删除 ($count)" else "删除",
                icon = Icons.Outlined.DeleteForever,
                tint = if (enabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                onClick = onDelete,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Single trash grid cell. Square thumbnail with:
 *  - bottom-left Liquid Glass pill: "还剩 X 天" (or "今日过期" when 0).
 *  - top-right selection circle when in selecting mode.
 *  - dim overlay + primary border when selected.
 */
@Composable
private fun TrashThumbnail(
    entry: TrashEntry,
    selected: Boolean,
    selecting: Boolean,
    cellPx: Int,
    thumbSizePx: Int,
) {
    val days = entry.daysRemaining()
    val countdownText = when {
        days <= 0 -> "今日过期"
        days == 1 -> "还剩 1 天"
        else -> "还剩 $days 天"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(with(LocalDensity.current) { cellPx.toDp() })
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
    ) {
        AsyncThumbnail(
            model = entry.item,
            sizePx = thumbSizePx,
            contentDescription = entry.item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Dim overlay when selected.
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
            )
        }

        // Day-countdown pill, bottom-left.
        DayCountdownPill(
            text = countdownText,
            urgent = days <= 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 6.dp)
        )

        // Selection circle, top-right.
        if (selecting) {
            SelectionCircle(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            )
        }
    }
}

@Composable
private fun DayCountdownPill(
    text: String,
    urgent: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = if (urgent) MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
    else Color.Black.copy(alpha = 0.55f)
    val fg = Color.White
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SelectionCircle(
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else Color.Black.copy(alpha = 0.35f)
            )
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Text(
                "✓",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Empty state — large glass circle with a trash icon, headline + retention copy.
 * Centered with vertical weight to feel balanced on any screen.
 */
@Composable
private fun TrashEmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LiquidGlassSurface(
                modifier = Modifier.size(96.dp),
                shape = CircleShape
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "回收站是空的",
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "已删除的照片会在这里保留 30 天",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Long-press detection: uses `Modifier.combinedClickable` directly on the Box,
 * matching the proven PrivacyVaultPage pattern. Long-press fires `onLongClick`
 * which sets `selecting = true` and seeds the selection set with the pressed URI.
 * A `TextButton("选择照片")` below the bulk-action bar provides a fallback
 * entry into selection mode if long-press is unreliable on any device.
 */
