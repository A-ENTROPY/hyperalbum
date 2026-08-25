# Bugfix B — Privacy Vault Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 C1 (vault ID 不一致) + C3 (写入原子性) + H11/H12/M14 (UI 反馈+本地化) + L14 (decoded 清理),加入 App 启动时自动迁移老数据。

**Architecture:**
- `MediaEntity` 新增 `vaultId: String?` 列,v2→v3 schema migration
- vault 文件名以 `vaultId` 命名,DB 通过 `vaultId` 关联
- 写入顺序:reserve DB → 写文件 → 失败回滚 → setHidden
- 启动时 `VaultMigrator` 一次性扫描老 uriHash 文件改名为 SecureRandom ID
- 错误类型用 `VaultException` sealed class,UI 监听 `VaultUiEvent`

**Tech Stack:** Room Migration, BiometricPrompt, AndroidKeyStore

**Dependency:** 子项目 A 必须先完成 (Entity 字段稳定 + Converter round-trip OK)

**Note:** 无 git,跳过 commit 步骤。

---

## Task 1: MediaEntity + MediaItem 加 vaultId 列

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/MediaEntity.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/data/model/MediaItem.kt`

- [ ] **Step 1: MediaEntity 加 vaultId 字段**

在 `MediaEntity.kt` 所有现有字段后添加:

```kotlin
@ColumnInfo(name = "vaultId")
val vaultId: String? = null,
```

如果 MediaEntity 已有 `equals/hashCode` 显式实现,保持不变。

- [ ] **Step 2: MediaItem 加 vaultId 字段**

在 `MediaItem.kt` 加:

```kotlin
val vaultId: String? = null,
```

- [ ] **Step 3: MediaRepository.toModel 传递 vaultId**

`MediaRepository.kt` 的 toModel 函数,在新 `MediaItem(...)` 构造调用中加 `vaultId = entity.vaultId,`。

- [ ] **Step 4: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 2: DAO 加 reserveVaultId + clearVaultId

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/db/MediaDao.kt`

- [ ] **Step 1: 添加两个 DAO 方法**

```kotlin
@Query("UPDATE media SET vaultId = :vaultId WHERE uri = :uri")
suspend fun reserveVaultId(uri: String, vaultId: String)

@Query("UPDATE media SET vaultId = NULL WHERE uri = :uri")
suspend fun clearVaultId(uri: String)
```

- [ ] **Step 2: Repository 暴露**

`MediaRepository.kt`:

```kotlin
suspend fun reserveVaultId(uri: Uri, vaultId: String) =
    mediaDao.reserveVaultId(uri.toString(), vaultId)

suspend fun clearVaultId(uri: Uri) =
    mediaDao.clearVaultId(uri.toString())
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 3: AppDatabase MIGRATION_2_3

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/data/AppDatabase.kt` 或新建 `AppDatabaseMigrations.kt`

- [ ] **Step 1: 实现 MIGRATION_2_3**

如果子项目 A Task 7 还没写,这里补全:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media ADD COLUMN vaultId TEXT")
    }
}
```

在 builder 中:
```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "smartvision.db")
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
    .build()
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 4: vault hide() 重写 + VaultResult

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/privacy/EncryptedPrivacyVault.kt:70-98`
- Create: `app/src/main/java/com/smartvision/gallery/privacy/VaultResult.kt`

- [ ] **Step 1: 创建 VaultResult sealed class**

```kotlin
package com.smartvision.gallery.privacy

