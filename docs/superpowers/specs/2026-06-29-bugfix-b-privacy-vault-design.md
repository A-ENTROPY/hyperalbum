# Bugfix B — 隐私金库 (EncryptedPrivacyVault)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this spec task-by-task.

**Goal:** 修复 vault ID 一致性、写入原子性、生物识别错误反馈、文案外部化、本地化问题,加入 App 启动时的自动迁移逻辑,确保老用户隐藏数据零丢失。

**Scope:** 子项目 B。修复 C1/C3/H11/H12/M14/L14 共 6 个金库 bug。

**依赖:** 子项目 A 已完成(Entity 字段稳定)。

**Architecture:**
- `MediaEntity` 新增 `vaultId: String?` 列(可空,只在 `isHidden=true` 时填充)
- `EncryptedPrivacyVault` 所有文件名以 `vaultId` 命名,不用 uriHash
- App 启动时(`SmartVisionApp.onCreate`)执行一次性 vault 迁移:扫描 `cacheDir/.vault/`,把 `uriHash.enc` 改名为 `<SecureRandom ID>.enc`,同步写 `MediaEntity.vaultId`
- vault 写入顺序改为: 先 reserve DB row → 写文件 → 失败回滚 → 删除 DB flag
- 文案全部走 `R.string.vault_*`,UI 层封装 `VaultResult` sealed class 让错误可观测

---

## Context

| 编号 | 文件 | 行 | 严重度 | 问题 |
|---|---|---|---|---|
| C1 | `privacy/EncryptedPrivacyVault.kt` | 79-107 | CRITICAL | `hide()` 用 SecureRandom ID 命名文件,`unhide()` 用 uriHash 找文件 → 永远找不到对应文件,DB flag 清除但 ciphertext 残留 |
| C3 | `privacy/EncryptedPrivacyVault.kt` | 91-95 | CRITICAL | vault 文件先写 `.enc` 和 `.meta`,然后才 `repository.setHidden(true)`;中途失败 → orphan ciphertext + DB flag 没设,状态错乱 |
| H11 | `privacy/EncryptedPrivacyVault.kt` | 185-187 | HIGH | `onAuthenticationError` 只 log,UI 看不到 → 用户在指纹失败重试界面疑惑 |
| H12 | `privacy/EncryptedPrivacyVault.kt` | 196-197 | HIGH | "解锁隐私空间" / "验证身份以查看隐藏的媒体" 硬编码 → 无法 i18n |
| M14 | `privacy/EncryptedPrivacyVault.kt` | (全局) | MEDIUM | 错误消息没有走 `R.string`,UI 层只能显示 "Error: ..." |
| L14 | `privacy/EncryptedPrivacyVault.kt` | 132-135 | LOW | `vault-decoded/` 缓存目录永远不清理,长期占用空间 |

## Approach

### B.1 — `MediaEntity` 加 `vaultId` 列 (支撑 C1)

**Schema 变化 (v2 → v3):**

```sql
ALTER TABLE media ADD COLUMN vaultId TEXT
```

**MediaEntity.kt:**
```kotlin
@Entity(tableName = "media")
data class MediaEntity(
    // ... existing fields
    @ColumnInfo(name = "vaultId") val vaultId: String? = null,
)
```

**MediaItem.kt:** 同步加 `val vaultId: String? = null` 字段。

