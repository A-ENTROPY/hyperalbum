# Bugfix C — ViewModel & Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 C7 (协程泄漏) + H6 (initialIndex) + H7/H8 (导航栈) + M1-M6 (CURATED/topBar/lens controller/state owner)。

**Architecture:**
- ViewModel 用 `stateIn(viewModelScope, WhileSubscribed(5000), initial)` 替代裸 collect
- PhotoViewer 路由加 `initialIndex` 参数,`SavedStateHandle` 读取
- AppRoot 导航:viewer onEdit 时 popUpTo VIEWER inclusive;viewer onNavigate 用 Routes.VIEWER 做 popUpTo
- `TOP_LEVEL_ROUTES` 加入 CURATED
- `LocalTopBarState` 改为 `TopBarState` stable class,page 用 `DisposableEffect` 写入 + onDispose reset
- lens controller 单一实例,LocalLiquidGlassLens 和 LocalSegmentedControlLens 共享
- LibraryOverlay 状态 owner 上移到 AppRoot

**Tech Stack:** Jetpack Navigation, ViewModel + SavedStateHandle, Compose state hoisting

**Dependency:** 子项目 A 完成 (Repository 缓存),子项目 B 完成 (Entity 字段稳定)

**Note:** 无 git,跳过 commit 步骤。

---

## Task 1: PhotoViewerViewModel stateIn 重构 (C7)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerViewModel.kt`
- Create: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerUiState.kt`

- [ ] **Step 1: 创建 UiState 数据类**

```kotlin
package com.smartvision.gallery.ui.viewer

import com.smartvision.gallery.data.model.MediaItem

data class PhotoViewerUiState(
    val items: List<MediaItem> = emptyList(),
    val initialIndex: Int = 0,
    val currentIndex: Int = 0,
)
```

- [ ] **Step 2: 重构 ViewModel**

替换 `PhotoViewerViewModel.kt` 中所有构造和 init 逻辑:

```kotlin
class PhotoViewerViewModel(
    private val repository: MediaRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val initialIndex: Int =
        savedStateHandle.get<Int>("initialIndex") ?: 0

    private val indexFlow = MutableStateFlow(initialIndex)

    val uiState: StateFlow<PhotoViewerUiState> = combine(
        repository.observeTimeline(),
        indexFlow
    ) { items, idx ->
        PhotoViewerUiState(
            items = items,
            initialIndex = initialIndex,
            currentIndex = idx.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PhotoViewerUiState(initialIndex = initialIndex),
    )

    fun next() { indexFlow.update { it + 1 } }
    fun prev() { indexFlow.update { it - 1 } }

    fun delete(onSuccess: () -> Unit) {
        viewModelScope.launch {
            val current = uiState.value.items.getOrNull(uiState.value.currentIndex)
                ?: return@launch
            repository.setTrash(Uri.parse(current.uri), true)
            onSuccess()
        }
    }
}
```

加 imports:
```kotlin
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
```

- [ ] **Step 3: factory 支持 SavedStateHandle (H6)**

替换 companion factory:

```kotlin
companion object {
    fun factory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val savedStateHandle = createSavedStateHandle()
            PhotoViewerViewModel(
                repository = SmartVisionApp.get().mediaRepository,
                savedStateHandle = savedStateHandle,
            )
        }
    }
}
```

加 imports:
```kotlin
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
```

- [ ] **Step 4: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 2: AppRoot 路由加 initialIndex (H6)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/AppRoot.kt`

- [ ] **Step 1: 修改 viewer 路由**

替换 AppRoot.kt 中 VIEWER 路由:

```kotlin
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
        initialIndex = initialIndex,
        onBack = { navController.popBackStack() },
        onEdit = {
            navController.navigate("${Routes.EDITOR}/${Uri.encode(uri.toString())}") {
                popUpTo("${Routes.VIEWER}/{uri}/{initialIndex}") { inclusive = true }
            }
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
```

加 import:
```kotlin
import androidx.navigation.NavType
import androidx.navigation.navArgument
```

