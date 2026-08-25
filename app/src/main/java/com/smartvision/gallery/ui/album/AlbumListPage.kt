package com.smartvision.gallery.ui.album

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import coil.size.Size
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.data.model.Album
import com.smartvision.gallery.data.model.AlbumKind
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.ui.apple.iOSListRow
import com.smartvision.gallery.ui.apple.iOSRowTrailing
import com.smartvision.gallery.ui.components.AsyncThumbnail
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard

/**
 * "精选" / Collections page — iOS 26 Photos inspired.
 *
 * Layout (top-to-bottom, each section collapsible via chevron):
 *  1. **Memories hero card** — large glass card with a photo collage + Apple
 *     Intelligence "回忆" affordance. Mirrors iOS 26's first-ever Collections
 *     section.
 *  2. **我的项目** (Pinned) — Favorites, Hidden Vault, Trash.
 *  3. **精选集** (Featured) — 本周精选 / 回忆之旅 / 人像 / 格式挑战.
 *  4. **媒体类型** (Media Types) — 2-column grid of format/bucket albums.
 *  5. **设置** (Settings) — gateway into app settings, surfaced here so the
 *     bottom tab bar can stay at the iOS-26 three-tab count.
 */
@Composable
fun AlbumListPage(
    onOpenAlbum: (albumId: String) -> Unit,
    onOpenPhoto: (List<android.net.Uri>, Int) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    val vm: AlbumListViewModel = viewModel(factory = AlbumListViewModel.factory(app.mediaRepository))
    val pinned by vm.pinnedAlbums.collectAsState()
    val mediaAlbums by vm.mediaAlbums.collectAsState()
    val memoryPhotos by vm.memoryPhotos.collectAsState()
    val memoryClusters by vm.memoryClusters.collectAsState()
    val formatChallengeCover by vm.formatChallengeCover.collectAsState()
    val aiCategoryFolders by vm.aiCategoryFolders.collectAsState()
    val animeCategoryFolders by vm.animeCategoryFolders.collectAsState()

    // Pre-compute the two pixel sizes the page needs once per composition
    // instead of letting every AsyncThumbnail fall back to the
    // BoxWithConstraints overload (which subcomposes a measure pass per
    // cell). The page uses three known dp geometries: hero (full width ×
    // 180dp), curated card (120 × 152dp), media-types card (½ screen ×
    // 120dp image area). One density + configuration read at the top,
    // then pass `Size` straight into AsyncThumbnail so Coil's
    // `calculateInSampleSize` short-circuits decode to exactly the
    // pixels the cell renders at — 4K source → ~360px decode instead of
    // 24 MB ARGB_8888.
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val heroSize = remember(density, configuration.screenWidthDp) {
        with(density) {
            Size(configuration.screenWidthDp.dp.roundToPx(), 180.dp.roundToPx())
        }
    }
    val curatedSize = remember(density) {
        with(density) { Size(120.dp.roundToPx(), 152.dp.roundToPx()) }
    }
    val mediaCardSize = remember(density, configuration.screenWidthDp) {
        // 2-column grid: (screenW - 32dp padding - 12dp gap) / 2, minus
        // 16dp internal padding on both sides of the LiquidGlassCard.
        val wDp = (configuration.screenWidthDp.toFloat() - 32f - 12f) / 2f
        val wPx = with(density) { (wDp - 16f).coerceAtLeast(1f).dp.roundToPx() }
        val hPx = with(density) { 120.dp.roundToPx() }
        Size(wPx, hPx)
    }

    // 错峰预解码 — 进入精选页时一次性把所有媒体类型卡 + 精选集卡的封面
    // enqueue 到 Coil，按 80ms 间隔错开避免 binder / 解码池瞬时打满。
    // 缓存命中后 AsyncImage 直接从内存读，零延迟上屏。
    // 去重键 = uri 字符串，避免每次 mediaAlbums 列表 instance 变都重跑。
    val coverPreheatKey = remember(mediaAlbums, aiCategoryFolders, animeCategoryFolders) {
        buildList {
            mediaAlbums.forEach { a -> a.coverUri?.toString()?.let(::add) }
            aiCategoryFolders.forEach { c -> c.coverUri?.toString()?.let(::add) }
            animeCategoryFolders.forEach { c -> c.coverUri?.toString()?.let(::add) }
        }.joinToString("\n")
    }
    LaunchedEffect(coverPreheatKey, mediaCardSize, curatedSize) {
        if (coverPreheatKey.isEmpty()) return@LaunchedEffect
        val loader = app.imageLoader
        val seen = HashSet<String>()
        val targets = buildList {
            mediaAlbums.forEach { a ->
                val u = a.coverUri ?: return@forEach
                if (seen.add(u.toString())) add(u to mediaCardSize)
            }
            aiCategoryFolders.forEach { c ->
                val u = c.coverUri ?: return@forEach
                if (seen.add(u.toString())) add(u to curatedSize)
            }
            animeCategoryFolders.forEach { c ->
                val u = c.coverUri ?: return@forEach
                if (seen.add(u.toString())) add(u to curatedSize)
            }
        }
        targets.forEach { (uri, size) ->
            loader.enqueue(
                ImageRequest.Builder(app)
                    .data(uri)
                    .size(size)
                    .scale(Scale.FILL)
                    .crossfade(false)
                    .allowHardware(true)
                    .memoryCacheKey(uri.toString())
                    .build()
            )
            kotlinx.coroutines.delay(80L)
        }
    }

    // iOS 26 lets users collapse individual sections with a chevron at the
    // header. Default state: everything expanded except the settings entry
    // which is collapsed to keep the top of the page focused.
    var pinnedExpanded by rememberSaveable { mutableStateOf(true) }
    var featuredExpanded by rememberSaveable { mutableStateOf(true) }
    var mediaExpanded by rememberSaveable { mutableStateOf(true) }
    var aiCategoriesExpanded by rememberSaveable { mutableStateOf(true) }
    var animeCategoriesExpanded by rememberSaveable { mutableStateOf(true) }

    // Top-bar config is owned by AppRoot's `LaunchedEffect(currentRoute)`
    // — see AppRoot.kt:215. Page-level `LaunchedEffect(Unit)` was racing
    // the central one on every navigation, and the prior page-level write
    // was a duplicate source of truth (review H12).

    // LazyColumn (instead of Column.verticalScroll) defers composition of
    // off-screen sections to when they're scrolled into view. The previous
    // Column.verticalScroll forced ALL sections — MemoriesHero, Pinned list,
    // CuratedCollectionsRow, MediaTypesGrid (6+ cards), Settings entry — to
    // compose on the very first frame after navigation, which is what caused
    // the visible jank when entering 精选 from 图库. With LazyColumn only the
    // viewport + small overscan composes; everything else is just zero-cost
    // scroll offsets until scrolled to.
    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = PaddingValues(top = 96.dp, bottom = 140.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // ---- Memories hero ----
        item(key = "memories-hero") {
            MemoriesHero(
                photos = memoryPhotos,
                heroSize = heroSize,
                onClick = {
                    if (memoryPhotos.isEmpty()) return@MemoriesHero
                    val list = memoryPhotos.map { it.uri }
                    onOpenPhoto(list, 0)
                }
            )
            Spacer(Modifier.height(8.dp))
        }

        // ---- 我的项目 (Pinned) ----
        item(key = "pinned-header") {
            SectionHeader(
                title = "我的项目",
                expanded = pinnedExpanded,
                onToggle = { pinnedExpanded = !pinnedExpanded }
            )
        }
        item(key = "pinned-body") {
            AnimatedVisibility(
                visible = pinnedExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                LiquidGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Column {
                        pinned.forEachIndexed { index, album ->
                            if (index > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                )
                            }
                            key(album.id) {
                                iOSListRow(
                                    title = album.name,
                                    subtitle = "${album.itemCount} 项",
                                    leading = iconForKind(album.kind),
                                    leadingTint = tintForKind(album.kind),
                                    trailing = iOSRowTrailing.Chevron,
                                    onClick = { onOpenAlbum(album.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "pinned-spacer") { Spacer(Modifier.height(20.dp)) }

        // ---- 精选集 (Featured) — 仅回忆之旅, AI 识别卡片已全数移至 AI 智能分类行 ----
        item(key = "featured-header") {
            SectionHeader(
                title = "精选集",
                expanded = featuredExpanded,
                onToggle = { featuredExpanded = !featuredExpanded }
            )
        }
        item(key = "featured-body") {
            AnimatedVisibility(
                visible = featuredExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (memoryClusters.isNotEmpty()) {
                        SectionLabel("回忆之旅")
                        MemoryClustersStrip(
                            clusters = memoryClusters,
                            cardSize = curatedSize,
                            onOpenMemory = { cluster ->
                                onOpenAlbum(cluster.id)
                            },
                        )
                    } else {
                        Text(
                            "暂无回忆之旅数据",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        item(key = "featured-spacer") { Spacer(Modifier.height(20.dp)) }

        // ---- AI 智能分类 (15 个子域) — 三行横滑卡 ----
        item(key = "ai-categories-header") {
            SectionHeader(
                title = "AI 智能分类",
                expanded = aiCategoriesExpanded,
                onToggle = { aiCategoriesExpanded = !aiCategoriesExpanded }
            )
        }
        if (aiCategoriesExpanded) {
            item(key = "ai-categories-body") {
                AnimatedVisibility(
                    visible = aiCategoriesExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 15 个 category 折成 3 行 × 5 列.
                        val rows = aiCategoryFolders.chunked(5)
                        rows.forEachIndexed { rowIndex, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { c ->
                                    AiCategoryTile(
                                        title = c.title,
                                        count = c.count,
                                        coverUri = c.coverUri,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenAlbum(c.albumId) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "ai-categories-spacer") { Spacer(Modifier.height(20.dp)) }

        // ---- 二次元分类 (v29) — 仅 aiDomain==anime 的照片按 Danbooru tag 分桶 ----
        item(key = "anime-categories-header") {
            SectionHeader(
                title = "二次元分类",
                expanded = animeCategoriesExpanded,
                onToggle = { animeCategoriesExpanded = !animeCategoriesExpanded }
            )
        }
        if (animeCategoriesExpanded) {
            item(key = "anime-categories-body") {
                AnimatedVisibility(
                    visible = animeCategoriesExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // 二次元分类固定 10 桶 + 动态角色专辑 (≤5), 折成 5 列.
                        val rows = animeCategoryFolders.chunked(5)
                        rows.forEachIndexed { rowIndex, row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                row.forEach { c ->
                                    AiCategoryTile(
                                        title = c.title,
                                        count = c.count,
                                        coverUri = c.coverUri,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onOpenAlbum(c.albumId) },
                                    )
                                }
                                // 不足 5 个补 Spacer 让 weight 对齐
                                if (row.size < 5) {
                                    repeat(5 - row.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item(key = "anime-categories-spacer") { Spacer(Modifier.height(20.dp)) }

        // ---- 媒体类型 (Media Types) — 2-column grid ----
        item(key = "media-header") {
            SectionHeader(
                title = "媒体类型",
                expanded = mediaExpanded,
                onToggle = { mediaExpanded = !mediaExpanded }
            )
        }
        // 媒体类型 grid 行 — 拆成外层 LazyColumn 多 item，每行一个。
        // 之前用单个 `item { Column { forEach { Row } } }` 一次性组合全部
        // ~10 张卡片，滑到该 section 时整组同时组合 + LiquidGlassCard
        // 液态玻璃背景流并发触发，引起卡顿。拆成 item 后 LazyColumn 只
        // 组合进入视口的行 + 小幅 overscan；未滑入的行零成本。
        if (mediaExpanded) {
            val rows = mediaAlbums.chunked(2)
            rows.forEachIndexed { rowIndex, rowAlbums ->
                item(key = "media-row-$rowIndex") {
                    AnimatedVisibility(
                        visible = mediaExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column {
                            MediaTypesRow(
                                rowAlbums = rowAlbums,
                                cardSize = mediaCardSize,
                                onOpenAlbum = onOpenAlbum,
                            )
                            // 卡片上下间距 — 原来相邻行贴在一起，视觉拥挤。
                            // 与其他 section 的 spacer 量级一致 (20dp)。
                            if (rowIndex < rows.lastIndex) {
                                Spacer(Modifier.height(20.dp))
                            }
                        }
                    }
                }
            }
        }

        item(key = "media-spacer") { Spacer(Modifier.height(24.dp)) }

        // ---- 设置 (Settings) gateway ----
        item(key = "settings") {
            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(0.dp),
                onClick = onOpenSettings
            ) {
                iOSListRow(
                    title = "设置",
                    subtitle = "外观、解码、隐私、云同步、关于",
                    leading = Icons.Outlined.Image,
                    leadingTint = Color(0xFF8E8E93),
                    trailing = iOSRowTrailing.Chevron
                )
            }
        }
    }
}

/* ----------------------- Memories hero ----------------------- */

@Composable
private fun MemoriesHero(
    photos: List<MediaItem>,
    heroSize: Size,
    onClick: () -> Unit,
) {
    if (photos.isEmpty()) return
    val heroUris = photos.take(3).map { it.uri }
    val extraCount = (photos.size - 3).coerceAtLeast(0)

    LiquidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(0.dp),
        onClick = onClick
    ) {
        Column {
            // Photo collage — overlapping horizontal stack, first photo larger
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            ) {
                if (heroUris.isNotEmpty()) {
                    AsyncThumbnail(
                        model = heroUris[0],
                        size = heroSize,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Combined top highlight + bottom darken in a single
                    // gradient pass — replaces the previous two Box overlays
                    // so the hero costs 1 drawRect instead of 2 on mount.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.00f to Color.White.copy(alpha = 0.20f),
                                        0.18f to Color.Transparent,
                                        0.55f to Color.Transparent,
                                        1.00f to Color.Black.copy(alpha = 0.45f),
                                    )
                                )
                            )
                    )
                }
                // "Type to Create" affordance — iOS 26 puts this on the
                // first memory card. We render the same shape with our
                // own glyph.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "回忆 · 输入描述创建",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.1).sp
                    )
                }
                // Play icon top-right (iOS 26 puts a play triangle on hero memories)
                if (photos.any { it.isVideo }) {
                    Icon(
                        imageVector = Icons.Outlined.PlayCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(28.dp)
                    )
                }
            }
            // Footer: title + photo count + extra badge
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "本周回忆",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${photos.size} 张照片 · 智能精选",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (extraCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "+$extraCount",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/* ----------------------- Section header ----------------------- */

@Composable
private fun SectionHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = 20.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            label = "chevronRotation"
        )
        Icon(
            imageVector = Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) "折叠" else "展开",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .rotate(rotation)
        )
    }
}

/* ----------------------- 精选集 (curated) ----------------------- */

@Composable
private fun CuratedCollectionsRow(
    collections: List<CuratedCollection>,
    cardSize: Size,
    onOpenAlbum: (String) -> Unit,
) {
    if (collections.isEmpty()) {
        Text(
            "暂无可精选的照片",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        return
    }
    // Plain Row + horizontalScroll instead of LazyRow — the list is fixed
    // at 4 items, and a LazyRow nested inside a verticalScroll Column
    // triggers extra subcomposition + an unbounded-height warning on some
    // devices. A Row is one fewer layer to measure on first mount.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        collections.forEach { collection ->
            key(collection.albumId, collection.title) {
                CuratedCard(
                    title = collection.title,
                    coverUri = collection.coverUri,
                    count = collection.count,
                    cardSize = cardSize,
                    onClick = { onOpenAlbum(collection.albumId) },
                )
            }
        }
    }
}

/**
 * AI 智能分类文件夹的小方块: 1:1 缩略图 + 标题 + 张数. count=0 时封面用
 * 半透明占位 + 灰文, 提示用户"该类暂未识别到". 点击调 onClick(albumId).
 */
@Composable
private fun AiCategoryTile(
    title: String,
    count: Int,
    coverUri: android.net.Uri?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (coverUri != null) androidx.compose.ui.graphics.Color.Transparent
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUri != null) {
                AsyncThumbnail(
                    model = coverUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    "—",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.05).sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Text(
            if (count > 0) "$count 张" else "暂无",
            fontSize = 10.sp,
            letterSpacing = 0.15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@Composable
private fun CuratedCard(
    title: String,
    coverUri: android.net.Uri?,
    count: Int,
    cardSize: Size,
    onClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        LiquidGlassCard(
            modifier = Modifier.size(width = 120.dp, height = 152.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (coverUri != null) {
                    AsyncThumbnail(
                        model = coverUri,
                        size = cardSize,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                // Gradient overlay for text legibility — subtler than before
                // since LiquidGlass's drawBackdrop already provides tint/highlight
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color(0xFF1F6FEB).copy(alpha = 0.30f),
                                    0.25f to Color.White.copy(alpha = 0.10f),
                                    0.50f to Color.Transparent,
                                    0.70f to Color.Black.copy(alpha = 0.15f),
                                    1.00f to Color.Black.copy(alpha = 0.40f),
                                )
                            )
                        )
                )
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            "$count 项",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

/* ----------------------- 媒体类型 grid ----------------------- */

@Composable
private fun MediaTypesRow(
    rowAlbums: List<Album>,
    cardSize: Size,
    onOpenAlbum: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rowAlbums.forEach { album ->
            Box(modifier = Modifier.weight(1f)) {
                key(album.id) {
                    AlbumGridCard(
                        album = album,
                        cardSize = cardSize,
                        onClick = { onOpenAlbum(album.id) },
                    )
                }
            }
        }
        if (rowAlbums.size == 1) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MediaTypesGrid(
    albums: List<Album>,
    cardSize: Size,
    onOpenAlbum: (String) -> Unit,
) {
    if (albums.isEmpty()) {
        Text(
            "暂无媒体类型",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        albums.chunked(2).forEach { rowAlbums ->
            MediaTypesRow(
                rowAlbums = rowAlbums,
                cardSize = cardSize,
                onOpenAlbum = onOpenAlbum,
            )
        }
    }
}

@Composable
private fun AlbumGridCard(album: Album, cardSize: Size, onClick: () -> Unit) {
    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(0.dp),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                if (album.coverUri != null) {
                    AsyncThumbnail(
                        model = album.coverUri,
                        size = cardSize,
                        contentDescription = album.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = iconForKind(album.kind),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                album.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.1).sp,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "${album.itemCount} 项",
                fontSize = 11.sp,
                letterSpacing = 0.15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun iconForKind(kind: AlbumKind): ImageVector = when (kind) {
    AlbumKind.FAVORITES -> Icons.Outlined.Favorite
    AlbumKind.TRASH -> Icons.Outlined.History
    AlbumKind.HIDDEN_VAULT -> Icons.Outlined.Lock
    else -> Icons.Outlined.Image
}

private fun tintForKind(kind: AlbumKind): Color = when (kind) {
    AlbumKind.FAVORITES -> Color(0xFFFF3B6E)
    AlbumKind.TRASH -> Color(0xFF8E8E93)
    AlbumKind.HIDDEN_VAULT -> Color(0xFF34C759)
    else -> Color(0xFF1F6FEB)
}

/* ----------------------- 双行精选 (real / anime) 副标题 + 回忆之旅 ----------------------- */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun MemoryClustersStrip(
    clusters: List<com.smartvision.gallery.data.repo.MemoryCluster>,
    cardSize: Size,
    onOpenMemory: (com.smartvision.gallery.data.repo.MemoryCluster) -> Unit,
) {
    if (clusters.isEmpty()) {
        Text(
            "暂无回忆聚类",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            clusters.forEach { cluster ->
                MemoryClusterCard(
                    cluster = cluster,
                    cardSize = cardSize,
                    onClick = { onOpenMemory(cluster) },
                )
            }
        }
    }
}

@Composable
private fun MemoryClusterCard(
    cluster: com.smartvision.gallery.data.repo.MemoryCluster,
    cardSize: Size,
    onClick: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as SmartVisionApp
    Column(
        modifier = Modifier
            .size(width = 132.dp, height = 168.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncThumbnail(
            model = cluster.heroUri,
            size = Size(cardSize.width, cardSize.height),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${cluster.bucketLabel} · ${cluster.count} 张",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        cluster.geoLabel?.let { geo ->
            Text(
                geo,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    // Suppress unused-app warning by avoiding raw `app` reference (kept for future Coil hooks).
    @Suppress("UNUSED_EXPRESSION") app
}