# Bugfix C — ViewModel + 导航 (ViewModel & Navigation)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this spec task-by-task.

**Goal:** 修复 ViewModel 协程生命周期泄漏、PhotoViewer 初始索引、AppRoot 导航栈累积、CURATED 缺失于 TOP_LEVEL_ROUTES、重复 topBarConfig 写入、双 lens controller 等导航 + 状态管理问题。

**Scope:** 子项目 C。修复 C7/H6/H7/H8/M1/M2/M3/M4/M5/M6 共 10 个 bug。

**依赖:** 子项目 A 完成(Repository 缓存);子项目 B 完成(Entity 字段稳定)。但本子项目不直接依赖 B 的新字段。

**Architecture:**
- 所有 ViewModel 用 `viewModelScope` 持有 collect 作业,统一通过 `stateIn(scope, SharingStarted.WhileSubscribed(5000), initial)` 暴露 UI state
- PhotoViewerViewModel 支持 `SavedStateHandle["initialIndex"]`,从 navigation argument 读取
- AppRoot 用单一 source of truth 的 `TopBarConfig` + `NavigationBackStack` 避免重复写入
- lens controller 由 AppRoot 单一实例化,通过 `CompositionLocal` 下传(已存在,只需去掉重复的 `segLensController`)

---

## Context

| 编号 | 文件 | 行 | 严重度 | 问题 |
|---|---|---|---|---|
| C7 | `ui/viewer/PhotoViewerViewModel.kt` | 32-38 | CRITICAL | `observeTimeline().collect { ... }` 在 ViewModel 构造时启动,从不取消 → ViewModel 销毁后仍在 collect,泄漏 |
| H6 | `ui/viewer/PhotoViewerViewModel.kt` | 51-55 | HIGH | ViewModel factory 忽略 SavedStateHandle 的 `initialIndex` → 永远是 0 |
| H7 | `ui/AppRoot.kt` | 213-214 | HIGH | `onEdit = { navController.navigate(EDITOR/...) }` 缺少 pop viewer → 编辑返回后 viewer 还在 back stack |
| H8 | `ui/AppRoot.kt` | 216-219 | HIGH | `popUpTo(uri)` 用当前 uri 弹出自己,但新 uri 重新 push → back stack 累积多个 viewer |
| M1 | `ui/AppRoot.kt` | 350-354 | MEDIUM | `TOP_LEVEL_ROUTES` 不包含 `CURATED` → 进/出精选时 tab bar 闪烁 |
| M2 | `ui/AppRoot.kt` | 125-135 | MEDIUM | `CompositionLocalProvider` 同时注入 `LocalLiquidGlassLens` 和 `LocalSegmentedControlLens`,但两个 controller 引用同一对象 → 浪费 |
| M3 | `ui/pages/TimelinePage.kt` | 149-155 | MEDIUM | TimelinePage 在 LaunchedEffect 写 `topBarState`,SettingsPage 也写 → 多个 page 同时写,后写覆盖 |
| M4 | `ui/liquidglass/LocalTopBarState.kt` | (定义) | MEDIUM | `TopBarConfig` 不可变 data class 缺 `copy()`,导致每次 write 都重新构造整个对象,触发整树 recomposition |
| M5 | `ui/album/AlbumListPage.kt` | (全局) | MEDIUM | AlbumListPage 没有写 topBarState → 共享 TimelinePage 的 "图库" 标题,显示错误 |
| M6 | `ui/liquidglass/LibraryOverlay.kt` | (全局) | MEDIUM | LibraryOverlay 持有 segBackdrop 和 icon state,这些应该由 AppRoot 统一管理 |

## Approach

### C.1 — PhotoViewerViewModel 协程生命周期 (C7)

**目标:** 用 `stateIn` 替代裸 collect。