- [ ] **Step 2: 加旧 viewer 路由 alias(防止 deep link 失效)**

在 Routes 路由注册块中,VIEWER 路由前添加:

```kotlin
// 旧路由 alias — 无 initialIndex 时默认为 0,保持 deep link 兼容
composable("${Routes.VIEWER}/{uri}") { entry ->
    val uriEncoded = entry.arguments?.getString("uri") ?: return@composable
    val uri = android.net.Uri.parse(java.net.URLDecoder.decode(uriEncoded, "UTF-8"))
    PhotoViewerPage(
        uri = uri,
        initialIndex = 0,
        onBack = { navController.popBackStack() },
        ...
    )
}
```

完整 list 替换为新路由,并保留旧路由在最后(让新路由优先匹配)。

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 3: TimelinePage onOpenPhoto 传 index (H6)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt`

- [ ] **Step 1: 修改 onOpenPhoto 签名**

```kotlin
onOpenPhoto = { uri ->
    val index = sections.flatMap { it.items }.indexOfFirst { it.uri == uri }
    navController.navigate("${Routes.VIEWER}/${Uri.encode(uri.toString())}/$index")
},
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 4: CURATED 加入 TOP_LEVEL_ROUTES (M1)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/AppRoot.kt:350-354`

- [ ] **Step 1: 修改 TOP_LEVEL_ROUTES**

```kotlin
private val TOP_LEVEL_ROUTES = listOf(
    Routes.TIMELINE,
    Routes.CURATED,
    Routes.SEARCH,
    Routes.SETTINGS,
)
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 5: 单一 lens controller (M2)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/AppRoot.kt`

- [ ] **Step 1: 删除 segLensController**

```kotlin
val lensController = rememberLiquidGlassLensController()
// 删除: val segLensController = rememberLiquidGlassLensController()
```

- [ ] **Step 2: 两个 CompositionLocal 用同一 controller**

```kotlin
CompositionLocalProvider(
    LocalImageLoader provides imageLoader,
    LocalLiquidGlassLens provides lensController,
    LocalSegmentedControlLens provides lensController,  // 复用,不再独立
    ...
)
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 6: TopBarState 改 stable class (M3, M4)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LocalTopBarState.kt`

- [ ] **Step 1: 重写为 stable class**

```kotlin
package com.smartvision.gallery.ui.liquidglass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

enum class TopBarVariant { COMPACT, LARGE_TITLE }

data class TopBarConfig(
    val title: String = "",
    val variant: TopBarVariant = TopBarVariant.LARGE_TITLE,
    val onBack: (() -> Unit)? = null,
)

@Stable
class TopBarState {
    var config by mutableStateOf(TopBarConfig())
        internal set

    fun reset() { config = TopBarConfig() }
}

val LocalTopBarState = staticCompositionLocalOf { TopBarState() }
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 7: 所有 Page 用 DisposableEffect 写 TopBarConfig

**Files:**
- Modify: 所有 Page 文件 (TimelinePage, SearchPage, SettingsPage, AlbumsPage, AlbumDetailPage, AlbumListPage, TrashPage, PrivacyVaultPage, CloudSyncPage)

- [ ] **Step 1: TimelinePage 改为 DisposableEffect**

`TimelinePage.kt` 中找到:
```kotlin
LaunchedEffect(Unit) {
    topBarState.value = TopBarConfig(title = "图库", variant = TopBarVariant.LARGE_TITLE)
}
```

替换为:
```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "图库", variant = TopBarVariant.LARGE_TITLE)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 2: SearchPage**

```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "搜索", variant = TopBarVariant.COMPACT)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 3: SettingsPage**

```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "设置", variant = TopBarVariant.LARGE_TITLE)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 4: AlbumsPage**

```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "图集", variant = TopBarVariant.COMPACT, onBack = onBack)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 5: AlbumDetailPage**

```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(album.name, onBack) {
    topBar.config = TopBarConfig(title = album.name, variant = TopBarVariant.COMPACT, onBack = onBack)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 6: AlbumListPage (M5)**

```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "精选", variant = TopBarVariant.LARGE_TITLE)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 7: TrashPage**

