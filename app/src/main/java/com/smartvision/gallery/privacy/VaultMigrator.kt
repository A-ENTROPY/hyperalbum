package com.smartvision.gallery.privacy

import android.content.Context
import com.smartvision.gallery.data.repo.MediaRepository
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom

/**
 * One-shot startup migrator for legacy vault rows.
 *
 * Background: pre-Plan-B [EncryptedPrivacyVault] hashed the media URI to derive
 * the on-disk filename (`<sha256-hash>.enc`). Plan B switched to SecureRandom
 * `vaultId` strings reserved in the DB row. To avoid losing already-encrypted
 * files we rename the legacy `.enc` / `.meta` sidecars to the new vaultId name
 * and write that vaultId back into the row.
 *
 * Run exactly once at process start from [com.smartvision.gallery.SmartVisionApp].
 */
class VaultMigrator(
    private val app: Context,
    private val vaultDir: File,
    private val repository: MediaRepository,
) {
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        // Only run when the vault directory actually contains legacy .enc files
        // (uri-hash-named). Without this guard, every "soft hide" (TimelinePage
        // selection toolbar — no encryption, just a flag) would be mistaken for a
        // legacy row and have its hidden flag cleared on next startup, making
        // hidden photos silently reappear in the gallery after a kill+restart.
        val legacyFiles = vaultDir.listFiles { f -> f.extension == "enc" } ?: emptyArray()
        if (legacyFiles.isEmpty()) {
            AppLog.i(TAG, "Vault migration: no legacy .enc files, skipping")
            return@withContext
        }
        val legacyHashes = legacyFiles.map { it.nameWithoutExtension }.toSet()
        AppLog.i(TAG, "Vault migration: ${legacyHashes.size} legacy .enc files found")

        val hidden = repository.observeHidden().first()
        val unmigrated = hidden.filter { item ->
            item.vaultId == null && legacyUriHash(item.uri.toString()) in legacyHashes
        }

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
            // Caller (migrateIfNeeded) pre-filtered this list to legacy items, so
            // we expect .enc to exist. If it's gone between the scan and now,
            // leave the hidden flag alone — better to keep a soft-hide than to
            // silently re-expose a user-hidden photo.
            AppLog.w(TAG, "Vault migration: legacy .enc vanished for ${item.uri}, leaving flag untouched")
            return
        }

        val newVaultId = generateVaultId()
        val newEnc = File(vaultDir, "$newVaultId.enc")
        val newMeta = File(vaultDir, "$newVaultId.meta")

        if (oldEnc.renameTo(newEnc).not()) {
            error("Failed to rename ${oldEnc.path} -> ${newEnc.path}")
        }
        oldMeta.takeIf { it.exists() }?.renameTo(newMeta)

        kotlinx.coroutines.runBlocking { repository.reserveVaultId(item.uri, newVaultId) }
    }

    private fun generateVaultId(): String {
        val secureRandom = SecureRandom()
        val idBytes = ByteArray(16).also { secureRandom.nextBytes(it) }
        return android.util.Base64.encodeToString(idBytes, android.util.Base64.NO_WRAP)
            .replace("/", "_").replace("+", "-")
    }

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