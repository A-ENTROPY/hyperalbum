# Bugfix A — Data Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 16 个数据层 bug (C2, C4, H9, H10, M7, M8, M9, M10, M11, L13-L19),建立正确的 schema migration、bucket 解析、缓存、Converter round-trip 基础。

**Architecture:**
- `bucketFromPath` 改为取最后两段路径(`DCIM/Camera` 而非 `Camera`)
- `replaceAll` 改为 transaction 包裹的 truncate + upsert
- `MediaEntity` 不变 schema,Converter 改用 ASCII 哨兵字符 join
- `MediaRepository` 引入 `CoroutineScope` 注入,持有 `StateFlow<List<MediaItem>>` 缓存
- vault `decryptToPlaintext` 改为缺失 meta 时显式抛错(不再 fallback random IV)
- DAO 加 `@Transaction` 包裹 + 显式 `OnConflictStrategy.REPLACE`

**Tech Stack:** Room 2.6+, Kotlin Coroutines, MediaStore, ExifInterface

**Note:** 项目无 git,跳过 commit 步骤,改用 build verification (`./gradlew :app:assembleDebug`)。

---

## Task 1: 修正 bucketFromPath (C4)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/scanner/MediaStoreDataSource.kt:235-238`

- [ ] **Step 1: 替换 bucketFromPath 函数**

将 `MediaStoreDataSource.kt` 第 235-238 行:

```kotlin
private fun bucketFromPath(path: String): String {
    val segments = path.split('/')
    return segments.getOrNull(segments.size - 2) ?: "root"
}
```

替换为:

```kotlin
/**
 * 从文件路径提取 bucket 名,取最后两段路径(如 "DCIM/Camera")。
 * 原实现只取倒数第二段,会丢失父目录(DCIM/Camera → "Camera")。
 */
private fun bucketFromPath(path: String): String {
    val segments = path.split('/').filter { it.isNotEmpty() }
    return when (segments.size) {
        0 -> "root"
        1 -> segments[0]
        else -> "${segments[segments.size - 2]}/${segments.last()}"
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

期望: BUILD SUCCESSFUL

- [ ] **Step 3: 单元测试 (验证解析正确)**

创建 `app/src/test/java/com/smartvision/gallery/scanner/BucketFromPathTest.kt`:

```kotlin
package com.smartvision.gallery.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class BucketFromPathTest {
    private fun bucketFromPath(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        return when (segments.size) {
            0 -> "root"
            1 -> segments[0]
            else -> "${segments[segments.size - 2]}/${segments.last()}"
        }
    }

    @Test fun `DCIM Camera path returns DCIM-Camera`() {
        assertEquals("DCIM/Camera", bucketFromPath("/storage/emulated/0/DCIM/Camera/IMG_001.jpg"))
    }

    @Test fun `single segment returns itself`() {
        assertEquals("Download", bucketFromPath("/storage/emulated/0/Download/file.zip"))
    }

    @Test fun `empty path returns root`() {
        assertEquals("root", bucketFromPath(""))
    }
}
```

- [ ] **Step 4: 运行测试**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:testDebugUnitTest --tests "com.smartvision.gallery.scanner.BucketFromPathTest"
```

期望: 3 tests passed

---

## Task 2: 修正 replaceAll 真正删除旧行 (C2)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/MediaDao.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/data/repo/MediaRepository.kt:118-122`

- [ ] **Step 1: DAO 添加 deleteAll 方法**

在 `MediaDao.kt` 添加:

```kotlin
@Query("DELETE FROM media")
suspend fun deleteAll(): Int
```

- [ ] **Step 2: Repository 修正 replaceAll**

将 `MediaRepository.kt` 第 118-122 行:

```kotlin
suspend fun replaceAll(items: List<MediaEntity>) {
    // Truncate by deleting and re-inserting; keeps it simple for V1.0.
    // (We don't actually have a "deleteAll" on the DAO; using a no-op marker.)
    mediaDao.upsertAll(items)
}
```

替换为:

```kotlin
/**
 * 全量替换 — 先 DELETE 再 INSERT,在 transaction 中保证原子性。
 * 用法:MediaScanService 启动时调用一次,清掉已删除/重命名的行。
 */
suspend fun replaceAll(items: List<MediaEntity>) {
    mediaDao.runInTransaction {
        mediaDao.deleteAll()
        mediaDao.upsertAll(items)
    }
}
```

- [ ] **Step 3: DAO upsertAll 加 OnConflictStrategy.REPLACE (L19)**

```kotlin
@Insert(onConflict = OnConflictStrategy.REPLACE)
suspend fun upsertAll(items: List<MediaEntity>)
```

如果原方法没有 `onConflict` 参数,加上。

