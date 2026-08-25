package com.smartvision.gallery.data.db

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * SharedPreferences-based backup for media_flags.
 *
 * Works as a safety net regardless of root cause: if Room wipes flags
 * (cascade, schema mismatch, DB recreation), this backup preserves them
 * and restores on next startup.
 *
 * Every flag write goes through MediaFlagDao → backup to SharedPreferences.
 * On app startup, compare SP flags vs DB flags and restore missing ones.
 *
 * IMPORTANT: All writes use commit() (synchronous) instead of apply() (async)
 * to guarantee data reaches disk before the caller continues. This is critical
 * because force-kill (swipe recents) = SIGKILL — any pending async writes are lost.
 */
class FlagBackupManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("flag_backup", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "FlagBackup"
        private const val PREFIX_HIDDEN = "hidden_"
        private const val PREFIX_FAVORITE = "fav_"
        private const val PREFIX_TRASH = "trash_"
        private const val PREFIX_VAULT = "vault_"
        private const val KEY_ALL_URIS = "all_uris"
    }

    /**
     * Drop every per-URI flag backup entry for [uris]. Called from the
     * hard-delete path: once Room rows for [uris] are gone, the SP backups
     * for the same URIs are dangling state — if we leave them, the next
     * cold start's `restoreIfMissing` will resurrect `media_flags` rows
     * for URIs whose underlying files have just been confirmed-deleted
     * from MediaStore. The badge then climbs back up after every restart.
     *
     * `all_uris` is the index set used by [getAllUris] / [restoreIfMissing],
     * so its entries must be removed too — otherwise the per-URI keys are
     * invisible to `getAllUris` but still readable via raw `prefs` lookups.
     */
    suspend fun clearAllFlagsFor(uris: Collection<String>) {
        try {
            val editor = prefs.edit()
            for (uri in uris) {
                editor.remove("$PREFIX_HIDDEN$uri")
                editor.remove("$PREFIX_FAVORITE$uri")
                editor.remove("$PREFIX_TRASH$uri")
                editor.remove("$PREFIX_VAULT$uri")
            }
            val current = prefs.getStringSet(KEY_ALL_URIS, emptySet()) ?: emptySet()
            val cleaned = current - uris.toSet()
            editor.putStringSet(KEY_ALL_URIS, cleaned)
            editor.commit()
            Log.i(TAG, "clearAllFlagsFor count=${uris.size} -> remaining in index=${cleaned.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear flag backup for ${uris.size} URIs", e)
        }
    }

    // --- Backup methods (called on every flag write, suspend + commit) ---

    suspend fun backupHidden(uri: String, isHidden: Boolean) {
        try {
            prefs.edit().putBoolean("$PREFIX_HIDDEN$uri", isHidden).commit()
            addToUriSet(uri)
            Log.d(TAG, "Backed up hidden=$isHidden for $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup hidden for $uri", e)
        }
    }

    suspend fun backupFavorite(uri: String, isFavorite: Boolean) {
        try {
            prefs.edit().putBoolean("$PREFIX_FAVORITE$uri", isFavorite).commit()
            addToUriSet(uri)
            Log.d(TAG, "Backed up fav=$isFavorite for $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup fav for $uri", e)
        }
    }

    suspend fun backupTrash(uri: String, isTrash: Boolean) {
        try {
            prefs.edit().putBoolean("$PREFIX_TRASH$uri", isTrash).commit()
            addToUriSet(uri)
            Log.d(TAG, "Backed up trash=$isTrash for $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup trash for $uri", e)
        }
    }

    suspend fun backupVaultId(uri: String, vaultId: String?) {
        try {
            if (vaultId != null) {
                prefs.edit().putString("$PREFIX_VAULT$uri", vaultId).commit()
            } else {
                prefs.edit().remove("$PREFIX_VAULT$uri").commit()
            }
            addToUriSet(uri)
            Log.d(TAG, "Backed up vault=$vaultId for $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup vault for $uri", e)
        }
    }

    // --- Restore methods (called on app startup) ---

    /**
     * Restore flags from SharedPreferences to Room database.
     * Returns number of restored flags.
     */
    suspend fun restoreIfMissing(mediaFlagDao: MediaFlagDao): Int {
        return try {
            val allUris = getAllUris()
            Log.i(TAG, "Restoring flags for ${allUris.size} URIs from backup")

            var restored = 0
            for (uri in allUris) {
                val existing = mediaFlagDao.findByUri(uri)
                if (existing != null) {
                    // Already has flags in DB, update backup
                    backupHidden(uri, existing.isHidden)
                    backupFavorite(uri, existing.isFavorite)
                    backupTrash(uri, existing.isTrash)
                    backupVaultId(uri, existing.vaultId)
                    continue
                }

                // Restore from backup
                val hidden = prefs.getBoolean("$PREFIX_HIDDEN$uri", false)
                val favorite = prefs.getBoolean("$PREFIX_FAVORITE$uri", false)
                val trash = prefs.getBoolean("$PREFIX_TRASH$uri", false)
                val vaultId = prefs.getString("$PREFIX_VAULT$uri", null)

                // Only restore if any flag is set
                if (hidden || favorite || trash || vaultId != null) {
                    val entity = MediaFlagEntity(
                        uri = uri,
                        isHidden = hidden,
                        isFavorite = favorite,
                        isTrash = trash,
                        vaultId = vaultId,
                        updatedAt = System.currentTimeMillis()
                    )
                    mediaFlagDao.upsert(entity)
                    restored++
                    Log.d(TAG, "Restored flags for $uri: hidden=$hidden fav=$favorite trash=$trash vault=$vaultId")
                }
            }

            // Also sync: backup any DB flags that aren't in SharedPreferences yet
            val dbFlags = mediaFlagDao.findAll()
            for (flag in dbFlags) {
                val inBackup = allUris.contains(flag.uri)
                if (!inBackup) {
                    backupHidden(flag.uri, flag.isHidden)
                    backupFavorite(flag.uri, flag.isFavorite)
                    backupTrash(flag.uri, flag.isTrash)
                    backupVaultId(flag.uri, flag.vaultId)
                }
            }

            Log.i(TAG, "Restored $restored flag entries from backup")
            restored
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore flags from backup", e)
            0
        }
    }

    /**
     * Get diagnostic info about backup state.
     */
    fun getBackupStats(): String {
        val allUris = getAllUris()
        var hiddenCount = 0
        var favCount = 0
        var trashCount = 0
        var vaultCount = 0

        for (uri in allUris) {
            if (prefs.getBoolean("$PREFIX_HIDDEN$uri", false)) hiddenCount++
            if (prefs.getBoolean("$PREFIX_FAVORITE$uri", false)) favCount++
            if (prefs.getBoolean("$PREFIX_TRASH$uri", false)) trashCount++
            if (prefs.getString("$PREFIX_VAULT$uri", null) != null) vaultCount++
        }

        return "FlagBackup stats: ${allUris.size} URIs, hidden=$hiddenCount, fav=$favCount, trash=$trashCount, vault=$vaultCount"
    }

    // --- Internal helpers ---

    private fun addToUriSet(uri: String) {
        val current = prefs.getStringSet(KEY_ALL_URIS, emptySet()) ?: emptySet()
        if (uri !in current) {
            prefs.edit().putStringSet(KEY_ALL_URIS, current + uri).commit()
        }
    }

    private fun getAllUris(): Set<String> {
        return prefs.getStringSet(KEY_ALL_URIS, emptySet()) ?: emptySet()
    }
}
