package com.smartvision.gallery.ui.album

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.decoder.format.MediaFormat
import com.smartvision.gallery.ui.components.AsyncThumbnail
import com.smartvision.gallery.ui.components.GridSelectionBadge
import com.smartvision.gallery.ui.components.GridSelectionBar
import com.smartvision.gallery.ui.components.rememberGridCellSizePx
import com.smartvision.gallery.ui.components.rememberGridThumbSizePx
import com.smartvision.gallery.ui.gestures.DragSelectGesture
import com.smartvision.gallery.ui.gestures.GridGeometry
import com.smartvision.gallery.ui.gestures.PinchToZoomGesture
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayList

/**
 * 相册详情页 — 内容层 (Z=0 inside NavHost).
 *
 * 布局: LazyVerticalGrid 全屏，内含：
 *  1. Header（fullSpan）— 相册名 + 数量，随滚动消失
 *  2. 照片网格（Adaptive 列数，双指缩放 2~7）
 *
 * AlbumDetailChrome (Z=1) 浮动芯片栏固定在 status bar 下方。
 * 多选模式时隐藏芯片栏，改用 GridSelectionBar。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumDetailPage(
    albumId: String,
    onBack: () -> Unit,
    onOpenPhoto: (List<Uri>, Int) -> Unit
) {
    if (albumId == "trash") {
        com.smartvision.gallery.ui.trash.TrashPage(
            onBack = onBack,
            onOpenPhoto = onOpenPhoto,
        )
        return
    }

    val app = LocalContext.current.applicationContext as SmartVisionApp
    val repo = app.mediaRepository

    val itemsFlow = remember(albumId) {
        when {
            albumId.startsWith("bucket:") -> repo.observeBucket(albumId.removePrefix("bucket:"))
            albumId.startsWith("format:") -> {
                val fmt = MediaFormat.valueOf(albumId.removePrefix("format:"))
                repo.observeByFormat(fmt)
            }
            albumId == "favorites" -> repo.observeFavorites()
            albumId == "hidden" -> repo.observeHidden()
            albumId == "trash" -> repo.observeTrash()
            albumId == "ai:untagged" -> repo.observeUntagged()
            albumId.startsWith("ai:anime:角色:") -> repo.queryAnimeByCharacter(albumId.removePrefix("ai:anime:角色:"), limit = 500)
            albumId.startsWith("ai:anime:") -> repo.queryAnimeByBucket(albumId, limit = 500)
            albumId.startsWith("ai:sub:") -> repo.queryAiSubDomain(albumId.removePrefix("ai:sub:"), limit = 500)
            albumId == "ai:thisWeekReal" -> repo.queryCuratedThisWeek(limit = 500)
            albumId == "ai:thisWeekAnime" -> repo.queryCuratedThisWeek(limit = 500)
            albumId == "ai:portraits" -> repo.queryPortraits(limit = 500)
            albumId == "ai:animeChars" -> repo.queryAnimeCharacters(limit = 500)
            albumId == "ai:games" -> repo.queryGameScreens(limit = 500)
            albumId == "ai:movies" -> repo.queryMovieScreens(limit = 500)
            albumId == "ai:animeArt" -> repo.queryAnimeArt(limit = 500)
            albumId == "ai:otherReal" -> repo.queryOtherReal(limit = 500)
            albumId.startsWith("memory:") -> repo.observeTimeline()
            else -> repo.observeTimeline()
        }
    }
    val items by itemsFlow.collectAsState(initial = emptyList())
    var selectedFilter by rememberSaveable { mutableStateOf("全部") }
    val filteredItems = remember(items, selectedFilter) {
        when (selectedFilter) {
            "视频" -> items.filter { it.format.isVideo }
            "收藏" -> items.filter { it.isFavorite }
            "实况" -> items.filter { it.isLivePhoto }
            else -> items
        }
    }

    val albumName = when {
        albumId.startsWith("bucket:") -> albumId.removePrefix("bucket:")
        albumId.startsWith("format:") -> MediaFormat.valueOf(albumId.removePrefix("format:")).displayName
        albumId == "favorites" -> "收藏"
        albumId == "hidden" -> "隐私空间"
        albumId == "trash" -> "回收站"
        albumId == "ai:untagged" -> "未处理"
        albumId.startsWith("ai:anime:") -> "动漫-" + albumId.removePrefix("ai:anime:")
        albumId.startsWith("ai:sub:") -> albumId.removePrefix("ai:sub:")
        albumId.startsWith("ai:") -> albumId.removePrefix("ai:")
        else -> "未知相册"
    }

    val gridState = rememberLazyGridState()
    val chromeState = LocalAlbumDetailChromeState.current

    // chip 栏在 Z=1 用 windowInsetsPadding(statusBars) + 4dp 定位
    // grid 用 windowInsetsPadding(statusBars) 适配状态栏
    // contentPadding.top 只留 chip bar 空间
    val chipBarHeightDp = 60.dp
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // ---- 双指缩放 + 多选状态 ----
    var gridColumns by rememberSaveable { mutableIntStateOf(3) }
    var selectModeEnabled by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val autoScrollSpeed = remember { mutableStateOf(0f) }
    val cellPx = rememberGridCellSizePx(columnCount = gridColumns)
    val thumbSizePx = rememberGridThumbSizePx(columnCount = gridColumns)

    // 扁平列表（含 header 哨兵）使 DragSelectGesture 的 flat index 与 grid 渲染顺序对齐
    val flatItems = remember(filteredItems, albumName) {
        listOf<Any>(GridGeometry.GridHeader(albumName)) + filteredItems
    }
    val flatItemsRef by rememberUpdatedState(flatItems)
    val filteredItemsRef by rememberUpdatedState(filteredItems)
    val selectedIdsRef by rememberUpdatedState(selectedIds)

    fun toggleItemSelection(id: Long) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    // 统一手势实例：顶层 remember，供 pointerInput attach + autoScroll 循环共享。
    // 用 rememberUpdatedState 闭包避免每次重组重建 gesture 实例。
    val dragGesture = remember {
        DragSelectGesture(
            gridState = gridState,
            isItemSelected = { idx ->
                val item = flatItemsRef.getOrNull(idx) as? MediaItem
                item?.id in selectedIdsRef
            },
            onToggleItem = { idx ->
                val item = flatItemsRef.getOrNull(idx) as? MediaItem
                if (item != null) {
                    if (item.id in selectedIdsRef) selectedIds = selectedIdsRef - item.id
                    else selectedIds = selectedIdsRef + item.id
                }
            },
            onRangeSelect = { prevRange, currRange, selectPass ->
                // 绝对索引范围直接映射到 flatItems 里的 MediaItem（header 自动过滤）。
                fun ids(range: IntRange) = range
                    .mapNotNull { i -> (flatItemsRef.getOrNull(i) as? MediaItem)?.id }
                    .toSet()
                val prevIds = ids(prevRange)
                val currIds = ids(currRange)
                val result = selectedIdsRef.toMutableSet()
                if (selectPass) { result.removeAll(prevIds); result.addAll(currIds) }
                else { result.addAll(prevIds); result.removeAll(currIds) }
                selectedIds = result
            },
            autoScrollSpeed = autoScrollSpeed,
            isSelectMode = { selectModeEnabled },
            onEnterSelectMode = { selectModeEnabled = true },
            onTapItem = { idx ->
                val item = flatItemsRef.getOrNull(idx) as? MediaItem
                if (item != null) {
                    if (selectModeEnabled) toggleItemSelection(item.id)
                    else {
                        val list = filteredItemsRef.map { it.uri }
                        val openIdx = list.indexOf(item.uri).coerceAtLeast(0)
                        onOpenPhoto(list, openIdx)
                    }
                }
            },
        )
    }

    // 自动滚动：轮询 autoScrollSpeed，每 tick 读当前速度，避免 collectLatest 取消重启竞态。
    LaunchedEffect(Unit) {
        while (isActive) {
            val speed = autoScrollSpeed.value
            if (speed != 0f) {
                gridState.scrollBy(speed * 0.01f)
                dragGesture.onAutoScrollTick()
            }
            delay(10)
        }
    }

    // 多选模式时隐藏芯片栏
    LaunchedEffect(items.size, selectedFilter, selectModeEnabled) {
        chromeState.value = AlbumDetailChromeState(
            isVisible = items.isNotEmpty() && !selectModeEnabled,
            selectedFilter = selectedFilter,
            onFilterChange = { selectedFilter = it },
        )
    }
    DisposableEffect(Unit) {
        onDispose { chromeState.value = AlbumDetailChromeState() }
    }

    BackHandler(enabled = selectModeEnabled) {
        selectModeEnabled = false
        selectedIds = emptySet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 多选操作栏
        if (selectModeEnabled) {
            GridSelectionBar(
                selectedCount = selectedIds.size,
                onCancel = {
                    selectModeEnabled = false
                    selectedIds = emptySet()
                },
                onSelectAll = {
                    selectedIds = filteredItems.map { it.id }.toSet()
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val uris = selectedIds.mapNotNull { id -> filteredItems.firstOrNull { it.id == id }?.uri }
                            val intent = if (uris.size == 1) {
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "image/*"
                                    putExtra(Intent.EXTRA_STREAM, uris[0])
                                }
                            } else {
                                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "image/*"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                }
                            }
                            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            val chooser = Intent.createChooser(intent, "分享")
                            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { app.startActivity(chooser) }
                                .onFailure { com.smartvision.gallery.util.AppLog.w("AlbumDetail", "share failed", it) }
                            selectModeEnabled = false
                            selectedIds = emptySet()
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val uris = selectedIds.mapNotNull { id -> filteredItems.firstOrNull { it.id == id }?.uri }
                            repo.setTrashBatch(uris, true)
                            selectModeEnabled = false
                            selectedIds = emptySet()
                        }
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除")
                    }
                },
            )
        }

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(gridColumns),
            contentPadding = PaddingValues(
                start = 2.dp, end = 2.dp,
                top = if (selectModeEnabled) 0.dp else chipBarHeightDp,
                bottom = 2.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            // 多选模式禁用 scrollable 指针抢占：DragSelectGesture 的拖拽快选
            // 需要独占 move 事件；程序化 dispatchRawDelta 自动滚动不受影响。
            userScrollEnabled = !selectModeEnabled,
            modifier = Modifier
                .fillMaxSize()
                // 双指缩放 — 恒挂载。选择模式下 isSelectMode() 让它静默让位，
                // 不消费事件。不再条件移除 modifier，避免链结构变化重启手势。
                .then(
                    Modifier.pointerInput(Unit) {
                        with(PinchToZoomGesture(
                            defaultColumns = { gridColumns },
                            onColumnChange = { newCols ->
                                if (newCols != gridColumns) gridColumns = newCols
                            },
                            onGestureEnd = {},
                            isSelectMode = { selectModeEnabled },
                        )) { attach() }
                    }
                )
                // 统一网格手势：点击打开 / 长按进多选 / 多选下滑动快选。
                // 常驻，key 稳定（gridState），gesture 实例顶层 remember。
                .then(
                    Modifier.pointerInput(gridState) {
                        with(dragGesture) { attach() }
                    }
                ),
        ) {
            // Header — fullSpan，随滚动消失
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 4.dp),
                ) {
                    Text(
                        albumName,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${filteredItems.size} 项",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 照片网格
            items(filteredItems, key = { it.id }) { item ->
                val isSelected = item.id in selectedIds
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(with(density) { cellPx.toDp() })
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (isSelected) Color(0x33007AFF)
                            else MaterialTheme.colorScheme.surface
                        )
                ) {
                    AsyncThumbnail(
                        model = item,
                        sizePx = thumbSizePx,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0x33007AFF))
                        )
                    }
                    GridSelectionBadge(
                        isSelected = isSelected,
                        visible = selectModeEnabled,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }
        }
    }
}