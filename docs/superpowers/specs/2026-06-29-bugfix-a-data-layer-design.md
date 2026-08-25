# Bugfix A — 数据层基础 (Data Layer Foundation)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this spec task-by-task.

**Goal:** 修复所有跨域的纯数据层 bug,确保 MediaStore → DAO → Repository → ViewModel 的链路在 schema 迁移、SQL 排序、缓存、类型转换、URI 桶解析、Calendar 实例化等方面无隐藏问题,为上层(金库/VM/UI)修复打基础。

**Scope:** 子项目 A。修复 C2/C4/H9/H10/M7/M8/M9/M10/M11/L13/L14/L15/L16/L17/L18/L19 共 16 个数据层 bug。

**Architecture:**
- 所有 DAO 操作走 Room v2 schema migration(不破坏现有 vault 隐藏项的 URI 映射)
- MediaStore → Entity 阶段的桶解析、字段映射、格式推断全部走单一函数,避免重复
- Repository 层新增轻量内存缓存(`StateFlow<Cache>`),避免每次 collect 都重做 O(n) 工作
- 关键日期运算用 `java.time` 替代 `Calendar`(避免时区漂移)

---

## Context

| 编号 | 文件 | 行 | 严重度 | 问题 |
|---|---|---|---|---|
| C2 | `data/repo/MediaRepository.kt` | 118-122 | CRITICAL | `replaceAll` 是 no-op,只 upsert 不删旧行 → 已删除/重命名的 MediaStore 行在 DB 残留 |
| C4 | `scanner/MediaStoreDataSource.kt` | 235-238 | CRITICAL | `bucketFromPath` 用 `size-2` 取倒数第二段,把 DCIM/Camera 解析成 `Camera`,丢父目录 |
| H9 | `data/db/Entities.kt` | 77-80 | HIGH | `aiTags` TypeConverter 在 null↔"" 之间往返,List 元素全丢(变成空字符串列表) |
| H10 | `privacy/EncryptedPrivacyVault.kt` | 121-125 | HIGH | `decryptToPlaintext` 在 .meta 缺失时 fallback 到 `SecureRandom().generateSeed(12)` → 静默解密失败(GCM tag mismatch 抛出但被吞) |
| M7 | `data/repo/MediaRepository.kt` | 132-154 | MEDIUM | `toModel` 每次 emit 都新建 `MediaItem`,但 `observeTimeline` 没缓存,subscribers 收到不同对象 → 触发不必要的 recomposition |
| M8 | `data/repo/MediaRepository.kt` | 62-113 | MEDIUM | `observeSmartAlbums` 每次 collect 都重做 groupBy + count + maxOf,O(n) 且无 diff |
| M9 | `scanner/MediaStoreDataSource.kt` | 47-55, 124-135 | MEDIUM | `queryImages/queryVideos` 走 `MediaStore.Images.Media.DATE_TAKEN DESC`,但很多设备的 `DATE_TAKEN = 0`(改成 `DATE_MODIFIED DESC`) |
| M10 | `scanner/MediaStoreDataSource.kt` | 220-232 | MEDIUM | `readGeoFromExif` 对每张图开新 stream,读取全文件 → O(file_size) × N 张照片,首扫巨慢 |
| M11 | `data/AppDatabase.kt` | (全局) | MEDIUM | `fallbackToDestructiveMigration` 在生产环境会丢用户所有 DB 数据(vault flag / favorites / trash) |
| L13 | `data/db/MediaDao.kt` | (全局) | LOW | DAO 缺 `@Transaction` 包裹的批量操作,大量插入时 race |
| L14 | `data/repo/MediaRepository.kt` | 64 | LOW | `it.bucketPath ?: "unknown"` 兜底,但 MediaStore 路径应该非空 |
| L15 | `data/db/MediaEntity.kt` | (构造器) | LOW | `equals/hashCode` 未定义 → DAO `distinctUntilChanged` 不生效 |
| L16 | `scanner/MediaStoreDataSource.kt` | 199-218 | LOW | `inferFormat` 用 extension 名优先,但文件名可能改过后缀(`.jpg.png`) |
| L17 | `data/db/Converters.kt` | (全局) | LOW | List<String> 转换器缺单元测试,空字符串处理未明确 |
| L18 | `scanner/MediaStoreDataSource.kt` | 37 | LOW | `queryImages/queryVideos` 缺 coroutine cancellation 传播 |
| L19 | `data/repo/MediaRepository.kt` | 117 | LOW | `upsertAll` 缺 `OnConflictStrategy.REPLACE` 显式标注,Room 默认 ABORT 会抛 |