**AppDatabase.kt:** 新增 `MIGRATION_2_3`:
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media ADD COLUMN vaultId TEXT")
    }
}
```

**验收:** DB 升级后 `PRAGMA table_info(media)` 包含 `vaultId` 列,旧数据该列为 NULL。

### B.2 — vault ID 用 SecureRandom 一致化 (C1)

**修改 `hide()`:**
```kotlin
suspend fun hide(item: MediaItem): Result<VaultResult> = withContext(Dispatchers.IO) {
    runCatching {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv

        val plainBytes = app.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
            ?: error("Could not read ${item.uri}")

        // 1. 先生成 vaultId 并 reserve 到 DB
        val vaultId = generateVaultId()
        repository.reserveVaultId(item.uri, vaultId)

        try {
            // 2. 加密 + 写文件
            val cipherBytes = cipher.doFinal(plainBytes)
            val outFile = File(vaultDir, "$vaultId.enc")
            FileOutputStream(outFile).use { it.write(cipherBytes) }

            val metaFile = File(vaultDir, "$vaultId.meta")
            val metaText = buildString {
                append("name=").append(item.displayName).append('\n')
                append("size=").append(plainBytes.size).append('\n')
                append("iv=").append(android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
            }
            FileOutputStream(metaFile).use { it.write(metaText.toByteArray(Charsets.UTF_8)) }

            // 3. 写 DB flag
            repository.setHidden(item.uri, true)
            VaultResult.Hidden(vaultId)
        } catch (t: Throwable) {
            // 4. 任何步骤失败 → 清理文件 + DB
            File(vaultDir, "$vaultId.enc").delete()
            File(vaultDir, "$vaultId.meta").delete()
            repository.clearVaultId(item.uri)
            throw t
        }
    }.onFailure { AppLog.e(TAG, "Vault.hide failed for ${item.uri}", it) }
        .recoverCatching { throw VaultException.HideFailed(it) }
}
```

**修改 `unhide()`:**
```kotlin
suspend fun unhide(item: MediaItem): Result<VaultResult> = withContext(Dispatchers.IO) {
    runCatching {
        val vaultId = item.vaultId
            ?: error("Item ${item.uri} is hidden but has no vaultId — corrupt state")
        repository.setHidden(item.uri, false)
        repository.clearVaultId(item.uri)
        File(vaultDir, "$vaultId.enc").takeIf { it.exists() }?.delete()
        File(vaultDir, "$vaultId.meta").takeIf { it.exists() }?.delete()
        VaultResult.Unhidden
    }.recoverCatching { throw VaultException.UnhideFailed(it) }
}
```

**新增 DAO 方法:**
```kotlin
@Query("UPDATE media SET vaultId = :vaultId WHERE uri = :uri")
suspend fun reserveVaultId(uri: String, vaultId: String)

@Query("UPDATE media SET vaultId = NULL WHERE uri = :uri")
suspend fun clearVaultId(uri: String)
```

**验收:** hide → unhide → 文件确实被删除;hide → 中途杀进程 → 重启后无 orphan 文件。

### B.3 — 启动时 vault 自动迁移 (C1 老数据)

**新增 `VaultMigrator.kt`:**
```kotlin
class VaultMigrator(
    private val vaultDir: File,
    private val repository: MediaRepository,
) {
    /**
     * Scan cacheDir/.vault/ for legacy uriHash-named files, rename to
     * SecureRandom IDs, populate MediaEntity.vaultId. Idempotent — safe
     * to run on every App start.
     */
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        val hiddenItems = repository.observeHidden().first()
            .filter { it.vaultId == null } // only unmigrated
        if (hiddenItems.isEmpty()) return@withContext

        hiddenItems.forEach { item ->
            val oldHash = uriHash(item.uri.toString())
            val oldEnc = File(vaultDir, "$oldHash.enc")
            val oldMeta = File(vaultDir, "$oldHash.meta")
            if (!oldEnc.exists()) {
                // Orphan DB flag, just clear it
                repository.setHidden(item.uri, false)
                return@forEach
            }
            val newVaultId = generateVaultId()
            File(oldEnc, oldMeta).forEach { /* rename */ }
            oldEnc.renameTo(File(vaultDir, "$newVaultId.enc"))
            oldMeta.takeIf { it.exists() }?.renameTo(File(vaultDir, "$newVaultId.meta"))
            repository.reserveVaultId(item.uri, newVaultId)
        }
    }
}
```

**`SmartVisionApp.onCreate()` 调用:**
```kotlin
override fun onCreate() {
    super.onCreate()
    appScope.launch {
        VaultMigrator(vaultDir, mediaRepository).migrateIfNeeded()
    }
}
```

**验收:** 模拟老用户(手动把一个隐藏项的 vault 目录文件改名为 uriHash)→ 重启 App → 文件被改名为 SecureRandom ID + DB vaultId 填充。

### B.4 — `onAuthenticationError` UI 反馈 (H11)

**新增 sealed class:**
```kotlin
sealed class VaultUiEvent {
    data object AuthSucceeded : VaultUiEvent()
    data class AuthFailed(val message: String) : VaultUiEvent()
    data class AuthError(val code: Int, val message: String) : VaultUiEvent()
    data object AuthCancelled : VaultUiEvent()
}
```

**`PrivacyVaultPage.kt` 处理:**
```kotlin
LaunchedEffect(events) {
    events.collect { event ->
        when (event) {
            is VaultUiEvent.AuthError -> snackbar.showSnackbar(event.message)
            is VaultUiEvent.AuthFailed -> snackbar.showSnackbar(getString(R.string.vault_auth_failed))
            ...
        }
    }
}
```

**验收:** 故意取消生物识别 → 看到 snackbar 提示 "已取消"。

### B.5 — 文案外部化 (H12, M14)

**`strings.xml` 新增:**
```xml
<string name="vault_unlock_title">解锁隐私空间</string>
<string name="vault_unlock_subtitle">验证身份以查看隐藏的媒体</string>
<string name="vault_auth_failed">身份验证失败,请重试</string>
<string name="vault_auth_error">身份验证出错: %1$s</string>
<string name="vault_auth_cancelled">已取消身份验证</string>
<string name="vault_hide_success">已隐藏到隐私空间</string>
<string name="vault_hide_failed">隐藏失败: %1$s</string>
<string name="vault_unhide_success">已恢复</string>
<string name="vault_file_corrupt">该文件已损坏,无法解密</string>
```

**修改 `showPrompt()` 接收 resource id:**
```kotlin
fun showPrompt(
    activity: FragmentActivity,
    @StringRes titleRes: Int = R.string.vault_unlock_title,
    @StringRes subtitleRes: Int = R.string.vault_unlock_subtitle,
    onResult: (VaultUiEvent) -> Unit
)
```

**验收:** 切换系统语言后,vault 提示文字跟随系统语言。

### B.6 — vault-decoded 目录清理 (L14)

**新增 `cleanupDecoded(maxAgeMs: Long = TimeUnit.DAYS.toMillis(7))`:**
```kotlin
fun cleanupDecoded(maxAgeMs: Long) {
    File(app.cacheDir, "vault-decoded").listFiles()?.forEach {
        if (System.currentTimeMillis() - it.lastModified() > maxAgeMs) it.delete()
    }
}
```

**调用:** App 启动时执行。

**验收:** vault-decoded 中 7 天前的文件被自动清理。

## File Changes

### Modify
- `data/db/MediaEntity.kt` (B.1)
- `data/db/Converters.kt` (B.1 - if needed)
- `data/db/MediaDao.kt` (B.1 - reserveVaultId, clearVaultId)
- `data/AppDatabase.kt` (B.1 - MIGRATION_2_3)
- `data/model/MediaItem.kt` (B.1 - vaultId field)
- `data/repo/MediaRepository.kt` (B.1 - pass through vaultId, B.2 methods)
- `privacy/EncryptedPrivacyVault.kt` (B.2, B.5)
- `SmartVisionApp.kt` (B.3, B.6 - onCreate hooks)
- `ui/privacy/PrivacyVaultPage.kt` (B.4 - event handling)

### Add
- `privacy/VaultMigrator.kt` (B.3)
- `privacy/VaultUiEvent.kt` (B.4)
- `privacy/VaultResult.kt` (B.2)
- `privacy/VaultException.kt` (B.2)

### Resources
- `res/values/strings.xml` (B.5)
- `res/values-zh/strings.xml` (B.5)

## Acceptance Criteria

1. ✅ 老用户(有 uriHash 命名的 vault 文件)重启 App 后,文件被重命名 + DB vaultId 填充,不丢数据
2. ✅ 新用户 hide/unhide 流程无 orphan 文件,无状态错乱
3. ✅ vault 写入中途模拟失败(手动 `kill -9`),重启后无 orphan `.enc` 文件
4. ✅ 生物识别取消 / 失败 / 错误都有 snackbar 反馈
5. ✅ vault 提示文字切换系统语言后跟随
6. ✅ vault-decoded 中 7 天前文件自动清理

## Risks

- **B.3 迁移失败** 必须 abort App 启动,不能继续使用(避免状态错乱)。`migrateIfNeeded()` 在 catch 块中抛 `IllegalStateException`,App.onCreate 让其崩溃,下次启动重试。
- **B.2 reserveVaultId** 在 `setHidden` 之前调用,如果 reserve 成功但 setHidden 失败,DB 有 vaultId 但 isHidden=false。这种情况 unhide 会试图删除不存在的文件,但有 `takeIf { it.exists() }` 保护。
- **B.6 清理太激进** 可能清除用户正在查看的解密文件。Mitigation: 默认 7 天,配置文件可调,且先 `lastModified` 检查再删除。