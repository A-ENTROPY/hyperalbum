package com.smartvision.gallery.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartvision.gallery.R
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.ui.components.AsyncThumbnail
import com.smartvision.gallery.ui.components.GridSelectionBadge
import com.smartvision.gallery.ui.components.GridSelectionBar
import com.smartvision.gallery.ui.components.rememberGridCellSizePx
import com.smartvision.gallery.ui.components.rememberGridThumbSizePx
import com.smartvision.gallery.ui.gestures.DragSelectGesture
import com.smartvision.gallery.ui.gestures.PinchToZoomGesture
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSpec
import com.smartvision.gallery.ui.liquidglass.LiquidGlassSurface
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.data.glass.toSpec

/**
 * Search page — iOS Photos inspired.
 *
 *  * Large title "搜索"
 *  * Big rounded Liquid Glass search bar at the top
 *  * Quick-access chip row: 收藏 / 地点 / 最近 / 实况 (all Liquid Glass)
 *  * "精选集" row below shows location-grouped memories when idle
 *  * Results grid (when query active) or mode-specific UI (when idle)
 *
 *  Back behavior: when the user has entered any mode or typed any query, the
 *  system back gesture (PredictiveBack + hardware Back + 左滑) resets the
 *  search to idle rather than popping the Search tab. This matches iOS
 *  Photos behavior — going back from "Recent" should not exit the Search tab.
 */