sealed class VaultResult {
    data class Hidden(val vaultId: String) : VaultResult()
    data object Unhidden : VaultResult()
    data class Failed(val reason: String, val cause: Throwable? = null) : VaultResult()
}
```

- [ ] **Step 2: 重写 hide()**

替换 `EncryptedPrivacyVault.kt:70-98`:

```kotlin
suspend fun hide(item: MediaItem): Result<VaultResult> = withContext(Dispatchers.IO) {
    runCatching {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        val iv = cipher.iv

        val plainBytes = app.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
            ?: error("Could not read ${item.uri}")

        val vaultId = generateVaultId()
        // 1. reserve vaultId to DB FIRST
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

            // 3. 最后 set DB hidden flag
            repository.setHidden(item.uri, true)
            VaultResult.Hidden(vaultId)
        } catch (t: Throwable) {
            // 4. 任何步骤失败 → 回滚
            File(vaultDir, "$vaultId.enc").delete()
            File(vaultDir, "$vaultId.meta").delete()
            repository.clearVaultId(item.uri)
            throw t
        }
    }.recoverCatching { throw VaultException.HideFailed(it) }
        .map { it.getOrThrow() }
}
```

- [ ] **Step 3: 添加 generateVaultId 辅助函数**

```kotlin
private fun generateVaultId(): String {
    val secureRandom = SecureRandom()
    val idBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
    return android.util.Base64.encodeToString(idBytes, android.util.Base64.NO_WRAP)
        .replace("/", "_").replace("+", "-")
}
```

- [ ] **Step 4: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 5: vault unhide() 重写

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/privacy/EncryptedPrivacyVault.kt:101-108`

- [ ] **Step 1: 替换 unhide()**

```kotlin
suspend fun unhide(item: MediaItem): Result<VaultResult> = withContext(Dispatchers.IO) {
    runCatching {
        val vaultId = item.vaultId
            ?: throw VaultException.CorruptMetadata(
                item.uri.toString(),
                "hidden item has no vaultId"
            )
        repository.setHidden(item.uri, false)
        repository.clearVaultId(item.uri)
        File(vaultDir, "$vaultId.enc").takeIf { it.exists() }?.delete()
        File(vaultDir, "$vaultId.meta").takeIf { it.exists() }?.delete()
        VaultResult.Unhidden
    }.recoverCatching { throw VaultException.UnhideFailed(it) }
        .map { it.getOrThrow() }
}
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 6: VaultMigrator 启动时迁移老数据

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/privacy/VaultMigrator.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/SmartVisionApp.kt`

- [ ] **Step 1: 创建 VaultMigrator**

```kotlin
package com.smartvision.gallery.privacy

import android.content.Context
import android.net.Uri
import com.smartvision.gallery.data.repo.MediaRepository
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * 启动时一次性迁移老 vault 文件:
 *  - 老文件命名: <uriHash>.enc / <uriHash>.meta
 *  - 新文件命名: <SecureRandom base64>.enc / <SecureRandom base64>.meta
 *
 *  对每个隐藏项,如果 vaultId 为 NULL 且存在老文件,改名为新 ID 并写 DB。
 *  幂等 — 每次启动都安全运行。
 */
class VaultMigrator(
    private val app: Context,
    private val vaultDir: File,
    private val repository: MediaRepository,
) {
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        val hidden = repository.observeHidden().first()
        val unmigrated = hidden.filter { it.vaultId == null }

        if (unmigrated.isEmpty()) {
            AppLog.i(TAG, "Vault migration: no items to migrate")
            return@withContext
        }

        AppLog.i(TAG, "Vault migration: migrating ${unmigrated.size} items")
        unmigrated.forEach { item ->
            try {
                migrateOne(item)
            } catch (t: Throwable) {
                AppLog.e(TAG, "Vault migration failed for ${item.uri}", t)
                // 中止整次迁移,下次启动重试 — 状态错乱比丢数据更糟
                throw IllegalStateException("Vault migration failed for ${item.uri}", t)
            }
        }
        AppLog.i(TAG, "Vault migration: complete")
    }

    private fun migrateOne(item: com.smartvision.gallery.data.model.MediaItem) {
        val oldHash = legacyUriHash(item.uri.toString())
        val oldEnc = File(vaultDir, "$oldHash.enc")
        val oldMeta = File(vaultDir, "$oldHash.meta")

        if (!oldEnc.exists()) {
            AppLog.w(TAG, "Vault migration: ${item.uri} marked hidden but no .enc — clearing flag")
            repository.setHidden(item.uri, false)
            return
        }

        val newVaultId = generateVaultId()
        val newEnc = File(vaultDir, "$newVaultId.enc")
        val newMeta = File(vaultDir, "$newVaultId.meta")

        if (oldEnc.renameTo(newEnc).not()) {
            error("Failed to rename ${oldEnc.path} → ${newEnc.path}")
        }
        oldMeta.takeIf { it.exists() }?.renameTo(newMeta)

        repository.reserveVaultId(item.uri, newVaultId)
    }

    private fun generateVaultId(): String {
        val secureRandom = SecureRandom()
        val idBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        return android.util.Base64.encodeToString(idBytes, android.util.Base64.NO_WRAP)
            .replace("/", "_").replace("+", "-")
    }

    /** 老代码用的 hash 算法,必须保留一致才能匹配老文件 */
    private fun legacyUriHash(uri: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val digest = md.digest(uri.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(digest.copyOfRange(0, 16), android.util.Base64.NO_WRAP)
            .replace("/", "_").replace("+", "-")
    }

    private companion object {
        const val TAG = "VaultMigrator"
    }
}
```

