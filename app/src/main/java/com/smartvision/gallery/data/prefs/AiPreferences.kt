package com.smartvision.gallery.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiDataStore by preferencesDataStore(name = "ai_preferences")

class AiPreferences(private val context: Context) {

    val aiEnabled: Flow<Boolean> = context.aiDataStore.data.map { it[KEY_ENABLED] ?: true }
    val aiProcessed: Flow<Long> = context.aiDataStore.data.map { it[KEY_PROCESSED] ?: 0L }
    val aiTotal: Flow<Long> = context.aiDataStore.data.map { it[KEY_TOTAL] ?: 0L }
    val foregroundAiEnabled: Flow<Boolean> = context.aiDataStore.data.map { it[KEY_FG_ENABLED] ?: false }
    val batchSize: Flow<Int> = context.aiDataStore.data.map { it[KEY_BATCH_SIZE] ?: 25 }
    val cooldownMs: Flow<Long> = context.aiDataStore.data.map { it[KEY_COOLDOWN_MS] ?: 30_000L }

    suspend fun setEnabled(enabled: Boolean) {
        context.aiDataStore.edit { it[KEY_ENABLED] = enabled }
    }

    suspend fun setProgress(processed: Long, total: Long) {
        context.aiDataStore.edit {
            it[KEY_PROCESSED] = processed
            it[KEY_TOTAL] = total
        }
    }

    suspend fun setForegroundEnabled(enabled: Boolean) {
        context.aiDataStore.edit { it[KEY_FG_ENABLED] = enabled }
    }

    suspend fun setBatchSize(size: Int) {
        context.aiDataStore.edit { it[KEY_BATCH_SIZE] = size.coerceIn(5, 100) }
    }

    suspend fun setCooldownMs(ms: Long) {
        context.aiDataStore.edit { it[KEY_COOLDOWN_MS] = ms.coerceIn(5_000L, 300_000L) }
    }

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("ai_enabled")
        private val KEY_PROCESSED = longPreferencesKey("ai_processed")
        private val KEY_TOTAL = longPreferencesKey("ai_total")
        private val KEY_FG_ENABLED = booleanPreferencesKey("ai_foreground_enabled")
        private val KEY_BATCH_SIZE = intPreferencesKey("ai_batch_size")
        private val KEY_COOLDOWN_MS = longPreferencesKey("ai_cooldown_ms")
    }
}
