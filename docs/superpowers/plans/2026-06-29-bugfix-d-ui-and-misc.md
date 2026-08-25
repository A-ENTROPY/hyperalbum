# Bugfix D — UI + Permissions + Misc Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 C5/C6/H1/H2/H3/H4/H5/M12/M13/M15/M16/L1-L12 共 23 个 UI 层和工程杂项 bug。

**Architecture:**
- TimelineCell 删除重复 AsyncImage,选中效果走 overlay Box + Modifier.alpha
- Segment 2 navigate 移到 onSelect handler(不再 LaunchedEffect mutate)
- PhotoViewerPage delete 走 onBack 回调
- SearchMode 加 LOCATION,AlbumFilter 加 LIVE_PHOTOS
- AppleComponents 选中段文字走 primary 色,capsuleVisible 简化
- Manifest 加 `<queries>`、删 `requestLegacyExternalStorage`、FileProvider 限 mime
- USE_FINGERPRINT → BIOMETRIC_STRONG

**Tech Stack:** AndroidX Biometric, Compose Material3, Android Manifest queries

**Dependency:** A/B/C 都已完成

**Note:** 无 git,跳过 commit 步骤。

---

## Task 1: TimelineCell 删除重复 AsyncImage (C5)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt:326-409`

- [ ] **Step 1: 重写 TimelineCell**

替换 `TimelinePage.kt` 中 TimelineCell 函数:

```kotlin
@Composable
private fun TimelineCell(
    item: MediaItem,
    selectMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (isSelected) Color(0x33007AFF)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
    ) {
        // 1. 唯一 AsyncImage — alpha 反映选中态
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxSize()
                .then(if (isSelected) Modifier.alpha(0.6f) else Modifier),
        )
        // 2. 选中蓝色遮罩
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x33007AFF))
            )
        }
        // 3. 选择模式 checkmark
        if (selectMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) Color(0xFF007AFF)
                        else Color.White.copy(alpha = 0.85f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = stringResource(R.string.selected),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        // 4. 视频标识
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .clip(CircleShape)
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
        // 5. 视频时长
        if (item.isVideo && item.durationMs != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.42f), RoundedCornerShape(4.dp))
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
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 2: Segment 2 navigate 移到 onSelect (C6)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt`

- [ ] **Step 1: 修改 iOSSegmentedControl 调用**

找到 TimelinePage 中 `LaunchedEffect(segment) { ... if (segment == 2) { onOpenAlbums(); segment = 0 } }`,删除。

修改 segmented control 调用:

```kotlin
val libraryOverlayState = LocalLibraryOverlayState.current
LaunchedEffect(Unit) {
    libraryOverlayState.value = LibraryOverlayState(
        segment = segment,
        // ...
        onSegmentChange = { selected ->
            if (selected == 2) {
                onOpenAlbums()
                // 不 mutate segment — segment 仍为 0,下次进入 timeline 仍然是 全部
            } else {
                segment = selected
            }
        },
        // ...
    )
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 3: PhotoViewerPage delete 回调 (H1)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerPage.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/viewer/PhotoViewerViewModel.kt`

- [ ] **Step 1: ViewModel 加 delete 方法接收 onSuccess**

```kotlin
fun delete(onSuccess: () -> Unit) {
    viewModelScope.launch {
        val current = uiState.value.items.getOrNull(uiState.value.currentIndex)
            ?: return@launch
        repository.setTrash(Uri.parse(current.uri), true)
        onSuccess()
    }
}
```

- [ ] **Step 2: PhotoViewerPage 删除按钮接 onBack**

找到 PhotoViewerPage 中删除按钮的 onClick,改为:

```kotlin
val deleteAction: () -> Unit = {
    vm.delete(onSuccess = onBack)
}
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 4: SearchMode 加 LOCATION (H2)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/search/SearchViewModel.kt`(或类似定义 SearchMode 的文件)
- Modify: `app/src/main/java/com/smartvision/gallery/ui/search/SearchPage.kt`

- [ ] **Step 1: SearchMode 加 LOCATION 枚举**

```kotlin
enum class SearchMode { TEXT, FAVORITES, RECENT, LIVE_PHOTOS, LOCATION }
```