## Approach

### A.1 — `MediaStoreDataSource.bucketFromPath` 修正 (C4)

**目标:** 从 `/storage/emulated/0/DCIM/Camera/IMG.jpg` 解析出 `DCIM/Camera`(两段),不是 `Camera`。

**实现:**
```kotlin
private fun bucketFromPath(path: String): String {
    val segments = path.split('/').filter { it.isNotEmpty() }
    // 取最后两段 (parent/leaf),如 DCIM/Camera;若只有一段则取该段
    return when (segments.size) {
        0 -> "root"
        1 -> segments[0]
        else -> "${segments[segments.size - 2]}/${segments.last()}"
    }
}
```

**验收:** 设备上 `/DCIM/Camera` 路径的 photo,bucketPath = "DCIM/Camera",AlbumsPage 分组正确。

### A.2 — `MediaRepository.replaceAll` 修正 (C2)

**目标:** 真的删除旧行,然后插入新行。

**实现:**
```kotlin
// MediaDao.kt 加一个真正删除所有行的方法
@Query("DELETE FROM media")
suspend fun deleteAll(): Int

// MediaRepository.replaceAll 改为
suspend fun replaceAll(items: List<MediaEntity>) {
    mediaDao.runInTransaction {
        mediaDao.deleteAll()
        mediaDao.upsertAll(items)
    }
}
```

**注意:** 调用方 `MediaScanService` 决定何时触发完整 replace,日常 insert 用 `upsertAll`。

**验收:** 删除一张照片后重启 App,该行不在 DB 中。

### A.3 — Entities.aiTags TypeConverter 修正 (H9)

**目标:** null 和空列表都能正确往返,不丢字段。

**实现:**
```kotlin
// Converters.kt
@TypeConverter
fun fromList(list: List<String>?): String = list?.joinToString("") ?: ""

@TypeConverter
fun toList(value: String?): List<String> =
    if (value.isNullOrEmpty()) emptyList() else value.split('').filter { it.isNotEmpty() }
```

**但要小心:** 如果未来 aiTags 可能包含 '' 字符,改用 JSON 序列化。当前 YAGNI 用 join。

**验收:** Round-trip `["cat","dog"]` → "" → DB → back,得到 `["cat","dog"]`。

### A.4 — vault `decryptToPlaintext` 失败显式化 (H10)

**目标:** .meta 缺失时不要 fallback random IV(会导致 GCM tag mismatch 静默失败),改为显式抛错并清理。

**实现:**
```kotlin
val metaFile = File(vaultUri.path!!.removeSuffix(".enc") + ".meta")
val iv = if (metaFile.exists()) {
    val line = metaFile.readText().lineSequence()
        .firstOrNull { it.startsWith("iv=") }
        ?: error("Vault file missing IV metadata: ${vaultUri.path}")
    android.util.Base64.decode(line.removePrefix("iv="), android.util.Base64.NO_WRAP)
} else error("Vault metadata missing for ${vaultUri.path}")
```

**注意:** 子项目 B 会做完整 vault 迁移,这里只把"silent fail"改成"loud fail",让损坏数据有迹可循。

**验收:** 手动删 .meta 文件后调用 decrypt,日志可见 Error,UI 显示 "文件已损坏"。

### A.5 — Repository 加内存缓存 (M7, M8)

**目标:** `observeSmartAlbums` 和 `observeTimeline` 不再每次 collect 都 O(n) 重算。

**实现:**
```kotlin
class MediaRepository(...) {
    // 缓存 timeline 列表的 normalized 形态,key = (uri.toString, favorite, hidden, trash, dateTaken)
    private val timelineCache = MutableStateFlow<List<MediaItem>>(emptyList())

    init {
        // 把 DAO 流的 toModel 结果缓存
        scope.launch {
            mediaDao.observeTimeline()
                .map { it.map(::toModel) }
                .distinctUntilChanged()
                .collect { timelineCache.value = it }
        }
    }

    fun observeTimeline(): Flow<List<MediaItem>> = timelineCache

    fun observeSmartAlbums(): Flow<List<Album>> = timelineCache.combine(...) { ... }
        .distinctUntilChanged()
        .onEach { /* cache albums too */ }
}
```

**注意:** `MediaRepository` 现在没有 scope。需要注入 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 或用 application scope。

**验收:** 同一 observeSmartAlbums Flow 被 collect 3 次,内部 toModel 只跑一次。

### A.6 — MediaStore query 排序改 DATE_MODIFIED (M9)

