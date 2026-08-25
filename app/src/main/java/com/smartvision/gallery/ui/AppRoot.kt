package com.smartvision.gallery.ui

import coil.ImageLoader
import coil.compose.LocalImageLoader
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.smartvision.gallery.SmartVisionApp
import com.smartvision.gallery.ui.album.AlbumDetailChrome
import com.smartvision.gallery.ui.album.AlbumDetailChromeState
import com.smartvision.gallery.ui.album.AlbumListPage
import com.smartvision.gallery.ui.album.LocalAlbumDetailChromeState
import com.smartvision.gallery.ui.album.AlbumsPage
import com.smartvision.gallery.ui.apple.iOSTabBar
import com.smartvision.gallery.ui.apple.iOSTabItem
import com.smartvision.gallery.ui.components.AppImageLoaderFactory
import com.smartvision.gallery.ui.glass.GlassConfigPanel
import com.smartvision.gallery.ui.glass.GlassConfigViewModel
import com.smartvision.gallery.ui.liquidglass.LiquidGlassTheme
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.data.glass.toSpec
import com.smartvision.gallery.ui.liquidglass.LocalGlassConfig
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassLens
import com.smartvision.gallery.ui.liquidglass.LocalSegmentedControlLens
import com.smartvision.gallery.ui.liquidglass.FloatingTopBarPill
import com.smartvision.gallery.ui.liquidglass.LocalLiquidGlassScreenBackdrop
import com.smartvision.gallery.ui.liquidglass.LiquidGlassLensOverlay
import com.smartvision.gallery.ui.liquidglass.LocalLensOverlayIconState
import com.smartvision.gallery.ui.liquidglass.LocalSegmentedControlBackdropState
import com.smartvision.gallery.ui.liquidglass.SegmentedControlLensOverlay
import com.smartvision.gallery.ui.liquidglass.LocalLibraryOverlayState
import com.smartvision.gallery.ui.liquidglass.LocalTopBarState
import com.smartvision.gallery.ui.liquidglass.LibraryOverlay
import com.smartvision.gallery.ui.liquidglass.LibraryOverlayState
import com.smartvision.gallery.ui.liquidglass.LensOverlayIconInfo
import com.smartvision.gallery.ui.liquidglass.TopBarConfig
import com.smartvision.gallery.ui.liquidglass.TopBarVariant
import com.smartvision.gallery.ui.liquidglass.rememberLiquidGlassLensController
import com.smartvision.gallery.ui.liquidglass.WallpaperGlassBackground
import com.smartvision.gallery.ui.pages.TimelinePage
import com.smartvision.gallery.ui.search.SearchPage
import com.smartvision.gallery.ui.settings.SettingsPage
import com.smartvision.gallery.ui.viewer.PhotoViewerActivity
import com.smartvision.gallery.ui.viewer.PhotoViewerPage

/**
 * Root navigation surface. Single-activity, single-graph.
 *
 * V3 layout — drops Material3 [androidx.compose.material3.Scaffold] entirely
 * (its `bottomBar` slot was intercepting taps on the content underneath,
 * breaking click-through). The new layout is a single [Box] with Z-stacked
 * children:
 *
 *  1. Z = 0 — `ContentBox` carrying `Modifier.layerBackdrop(L)`. The NavHost
 *     lives here; L captures only this subtree.
 *  2. Z = 1 — `iOSTabBar` aligned to the bottom of the Box. It samples L
 *     via [LocalLiquidGlassScreenBackdrop] and is a SIBLING of the
 *     captured subtree (no recursion / no stack overflow).
 *  3. Z = 2 — `LiquidGlassLensOverlay` (the iOS 26 long-press magnifier).
 *
 * The Liquid Glass tuning config comes from [SmartVisionApp.glassConfigRepository]
 * via [GlassConfigViewModel] and is provided as [LocalGlassConfig] so every
 * glass surface in the tree re-renders when a slider moves.
 */