```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "回收站", variant = TopBarVariant.COMPACT, onBack = onBack)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 8: PrivacyVaultPage**

```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "隐私空间", variant = TopBarVariant.LARGE_TITLE, onBack = onBack)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 9: CloudSyncPage**

```kotlin
val topBar = LocalTopBarState.current
DisposableEffect(Unit) {
    topBar.config = TopBarConfig(title = "云同步", variant = TopBarVariant.COMPACT, onBack = onBack)
    onDispose { topBar.reset() }
}
```

- [ ] **Step 10: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 8: LibraryOverlay state owner 上移 (M6)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/AppRoot.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LibraryOverlay.kt`

- [ ] **Step 1: AppRoot 创建 segBackdrop**

确认 AppRoot 已经创建了 `segBackdrop`(在子项目 C Step 5 的基础上):

```kotlin
val segBackdrop = rememberLayerBackdrop()
```

如果没有,添加。

- [ ] **Step 2: LibraryOverlay 接收 segBackdrop prop**

修改 `LibraryOverlay.kt`:

```kotlin
@Composable
fun LibraryOverlay(
    state: LibraryOverlayState,
    segBackdrop: Backdrop,
) {
    // 删除内部 val segBackdrop = rememberLayerBackdrop()
    // ...

    // 末尾的 LaunchedEffect 保留,但 segBackdrop 现在是参数
    LaunchedEffect(segBackdrop) {
        LocalSegmentedControlBackdropState.current.value = segBackdrop
    }
}
```

- [ ] **Step 3: AppRoot 调用处传入**

```kotlin
if (currentRoute == Routes.TIMELINE) {
    LibraryOverlay(
        state = libraryOverlayState.value,
        segBackdrop = segBackdrop,
    )
}
```

- [ ] **Step 4: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 9: PhotoViewerPage 接 initialIndex

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerPage.kt`

- [ ] **Step 1: 添加 initialIndex 参数**

```kotlin
@Composable
fun PhotoViewerPage(
    uri: android.net.Uri,
    initialIndex: Int = 0,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onNavigate: (newUri: android.net.Uri, newIndex: Int) -> Unit,
) {
    val vm: PhotoViewerViewModel = viewModel(factory = PhotoViewerViewModel.factory())
    val state by vm.uiState.collectAsStateWithLifecycle()
    // ...
}
```

- [ ] **Step 2: onNavigate 调用处传 index**

找到 onNavigate 调用处,改为:

```kotlin
onNavigate = { newUri ->
    val newIdx = vm.uiState.value.currentIndex + 1
    onNavigate(newUri, newIdx)
}
```

- [ ] **Step 3: delete 回调**

```kotlin
val deleteAction: () -> Unit = {
    vm.delete(onSuccess = onBack)
}
```

- [ ] **Step 4: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 10: 最终验证

- [ ] **Step 1: 全量构建**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

- [ ] **Step 2: 检查 memory leak**

```bash
adb logcat -c
# 启动 App,进入 viewer,退出,等 5 秒
adb logcat | grep -i "PhotoViewerViewModel\|leak"
```

期望:无 leak warning

---

## Self-Review Checklist

- [x] **Spec coverage:** C.1-C.9 全部覆盖 (Task 1-9)
- [x] **Type consistency:** `PhotoViewerUiState.initialIndex` 在 Task 1 定义,Task 9 读取一致
- [x] **路由兼容性:** Task 2 Step 2 保留旧路由 alias
- [x] **依赖子项目 A:** Task 1 依赖 Repository.observeTimeline 缓存
- [x] **依赖子项目 B:** 不直接依赖(Entity vaultId 字段由 B 加)

## Risks Mitigated

- **Task 2 Step 2** 旧路由保留防止 deep link 断裂
- **Task 7 DisposableEffect reset** 在 page 转场过程中可能闪一下默认 title — 不 reset 反而错
- **Task 5 共享 controller** 已确认 LiquidGlassLensController 是单值状态,不会互相干扰