- [ ] **Step 2: SmartVisionApp.onCreate 触发迁移**

修改 `SmartVisionApp.kt`:

```kotlin
override fun onCreate() {
    super.onCreate()
    appScope.launch {
        try {
            val vaultDir = File(cacheDir, ".vault").apply { mkdirs() }
            VaultMigrator(this@SmartVisionApp, vaultDir, mediaRepository)
                .migrateIfNeeded()
        } catch (t: Throwable) {
            AppLog.e("SmartVisionApp", "Vault migration aborted", t)
            throw t  // 让 App 崩溃,下次启动重试
        }
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 7: VaultUiEvent + UI 反馈 (H11)

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/privacy/VaultUiEvent.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/privacy/EncryptedPrivacyVault.kt:179-207`
- Modify: `app/src/main/java/com/smartvision\gallery\ui\privacy\PrivacyVaultPage.kt`

- [ ] **Step 1: 创建 VaultUiEvent**

```kotlin
package com.smartvision.gallery.privacy

sealed class VaultUiEvent {
    data object AuthSucceeded : VaultUiEvent()
    data object AuthCancelled : VaultUiEvent()
    data class AuthFailed(val message: String) : VaultUiEvent()
    data class AuthError(val code: Int, val message: String) : VaultUiEvent()
    data class HideSucceeded(val vaultId: String) : VaultUiEvent()
    data class UnhideSucceeded(val vaultId: String) : VaultUiEvent()
    data class HideFailed(val message: String) : VaultUiEvent()
}
```

- [ ] **Step 2: 修改 showPrompt 走 VaultUiEvent**

替换 `EncryptedPrivacyVault.kt:194-207`:

```kotlin
fun showPrompt(
    activity: FragmentActivity,
    onResult: (VaultUiEvent) -> Unit
) {
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(activity.getString(R.string.vault_unlock_title))
        .setSubtitle(activity.getString(R.string.vault_unlock_subtitle))
        .setAllowedAuthenticators(BIOMETRIC_OR_DEVICE_CREDENTIAL)
        .setConfirmationRequired(false)
        .build()
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) =
                onResult(VaultUiEvent.AuthSucceeded)
            override fun onAuthenticationError(code: Int, msg: CharSequence) {
                when (code) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED ->
                        onResult(VaultUiEvent.AuthCancelled)
                    else -> onResult(VaultUiEvent.AuthError(code, msg.toString()))
                }
            }
            override fun onAuthenticationFailed() =
                onResult(VaultUiEvent.AuthFailed(activity.getString(R.string.vault_auth_failed)))
        }
    ).authenticate(info)
}
```

加 import: `import com.smartvision.gallery.R`

- [ ] **Step 3: PrivacyVaultPage 接收事件**

在 `PrivacyVaultPage.kt` 中,找到调用 `showPrompt` 处,把 `(BiometricResult) -> Unit` 改为 `(VaultUiEvent) -> Unit`:

```kotlin
vault.showPrompt(activity) { event ->
    when (event) {
        is VaultUiEvent.AuthSucceeded -> { /* unlock UI */ }
        is VaultUiEvent.AuthCancelled -> snackbar.showSnackbar(R.string.vault_auth_cancelled)
        is VaultUiEvent.AuthFailed -> snackbar.showSnackbar(event.message)
        is VaultUiEvent.AuthError -> snackbar.showSnackbar(
            getString(R.string.vault_auth_error, event.message))
        else -> {}
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:compileDebugKotlin
```

---

## Task 8: strings.xml 外部化 (H12, M14)

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`(如果不存在则创建)

- [ ] **Step 1: 添加 vault 字符串**

`values/strings.xml` 中追加:

```xml
<string name="vault_unlock_title">Unlock Privacy Space</string>
<string name="vault_unlock_subtitle">Authenticate to view hidden media</string>
<string name="vault_auth_failed">Authentication failed. Please try again.</string>
<string name="vault_auth_cancelled">Authentication cancelled</string>
<string name="vault_auth_error">Authentication error: %1$s</string>
<string name="vault_hide_success">Hidden to Privacy Space</string>
<string name="vault_hide_failed">Hide failed: %1$s</string>
<string name="vault_unhide_success">Restored</string>
<string name="vault_file_corrupt">This file is corrupt and cannot be decrypted</string>
<string name="vault_not_enrolled">No biometrics enrolled. Tap to set up.</string>
```

`values-zh/strings.xml`(中文翻译):

```xml
<string name="vault_unlock_title">解锁隐私空间</string>
<string name="vault_unlock_subtitle">验证身份以查看隐藏的媒体</string>
<string name="vault_auth_failed">身份验证失败,请重试</string>
<string name="vault_auth_cancelled">已取消身份验证</string>
<string name="vault_auth_error">身份验证出错: %1$s</string>
<string name="vault_hide_success">已隐藏到隐私空间</string>
<string name="vault_hide_failed">隐藏失败: %1$s</string>
<string name="vault_unhide_success">已恢复</string>
<string name="vault_file_corrupt">该文件已损坏,无法解密</string>
<string name="vault_not_enrolled">未设置生物识别,点击前往设置</string>
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

---

## Task 9: vault-decoded 启动清理 (L14)

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/SmartVisionApp.kt`

- [ ] **Step 1: App 启动时清理过期解密文件**

在 `SmartVisionApp.onCreate` 添加(放在 vault 迁移之后):

```kotlin
appScope.launch(Dispatchers.IO) {
    val decodedDir = File(cacheDir, "vault-decoded")
    decodedDir.listFiles()?.forEach { file ->
        val ageMs = System.currentTimeMillis() - file.lastModified()
        if (ageMs > TimeUnit.DAYS.toMillis(7)) {
            file.delete()
            AppLog.i("SmartVisionApp", "Cleaned up old decoded file: ${file.name}")
        }
    }
}
```

加 imports:
```kotlin
import java.util.concurrent.TimeUnit
```

- [ ] **Step 2: 编译验证**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

---

## Task 10: 最终验证

- [ ] **Step 1: 全量构建**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:assembleDebug
```

- [ ] **Step 2: 全量测试**

```bash
cd "H:/workspace-minimaxcode/超级相册" && ./gradlew :app:testDebugUnitTest
```

---

## Self-Review Checklist

- [x] **Spec coverage:** B.1-B.6 全部覆盖 (Task 1-9)
- [x] **Type consistency:** VaultResult.Hidden(vaultId), VaultUiEvent.HideSucceeded(vaultId) 一致
- [x] **Migration 失败行为:** VaultMigrator.migrateIfNeeded 失败抛 IllegalStateException,App.onCreate 让其崩溃
- [x] **依赖子项目 A:** Task 1-3 依赖 A 的 schema migration 基础

## Risks Mitigated

- **Task 6 迁移失败** 直接抛 IllegalStateException,App.onCreate 让其崩溃
- **Task 4 reserveVaultId** 先于文件写入,失败后 catch 块清理 + clearVaultId
- **Task 5 unhide 缺 vaultId** 显式抛 CorruptMetadata,不静默失败
- **Task 9 vault-decoded 清理** 7 天阈值,先检查 lastModified