@Composable
fun AppRoot(
    permissionState: com.smartvision.gallery.ui.permission.PermissionState = com.smartvision.gallery.ui.permission.PermissionState.Granted,
    onRequestPermission: () -> Unit = {},
    onOpenAppSettings: () -> Unit = {},
    onFirstFrame: () -> Unit,
) {
    val denied = permissionState is com.smartvision.gallery.ui.permission.PermissionState.Denied
    if (denied) {
        PermissionGuidePage(
            state = permissionState as com.smartvision.gallery.ui.permission.PermissionState.Denied,
            onRequestPermission = onRequestPermission,
            onOpenAppSettings = onOpenAppSettings,
        )
        LaunchedEffect(Unit) { onFirstFrame() }
        return
    }
    // Loading: minimal splash — the system permission dialog is showing or
    // about to show; no guide page yet. MUST carry an opaque background — the
    // app window is transparent (FLAG_SHOW_WALLPAPER + transparent
    // windowBackground, set in MainActivity for Liquid Glass). Without this
    // background the system wallpaper bleeds through during the cold-start
    // window before the permission dialog appears, looking unpolished.
    if (permissionState is com.smartvision.gallery.ui.permission.PermissionState.Loading) {
        Box(Modifier.fillMaxSize()) {
            // Full-screen background BEHIND the system bars — the app window
            // is transparent (FLAG_SHOW_WALLPAPER), so the gradient must cover
            // the whole window including status/nav bar strips or the system
            // wallpaper bleeds through there.
            WallpaperGlassBackground()
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
                contentAlignment = Alignment.Center,
            ) {
                // 主题自适应 — 白色 spinner 在 light mode 米色底上不可见。
                val spinnerColor = if (isSystemInDarkTheme()) {
                    Color.White.copy(alpha = 0.6f)
                } else {
                    Color(0xFF5B7FFF)
                }
                androidx.compose.material3.CircularProgressIndicator(
                    color = spinnerColor,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        return
    }

    LiquidGlassTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val currentRoute = backStack?.destination?.route
        val context = LocalContext.current
        val app = context.applicationContext as SmartVisionApp
        // remember: 每次重组都重新 create() 会重建 Coil ImageLoader,
        // 清空内存缓存 (滚动时缩略图闪烁/反复加载). context 不变即复用.
        val imageLoader: ImageLoader = remember { AppImageLoaderFactory.create(context) }
        val lensController = rememberLiquidGlassLensController()
        val segLensController = rememberLiquidGlassLensController()
        val glassVm = remember { GlassConfigViewModel(app.glassConfigRepository) }
        val glassConfig by glassVm.config.collectAsState()

        // The single source of truth that every glass surface reads from.
        // Created here so it lives across the content + glass overlay
        // composition; provided to all children via [LocalLiquidGlassScreenBackdrop].
        val liquidBackdrop = rememberLayerBackdrop()
        // barBackdrop — captured by the hidden backdrop-only tab bar (Z=1a
        // twin of the visible iOSTabBar at Z=1b). The hidden twin renders
        // with always-cyan icons so the LiquidGlassLensOverlay can composite
        // those cyan icons through its lens shader at the pixel level
        // (matching iOS 26's per-pixel highlight behavior). Without this
        // twin, the lens could only refract the page backdrop — the bar's
        // own icons would never appear inside the magnifier.
        val barBackdrop = rememberLayerBackdrop()

        // Mutable state for native icon rendering inside the lens overlay.
        // Written by iOSTabBar on each layout change; read by LiquidGlassLensOverlay
        // to position cyan-tinted icons clipped to the lens shape.
        val lensOverlayIconState = remember { mutableStateOf<LensOverlayIconInfo?>(null) }

        // Mutable state for segmented control backdrop. Written by LibraryOverlay
        // (hidden backdrop-only capture), read by SegmentedControlLensOverlay.
        val segBackdropState = remember { mutableStateOf<com.kyant.backdrop.backdrops.LayerBackdrop?>(null) }

        // Page-level top bar config — written by each page composable (Z=0)
        // and read by FloatingTopBarPill (Z=1). Uses a MutableStateFlow so
        // pages can do `topBar.value = ...` and reads observe the change.
        val topBarState = remember {
            kotlinx.coroutines.flow.MutableStateFlow(TopBarConfig())
        }

        // Library page chrome — action row (选择/排序 chips) and segmented
        // control (全部/日月年/选择) — written by TimelinePage (segment
        // state + scroll collapse ratio), read by Z=1 LibraryOverlay so
        // these surfaces float above the photo grid as siblings of the
        // captured subtree (full Liquid Glass physics over real photo content,
        // same pattern as the bottom tab bar).
        val libraryOverlayState = remember { mutableStateOf(LibraryOverlayState()) }

        // Pending segment requested by another page (e.g. Albums) — TimelinePage consumes
        // it on first composition via `remember { onConsumePendingSegment() ?: 0 }`.
        // This survives the round-trip because rememberSaveable in TimelinePage
        // reads the value BEFORE LaunchedEffect(Unit) overwrites libraryOverlayState.
        val pendingTimelineSegment = remember { mutableStateOf<Int?>(null) }

        // Dedicated overlay state for AlbumsPage — shares segment with TimelinePage
        // so popping back lands on the right segment (e.g. tap "日月年" on Albums → returns
        // to Timeline with segment=1).
        val albumsOverlayState = remember {
            LibraryOverlayState(
                segment = 2,
                onSegmentChange = { selected ->
                    if (selected != 2) {
                        pendingTimelineSegment.value = selected
                        navController.popBackStack()
                    }
                },
            )
        }

        // AlbumDetailPage chrome state — written by AlbumDetailPage (Z=0),
        // read by AlbumDetailChrome (Z=1, sibling of layerBackdrop Box).
        val albumDetailChromeState = remember { mutableStateOf(AlbumDetailChromeState()) }

        CompositionLocalProvider(
            LocalImageLoader provides imageLoader,
            LocalLiquidGlassLens provides lensController,
            LocalSegmentedControlLens provides segLensController,
            LocalLiquidGlassScreenBackdrop provides liquidBackdrop,
            LocalGlassConfig provides glassConfig,
            LocalLensOverlayIconState provides lensOverlayIconState,
            LocalSegmentedControlBackdropState provides segBackdropState,
            LocalTopBarState provides topBarState,
            LocalLibraryOverlayState provides libraryOverlayState,
            LocalAlbumDetailChromeState provides albumDetailChromeState,
        ) {
            // Consume the pending segment SYNCHRONOUSLY in AppRoot's composition,
            // BEFORE the Box below composes its children (NavHost, LibraryOverlay).
            //
            // Why this is needed: AppRoot reads `libraryOverlayState.value` and
            // passes it to LibraryOverlay at its composition time. If we leave
            // the consume to TimelinePage (a child of NavHost), TimelinePage
            // updates libraryOverlayState AFTER AppRoot has already read the
            // stale value. The first frame is rendered with AppRoot's stale
            // snapshot (segment=0, the default), the second frame is rendered
            // with the new value (segment=1) after AppRoot recomposes. The user
            // sees a one-frame flicker where the wrong button is highlighted.
            //
            // By consuming here, the remember(pendingValue) block runs BEFORE
            // AppRoot's children compose, so libraryOverlayState.value is
            // already correct when AppRoot reads it to pass to LibraryOverlay.
            //
            // AppRoot is invalidated by reading pendingTimelineSegment.value
            // (State subscription), so it re-composes whenever AlbumsPage sets
            // the pending value — which is exactly when we want the consume.
            val pendingValue = pendingTimelineSegment.value
            if (pendingValue != null) {
                remember(pendingValue) {
                    pendingTimelineSegment.value = null
                    libraryOverlayState.value =
                        libraryOverlayState.value.copy(segment = pendingValue)
                }
            }

            // Centralize top bar config based on currentRoute. This fixes the
            // bug where the top bar stayed stale (e.g., "图集" COMPACT with back
            // arrow) after navigating Timeline → 图集 → Timeline. Previously
            // each page set topBar via `LaunchedEffect(Unit) { topBar.value = ... }`,
            // which only ran once per composable instance — if NavHost re-uses
            // the composable on popBackStack, the LaunchedEffect doesn't re-run
            // and the top bar stays as the previous page's config.
            //
            // By keying on currentRoute (changes on every navigation),
            // AppRoot reliably writes the correct top bar for the current page.
            //
            // Routes with dynamic titles (AlbumDetail — title is albumName) keep
            // their page-level LaunchedEffect and are NOT touched here.
            LaunchedEffect(currentRoute) {
                topBarState.value = if (currentRoute?.startsWith(Routes.ALBUM_DETAIL) == true) {
                    TopBarConfig(
                        title = "",
                        variant = TopBarVariant.HIDDEN,
                    )
                } else when (currentRoute) {
                    Routes.TIMELINE -> TopBarConfig(
                        title = "图库",
                        variant = TopBarVariant.LARGE_TITLE,
                    )
                    Routes.ALBUMS -> TopBarConfig(
                        title = "图集",
                        variant = TopBarVariant.COMPACT,
                        onBack = { navController.popBackStack() },
                    )
                    Routes.CURATED -> TopBarConfig(
                        title = "精选",
                        variant = TopBarVariant.LARGE_TITLE,
                    )
                    Routes.SEARCH -> TopBarConfig(
                        title = "搜索",
                        variant = TopBarVariant.LARGE_TITLE,
                    )
                    Routes.SETTINGS -> TopBarConfig(
                        title = "设置",
                        variant = TopBarVariant.LARGE_TITLE,
                    )
                    Routes.PRIVACY -> TopBarConfig(
                        title = "隐私空间",
                        variant = TopBarVariant.LARGE_TITLE,
                        onBack = { navController.popBackStack() },
                    )
                    Routes.TRASH -> TopBarConfig(
                        title = "回收站",
                        variant = TopBarVariant.COMPACT,
                        onBack = { navController.popBackStack() },
                    )
                    Routes.CLOUD -> TopBarConfig(
                        title = "云同步",
                        variant = TopBarVariant.COMPACT,
                        onBack = { navController.popBackStack() },
                    )
                    Routes.CLOUD_PROVIDER -> TopBarConfig(
                        title = "云服务商",
                        variant = TopBarVariant.COMPACT,
                        onBack = { navController.popBackStack() },
                    )
                    Routes.GLASS_PLAYGROUND -> TopBarConfig(
                        title = "",
                        variant = TopBarVariant.HIDDEN,
                    )
                    Routes.LAN_SHARE -> TopBarConfig(
                        title = "局域网共享",
                        variant = TopBarVariant.COMPACT,
                        onBack = { navController.popBackStack() },
                    )
                    // Viewer / Editor — keep page-level config.
                    else -> topBarState.value
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // Z = 0: real content. The `layerBackdrop` modifier here
                // captures this subtree into the graphics layer L. Glass
                // surfaces above read L and refractor these pixels.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(liquidBackdrop)
                ) {
                    // Z=0a: Liquid Glass background — system wallpaper visible
                    // through semi-transparent tint + highlight overlay.
                    // Rendered INSIDE the layerBackdrop capture so chrome glass
                    // surfaces (tab bar, top bar) see [glassBg + page content]
                    // behind their frosted overlay.
                    //
                    // The wallpaper itself is rendered by Android's window
                    // compositor via FLAG_SHOW_WALLPAPER (set in MainActivity).
                    // This composable adds the frosted tint effect on top.
                    WallpaperGlassBackground()

                    // Z=0b: Page content on top of glass background
                    NavHost(
                        navController = navController,
                        startDestination = Routes.TIMELINE,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable(Routes.TIMELINE) {
                            TimelinePage(
                                onFirstFrame = onFirstFrame,
                                onOpenPhoto = { list, index ->
                                    com.smartvision.gallery.util.AppLog.i("Nav", "Timeline → viewer (standalone Activity): idx=$index listSize=${list.size}")
                                    try {
                                        context.startActivity(PhotoViewerActivity.launchIntent(context, list, index))
                                    } catch (t: Throwable) {
                                        com.smartvision.gallery.util.AppLog.e("Nav", "viewer launch crashed for idx=$index listSize=${list.size}", t)
                                    }
                                },
                                onOpenAlbums = { navController.navigate(Routes.ALBUMS) },
                                onConsumePendingSegment = {
                                    val s = pendingTimelineSegment.value
                                    pendingTimelineSegment.value = null
                                    com.smartvision.gallery.util.AppLog.i("Nav", "Timeline consumed pendingSegment=$s")
                                    s
                                },
                            )
                        }
                        composable(Routes.ALBUMS) {
                            AlbumsPage(
                                onOpenAlbum = { albumId ->
                                    // albumId may contain "/" (e.g. "bucket:DCIM/Camera") — encode so
                                    // Navigation Compose treats it as a single path segment.
                                    try {
                                        val route = "${Routes.ALBUM_DETAIL}/${Uri.encode(albumId)}"
                                        com.smartvision.gallery.util.AppLog.i("Nav", "Albums → album_detail: albumId=$albumId route=$route")
                                        navController.navigate(route)
                                    } catch (t: Throwable) {
                                        com.smartvision.gallery.util.AppLog.e("Nav", "album_detail nav crashed for $albumId", t)
                                    }
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(Routes.CURATED) {
                            AlbumListPage(
                                onOpenAlbum = { albumId ->
                                    when (albumId) {
                                        "hidden" -> navController.navigate(Routes.PRIVACY)
                                        "trash" -> navController.navigate(Routes.TRASH)
                                        "favorites" -> navController.navigate("${Routes.ALBUM_DETAIL}/${Uri.encode(albumId)}")
                                        else -> navController.navigate("${Routes.ALBUM_DETAIL}/${Uri.encode(albumId)}")
                                    }
                                },
                                onOpenPhoto = { list, index ->
                                    com.smartvision.gallery.util.AppLog.i("Nav", "AlbumDetail → viewer: listSize=${list.size} index=$index")
                                    context.startActivity(PhotoViewerActivity.launchIntent(context, list, index))
                                },
                                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                            )
                        }
                        composable(Routes.SEARCH) {
                            SearchPage(
                                onOpenPhoto = { list, index ->
                                    com.smartvision.gallery.util.AppLog.i("Nav", "Search → viewer: listSize=${list.size} index=$index")
                                    context.startActivity(PhotoViewerActivity.launchIntent(context, list, index))
                                }
                            )
                        }
                        composable(Routes.SETTINGS) {
                            SettingsPage(
                                onOpenPrivacy = { navController.navigate(Routes.PRIVACY) },
                                onOpenTrash = { navController.navigate(Routes.TRASH) },
                                onOpenGlassPlayground = { navController.navigate(Routes.GLASS_PLAYGROUND) },
                                onOpenLanShare = { navController.navigate(Routes.LAN_SHARE) }
                            )
                        }
                        // GLASS_PLAYGROUND is registered with an empty body here so
                        // `navController.navigate(Routes.GLASS_PLAYGROUND)` from
                        // SettingsPage finds a valid destination. The actual
                        // GlassConfigPanel is rendered as a SIBLING of the
                        // layerBackdrop capture Box (see Z=0c below) — its
                        // LivePreview primitives use `Modifier.drawBackdrop` which
                        // recurses through RenderNode::prepareTreeImpl on ColorOS 16
                        // when nested inside a `Modifier.layerBackdrop` capture area.
                        composable(Routes.GLASS_PLAYGROUND) { /* sibling renders it */ }
                        composable(Routes.CLOUD_PROVIDER) {
                            com.smartvision.gallery.ui.settings.CloudProviderPickerPage(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable(
                            route = "${Routes.VIEWER}/{uri}/{initialIndex}",
                            arguments = listOf(
                                navArgument("uri") { type = NavType.StringType },
                                navArgument("initialIndex") { type = NavType.IntType; defaultValue = 0 },
                            )
                        ) { entry ->
                            val uriEncoded = entry.arguments?.getString("uri") ?: return@composable
                            val initialIndex = entry.arguments?.getInt("initialIndex") ?: 0
                            val uri = android.net.Uri.parse(java.net.URLDecoder.decode(uriEncoded, "UTF-8"))
                            PhotoViewerPage(
                                uri = uri,
                                onBack = { navController.popBackStack() },
                                onEdit = {
                                    // 调用系统相册编辑器（用户要求）。授予读写权限，
                                    // 编辑器保存后写回原 URI。
                                    val edit = android.content.Intent(android.content.Intent.ACTION_EDIT).apply {
                                        setDataAndType(uri, "image/*")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    val chooser = android.content.Intent.createChooser(edit, "使用其他应用编辑")
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(chooser) }
                                },
                                onNavigate = { newUri, newIndex ->
                                    navController.navigate(
                                        "${Routes.VIEWER}/${Uri.encode(newUri.toString())}/$newIndex"
                                    ) {
                                        popUpTo(Routes.VIEWER) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable("${Routes.VIEWER}/{uri}") { entry ->
                            val uriEncoded = entry.arguments?.getString("uri") ?: return@composable
                            val uri = android.net.Uri.parse(java.net.URLDecoder.decode(uriEncoded, "UTF-8"))
                            PhotoViewerPage(
                                uri = uri,
                                onBack = { navController.popBackStack() },
                                onEdit = {
                                    val edit = android.content.Intent(android.content.Intent.ACTION_EDIT).apply {
                                        setDataAndType(uri, "image/*")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    val chooser = android.content.Intent.createChooser(edit, "使用其他应用编辑")
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    runCatching { context.startActivity(chooser) }
                                },
                                onNavigate = { newUri, _ ->
                                    navController.navigate(
                                        "${Routes.VIEWER}/${Uri.encode(newUri.toString())}/0"
                                    ) {
                                        popUpTo(Routes.VIEWER) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable("${Routes.ALBUM_DETAIL}/{albumId}") { entry ->
                            val albumIdEncoded = entry.arguments?.getString("albumId") ?: return@composable
                            // albumId may contain "/" (e.g. "bucket:DCIM/Camera") — decode after Navigation Compose parsing.
                            val albumId = java.net.URLDecoder.decode(albumIdEncoded, "UTF-8")
                            com.smartvision.gallery.ui.album.AlbumDetailPage(
                                albumId = albumId,
                                onBack = { navController.popBackStack() },
                                onOpenPhoto = { list, index ->
                                    com.smartvision.gallery.util.AppLog.i("Nav", "AlbumDetail → viewer: listSize=${list.size} index=$index")
                                    context.startActivity(PhotoViewerActivity.launchIntent(context, list, index))
                                }
                            )
                        }
                        composable(Routes.TRASH) {
                            com.smartvision.gallery.ui.trash.TrashPage(
                                onBack = { navController.popBackStack() },
                                onOpenPhoto = { list, index ->
                                    com.smartvision.gallery.util.AppLog.i("Nav", "Trash → viewer: listSize=${list.size} index=$index")
                                    context.startActivity(PhotoViewerActivity.launchIntent(context, list, index))
                                }
                            )
                        }
                        composable(Routes.PRIVACY) {
                            com.smartvision.gallery.ui.privacy.PrivacyVaultPage(
                                onBack = { navController.popBackStack() },
                                onOpenPhoto = { list, index ->
                                    com.smartvision.gallery.util.AppLog.i("Nav", "PrivacyVault → viewer: listSize=${list.size} index=$index")
                                    context.startActivity(PhotoViewerActivity.launchIntent(context, list, index))
                                }
                            )
                        }
                        composable(Routes.CLOUD) {
                            com.smartvision.gallery.ui.cloud.CloudSyncPage(onBack = { navController.popBackStack() })
                        }
                        composable(Routes.LAN_SHARE) {
                            com.smartvision.gallery.ui.lan.LanSharePage(onBack = { navController.popBackStack() })
                        }
                        composable("${Routes.EDITOR}/{uri}") { entry ->
                            val uriEncoded = entry.arguments?.getString("uri") ?: return@composable
                            val uri = android.net.Uri.parse(java.net.URLDecoder.decode(uriEncoded, "UTF-8"))
                            com.smartvision.gallery.ui.editor.PhotoEditorPage(
                                uri = uri,
                                onBack = { navController.popBackStack() },
                                onSave = { _ -> navController.popBackStack() }
                            )
                        }
                    }
                }

                // Z = 0c: Liquid Glass Playground — rendered as a SIBLING of the
                // layerBackdrop capture Box (NOT inside it). The playground's
                // LivePreview uses `Modifier.drawBackdrop` primitives which
                // recurse through RenderNode::prepareTreeImpl on ColorOS 16
                // when nested inside a `Modifier.layerBackdrop` capture area,
                // crashing the RenderThread with stack overflow. By placing
                // the playground outside the capture, drawBackdrop runs against
                // the playground's own dedicated backdrop (set up inside the
                // page via CompositionLocalProvider) without nested capture.
                if (currentRoute == Routes.GLASS_PLAYGROUND) {
                    GlassConfigPanel(vm = glassVm, modifier = Modifier.fillMaxSize())
                }

                // Z = 1: floating glass tab bar. Two sibling composables:
                //
                //  * Z=1a — hidden backdrop-only twin of the bar. Renders with
                //           `backdropOnly = true` (forceCyan icons, no
                //           gesture handlers, no drawBackdrop sampling) and is
                //           captured by `Modifier.layerBackdrop(barBackdrop)`.
                //           The captured layer is the magnifier's Layer 2 —
                //           it lets the lens shader refract the bar's own
                //           cyan icons at the pixel level.
                //  * Z=1b — the visible interactive bar. Reads
                //           `LocalLiquidGlassScreenBackdrop` for its own
                //           frosted track. One-way sample (page content →
                //           bar surface), no cycle.
                if (currentRoute == null || currentRoute in TOP_LEVEL_ROUTES) {
                    // Z=1a: hidden backdrop-only twin — captured by barBackdrop
                    // MUST read config here (not in default param) for Compose tracking
                    val tabBarSpec = glassConfig.tabBar.toSpec()
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .layerBackdrop(barBackdrop)
                    ) {
                        iOSTabBar(
                            items = BOTTOM_NAV_ITEMS,
                            selectedRoute = currentRoute,
                            onSelect = {},
                            backdropOnly = true,
                            modifier = Modifier,
                        )
                    }
                    // Z=1b: visible interactive bar
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                    ) {
                        iOSTabBar(
                            items = BOTTOM_NAV_ITEMS,
                            selectedRoute = currentRoute,
                            onSelect = { item ->
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }

                // Z=1 (top): Floating top bar pill — same ContinuousCapsule
                // shape as the bottom bar, with liquid lensing on long-press.
                // Renders above the page content but below the lens overlay.
                val topBarConfig by topBarState.collectAsState()
                FloatingTopBarPill(config = topBarConfig)

                // Z=1 (Library page chrome): action row + segmented control
                // float as siblings of the captured subtree so they sample
                // real photo content via LocalLiquidGlassScreenBackdrop —
                // matching the bottom tab bar's full Liquid Glass physics.
                // Uses DEDICATED state per route: libraryOverlayState for
                // TimelinePage, albumsOverlayState for AlbumsPage. AlbumsPage
                // NEVER writes to libraryOverlayState — this prevents state
                // corruption when navigating between the two pages.
                if (currentRoute == Routes.TIMELINE) {
                    LibraryOverlay(state = libraryOverlayState.value)
                } else if (currentRoute == Routes.ALBUMS) {
                    LibraryOverlay(state = albumsOverlayState)
                }

                // Z=1 (AlbumDetail chrome): FloatingFilterBar only — header is
                // rendered inside AlbumDetailPage at Z=0. Sibling of the captured
                // subtree, sampling LocalLiquidGlassScreenBackdrop directly.
                val albumDetailChromeData by albumDetailChromeState
                if (currentRoute?.startsWith(Routes.ALBUM_DETAIL) == true) {
                    AlbumDetailChrome(
                        state = albumDetailChromeData,
                        chipSpec = glassConfig.chipFilter.toSpec(),
                        backdrop = liquidBackdrop,
                        springDampingRatio = glassConfig.chipFilter.springDampingRatio,
                        springStiffness = glassConfig.chipFilter.springStiffness,
                        selectedScale = glassConfig.chipFilter.selectedScale,
                    )
                }

                // Z = 2: iOS 26 Liquid Lensing magnifier overlay. Renders
                // above the tab bar using TWO backdrops: pageBackdrop (the
                // page content layer, Layer 1) + barBackdrop (the hidden
                // backdrop-only bar twin captured at Z=1a, Layer 2). The
                // magnifier shows both the page underneath AND the bar's own
                // icons with the refraction shader — matching iOS 26's
                // "magnifier shows whatever is on screen at that position"
                // behavior. The controller's internal visibility guard
                // prevents rendering when no long-press is active.
                LiquidGlassLensOverlay(barBackdrop = barBackdrop)
                SegmentedControlLensOverlay()
            }
        }
    }
}

/**
 * 权限引导页 — 无权限时显示。商业级 onboarding 风格:主题自适应配色、
 * icon 锚点、清晰文案层级、pill 形 CTA。背景全屏铺到系统栏后。
 *
 * 窗口透明 (FLAG_SHOW_WALLPAPER),故 WallpaperGlassBackground 必须覆盖
 * 整窗含状态/导航栏条带,否则桌面壁纸透传。
 */
@Composable
private fun PermissionGuidePage(
    state: com.smartvision.gallery.ui.permission.PermissionState.Denied,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    // 主题自适应文字色 — 浅底用深色字,深底用白字。
    // 旧版硬编码 Color.White 在 light mode 米色渐变上不可读。
    val titleColor = if (isDark) Color.White else Color(0xFF2A2233)
    val bodyColor = if (isDark) Color.White.copy(alpha = 0.72f) else Color(0xFF4A4252).copy(alpha = 0.85f)
    val captionColor = if (isDark) Color.White.copy(alpha = 0.5f) else Color(0xFF5A5266).copy(alpha = 0.7f)
    val accent = Color(0xFF5B7FFF)

    Box(modifier = Modifier.fillMaxSize()) {
        WallpaperGlassBackground()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 32.dp),
        ) {
            // Icon 锚点 — 圆角玻璃容器,视觉中心,非裸文字开场。
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(accent.copy(alpha = if (isDark) 0.22f else 0.16f))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.PhotoLibrary,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(48.dp),
                )
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "欢迎使用超级相册",
                color = titleColor,
                fontSize = 26.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "查看和管理您的照片与视频\n享受 AI 智能分类与超高清浏览体验",
                color = bodyColor,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(36.dp))
            if (state.permanently) {
                // 勾选"不再询问" — 系统框不再弹出,只有设置页能恢复。
                Text(
                    text = "媒体权限已被永久拒绝,\n请在系统设置中开启后继续。",
                    color = captionColor,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                PillButton(
                    text = "前往设置",
                    accent = accent,
                    titleColor = titleColor,
                    onClick = onOpenAppSettings,
                )
            } else {
                PillButton(
                    text = "允许访问照片",
                    accent = accent,
                    titleColor = titleColor,
                    onClick = onRequestPermission,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "仅用于读取并展示您相册中的照片与视频",
                    color = captionColor,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Pill 形 CTA — 填充主色,圆角 26dp,全宽 78%,高 52dp。 */
@Composable
private fun PillButton(
    text: String,
    accent: Color,
    titleColor: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent),
        modifier = Modifier
            .fillMaxWidth(0.78f)
            .height(52.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

object Routes {
    const val TIMELINE = "timeline"
    const val ALBUMS = "albums"
    const val CURATED = "curated"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val VIEWER = "viewer"
    const val ALBUM_DETAIL = "album_detail"
    const val TRASH = "trash"
    const val PRIVACY = "privacy"
    const val CLOUD = "cloud"
    const val CLOUD_PROVIDER = "cloud_provider"
    const val GLASS_PLAYGROUND = "glass_playground"
    const val EDITOR = "editor"
    const val LAN_SHARE = "lan_share"
}

private val TOP_LEVEL_ROUTES = listOf(
    Routes.TIMELINE,
    Routes.CURATED,
    Routes.SEARCH,
    Routes.SETTINGS,
)

private val BOTTOM_NAV_ITEMS = listOf(
    iOSTabItem(Routes.TIMELINE, "图库", Icons.Outlined.Image),
    iOSTabItem(Routes.CURATED, "精选", Icons.Outlined.PhotoLibrary),
    iOSTabItem(Routes.SEARCH, "搜索", Icons.Outlined.Search),
    iOSTabItem(Routes.SETTINGS, "设置", Icons.Outlined.Settings)
)