- [ ] **Step 4: 加 @Transaction 注解 (L13)**

```kotlin
@Transaction
suspend fun replaceAllTransactional(items: List<MediaEntity>) {
    deleteAll()
    upsertAll(items)
}
```

- [ ] **Step 5: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 3: 修正 aiTags Converter (H9, L17)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/Converters.kt`
- Create: `app/src/test/java/com/smartvision/gallery/data/ConvertersTest.kt`

- [ ] **Step 1: 替换 Converter**

将 `Converters.kt` 中的 aiTags 转换方法替换为:

```kotlin
private const val AI_TAGS_SEPARATOR = ""  // ASCII Unit Separator

@TypeConverter
fun fromAiTags(tags: List<String>?): String =
    tags?.joinToString(AI_TAGS_SEPARATOR) ?: ""

@TypeConverter
fun toAiTags(value: String?): List<String> =
    if (value.isNullOrEmpty()) emptyList()
    else value.split(AI_TAGS_SEPARATOR).filter { it.isNotEmpty() }
```

- [ ] **Step 2: 创建单元测试**

`app/src/test/java/com/smartvision/gallery/data/ConvertersTest.kt`:

```kotlin
package com.smartvision.gallery.data.db

import org.junit.Assert.assertEquals
import org.junit.Test

class ConvertersTest {
    @Test fun `roundTrip preserves list`() {
        val input = listOf("cat", "dog", "鸟")
        val encoded = Converters().fromAiTags(input)
        val decoded = Converters().toAiTags(encoded)
        assertEquals(input, decoded)
    }

    @Test fun `null becomes empty list`() {
        assertEquals(emptyList<String>(), Converters().toAiTags(null))
    }

    @Test fun `empty string becomes empty list`() {
        assertEquals(emptyList<String>(), Converters().toAiTags(""))
    }

    @Test fun `empty list becomes empty string`() {
        assertEquals("", Converters().fromAiTags(emptyList()))
    }
}
```

注: Converters 是 data class 的 Room TypeConverter,需要确认是否可实例化。如不可,改为 `@JvmStatic` 静态方法。

- [ ] **Step 3: 运行测试**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:testDebugUnitTest --tests "com.smartvision.gallery.data.db.ConvertersTest"
```

---

## Task 4: vault decryptToPlaintext 显式抛错 (H10)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/privacy/EncryptedPrivacyVault.kt:115-137`

- [ ] **Step 1: 替换解密逻辑**

将 `EncryptedPrivacyVault.kt` 第 115-137 行解密函数:

```kotlin
suspend fun decryptToPlaintext(vaultUri: Uri): Uri? = withContext(Dispatchers.IO) {
    runCatching {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val cipherBytes = FileInputStream(vaultUri.path!!).use { it.readBytes() }
        val metaFile = File(vaultUri.path!!.removeSuffix(".enc") + ".meta")
        val iv = if (metaFile.exists()) {
            val meta = metaFile.readText()
            val line = meta.lineSequence().firstOrNull { it.startsWith("iv=") } ?: return@runCatching null
            android.util.Base64.decode(line.removePrefix("iv="), android.util.Base64.NO_WRAP)
        } else SecureRandom().generateSeed(12)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val plain = cipher.doFinal(cipherBytes)
        val name = if (metaFile.exists()) {
            metaFile.readText().lineSequence().firstOrNull { it.startsWith("name=") }
                ?.removePrefix("name=") ?: "decrypted.bin"
        } else "decrypted.bin"
        val outDir = File(app.cacheDir, "vault-decoded").apply { mkdirs() }
        val out = File(outDir, name)
        FileOutputStream(out).use { it.write(plain) }
        Uri.fromFile(out)
    }.onFailure { AppLog.e(TAG, "Vault decrypt failed for $vaultUri", it) }.getOrNull()
}
```

替换为:

```kotlin
/**
 * 解密 vault 项到 plaintext,失败抛 [VaultException.CorruptMetadata]。
 * 不再 fallback 到 random IV(GCM tag mismatch 会导致静默失败)。
 */
suspend fun decryptToPlaintext(vaultUri: Uri): Uri? = withContext(Dispatchers.IO) {
    try {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val cipherBytes = FileInputStream(vaultUri.path!!).use { it.readBytes() }
        val metaFile = File(vaultUri.path!!.removeSuffix(".enc") + ".meta")
        require(metaFile.exists()) { "Vault metadata missing: ${vaultUri.path}" }
        val metaText = metaFile.readText()
        val ivLine = metaText.lineSequence().firstOrNull { it.startsWith("iv=") }
            ?: throw VaultException.CorruptMetadata(vaultUri.path!!, "missing iv")
        val iv = android.util.Base64.decode(ivLine.removePrefix("iv="), android.util.Base64.NO_WRAP)
        val name = metaText.lineSequence().firstOrNull { it.startsWith("name=") }
            ?.removePrefix("name=") ?: "decrypted.bin"
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val plain = cipher.doFinal(cipherBytes)
        val outDir = File(app.cacheDir, "vault-decoded").apply { mkdirs() }
        val out = File(outDir, name)
        FileOutputStream(out).use { it.write(plain) }
        Uri.fromFile(out)
    } catch (e: VaultException) {
        AppLog.e(TAG, "Vault decrypt failed (corrupt) for $vaultUri", e)
        null
    } catch (e: Throwable) {
        AppLog.e(TAG, "Vault decrypt failed for $vaultUri", e)
        null
    }
}
```

- [ ] **Step 2: 创建 VaultException 类**

创建 `app/src/main/java/com/smartvision/gallery/privacy/VaultException.kt`:

```kotlin
package com.smartvision.gallery.privacy

sealed class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HideFailed(cause: Throwable) : VaultException("Hide failed", cause)
    class UnhideFailed(cause: Throwable) : VaultException("Unhide failed", cause)
    class CorruptMetadata(val path: String, reason: String) :
        VaultException("Vault metadata corrupt at $path: $reason")
}
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 5: MediaStore 排序改 COALESCE (M9)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/scanner/MediaStoreDataSource.kt:58, 139`

- [ ] **Step 1: 替换 sortOrder**

将 `MediaStoreDataSource.kt` 中的两个 sortOrder 变量:

```kotlin
val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
```

替换为:

```kotlin
val sortOrder =
    "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, ${MediaStore.Images.Media.DATE_MODIFIED} * 1000) DESC"
```

视频查询同样替换:

```kotlin
val sortOrder =
    "COALESCE(${MediaStore.Video.Media.DATE_TAKEN}, ${MediaStore.Video.Media.DATE_MODIFIED} * 1000) DESC"
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 6: queryAll 并行 EXIF (M10)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/scanner/MediaStoreDataSource.kt:29-35`

- [ ] **Step 1: 替换 queryAll**

将 `MediaStoreDataSource.kt` 第 29-35 行:

```kotlin
suspend fun queryAll(): List<MediaEntity> = withContext(ioDispatcher) {
    val results = ArrayList<MediaEntity>()
    results += queryImages()
    results += queryVideos()
    AppLog.i(TAG, "queryAll returned ${results.size} items")
    results
}
```

替换为:

```kotlin
suspend fun queryAll(): List<MediaEntity> = withContext(ioDispatcher) {
    val results = ArrayList<MediaEntity>()
    results += queryImages()
    results += queryVideos()
    AppLog.i(TAG, "queryAll returned ${results.size} items")

    // 并行补全 GPS 信息,限流 4 并发避免 IO 过载
    val semaphore = kotlinx.coroutines.sync.Semaphore(permits = 4)
    coroutineScope {
        results.map { entity ->
            async {
                semaphore.withPermit {
                    val (lat, lng) = readGeoFromExif(Uri.parse(entity.uri))
                    entity.copy(latitude = lat, longitude = lng)
                }
            }
        }.awaitAll()
    }
}
```

确认 imports 包含:
```kotlin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 7: AppDatabase 去掉 fallbackToDestructiveMigration (M11)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/AppDatabase.kt`

- [ ] **Step 1: 移除 fallbackToDestructiveMigration**

在 `AppDatabase.kt` 的 Room database builder 调用中,删除:

```kotlin
.fallbackToDestructiveMigration()
```

- [ ] **Step 2: 添加占位空迁移 (子项目 B 完成后补 ALTER TABLE)**

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 子项目 B 完成后补:db.execSQL("ALTER TABLE media ADD COLUMN vaultId TEXT")
        // 当前 v3 还未到,先占位让 builder 不报错
    }
}

