# Bugfix D — UI + 权限 + 杂项 (UI, Permissions, Misc)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this spec task-by-task.

**Goal:** 修复 TimelinePage 重复 AsyncImage、composition-time state mutation、PhotoViewer 删除无回调、SearchPage 地点 chip 无功能、AlbumDetail 实况 filter 错、AppleComponents 颜色和动画 bug,以及 selection toolbar 视觉、monthly header 字号、`.meta` 加密、NotEnrolled 处理、Manifest 权限、deprecated API、硬编码 padding/contentDescription 等所有 UI 层和工程杂项。

**Scope:** 子项目 D。修复 C5/C6/H1/H2/H3/H4/H5/M12/M13/M15/M16/L1/L2/L3/L4/L5/L6/L7/L8/L9/L10/L11/L12 共 23 个 bug。

**依赖:** 子项目 A/B/C 都已完成。但本子项目修改面广,适合拆为 D1 (UI 关键) + D2 (杂项) 并行。

**Architecture:**
- 关键 UI 修复集中在 `TimelinePage`、`AppleComponents`、`PhotoViewerPage`、`SearchPage`、`AlbumDetailPage`
- Manifest 修复:`AndroidManifest.xml` 加 `<queries>`、去掉 `requestLegacyExternalStorage`、加 `<intent-filter>` for BROWSABLE
- Deprecated API 替换:`USE_FINGERPRINT` → `BIOMETRIC_STRONG`
- 字符串外部化:所有硬编码中文移到 `strings.xml`

---

## Context

### UI Critical

| 编号 | 文件 | 行 | 严重度 | 问题 |
|---|---|---|---|---|
| C5 | `ui/pages/TimelinePage.kt` | 336, 363 | CRITICAL | 同一个 `AsyncImage` 渲染两次,选中的 alpha 0.6 被第二次 1.0 覆盖 → 选中效果不可见 |
| C6 | `ui/pages/TimelinePage.kt` | 124-129 | CRITICAL | `LaunchedEffect(segment)` 中 mutate `segment = 0`,在 composition 中触发 state change → IllegalStateException 风险 |
| H1 | `ui/viewer/PhotoViewerPage.kt` | 211 | HIGH | `delete()` 不调 `onBack()`,删除后 viewer 仍显示空白 |
| H2 | `ui/search/SearchPage.kt` | 193 | HIGH | "地点" QuickChip `onClick = { }` 空 → 点击无反应 |
| H3 | `ui/album/AlbumDetailPage.kt` | 91 | HIGH | "实况" filter 用 `isVideo` 判断,而不是 Live Photo flag |
| H4 | `ui/apple/AppleComponents.kt` | 820 | HIGH | 选中 segment 文字 `Color.Black`,应该是 primary blue |
| H5 | `ui/apple/AppleComponents.kt` | 418, 852 | HIGH | `capsuleVisible` 条件 `hovered != null && lensActive` 恒真(lensActive 默认 true) |

### UI Medium

| 编号 | 文件 | 严重度 | 问题 |
|---|---|---|---|
| M12 | `ui/liquidglass/LibraryOverlay.kt` | MEDIUM | selection toolbar 不在 barBackdrop capture 内,long-press magnifier 不工作 |
| M13 | `ui/pages/TimelinePage.kt` | MEDIUM | monthly header 字号 22sp 太重,iOS 26 用 18sp regular |
| M15 | `privacy/EncryptedPrivacyVault.kt` | MEDIUM | `.meta` 文件 plaintext,记录 iv + name,可推断 |
| M16 | `ui/privacy/PrivacyVaultPage.kt` | MEDIUM | NotEnrolled 时只显示 "需要设置生物识别",无引导跳转设置流程 |

### UI Low

| 编号 | 文件 | 问题 |
|---|---|---|
| L1 | `ui/liquidglass/LibraryOverlay.kt` | "选择" 按钮硬编码 96dp bottom padding,应该用 WindowInsets 或 token |
| L2 | `app/src/main/AndroidManifest.xml` | 缺 `<queries>` element,Android 11+ 包可见性受限 |
| L3 | `app/src/main/AndroidManifest.xml` | `requestLegacyExternalStorage` 在 Android 11+ 无效 |
| L4 | `ui/apple/AppleComponents.kt` | `USE_FINGERPRINT` deprecated |
| L5 | `app/src/main/AndroidManifest.xml` | 缺 `BROWSABLE` intent filter,FileProvider URI 无法被外部 app 打开 |
| L6 | 全局 | 多处硬编码中文(按钮、错误、snackbar)未走 `strings.xml` |
| L7 | 全局 | `contentDescription = null` 多处,无障碍工具读不到 |
| L8 | `app/src/main/AndroidManifest.xml` | FileProvider paths 配置太宽泛,应该限定 mime |
| L9 | `ui/liquidglass/LibraryOverlay.kt` | `96.dp` 硬编码 |
| L10 | `ui/viewer/PhotoViewerPage.kt` | 删除按钮 `contentDescription = null` |
| L11 | `ui/components/*` | 缺 WindowInsets 处理,被 status bar 遮挡 |
| L12 | `data/db/AlbumEntity.kt` | `equals` 未定义,DiffUtil 失效 |