**修改:**
```kotlin
class PhotoViewerViewModel(
    private val repository: MediaRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialIndex: Int = savedStateHandle.get<Int>("initialIndex") ?: 0

    val uiState: StateFlow<PhotoViewerUiState> = repository.observeTimeline()
        .map { items -> PhotoViewerUiState(items = items, initialIndex = initialIndex) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PhotoViewerUiState()
        )

    fun next() { ... }
    fun prev() { ... }
    fun delete() { ... }
}

data class PhotoViewerUiState(
    val items: List<MediaItem> = emptyList(),
    val initialIndex: Int = 0,
)
```

**验收:** 进入 viewer → 退出 → 5 秒后 ViewModel `isCleared`,无后台 collect。

### C.2 — factory 注入 SavedStateHandle (H6)

**目标:** factory 用 `AbstractSavedStateViewModelFactory` 或 `viewModelFactory { initializer { ... } }`。

**修改:**
```kotlin
companion object {
    fun factory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val savedStateHandle = createSavedStateHandle()
            PhotoViewerViewModel(
                repository = SmartVisionApp.get().mediaRepository,
                savedStateHandle = savedStateHandle
            )
        }
    }
}
```

**AppRoot.kt:** navigation 路径 `viewer/{uri}/{initialIndex}`:
```kotlin
composable(
    route = "${Routes.VIEWER}/{uri}/{initialIndex}",
    arguments = listOf(
        navArgument("uri") { type = NavType.StringType },
        navArgument("initialIndex") { type = NavType.IntType; defaultValue = 0 }
    )
) { entry ->
    val uri = ...
    val initialIndex = entry.arguments?.getInt("initialIndex") ?: 0
    PhotoViewerPage(uri, initialIndex, ...)
}
```

**调用方:** TimelinePage `onOpenPhoto` 传入当前 index:
```kotlin
onOpenPhoto = { uri, index ->
    navController.navigate("${Routes.VIEWER}/${Uri.encode(uri.toString())}/$index")
}
```

**验收:** 从第 5 张照片打开 viewer,initialIndex=5,初始显示第 5 张。

### C.3 — onEdit 导航修正 (H7)

**修改 AppRoot.kt:**
```kotlin
PhotoViewerPage(
    uri = uri,
    onBack = { navController.popBackStack() },
    onEdit = {
        navController.navigate("${Routes.EDITOR}/${Uri.encode(uri.toString())}") {
            // 进入 editor 时把 viewer 也弹掉,editor 完成后回到 timeline
            popUpTo("${Routes.VIEWER}/{uri}") { inclusive = true }
        }
    },
)
```

**验收:** viewer → edit → 保存 → 直接回到 timeline,中间无 viewer 残留。

### C.4 — popUpTo 修正确保单实例 (H8)

**修改 AppRoot.kt onNavigate:**
```kotlin
onNavigate = { newUri ->
    navController.navigate("${Routes.VIEWER}/${Uri.encode(newUri.toString())}/$currentIndex") {
        popUpTo(Routes.VIEWER) { inclusive = true }  // 弹出所有 viewer 路由
        launchSingleTop = true
    }
}
```

**验收:** 在 viewer 中左右滑切换 5 次照片,back 一次回到 timeline(不是回到第 4 张)。

### C.5 — CURATED 加入 TOP_LEVEL_ROUTES (M1)

**修改:**
```kotlin
private val TOP_LEVEL_ROUTES = listOf(
    Routes.TIMELINE,
    Routes.CURATED,
    Routes.SEARCH,
    Routes.SETTINGS
)
```

**验收:** 进入/离开精选 tab 不闪烁 tab bar。

### C.6 — 单一 lens controller (M2)

**修改 AppRoot.kt:**
```kotlin
val lensController = rememberLiquidGlassLensController()
// 删掉 val segLensController = rememberLiquidGlassLensController()

CompositionLocalProvider(
    LocalImageLoader provides imageLoader,
    LocalLiquidGlassLens provides lensController,  // 同一个 controller
    LocalSegmentedControlLens provides lensController,  // 复用
    ...
)
```

**验收:** 两处 magnifier 共享同一 controller state,无重复初始化。

### C.7 — 单一 TopBarConfig writer (M3, M4, M5)

**目标:** 让每个 page 只在 LaunchedEffect 写一次,page dispose 时 reset。