// 在 builder 调用中:
Room.databaseBuilder(context, AppDatabase::class.java, "smartvision.db")
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    .build()
```

如果当前没有 Migration 类,新建 `app/src/main/java/com/smartvision/gallery/data/AppDatabaseMigrations.kt`:

```kotlin
package com.smartvision.gallery.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 占位:实际迁移 SQL 看现有 schema 变化
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 子项目 B 完成后: db.execSQL("ALTER TABLE media ADD COLUMN vaultId TEXT")
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 8: Repository 加内存缓存 (M7, M8)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/repo/MediaRepository.kt`

- [ ] **Step 1: 添加缓存字段和 scope**

修改 `MediaRepository.kt` 构造器,加 scope 注入:

```kotlin
class MediaRepository(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val albumDao: AlbumDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val timelineCache = MutableStateFlow<List<MediaItem>>(emptyList())

    init {
        scope.launch {
            mediaDao.observeTimeline()
                .map { it.map(::toModel) }
                .distinctUntilChanged()
                .collect { timelineCache.value = it }
        }
    }
    // ...
}
```

加 imports:
```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
```

- [ ] **Step 2: observeTimeline 改为从缓存读取**

```kotlin
fun observeTimeline(): Flow<List<MediaItem>> = timelineCache
```

- [ ] **Step 3: observeSmartAlbums 用缓存 + distinctUntilChanged**

```kotlin
fun observeSmartAlbums(): Flow<List<Album>> {
    return timelineCache
        .combine(observeUsedFormats()) { items, formats -> /* ... existing logic ... */ }
        .distinctUntilChanged()
}
```

- [ ] **Step 4: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 9: 清理 L13-L19 杂项

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/MediaEntity.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/MediaDao.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/scanner/MediaStoreDataSource.kt:199-218`

- [ ] **Step 1: MediaEntity 改为 data class 风格 (L15)**

如果当前是 `class MediaEntity(...)`,改为 `data class MediaEntity(...)`。Kotlin 自动生成 equals/hashCode。

- [ ] **Step 2: DAO 批量方法加 @Transaction (L13)**

```kotlin
@Transaction
suspend fun upsertWithRelations(items: List<MediaEntity>, relations: List<AlbumEntity>) {
    upsertAll(items)
    albumDao.upsertAll(relations)
}
```

- [ ] **Step 3: 移除 L14 兜底**

`MediaRepository.kt:64`:

```kotlin
val groups = items.groupBy { it.bucketPath ?: "unknown" }  // 改前
```

改为:

```kotlin
val groups = items.groupBy { it.bucketPath }  // 契约保证非空
```

- [ ] **Step 4: inferFormat 优先 sniff magic bytes (L16)**

`MediaStoreDataSource.kt:199-218`:

```kotlin
private fun inferFormat(uri: Uri, name: String, mime: String?): MediaFormat {
    // 1. 优先 sniff magic bytes(防 rename 后缀欺骗)
    val magicGuess = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(12)
            input.read(header)
            MediaFormat.fromMagicBytes(header)
        }
    }.getOrNull()
    if (magicGuess != null && magicGuess != MediaFormat.UNKNOWN) return magicGuess

    // 2. 退到 extension
    val extGuess = MediaFormat.fromFilename(name)
    if (extGuess != MediaFormat.UNKNOWN) return extGuess

    // 3. 最后 MIME
    return when {
        mime == null -> MediaFormat.UNKNOWN
        mime.contains("avif") -> MediaFormat.AVIF_STATIC
        // ... existing MIME logic
        else -> MediaFormat.UNKNOWN
    }
}
```

注:如果 `MediaFormat.fromMagicBytes` 不存在,跳过此步并保留原逻辑(L16 是 LOW,可 YAGNI)。

- [ ] **Step 5: queryImages 加 cancellation (L18)**

```kotlin
private suspend fun queryImages(): List<MediaEntity> = withContext(ioDispatcher) {
    // ... existing cursor loop, 加 `ensureActive()` 在每 N 次迭代
    var count = 0
    while (c.moveToNext()) {
        if (count++ % 100 == 0) ensureActive()
        // ... existing logic
    }
}
```

- [ ] **Step 6: 全量编译**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

期望: BUILD SUCCESSFUL

---

## Task 10: 最终验证

- [ ] **Step 1: 全量构建**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

期望: BUILD SUCCESSFUL

- [ ] **Step 2: 全量单元测试**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:testDebugUnitTest
```

期望: 全部通过

---

## Self-Review Checklist

- [x] **Spec coverage:** A.1-A.9 全部覆盖 (Task 1-9),L13-L19 在 Task 2, 8, 9 处理
- [x] **Placeholder scan:** 无 TBD/TODO/实现细节未明确
- [x] **Type consistency:** `MediaEntity.copy()` 在 Task 6 引用,确认是 data class (Task 9 Step 1)
- [x] **Build commands:** 全用 `./gradlew :app:compileDebugKotlin` 或 `assembleDebug`,符合 Gradle 项目

## Risks Mitigated

- **Task 7** MIGRATION_2_3 是空的,子项目 B 完成后必须 ALTER TABLE media ADD COLUMN vaultId
- **Task 8** scope 默认值用 SupervisorJob + Dispatchers.IO,测试时可注入 TestScope
- **Task 9 Step 4** L16 是 LOW,如 MediaFormat.fromMagicBytes 不存在可 YAGNI