## Approach

### D.1 — TimelinePage 重复 AsyncImage 修复 (C5)

**问题:** line 336 渲染一次 `AsyncImage`,line 363 又渲染一次,选中的 alpha 0.6 被第二次的 1.0 覆盖。

**修改:**
```kotlin
@Composable
private fun TimelineCell(...) {
    val overlayColor = if (isSelected) Color(0xCC007AFF) else Color.Transparent
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) Color(0x33007AFF) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        // 只渲染一次
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxSize()
                .then(if (isSelected) Modifier.alpha(0.6f) else Modifier)
        )
        // 选中遮罩(覆盖在图片上方)
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x33007AFF))
            )
        }
        // 选择模式指示器
        if (selectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Color(0xFF007AFF) else Color.White.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        // 视频标识
        if (item.isVideo) { ... }
    }
}
```

**验收:** 进入选择模式 → 选中一张照片 → 看到 0.6 alpha + 蓝色遮罩 + 蓝色 checkmark。

### D.2 — TimelinePage segment 2 不再 mutate composition (C6)

**修改:** 用 `SideEffect` 或把 navigate 放到 `onClick` handler。

**修改:**
```kotlin
// 改用 LaunchedEffect + snapshotFlow,在 effect 内 mutate 不算 composition
LaunchedEffect(segment) {
    snapshotFlow { segment }
        .filter { it == 2 }
        .collect {
            onOpenAlbums()
            segment = 0
        }
}
```

或更稳妥:在 chip `onSelect` 中处理,不在 effect:
```kotlin
iOSSegmentedControl(
    ...
    onSelect = { selected ->
        if (selected == 2) {
            onOpenAlbums()
        } else {
            segment = selected
        }
    }
)
```

**验收:** 选 "图集" segment 不会触发 IllegalStateException。

### D.3 — PhotoViewerPage delete 回调 (H1)

**修改:**
```kotlin
fun delete(onSuccess: () -> Unit) {
    scope.launch {
        repository.setTrash(uri, true)
        onSuccess()  // 触发 onBack
    }
}

// 调用
onDelete = { 
    viewModel.delete(onSuccess = { onBack() })
}
```

### D.4 — SearchPage "地点" chip 接 SearchMode.LOCATION (H2)

**修改 `SearchMode.kt`:**
```kotlin
enum class SearchMode { TEXT, FAVORITES, RECENT, LIVE_PHOTOS, LOCATION }
```

**`SearchViewModel.kt`:**
```kotlin
fun setMode(mode: SearchMode) { ... }
// LOCATION 模式需要:从 MediaItem 提取经纬度,做简单地区聚合
```

**`SearchPage.kt`:**
```kotlin
QuickChip(Icons.Outlined.Place, "地点", Modifier.weight(1f)) { 
    vm.setMode(SearchMode.LOCATION) 
}
```

**验收:** 点 "地点" chip → 进入 location 模式 → 显示有 GPS 标签的照片列表(简化版:只显示有 lat/lng 的)。

### D.5 — AlbumDetail 实况 filter 改 isLivePhoto (H3)

**修改 `AlbumDetailPage.kt`:**
```kotlin
when (filter) {
    AlbumFilter.LIVE_PHOTOS -> items.filter { it.isLivePhoto }  // 而非 isVideo
    ...
}
```

**MediaItem.kt:** 加 `isLivePhoto: Boolean = false` 字段,`MediaStoreDataSource` 读取 `MediaStore.MediaColumns.IS_PARTIAL` 或文件扩展名(`.mov` + has JPEG embedded)判断。

### D.6 — AppleComponents 选中 segment 颜色 (H4)

**修改 `iOSSegmentedControl`:**
```kotlin
val textColor = when {
    selected && isHovered && lensActive -> highlightCyan
    selected -> MaterialTheme.colorScheme.primary  // 而非 Color.Black
    lensActive -> onSurfaceVariant.copy(alpha = 0.6f)
    isHovered -> onSurfaceVariant.copy(alpha = 0.8f)
    else -> onSurfaceVariant
}
```

### D.7 — capsuleVisible 条件修复 (H5)

**修改:**
```kotlin
val capsuleVisible = hovered != null && lensController.isLensing.value
// 或
val capsuleVisible = lensController.isLensing.value  // 只要 lens 激活就显示
```

取决于 iOS 26 原生行为 — 是的,iOS 26 lens 激活时 capsule 持续显示。

### D.8 — selection toolbar 移入 barBackdrop (M12)

**修改 `LibraryOverlay.kt`:** toolbar 放到 `barBackdrop` capture box 内部(同 iOSTabBar 模式)。

### D.9 — monthly header 字号 (M13)

**修改:** 22sp Bold → 18sp SemiBold + small caps。

### D.10 — `.meta` 加密 (M15)

