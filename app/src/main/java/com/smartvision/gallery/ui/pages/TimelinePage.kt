package com.smartvision.gallery.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartvision.gallery.R
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.ui.components.AsyncThumbnail
import com.smartvision.gallery.ui.components.rememberGridCellSizePx
import com.smartvision.gallery.ui.components.rememberGridThumbSizePx
import com.smartvision.gallery.ui.liquidglass.LibraryOverlayState
import com.smartvision.gallery.ui.liquidglass.LocalLibraryOverlayState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.input.pointer.pointerInput
import com.smartvision.gallery.ui.components.GridSelectionBadge
import com.smartvision.gallery.ui.gestures.DragSelectGesture
import com.smartvision.gallery.ui.gestures.GridGeometry
import com.smartvision.gallery.ui.gestures.PinchToZoomGesture
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * "图库" page — iOS 26 Photos Library tab.
 *
 * Z-stack architecture (this page is Z=0 — captured subtree):
 *  * Photo grid lives here. The surrounding `Box(Modifier.layerBackdrop(L))`
 *    in AppRoot captures this whole subtree into the screen layer.
 *  * The action row chips (选择 / Tune) and the segmented control (全部 /
 *    日月年 / 选择) are NOT rendered here. They live in [LibraryOverlay] at
 *    Z=1 as siblings of the captured subtree, so they sample the real
 *    screen capture via [LocalLiquidGlassScreenBackdrop] and render with
 *    full Liquid Glass physics over the photos — the same architecture as
 *    the bottom tab bar.
 *
 * This page owns:
 *  * `segment` — selected segment index, pushed to `LocalLibraryOverlayState`.
 *  * `scrollCollapsedRatio` — derived from grid scroll, drives the
 *    segmented control's fade + translate-up (handled inside LibraryOverlay).
 *  * `topBarConfig` — title/variant for the floating top pill.
 *
 * No "精选集" hero row here — iOS 26 puts curated content in the Collections
 * tab. Library is a pure grid view focused on browseability.
 */