**目标:** `DATE_TAKEN = 0` 的设备也能按时间倒序排。

**实现:**
```kotlin
val sortOrder = "COALESCE(${MediaStore.Images.Media.DATE_TAKEN}, ${MediaStore.Images.Media.DATE_MODIFIED} * 1000) DESC"
```

**验收:** 设备上没 EXIF 日期的照片也按修改时间排序,不是随机。

### A.7 — readGeoFromExif 性能 (M10)

**目标:** 不读整个文件,只读 EXIF header。

**实现:** ExifInterface(input) 实际只解析头部,不需要改 IO。但优化点是把 readGeoFromExif 移到后台分批执行(目前每个 item 同步)。

**实现:**
```kotlin
// 把 readGeoFromExif 改为协程并行
suspend fun queryAll(): List<MediaEntity> = withContext(ioDispatcher) {
    val items = mutableListOf<MediaEntity>()
    items += queryImages()
    items += queryVideos()
    // 并行补全 EXIF
    coroutineScope {
        items.map { item ->
            async {
                val (lat, lng) = readGeoFromExif(Uri.parse(item.uri))
                item.copy(latitude = lat, longitude = lng)
            }
        }.awaitAll()
    }
}
```

**验收:** 扫描 1000 张照片的耗时下降 ≥ 30%。

### A.8 — 去掉 fallbackToDestructiveMigration (M11)

**目标:** 强制迁移,不要 destructive。

**实现:**
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 当前没有 v3 schema,需要根据子项目 B 的 vault_id 字段加迁移
        // 先做空迁移占位
    }
}

Room.databaseBuilder(...)
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    // 删掉 fallbackToDestructiveMigration
    .build()
```

**注意:** 如果 v3 schema 加了 vaultId 列(子项目 B),这里要补实际 ALTER TABLE。当前先写空迁移,等 B 完成后再补 SQL。

### A.9 — 清理 L13-L19 杂项

- **L13** DAO 批量操作加 `@Transaction`
- **L14** 去掉 `?: "unknown"` 兜底(契约保证非空)
- **L15** `MediaEntity` 加 `data class` 风格 `equals/hashCode`(Kotlin 自动生成)
- **L16** `inferFormat` 先 sniff magic bytes,再退到 extension,MIME 最后(防 rename)
- **L17** Converter 加单元测试(`@Test fun aiTags_roundTrip()` 等)
- **L18** queryImages/queryVideos 接收 coroutine context,提前 break on cancellation
- **L19** `upsertAll` 显式 `OnConflictStrategy.REPLACE`

## File Changes

### Modify
- `scanner/MediaStoreDataSource.kt` (A.1, A.6, A.7, A.9-L16, A.9-L18)
- `data/repo/MediaRepository.kt` (A.2, A.5, A.9-L14)
- `data/db/MediaDao.kt` (A.2, A.9-L13, A.9-L19)
- `data/db/MediaEntity.kt` (A.9-L15)
- `data/db/Converters.kt` (A.3, A.9-L17)
- `data/AppDatabase.kt` (A.8)
- `privacy/EncryptedPrivacyVault.kt` (A.4)

### Add
- `app/src/test/java/com/smartvision/gallery/data/ConvertersTest.kt` (A.9-L17 单元测试)

## Acceptance Criteria

1. ✅ App 启动 + 扫描 + 显示:无 crash,无 ANR
2. ✅ 删除设备上一张照片 → 重启 App → DB 不再包含该 URI(通过 `adb shell run-as ... sqlite3` 验证)
3. ✅ `/DCIM/Camera/IMG_001.jpg` 解析为 bucketPath `"DCIM/Camera"`,AlbumsPage 显示 "Camera" 作为 bucket 名
4. ✅ `aiTags` round-trip 不丢字段
5. ✅ vault `.meta` 缺失时解密失败有日志/UI 提示
6. ✅ 1000 张照片首次扫描 ≤ 5 秒(原 ≥ 10 秒)
7. ✅ DB migration 不丢数据(写一个 instrumentation 测试:创建 v2 DB → 升级到 v3 → 数据保留)

## Risks

- **A.8** 强制 migration 可能因为 schema 不匹配导致启动崩溃。Mitigation: 子项目 B 完成前先写空迁移,所有 ALTER TABLE 在 B 完成后再加。
- **A.5** 加内存缓存需要 scope 注入,可能引入泄漏。Mitigation: 用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)` 且不持有 Activity 引用。
- **A.7** 并行 IO 受限于 CPU 核数。Mitigation: 用 `Semaphore(4)` 限流。