@Composable
fun SearchPage(onOpenPhoto: (List<android.net.Uri>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(app.mediaRepository))
    val query by vm.query.collectAsState()
    val results by vm.results.collectAsState()
    val searchMode by vm.searchMode.collectAsState()
    val searchModeLabel by vm.searchModeLabel.collectAsState()
    val locationGroups by vm.locationGroups.collectAsState()

    BackHandler(enabled = searchMode != SearchMode.TEXT || query.isNotBlank()) {
        vm.resetToIdle()
    }

    // Z=0 content: Box (not Column). The floating search bar overlays the
    // content as a sibling — no opaque "div above the images" constraining
    // the scroll area. Grids scroll under the frosted bar; wallpaper shows
    // through the bar via LocalLiquidGlassScreenBackdrop. Same architecture
    // as TimelinePage: contentPadding top clears the floating chrome, the
    // chrome itself is rendered as a floating glass surface above Z=0.
    //
    // Top bar (LargeTitleBar, 64dp + 12dp top = 76dp) is rendered by
    // FloatingTopBarPill at Z=1 in AppRoot. The search bar floats just
    // below it.
    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val glassCfg = LocalGlassConfig.current
        val searchBarSpec = glassCfg.searchBar.toSpec()

        when {
            query.isNotBlank() -> {
                if (results.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.empty_search_no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    ResultsGrid(results, onOpenPhoto)
                }
            }
            searchMode == SearchMode.LOCATION -> LocationGroupsGrid(locationGroups, onOpenPhoto)
            searchMode == SearchMode.TEXT -> QuickAccess(vm, locationGroups, searchMode)
            else -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = searchModeLabel,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    if (results.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "暂无${searchModeLabel}内容",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        ResultsGrid(results, onOpenPhoto)
                    }
                }
            }
        }

        // Floating search bar — overlay, not a layout divider. Padding top
        // = 88dp (76dp top bar + 12dp gap) clears the LargeTitleBar above.
        LiquidGlassCard(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = 16.dp, end = 16.dp, top = 88.dp)
                .fillMaxWidth(),
            spec = searchBarSpec,
            shape = RoundedCornerShape(searchBarSpec.cornerRadius.coerceAtLeast(8.dp)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ResultsGrid(results: List<MediaItem>, onOpenPhoto: (List<android.net.Uri>, Int) -> Unit) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val repo = app.mediaRepository
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val gridState = rememberLazyGridState()

    var gridColumns by rememberSaveable { mutableIntStateOf(3) }
    var selectModeEnabled by rememberSaveable { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val autoScrollSpeed = remember { mutableStateOf(0f) }

    BackHandler(enabled = selectModeEnabled) {
        selectModeEnabled = false
        selectedIds = emptySet()
    }

    // 自动滚动：速度 0 时不轮询 (10ms 空转白耗 CPU/电量)。
    LaunchedEffect(autoScrollSpeed.value) {
        val speed = autoScrollSpeed.value
        if (speed == 0f) return@LaunchedEffect
        while (isActive) {
            gridState.scrollBy(speed * 0.01f)
            delay(10)
        }
    }
    val cellPx = rememberGridCellSizePx(columnCount = gridColumns)
    val thumbSizePx = rememberGridThumbSizePx(columnCount = gridColumns)

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectModeEnabled) {
            GridSelectionBar(
                selectedCount = selectedIds.size,
                onCancel = {
                    selectModeEnabled = false
                    selectedIds = emptySet()
                },
                onSelectAll = {
                    selectedIds = results.map { it.id }.toSet()
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val uris = selectedIds.mapNotNull { id -> results.firstOrNull { it.id == id }?.uri }
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
                                .onFailure { com.smartvision.gallery.util.AppLog.w("Search", "share failed", it) }
                            selectModeEnabled = false
                            selectedIds = emptySet()
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "分享")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            val uris = selectedIds.mapNotNull { id -> results.firstOrNull { it.id == id }?.uri }
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
            // top=144dp clears the floating search bar (88dp top + ~52dp
            // height). Lets the grid scroll under the frosted bar — same
            // pattern as TimelinePage's 168dp top padding for its chrome.
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 144.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            // 多选模式禁用 scrollable 指针抢占：拖拽快选需独占 move；自动滚动不受影响。
            userScrollEnabled = !selectModeEnabled,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (selectModeEnabled) Modifier
                    else Modifier.pointerInput(Unit) {
                        with(PinchToZoomGesture(
                            defaultColumns = { gridColumns },
                            onColumnChange = { newCols ->
                                if (newCols != gridColumns) gridColumns = newCols
                            },
                            onGestureEnd = {},
                        )) { attach() }
                    }
                )
                .then(
                    Modifier.pointerInput(results, gridColumns) {
                        with(DragSelectGesture(
                            gridState = gridState,
                            isItemSelected = { idx ->
                                results.getOrNull(idx)?.id in selectedIds
                            },
                            onToggleItem = { idx ->
                                val item = results.getOrNull(idx)
                                if (item != null) {
                                    if (item.id in selectedIds) selectedIds = selectedIds - item.id
                                    else selectedIds = selectedIds + item.id
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
                            isSelectMode = { selectModeEnabled },
                            onEnterSelectMode = { selectModeEnabled = true },
                            onTapItem = { idx ->
                                val item = results.getOrNull(idx)
                                if (item != null) {
                                    if (selectModeEnabled) {
                                        selectedIds = if (item.id in selectedIds) selectedIds - item.id
                                                        else selectedIds + item.id
                                    } else {
                                        val list = results.map { it.uri }
                                        val openIdx = list.indexOf(item.uri).coerceAtLeast(0)
                                        onOpenPhoto(list, openIdx)
                                    }
                                }
                            },
                        )) { attach() }
                    }
                ),
        ) {
            items(results, key = { it.id }) { item ->
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
                        modifier = Modifier.fillMaxWidth()
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
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAccess(vm: SearchViewModel, locationGroups: List<LocationGroup>, currentMode: SearchMode) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 144.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column {
        Text(
            text = "快速访问".uppercase(),
            fontSize = 12.sp,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp, bottom = 10.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QuickChip(
                icon = Icons.Outlined.Favorite,
                label = "收藏",
                selected = currentMode == SearchMode.FAVORITES,
                modifier = Modifier.weight(1f),
            ) { vm.setMode(SearchMode.FAVORITES) }
            // Context 在 Composable scope 外层获取一次，QuickChip 的 onClick 是普通 lambda。
            val ctx = LocalContext.current
            QuickChip(
                icon = Icons.Outlined.Place,
                label = "地点",
                selected = currentMode == SearchMode.LOCATION,
                modifier = Modifier.weight(1f),
            ) {
                // 地点功能依赖 ACCESS_MEDIA_LOCATION（Android 10+ 读取 EXIF GPS）。
                // 未授权时 Toast 提示，不主动弹授权框。
                if (com.smartvision.gallery.util.LocationPermissionHint.ensureOrHint(ctx)) {
                    vm.setMode(SearchMode.LOCATION)
                }
            }
            QuickChip(
                icon = Icons.Outlined.Camera,
                label = "最近",
                selected = currentMode == SearchMode.RECENT,
                modifier = Modifier.weight(1f),
            ) { vm.setMode(SearchMode.RECENT) }
            QuickChip(
                icon = Icons.Outlined.Image,
                label = "实况",
                selected = currentMode == SearchMode.LIVE_PHOTOS,
                modifier = Modifier.weight(1f),
            ) { vm.setMode(SearchMode.LIVE_PHOTOS) }
        }

        Text(
            text = "精选集".uppercase(),
            fontSize = 13.sp,
            letterSpacing = 0.8.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 24.dp, bottom = 12.dp)
        )

        if (locationGroups.isEmpty()) {
            CuratedEmptyHint()
        } else {
            val ctx = LocalContext.current
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                locationGroups.take(5).forEach { group ->
                    CuratedMemoryRow(
                        group = group,
                        onClick = {
                            // 精选集地点 row 也是地点入口 — 同样需要 ACCESS_MEDIA_LOCATION。
                            // 注意：如果扫描时未授权，DB 的 lat/lng 全为 null，
                            // locationGroups 会是空的，CuratedEmptyHint 已显示。
                            // 这里仅在有内容可显示但权限被撤销的边缘场景下 Toast 提示。
                            if (com.smartvision.gallery.util.LocationPermissionHint.ensureOrHint(ctx)) {
                                vm.setMode(SearchMode.LOCATION)
                            }
                        },
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun CuratedEmptyHint() {
    val spec = LocalGlassConfig.current.staticGlass.toSpec()
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        spec = spec,
        shape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "还没有带地点的照片",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    "开启相机定位权限后，精彩瞬间会自动按地点聚合在这里。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CuratedMemoryRow(
    group: LocationGroup,
    onClick: () -> Unit,
) {
    val spec = LocalGlassConfig.current.staticGlass.toSpec()
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        spec = spec,
        shape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(14.dp)),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
            ) {
                AsyncThumbnail(
                    model = group.coverUri,
                    contentDescription = group.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    "${group.count} 张照片",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LocationGroupsGrid(
    groups: List<LocationGroup>,
    onOpenPhoto: (List<android.net.Uri>, Int) -> Unit,
) {
    if (groups.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无带位置的照片",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val spec = LocalGlassConfig.current.staticGlass.toSpec()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 144.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(groups, key = { it.label }) { group ->
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth(),
                spec = spec,
                shape = RoundedCornerShape(spec.cornerRadius.coerceAtLeast(14.dp)),
                onClick = {
                    val flat = group.items.map { it.uri }
                    if (flat.isNotEmpty()) onOpenPhoto(flat, 0)
                },
                contentPadding = PaddingValues(bottom = 10.dp),
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.4f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                    ) {
                        AsyncThumbnail(
                            model = group.coverUri,
                            contentDescription = group.label,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Text(
                        group.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp),
                        maxLines = 1,
                    )
                    Text(
                        "${group.count} 张",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 参考 iOS Photos：圆形玻璃图标容器 + 大图标 + 选中态主色高亮。
    // 容器用 LiquidGlassSurface（无内置 ripple）配 CircleShape，外层 Box.clickable
    // 提供点击反馈。这样玻璃纹理完整覆盖到圆形边缘，避免 LiquidGlassCard 方形容器
    // 与圆形图标视觉打架的问题。
    val glassCfg = LocalGlassConfig.current
    val spec = glassCfg.control.toSpec().copy(cornerRadius = 999.dp)
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 圆形玻璃图标容器 — 选中时叠加主色径向渐变，未选中时只有玻璃纹理
        Box(
            modifier = Modifier
                .size(56.dp)
                .shadow(
                    elevation = if (selected) 10.dp else 0.dp,
                    shape = CircleShape,
                    ambientColor = primary.copy(alpha = if (selected) 0.40f else 0f),
                    spotColor = primary.copy(alpha = if (selected) 0.55f else 0f),
                )
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // 底层：液态玻璃 — 始终存在，确保未选中也有玻璃质感
            LiquidGlassSurface(
                modifier = Modifier.fillMaxSize(),
                spec = spec,
                shape = CircleShape,
                propagateMinConstraints = true,
            ) {}
            // 选中态：叠加主色径向渐变 + 微高光，给玻璃加一层"亮起来"的视觉
            if (selected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    primary.copy(alpha = 0.55f),
                                    primary.copy(alpha = 0.30f),
                                ),
                                radius = 60f,
                            )
                        )
                )
                // 顶部高光条纹
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                                startY = 0f,
                                endY = 28f,
                            )
                        )
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) onPrimary else primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurface,
            letterSpacing = 0.1.sp,
        )
    }
}