**修改:** 把 name + iv 加密后存到 `.meta`,或者改用文件名编码:`<vaultId>.enc` 文件名内嵌 IV(`<vaultId-base64url>.<iv-base64url>.enc`),无 `.meta`。

### D.11 — NotEnrolled 引导 (M16)

**修改:** 当 `canAuthenticate` 返回 `NotEnrolled` 时,显示按钮 "去设置 →",跳 `Settings.ACTION_BIOMETRIC_ENROLL` + `EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED`。

### D.12 — Manifest 修复 (L2, L3, L5, L8)

**`AndroidManifest.xml`:**
```xml
<queries>
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:mimeType="image/*" />
    </intent>
    <intent>
        <action android:name="android.intent.action.SEND" />
        <data android:mimeType="image/*" />
    </intent>
</queries>

<!-- 删掉: -->
<!-- android:requestLegacyExternalStorage="true" -->

<!-- FileProvider 加 mime 限制: -->
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="..."
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
    <!-- 加 intent-filter for BROWSABLE (L5) -->
</provider>

<!-- Activity 加 intent-filter for shared images: -->
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="image/*" />
</intent-filter>
```

### D.13 — USE_FINGERPRINT 替换 (L4)

**修改:** `BiometricManager.Authenticators.USE_FINGERPRINT` → `BIOMETRIC_STRONG`。

### D.14 — 硬编码中文外部化 (L6)

**新增 `strings.xml` 资源:**
- 所有 chip 文字、placeholder、错误消息、empty state 文字

**全局搜索硬编码中文 → 提到 `R.string.*`。**

### D.15 — contentDescription 补全 (L7, L10)

**原则:**
- 所有 `Icon` 都有 `contentDescription`
- 装饰性 Icon(`null` 可接受,但用 `null` 而不是省略参数,语义明确)

### D.16 — 硬编码 96dp (L1, L9)

**修改:** `LibraryOverlay.kt` selection toolbar 改用 `WindowInsets.systemBars.asPaddingValues()` 或新建 `Spacing.Token.tabBarClearance`。

### D.17 — WindowInsets 处理 (L11)

**AppRoot.kt `Box` 加 `windowInsetsPadding`:**
```kotlin
WindowInsets.systemBars.asPaddingValues()
```

### D.18 — AlbumEntity equals (L12)

**`AlbumEntity.kt`:**
```kotlin
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    // ...
)  // data class 自动 equals/hashCode
```

## File Changes

### Modify

- `ui/pages/TimelinePage.kt` (D.1, D.2, D.13)
- `ui/viewer/PhotoViewerPage.kt` (D.3, D.10)
- `ui/viewer/PhotoViewerViewModel.kt` (D.3)
- `ui/search/SearchPage.kt` (D.4)
- `ui/search/SearchViewModel.kt` (D.4)
- `ui/album/AlbumDetailPage.kt` (D.5)
- `ui/apple/AppleComponents.kt` (D.6, D.7)
- `privacy/EncryptedPrivacyVault.kt` (D.13 - USE_FINGERPRINT → BIOMETRIC_STRONG)
- `ui/liquidglass/LibraryOverlay.kt` (D.8, D.16)
- `privacy/EncryptedPrivacyVault.kt` (D.10)
- `ui/privacy/PrivacyVaultPage.kt` (D.11)
- `app/src/main/AndroidManifest.xml` (D.12)
- `data/db/AlbumEntity.kt` (D.18)
- `data/model/MediaItem.kt` (D.5 - isLivePhoto)
- `scanner/MediaStoreDataSource.kt` (D.5)
- `ui/AppRoot.kt` (D.17)
- `res/values/strings.xml` (D.14)
- `res/values-zh/strings.xml` (D.14)
- 全局硬编码中文文件 (D.14)

## Acceptance Criteria

1. ✅ TimelinePage 选中状态视觉正确(alpha + 遮罩 + checkmark)
2. ✅ 选 "图集" segment 不抛异常
3. ✅ viewer 删除照片后自动返回
4. ✅ 搜索 "地点" 显示有 GPS 的照片
5. ✅ AlbumDetail 实况 filter 只显示 Live Photos
6. ✅ segmented control 选中文字显示 primary 色
7. ✅ lens magnifier capsule 正确显示/隐藏
8. ✅ NotEnrolled 提示有引导跳转
9. ✅ 切换系统语言所有 UI 文案跟随
10. ✅ Android 11+ 设备上 App 能正确处理 MediaStore URI
11. ✅ USE_FINGERPRINT 警告消失

## Risks

- **D.10 .meta 加密** 让现有 vault 文件读不到。Mitigation: 先尝试读 plain meta,失败再尝试解密,带 version 字段标识。
- **D.12 加 BROWSABLE intent-filter** 可能让 App 出现在"分享到"列表。Mitigation: 测试确认体验可接受。
- **D.5 Live Photo 判断** 在 Android 14+ 用 MediaStore 新字段,旧设备用 `.mov` extension 兜底。