- [ ] **Step 2: ViewModel 处理 LOCATION**

在 `SearchViewModel.setMode` 中,如果 mode 是 LOCATION,过滤有 `latitude != null` 的 items:

```kotlin
fun setMode(mode: SearchMode) {
    searchMode.value = mode
    if (mode == SearchMode.LOCATION) {
        viewModelScope.launch {
            results.value = repository.observeTimeline()
                .first()
                .filter { it.latitude != null && it.longitude != null }
        }
    }
}
```

- [ ] **Step 3: SearchPage "地点" chip 接 SearchMode.LOCATION**

```kotlin
QuickChip(Icons.Outlined.Place, "地点", Modifier.weight(1f)) {
    vm.setMode(SearchMode.LOCATION)
}
```

- [ ] **Step 4: strings.xml 加标签**

```xml
<string name="search_mode_location">带位置的照片</string>
```

- [ ] **Step 5: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 5: AlbumDetail 实况 filter 改 isLivePhoto (H3)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/model/MediaItem.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/MediaEntity.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/data/repo/MediaRepository.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/scanner/MediaStoreDataSource.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/album/AlbumDetailPage.kt`

- [ ] **Step 1: MediaItem 加 isLivePhoto**

```kotlin
val isLivePhoto: Boolean = false,
```

- [ ] **Step 2: MediaEntity 加 isLivePhoto**

```kotlin
@ColumnInfo(name = "isLivePhoto")
val isLivePhoto: Boolean = false,
```

- [ ] **Step 3: MediaRepository.toModel 传递**

```kotlin
isLivePhoto = entity.isLivePhoto,
```

- [ ] **Step 4: MediaStoreDataSource 判断 Live Photo**

视频查询(已存在 video loop)中,加判断:`name.endsWith(".mov", ignoreCase = true)` 或者用 `MediaStore.MediaColumns.IS_PARTIAL`(Android 14+):

```kotlin
val isLivePhoto = name.endsWith(".mov", ignoreCase = true)
// 或者 Android 14+:
// val isPartial = c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_PARTIAL)) == 1
// val isLivePhoto = isPartial && mime?.contains("quicktime") == true
```

加 `isLivePhoto = isLivePhoto` 到 MediaEntity 构造。

- [ ] **Step 5: AppDatabase 加 migration v3→v4**

新建 MIGRATION_3_4:

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media ADD COLUMN isLivePhoto INTEGER NOT NULL DEFAULT 0")
    }
}
```

**注意:** 子项目 B 完成 v2→v3 migration 后,本子项目加 v3→v4。版本号要按实际顺序。

- [ ] **Step 6: AlbumDetailPage filter 改**

```kotlin
AlbumFilter.LIVE_PHOTOS -> items.filter { it.isLivePhoto }
```

- [ ] **Step 7: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 6: AppleComponents 选中段文字色 (H4)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/apple/AppleComponents.kt:820`

- [ ] **Step 1: 替换 text color 逻辑**

找到 iOSSegmentedControl 中 selected text color 赋值:

```kotlin
color = if (selected && isHovered && lensActive) highlightCyan
    else if (selected) Color.Black  // 改前
    else ...