**修改 `LocalTopBarState.kt`:**
```kotlin
data class TopBarConfig(
    val title: String = "",
    val variant: TopBarVariant = TopBarVariant.LARGE_TITLE,
    val onBack: (() -> Unit)? = null,
)

@Stable
class TopBarState {
    var config by mutableStateOf(TopBarConfig())
        internal set
}

val LocalTopBarState = staticCompositionLocalOf { TopBarState() }
```

**每个 Page:**
```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(...)
    onDispose { topBar.config = TopBarConfig() }  // reset,避免泄漏
}
```

**验收:** 进入 album detail → 返回 → top bar 不显示错的 page title。

### C.8 — AlbumListPage 加 TopBarConfig (M5)

**AlbumListPage.kt:**
```kotlin
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "精选", variant = TopBarVariant.LARGE_TITLE)
    onDispose { topBar.config = TopBarConfig() }
}
```

### C.9 — LibraryOverlay state owner 上移到 AppRoot (M6)

**AppRoot.kt 持有:**
```kotlin
val libraryOverlayState = remember { mutableStateOf(LibraryOverlayState()) }
val segBackdrop = rememberLayerBackdrop()  // AppRoot owns

// LibraryOverlay 接收 prop,不持有
LibraryOverlay(state = libraryOverlayState.value, segBackdrop = segBackdrop)
```

**验收:** LibraryOverlay 重构后不再触发 hidden capture subtree 内不必要的 recomposition。

## File Changes

### Modify
- `ui/viewer/PhotoViewerViewModel.kt` (C.1, C.2)
- `ui/viewer/PhotoViewerPage.kt` (C.1 - 接收 initialIndex)
- `ui/AppRoot.kt` (C.2 路由, C.3, C.4, C.5, C.6)
- `ui/liquidglass/LocalTopBarState.kt` (C.7)
- `ui/pages/TimelinePage.kt` (C.2 onOpenPhoto 加 index, C.7 DisposableEffect)
- `ui/pages/SearchPage.kt` (C.7 DisposableEffect)
- `ui/pages/SettingsPage.kt` (C.7 DisposableEffect)
- `ui/album/AlbumsPage.kt` (C.7 DisposableEffect)
- `ui/album/AlbumDetailPage.kt` (C.7 DisposableEffect)
- `ui/album/AlbumListPage.kt` (C.8 DisposableEffect)
- `ui/trash/TrashPage.kt` (C.7 DisposableEffect)
- `ui/privacy/PrivacyVaultPage.kt` (C.7 DisposableEffect)
- `ui/cloud/CloudSyncPage.kt` (C.7 DisposableEffect)
- `ui/liquidglass/LibraryOverlay.kt` (C.9 - 接收 segBackdrop prop)

### Add
- `ui/viewer/PhotoViewerUiState.kt` (C.1)

## Acceptance Criteria

1. ✅ ViewModel 销毁后无后台 collect(`adb shell dumpsys meminfo` 验证内存稳定)
2. ✅ 从第 N 张照片点开 viewer,初始显示第 N 张
3. ✅ viewer → edit → 返回,viewer 不在 back stack 中
4. ✅ viewer 内切换照片,back 一次回 timeline
5. ✅ CURATED tab 进入/离开无 tab bar 闪烁
6. ✅ 每个 page 都正确设置自己的 topBarConfig,返回时 reset

## Risks

- **C.2 加 initialIndex 路由参数** 会破坏已有的 deep link / share intent。Mitigation: 提供旧路由 `viewer/{uri}` 的 alias,无 initialIndex 时默认为 0。
- **C.7 DisposableEffect reset** 在 page 转场过程中会先 reset 再 set 新值,可能闪一下默认 title。Mitigation: reset 不写 `TopBarConfig()` 而是上一个值,或保留 N 帧 grace period。
- **C.6 共用 controller** 可能让两个 magnifier 互相干扰。Mitigation: 测试确认 lens `controller.active` 是单值,segmented control magnifier 不会同时 active。