@Composable
fun TimelinePage(
    onFirstFrame: () -> Unit,
    onOpenPhoto: (List<android.net.Uri>, Int) -> Unit,
    onOpenAlbums: () -> Unit = {},
    onConsumePendingSegment: () -> Int? = { null },
) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val viewModel: TimelineViewModel = viewModel(
        factory = TimelineViewModelFactory(app.mediaRepository)
    )
    val sections by viewModel.sections.collectAsStateWithLifecycle()

    // Segment state.
    //
    // The pending segment (set by AlbumsPage onSegmentChange → popBackStack) is
    // consumed in AppRoot's composition, BEFORE AppRoot's children compose.
    // AppRoot writes the consumed value into `libraryOverlayState.value.segment`
    // and then passes that state to LibraryOverlay at Z=1. TimelinePage reads
    // the same state as the initial value of its own `segment` mirror — so
    // the first frame after popBackStack already shows the correct highlighted
    // segment, no flicker.
    //
    // Earlier attempts (LaunchedEffect + repeatOnLifecycle, then `remember`
    // inside TimelinePage) all suffered from the same flicker because
    // TimelinePage is a CHILD of AppRoot — by the time it updates the shared
    // state, AppRoot has already passed the stale snapshot to LibraryOverlay.
    // The only fix is to do the consume in AppRoot itself.
    val libraryOverlayState = LocalLibraryOverlayState.current
    val initialSegment = remember { libraryOverlayState.value.segment }
    var segment by remember { mutableIntStateOf(initialSegment) }

    // Selection state & sort order (must be declared before displaySections)
    var sortNewestFirst by remember { mutableStateOf(true) }
    var selectModeEnabled by remember { mutableStateOf(false) }
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val autoScrollSpeed = remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    BackHandler(enabled = selectModeEnabled) {
        selectModeEnabled = false
        viewModel.clearSelection()
    }

    // Compute display grouping based on segment:
    //   0 = 全部 — broad monthly overview (see more items per screen, fewer headers)
    //   1 = 日月年 — detailed daily grouping (day headers, day-by-day browsing)
    //   2 = 图集 — navigates to the Albums page (Routes.ALBUMS) and bounces
    //     back to segment 0. The grid below keeps its current grouping
    //     while the navigation transition runs, then refreshes on return.
    val displaySections by remember(sections, segment, sortNewestFirst) {
        derivedStateOf {
            val base = when (segment) {
                1 -> sections
                else -> bucketByMonth(sections)
            }
            if (sortNewestFirst) base
            else base.map { sec -> sec.copy(items = sec.items.reversed()) }.reversed()
        }
    }

    // Segment 2 (图集) navigation now happens inside LibraryOverlay's
    // onSegmentChange callback (C6 fix) — the user-select callback decides
    // whether to navigate or mutate `segment`. We no longer need a
    // LaunchedEffect(segment) to bounce-back, and the selected segment
    // stays in sync without flicker.

    LaunchedEffect(sections.isNotEmpty()) { onFirstFrame() }

    val gridState = rememberLazyGridState()

    // Grid columns — 2..7, default 3, persist via rememberSaveable.
    var gridColumns by rememberSaveable { mutableIntStateOf(3) }

    // Pre-compute the cell pixel size once per page so every TimelineCell
    // decodes at the exact size Compose will draw. Without this hint Coil
    // defaults to Size.ORIGINAL — a 4K JPEG decodes into a ~24MB bitmap
    // only to be cropped to a ~360px cell, which is the dominant cause
    // of "thumbnail pops in 200ms late" on the timeline grid.
    val cellPx = rememberGridCellSizePx(columnCount = gridColumns)
    val thumbSizePx = rememberGridThumbSizePx(columnCount = gridColumns)

    // iOS 26 Photos: the segmented control stays visible through the first
    // ~320dp of scroll, then fades + translates up. The animation lives
    // inside [LibraryOverlay]; this page just publishes the ratio.
    val collapsedRatio by remember {
        derivedStateOf {
            if (gridState.firstVisibleItemIndex > 0) 1f
            else {
                val offset = gridState.firstVisibleItemScrollOffset
                (offset / 320f).coerceIn(0f, 1f)
            }
        }
    }

    // Top bar is set centrally in AppRoot based on currentRoute — pages
    // no longer need their own LaunchedEffect(Unit) here. See AppRoot.kt
    // for the centralization rationale (fixes stale-top-bar-after-popBackStack).

    fun toggleItemSelection(id: Long) {
        viewModel.toggleItemSelection(id)
    }

    // Publish chrome state up to LibraryOverlay (Z=1). LibraryOverlay owns
    // its own segBackdrop and lens compositing — TimelinePage only needs to
    // forward the segment index, the scroll-collapse ratio, and the
    // onSegmentChange callback so Z=1's interactive control can mutate Z=0
    // state without prop drilling.
    // (libraryOverlayState was captured earlier at the top of this composable
    //  so the initial segment read sees the value AppRoot just wrote.)
    //
    // Segment sync contract (fixes the "stuck segment" bug):
    //  * The click handler is the SINGLE SOURCE OF TRUTH for segment changes.
    //    It synchronously updates BOTH local `segment` AND the shared
    //    `libraryOverlayState.value.segment` via `.copy()`. This is synchronous
    //    so there's no race with LaunchedEffect(Unit).
    //  * LaunchedEffect(Unit) writes the initial state with all callbacks.
    //    The initial segment is read from `libraryOverlayState.value.segment`
    //    (the shared state — which AppRoot may have just updated via the
    //    pending-segment consume), NOT from local `segment`. This prevents
    //    the coroutine from overwriting a freshly-consumed segment value.
    //  * LaunchedEffect(selectModeEnabled, ...) syncs the 3 selection-mode
    //    fields via `.copy()`. It does NOT touch segment (the click handler
    //    already manages segment exclusively).
    //  * The previous `remember(segment) { ... }` sync block was REMOVED —
    //    it could miss transitions (remember keys don't fire on no-op key
    //    changes) and raced with LaunchedEffect(Unit).
    LaunchedEffect(Unit) {
        libraryOverlayState.value = LibraryOverlayState(
            segment = libraryOverlayState.value.segment,
            scrollCollapsedRatio = 0f,
            onSegmentChange = { selected ->
                if (selected == 2) {
                    onOpenAlbums()
                } else {
                    segment = selected
                    // SYNCHRONOUS: also write to shared state so LibraryOverlay
                    // sees the new highlight on the very next recomposition.
                    libraryOverlayState.value =
                        libraryOverlayState.value.copy(segment = selected)
                }
            },
            selectModeEnabled = selectModeEnabled,
            onToggleSelectMode = {
                val entering = !selectModeEnabled
                selectModeEnabled = entering
                if (!entering) viewModel.clearSelection()
            },
            selectedCount = selectedIds.size,
            onToggleItemSelection = { toggleItemSelection(it) },
            sortNewestFirst = sortNewestFirst,
            onToggleSort = { sortNewestFirst = !sortNewestFirst },
            onDeleteSelected = {
                scope.launch {
                    val ids = selectedIds.toList()
                    // 批量删除：先查 URI，再 setTrashBatch
                    val allItems = sections.flatMap { it.items }
                    val uris = ids.mapNotNull { id -> allItems.firstOrNull { it.id == id }?.uri }
                    app.mediaRepository.setTrashBatch(uris, true)
                    viewModel.clearSelection()
                    selectModeEnabled = false
                }
            },
            onFavoriteSelected = {
                scope.launch {
                    val allItems = sections.flatMap { it.items }
                    selectedIds.forEach { id ->
                        val item = allItems.firstOrNull { it.id == id } ?: return@forEach
                        app.mediaRepository.setFavorite(item.uri, !item.isFavorite)
                    }
                    viewModel.clearSelection()
                    selectModeEnabled = false
                }
            },
            onShareSelected = {
                scope.launch {
                    val allItems = sections.flatMap { it.items }
                    val uris = selectedIds.mapNotNull { id -> allItems.firstOrNull { it.id == id }?.uri }
                    val intent = if (uris.size == 1) {
                        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "image/*"
                            putExtra(android.content.Intent.EXTRA_STREAM, uris[0])
                        }
                    } else {
                        android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                            type = "image/*"
                            putParcelableArrayListExtra(
                                android.content.Intent.EXTRA_STREAM,
                                java.util.ArrayList(uris)
                            )
                        }
                    }
                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val chooser = android.content.Intent.createChooser(intent, "分享")
                    chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { app.startActivity(chooser) }
                        .onFailure { com.smartvision.gallery.util.AppLog.w("Share", "share failed", it) }
                    viewModel.clearSelection()
                    selectModeEnabled = false
                }
            },
            onHideSelected = {
                scope.launch {
                    val allItems = sections.flatMap { it.items }
                    val uris = selectedIds.mapNotNull { id -> allItems.firstOrNull { it.id == id }?.uri }
                    app.mediaRepository.setHiddenBatch(uris, true)
                    viewModel.clearSelection()
                    selectModeEnabled = false
                }
            },
            onOpenAlbums = onOpenAlbums,
        )
    }
    LaunchedEffect(selectModeEnabled, selectedIds, sortNewestFirst) {
        // NOTE: do NOT include segment here — the click handler owns segment
        // exclusively (see Segment sync contract above). Touching segment
        // here would race with the click handler.
        libraryOverlayState.value = libraryOverlayState.value.copy(
            selectModeEnabled = selectModeEnabled,
            selectedCount = selectedIds.size,
            sortNewestFirst = sortNewestFirst,
        )
    }
    LaunchedEffect(gridState) {
        snapshotFlow { collapsedRatio }
            .distinctUntilChanged()
            .collect { ratio ->
                libraryOverlayState.value = libraryOverlayState.value.copy(
                    scrollCollapsedRatio = ratio,
                )
            }
    }

    val density = LocalDensity.current

    // Flat list for drag-select: header items + media items
    val flatList: List<Any> = remember(displaySections) {
        displaySections.flatMap { sec ->
            listOf<Any>(GridGeometry.GridHeader(sec.header)) + sec.items
        }
    }
    // Media-only flat list (no headers) for click/index lookup — cached once
    // per data change, not rebuilt per frame inside the items() lambda.
    val flatItems: List<MediaItem> = remember(displaySections) {
        displaySections.flatMap { it.items }
    }
    val flatListRef by rememberUpdatedState(flatList)
    val flatItemsRef by rememberUpdatedState(flatItems)
    val selectedIdsRef by rememberUpdatedState(selectedIds)
    // 非 by 委托的 State 引用：remember 闭包内读取最新值，且不让 lambda 捕获 var。
    val flatItemsState = rememberUpdatedState(flatItems)

    // 统一手势实例：顶层 remember，供 pointerInput attach + autoScroll 循环共享。
    // 用 rememberUpdatedState 闭包避免每次重组重建 gesture 实例。
    val dragGesture = remember {
        DragSelectGesture(
            gridState = gridState,
            isItemSelected = { idx ->
                val item = flatListRef.getOrNull(idx) as? MediaItem
                item?.id in selectedIdsRef
            },
            onToggleItem = { idx ->
                val item = flatListRef.getOrNull(idx) as? MediaItem
                if (item != null) viewModel.toggleItemSelection(item.id)
            },
            onRangeSelect = { prevRange, currRange, selectPass ->
                // 绝对索引范围直接映射到 flatList 里的 MediaItem（header 类型自动过滤）。
                // 不能用「绝对索引 - 1」换算——TimelinePage 每 section 前有 header，
                // 跨 section 时只减 1 会错位选中。
                fun ids(range: IntRange) = range
                    .mapNotNull { i -> (flatListRef.getOrNull(i) as? MediaItem)?.id }
                    .toSet()
                val prevIds = ids(prevRange)
                val currIds = ids(currRange)
                val result = selectedIdsRef.toMutableSet()
                if (selectPass) { result.removeAll(prevIds); result.addAll(currIds) }
                else { result.addAll(prevIds); result.removeAll(currIds) }
                viewModel.setSelection(result)
            },
            autoScrollSpeed = autoScrollSpeed,
            isSelectMode = { selectModeEnabled },
            onEnterSelectMode = { selectModeEnabled = true },
            onTapItem = { idx ->
                val item = flatListRef.getOrNull(idx) as? MediaItem
                if (item != null) {
                    try {
                        val items = flatItemsRef
                        val flatIdx = items.indexOfFirst { it.id == item.id }
                        com.smartvision.gallery.util.AppLog.i("Timeline",
                            "click uri=${item.uri} idx=$flatIdx flatSize=${items.size}")
                        onOpenPhoto(items.map { it.uri }, flatIdx)
                    } catch (t: Throwable) {
                        com.smartvision.gallery.util.AppLog.e("Timeline",
                            "click handler crashed", t)
                    }
                }
            },
        )
    }

    // 自动滚动：速度 0 时不轮询 (10ms 空转白耗 CPU/电量)。
    // 外层监听速度变化, 非 0 才启动滚动循环; speed=0 时协程取消。
    LaunchedEffect(autoScrollSpeed.value) {
        val speed = autoScrollSpeed.value
        if (speed == 0f) return@LaunchedEffect
        while (isActive) {
            gridState.scrollBy(speed * 0.01f)
            dragGesture.onAutoScrollTick()
            delay(10)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // No top spacer — the grid extends to Y=0 so the Z=1 chrome
        // (large title, segmented control, action row) samples real
        // photo content through its frosted glass backdrop.
        // Bottom spacer removed too — the tab bar overlays the grid
        // with real backdrop sampling at Z=1.

        if (sections.isEmpty()) {
            EmptyState(onFirstFrame)
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(gridColumns),
                // Top padding clears the Z=1 chrome (large title bar at top=12dp +
                // 64dp height = 76dp, segmented control at top=84dp + ~32dp = 116dp,
                // action row at top=132dp + ~28dp = 160dp). 168dp puts the first
                // row below all chrome with a small gap. Pure padding — no opaque
                // background div, so the wallpaper shows through the chrome above.
                contentPadding = PaddingValues(
                    start = 3.dp, end = 3.dp, top = 168.dp, bottom = 3.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                // 多选模式禁用 scrollable 指针抢占：DragSelectGesture 的拖拽快选
                // 需要独占 move 事件；程序化 dispatchRawDelta 自动滚动不受影响。
                userScrollEnabled = !selectModeEnabled,
                modifier = Modifier
                    .fillMaxSize()
                    // Pinch-to-zoom — 恒挂载。选择模式下 isSelectMode() 让它静默让位，
                    // 不消费事件；双指缩放由主手势让位。不再条件移除 modifier，
                    // 避免链结构变化重启所有 pointerInput 协程（长按进多选瞬间拖拽被杀）。
                    .then(
                        Modifier.pointerInput(Unit) {
                            with(PinchToZoomGesture(
                                defaultColumns = { gridColumns },
                                onColumnChange = { newCols ->
                                    if (newCols != gridColumns) gridColumns = newCols
                                },
                                onGestureEnd = { /* 弹簧吸附动画简化 */ },
                                isSelectMode = { selectModeEnabled },
                            )) { attach() }
                        }
                    )
                    // 统一网格手势：点击打开 / 长按进多选 / 多选下滑动快选。
                    // 常驻（始终挂载），key 稳定（gridState），gesture 实例顶层 remember，
                    // 拖拽期间 displaySections 变化不会重启协程。
                    .then(
                        Modifier.pointerInput(gridState) {
                            with(dragGesture) { attach() }
                        }
                    )
            ) {
                displaySections.forEach { section ->
                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = { "header" },
                    ) {
                        DayHeader(title = section.header)
                    }
                    items(
                        items = section.items,
                        key = { it.id },
                        contentType = { "media" },
                    ) { item ->
                        val isSelected = item.id in selectedIds
                        // remember(item.id) 冻结 onClick lambda 引用：捕获 State 引用而非
                        // 每帧重建 lambda。强跳过模式据此跳过未变化 cell 的整棵子树重组。
                        val onClick = remember(item.id) {
                            {
                                if (selectModeEnabled) {
                                    viewModel.toggleItemSelection(item.id)
                                } else {
                                    try {
                                        val all = flatItemsState.value
                                        val idx = all.indexOfFirst { it.id == item.id }
                                        com.smartvision.gallery.util.AppLog.i("Timeline",
                                            "click uri=${item.uri} idx=$idx flatSize=${all.size}")
                                        onOpenPhoto(all.map { it.uri }, idx)
                                    } catch (t: Throwable) {
                                        com.smartvision.gallery.util.AppLog.e("Timeline",
                                            "click handler crashed", t)
                                    }
                                }
                            }
                        }
                        TimelineCell(
                            item = item,
                            selectMode = selectModeEnabled,
                            isSelected = isSelected,
                            cellPx = cellPx,
                            thumbSizePx = thumbSizePx,
                            onClick = onClick,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Day section header — iOS 26 Photos style: large bold date label, no
 * subtitle / item count (iOS infers the count from the grid below).
 */
@Composable
private fun DayHeader(title: String) {
    Text(
        text = title,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 10.dp)
    )
}

/**
 * Single grid cell — iOS 26 Photos Library cell.
 *
 *  * Square 1:1 aspect ratio with 4dp corner radius (iOS uses ~4dp).
 *  * Video cells show a small filled play triangle at center and a tight
 *    duration badge in the bottom-left.
 *  * No format badge — iOS 26 doesn't overlay format on grid cells (the
 *    format appears in the photo detail view). Adding one here is a strong
 *    "Android native" tell.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineCell(
    item: MediaItem,
    selectMode: Boolean = false,
    isSelected: Boolean = false,
    cellPx: Int,
    thumbSizePx: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(7.dp))
            .background(
                if (isSelected) Color(0x33007AFF)
                else MaterialTheme.colorScheme.surfaceVariant
            )
    ) {
        AsyncThumbnail(
            model = item,
            sizePx = thumbSizePx,
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(if (isSelected) Modifier.alpha(0.6f) else Modifier),
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
            visible = selectMode,
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
        )
        if (item.isFavorite && !selectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Star,
                    contentDescription = "已收藏",
                    tint = Color(0xFFFFCC00),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (item.isVideo) {
            // iOS 26 uses a small filled play triangle at center, not a
            // 36dp circle. The triangle reads "video" without dominating
            // the thumbnail.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.32f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(R.string.video),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (item.isVideo && item.durationMs != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(
                        Color.Black.copy(alpha = 0.42f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    formatDuration(item.durationMs),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.1.sp
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onFirstFrame: () -> Unit) {
    LaunchedEffect(Unit) { onFirstFrame() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasPermission = remember {
        com.smartvision.gallery.util.PermissionHelper.hasStoragePermission(context)
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (!hasPermission) {
                // Permission revoked mid-session (e.g. disabled in system settings)
                // while the DB is empty — without this exit the grid is a permanent
                // spinner. Offer a path back to the system settings page.
                Text(
                    "媒体权限已被关闭，无法读取相册。",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        val i = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.fromParts("package", context.packageName, null))
                        context.startActivity(i)
                    },
                    shape = RoundedCornerShape(16.dp),
                ) { Text("前往设置", fontSize = 15.sp) }
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    stringResource(R.string.empty_no_media),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

/**
 * Re-group daily sections by month for the "日月年" segment.
 * Merges all items whose date falls in the same calendar month
 * under a single header like "2026年6月". Sections stay in
 * reverse chronological order.
 */
private fun bucketByMonth(sections: List<TimelineSection>): List<TimelineSection> {
    if (sections.isEmpty()) return emptyList()
    val cal = Calendar.getInstance()
    val monthGroups = sections.flatMap { it.items }.groupBy { item ->
        cal.timeInMillis = item.dateTakenMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }.toSortedMap(reverseOrder())
    val monthFmt = java.text.SimpleDateFormat("yyyy年M月", java.util.Locale.CHINESE)
    return monthGroups.map { (startMs, items) ->
        TimelineSection(
            header = monthFmt.format(java.util.Date(startMs)),
            bucketStartMs = startMs,
            items = items
        )
    }
}