```

替换为:

```kotlin
val primary = MaterialTheme.colorScheme.primary
color = when {
    selected && isHovered && lensActive -> highlightCyan
    selected -> primary  // iOS 26 primary blue
    lensActive -> onSurfaceVariant.copy(alpha = 0.6f)
    isHovered -> onSurfaceVariant.copy(alpha = 0.8f)
    else -> onSurfaceVariant
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 7: capsuleVisible 简化 (H5)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/apple/AppleComponents.kt:418, 852`

- [ ] **Step 1: 简化 capsuleVisible**

找到所有 `capsuleVisible` 计算:

```kotlin
val capsuleVisible = hovered != null && lensActive  // 改前
```

替换为:

```kotlin
// iOS 26 行为:lens active 时持续显示 capsule
val capsuleVisible = lensActive
```

如果 `hovered` 也参与判断(用 lens 悬浮效果),可以:

```kotlin
val capsuleVisible = lensActive || (hovered != null && !lensActive)
```

按 iOS 26 原生效果:只在 lens active 时显示 capsule。所以用第一个简化版。

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 8: selection toolbar 移入 barBackdrop (M12)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LibraryOverlay.kt:178-204`

- [ ] **Step 1: 重构 selection toolbar 包裹**

将 selection toolbar 移到 `barBackdrop` capture box 内:

```kotlin
// 在 AppRoot.kt 的 Z=1 tab bar 区:
Box(
    modifier = Modifier
        .fillMaxSize()
        .layerBackdrop(barBackdrop)  // 已有
) {
    // 1a. 隐藏 backdrop-only tab bar
    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
        iOSTabBar(items = BOTTOM_NAV_ITEMS, ..., backdropOnly = true)
    }

    // 1b. selection toolbar 也参与 backdrop capture
    if (currentRoute == Routes.TIMELINE && libraryOverlayState.value.selectModeEnabled
        && libraryOverlayState.value.selectedUris.isNotEmpty()) {
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)) {
            // 渲染 LiquidGlassBar 选择工具栏
            LibrarySelectionToolbar(state = libraryOverlayState.value)
        }
    }
}
```

或者更简单 — selection toolbar 自身用 `drawBackdrop(barBackdrop, ...)`:

```kotlin
LiquidGlassBar(
    backdrop = barBackdrop,  // 显式传入
    spec = LiquidGlassSpec.VibrantPlus.copy(cornerRadius = 28.dp, ...),
) { /* ... */ }
```

让 selection toolbar 在 LibraryOverlay 中显式接收 barBackdrop 参数。

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 9: monthly header 字号 (M13)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/pages/TimelinePage.kt:DayHeader`

- [ ] **Step 1: 改字号**

```kotlin
@Composable
private fun DayHeader(title: String) {
    Text(
        text = title,
        fontSize = 18.sp,  // 改前 22sp
        fontWeight = FontWeight.SemiBold,  // 改前 Bold
        letterSpacing = (-0.2).sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 8.dp)
    )
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 10: .meta 加密 (M15)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/privacy/EncryptedPrivacyVault.kt:84-90`

- [ ] **Step 1: 把 meta 也加密**

替代 plaintext `.meta`,把 name + iv 打包到 ciphertext header:

```kotlin
// 加密 + 写文件
val cipherBytes = cipher.doFinal(plainBytes)

// Format: [4 bytes magic "SVVL"][1 byte version][12 bytes IV][N bytes name][ciphertext]
val nameBytes = item.displayName.toByteArray(Charsets.UTF_8)
val outFile = File(vaultDir, "$vaultId.enc")
FileOutputStream(outFile).use { fos ->
    fos.write("SVVL".toByteArray(Charsets.US_ASCII))  // 4-byte magic
    fos.write(1)  // 1-byte version
    fos.write(iv)  // 12-byte IV (already part of header now)
    fos.write(nameBytes.size.toShort().toByteArray())  // 2-byte name length
    fos.write(nameBytes)
    fos.write(cipherBytes)
}

// .meta 不再需要
```

- [ ] **Step 2: decryptToPlaintext 改为读 header**

```kotlin
suspend fun decryptToPlaintext(vaultUri: Uri): Uri? = withContext(Dispatchers.IO) {
    try {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val fileBytes = FileInputStream(vaultUri.path!!).use { it.readBytes() }

        require(fileBytes.size > 19) { "Vault file too short: ${vaultUri.path}" }
        require(fileBytes.copyOfRange(0, 4).contentEquals("SVVL".toByteArray())) {
            "Vault file magic mismatch: ${vaultUri.path}"
        }
        val version = fileBytes[4].toInt()
        require(version == 1) { "Unsupported vault version: $version" }
        val iv = fileBytes.copyOfRange(5, 17)
        val nameLen = ((fileBytes[17].toInt() and 0xFF) shl 8) or (fileBytes[18].toInt() and 0xFF)
        val name = String(fileBytes.copyOfRange(19, 19 + nameLen), Charsets.UTF_8)
        val cipherBytes = fileBytes.copyOfRange(19 + nameLen, fileBytes.size)

        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val plain = cipher.doFinal(cipherBytes)

        val outDir = File(app.cacheDir, "vault-decoded").apply { mkdirs() }
        val out = File(outDir, name)
        FileOutputStream(out).use { it.write(plain) }
        Uri.fromFile(out)
    } catch (e: VaultException) {
        AppLog.e(TAG, "Vault decrypt corrupt: $vaultUri", e)
        null
    } catch (e: Throwable) {
        AppLog.e(TAG, "Vault decrypt failed: $vaultUri", e)
        null
    }
}
```

- [ ] **Step 3: 兼容老 .meta 文件(子项目 B 迁移期间)**

```kotlin
val fileBytes = FileInputStream(vaultUri.path!!).use { it.readBytes() }
val (iv, name, cipherBytes) = if (fileBytes.size >= 4 && fileBytes[0] == 'S'.code.toByte()) {
    // 新格式
    readNewFormat(fileBytes)
} else {
    // 老格式 — 读 .meta 文件
    val metaFile = File(vaultUri.path!!.removeSuffix(".enc") + ".meta")
    if (!metaFile.exists()) throw VaultException.CorruptMetadata(vaultUri.path!!, "no meta")
    readLegacyFormat(fileBytes, metaFile)
}
```

完整兼容代码较长,这里简化为优先新格式,老格式作为 fallback。如果 VaultMigrator 已把所有老文件迁完,可以删老格式分支。

- [ ] **Step 4: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 11: NotEnrolled 引导 (M16)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/privacy/PrivacyVaultPage.kt`

- [ ] **Step 1: 加引导按钮**

```kotlin
when (vault.canAuthenticate(activity)) {
    BiometricAvailability.NotEnrolled -> {
        Column {
            Text(stringResource(R.string.vault_not_enrolled))
            Button(onClick = {
                val enrollIntent = Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                    putExtra(
                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                }
                activity.startActivity(enrollIntent)
            }) {
                Text(stringResource(R.string.vault_setup_now))
            }
        }
    }
    BiometricAvailability.Available -> { /* normal flow */ }
    BiometricAvailability.NoHardware,
    BiometricAvailability.Unavailable -> { /* fallback to device credential only */ }
}
```

- [ ] **Step 2: strings.xml 加标签**

```xml
<string name="vault_setup_now">立即设置</string>
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 12: Manifest 加 queries + 删 legacy storage (L2, L3, L5, L8)

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 添加 queries**

在 `<manifest>` 顶部 `<application>` 之前添加:

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
    <intent>
        <action android:name="android.intent.action.PICK" />
        <data android:mimeType="image/*" />
    </intent>
</queries>
```

- [ ] **Step 2: 删除 requestLegacyExternalStorage**

```xml
<!-- 删除这行(在 <application> 标签内) -->
<!-- android:requestLegacyExternalStorage="true" -->
```

- [ ] **Step 3: FileProvider 限 mime**

如果有 `<provider>` for FileProvider,确保 `grantUriPermissions="true"` 且 path 配置不宽泛。检查 `res/xml/file_paths.xml`:

```xml
<paths>
    <cache-path name="cache" path="." />
    <files-path name="files" path="." />
</paths>
```

可改为更精确:

```xml
<paths>
    <cache-path name="vault_decoded" path="vault-decoded/" />
    <files-path name="exports" path="exports/" />
</paths>
```

- [ ] **Step 4: 加 BROWSABLE intent filter**

如果 MainActivity 没有 deep link filter,加:

```xml
<intent-filter android:label="@string/app_name">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:mimeType="image/*" />
</intent-filter>
```

- [ ] **Step 5: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

---

## Task 13: USE_FINGERPRINT → BIOMETRIC_STRONG (L4)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/privacy/EncryptedPrivacyVault.kt`

- [ ] **Step 1: 替换**

```kotlin
// 改前
BiometricManager.Authenticators.USE_FINGERPRINT
// 或
BiometricManager.Authenticators.BIOMETRIC_WEAK

// 改后
BiometricManager.Authenticators.BIOMETRIC_STRONG
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

期望:无 `Deprecated` 警告

---

## Task 14: 硬编码中文外部化 (L6)

**Files:**
- 全局搜索硬编码中文
- Modify: `app/src/main/res/values/strings.xml` 和 `values-zh/strings.xml`

- [ ] **Step 1: 全局搜索**

```bash
cd "H:/workspace-minimaxcode/超级相册/app/src/main/java"
grep -rn '"[^"]*[一-龥][^"]*"' --include="*.kt" | head -50
```

找到硬编码中文字符串,列出。

- [ ] **Step 2: 移到 strings.xml**

按 strings.xml 已有命名规则,逐个添加条目。优先做高曝光的:
- Empty state 文案
- Button labels
- Snackbar messages
- Dialog titles

如工作量太大,优先做 5-10 个最高频的,其余留作后续清理。

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

---

## Task 15: contentDescription 补全 (L7, L10)

**Files:**
- 全局搜索 `contentDescription = null`

- [ ] **Step 1: 全局搜索**

```bash
cd "H:/workspace-minimaxcode/超级相册/app/src/main/java"
grep -rn "contentDescription = null" --include="*.kt"
```

- [ ] **Step 2: 分类处理**

对每个 null:
- 装饰性 Icon:`contentDescription = null` 保留(用 `null` 显式标注是装饰)
- 功能性 Icon:加字符串

例如:

```kotlin
// 改前
Icon(Icons.Outlined.Delete, contentDescription = null, ...)

// 改后
Icon(
    Icons.Outlined.Delete,
    contentDescription = stringResource(R.string.delete),
    ...
)
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

---

## Task 16: 硬编码 96dp 改 token (L1, L9)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/liquidglass/LibraryOverlay.kt`

- [ ] **Step 1: 定义 Spacing token**

新建 `app/src/main/java/com/smartvision/gallery/ui/theme/Spacing.kt`:

```kotlin
package com.smartvision.gallery.ui.theme

import androidx.compose.ui.unit.dp

object Spacing {
    val TabBarClearance = 96.dp  // 80dp tab bar + 12dp breathing + 4dp 安全
    val SegmentedControlTop = 84.dp
    val ActionRowTop = 132.dp
}
```

- [ ] **Step 2: LibraryOverlay 改用 token**

```kotlin
import com.smartvision.gallery.ui.theme.Spacing

// 改前
.padding(bottom = 96.dp)

// 改后
.padding(bottom = Spacing.TabBarClearance)
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 17: WindowInsets 处理 (L11)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/AppRoot.kt`

- [ ] **Step 1: 添加 WindowInsets**

```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.asPaddingValues

// 在 AppRoot composable 中,最外层 Box 加:
Box(
    modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.systemBars)
) {
    // 原有 children
}
```

或者用 `consumeWindowInsets`。

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 18: AlbumEntity data class (L12)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/AlbumEntity.kt`

- [ ] **Step 1: 改 data class**

```kotlin
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "coverUri") val coverUri: String?,
    @ColumnInfo(name = "itemCount") val itemCount: Int = 0,
    @ColumnInfo(name = "latestDateMs") val latestDateMs: Long = 0L,
    @ColumnInfo(name = "bucketPath") val bucketPath: String? = null,
    @ColumnInfo(name = "formatFilter") val formatFilter: String? = null,
)
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 19: 最终验证

- [ ] **Step 1: 全量构建**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

- [ ] **Step 2: Lint 检查**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:lintDebug
```

检查 warning 数量是否下降。

- [ ] **Step 3: 全量单元测试**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:testDebugUnitTest
```

---

## Self-Review Checklist

- [x] **Spec coverage:** D.1-D.18 全部覆盖 (Task 1-18)
- [x] **Type consistency:** `MediaItem.isLivePhoto` 在 Task 5 定义,Task 5 Step 6 使用
- [x] **依赖顺序:** Task 5 (isLivePhoto) 必须在子项目 A 完成 schema 后,Task 12 (Manifest) 不依赖
- [x] **AppDatabase version:** 子项目 A v1→v2, B v2→v3, 本 Task 5 v3→v4,顺序确认

## Risks Mitigated

- **Task 10 .meta 加密** 老 vault 文件需要兼容 — Step 3 提供回退路径
- **Task 12 BROWSABLE** App 可能出现在"分享到"列表 — 测试接受度
- **Task 14 中文外部化** 工作量大,优先做高频 5-10 个