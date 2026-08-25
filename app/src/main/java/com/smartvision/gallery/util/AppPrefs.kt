package com.smartvision.gallery.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "smartvision_prefs")

/**
 * Typed wrapper around [DataStore] for all user preferences. Kept tiny on purpose;
 * heavy state lives in Room.
 */
class AppPrefs(private val context: Context) {

    private val store = context.dataStore

    val performanceMode: Flow<Boolean> = store.data.map { it[KEY_PERF_MODE] ?: false }
    val hardwareAccel: Flow<Boolean> = store.data.map { it[KEY_HW_ACCEL] ?: true }
    val preferNextGen: Flow<Boolean> = store.data.map { it[KEY_PREFER_NEXT_GEN] ?: false }
    val lastScanTimeMs: Flow<Long> = store.data.map { it[KEY_LAST_SCAN_MS] ?: 0L }

    suspend fun getLastScanTimeMs(): Long = lastScanTimeMs.first()
    val glassTransparency: Flow<Float> = store.data.map { it[KEY_GLASS_TRANSPARENCY] ?: 0f }
    val cloudProvider: Flow<CloudProvider> = store.data.map {
        CloudProvider.fromOrDefault(it[KEY_CLOUD])
    }

    suspend fun setPerformanceMode(enabled: Boolean) =
        store.edit { it[KEY_PERF_MODE] = enabled }

    suspend fun setHardwareAccel(enabled: Boolean) =
        store.edit { it[KEY_HW_ACCEL] = enabled }

    suspend fun setPreferNextGen(enabled: Boolean) =
        store.edit { it[KEY_PREFER_NEXT_GEN] = enabled }

    suspend fun setLastScanTimeMs(time: Long) =
        store.edit { it[KEY_LAST_SCAN_MS] = time }

    suspend fun setGlassTransparency(slider: Float) =
        store.edit { it[KEY_GLASS_TRANSPARENCY] = slider.coerceIn(0f, 1f) }

    suspend fun setCloudProvider(provider: CloudProvider) =
        store.edit { it[KEY_CLOUD] = provider.id }

    /**
     * Whether ACCESS_MEDIA_LOCATION was granted last time we checked.
     * Used to detect the transition "no-permission → granted" so we can schedule
     * a one-shot geo refill (otherwise the DB rows from before the grant stay
     * with latitude=null forever).
     */
    suspend fun getMediaLocationGrantedLastCheck(): Boolean =
        store.data.map { it[KEY_MEDIA_LOCATION_GRANTED] ?: false }.first()

    suspend fun setMediaLocationGrantedLastCheck(granted: Boolean) =
        store.edit { it[KEY_MEDIA_LOCATION_GRANTED] = granted }

    /** Whether the media-permission prompt has been shown at least once.
     *  Persistent across launches so a denied user is not re-prompted every
     *  cold start (they should land on the rationale guide page instead). */
    suspend fun getPermissionPromptShown(): Boolean =
        store.data.map { it[KEY_PERMISSION_PROMPT_SHOWN] ?: false }.first()

    suspend fun setPermissionPromptShown() =
        store.edit { it[KEY_PERMISSION_PROMPT_SHOWN] = true }

    private companion object {
        val KEY_PERF_MODE = booleanPreferencesKey("perf_mode")
        val KEY_HW_ACCEL = booleanPreferencesKey("hw_accel")
        val KEY_PREFER_NEXT_GEN = booleanPreferencesKey("prefer_next_gen")
        val KEY_LAST_SCAN_MS = androidx.datastore.preferences.core.longPreferencesKey("last_scan_ms")
        val KEY_CLOUD = stringPreferencesKey("cloud_provider")
        val KEY_GLASS_TRANSPARENCY = androidx.datastore.preferences.core.floatPreferencesKey("glass_transparency")
        val KEY_MEDIA_LOCATION_GRANTED = booleanPreferencesKey("media_location_granted")
        val KEY_PERMISSION_PROMPT_SHOWN = booleanPreferencesKey("permission_prompt_shown")
    }
}

enum class CloudProvider(val id: String, val displayName: String) {
    NONE("none", "未启用"),
    LOCAL_FAKE("local_fake", "本地模拟云"),
    GOOGLE_PHOTOS("google_photos", "Google Photos"),
    ALIYUN_DRIVE("aliyun", "阿里云盘"),
    TENCENT_WECLOUD("tencent", "腾讯微云");

    companion object {
        fun fromOrDefault(id: String?): CloudProvider =
            entries.firstOrNull { it.id == id } ?: NONE
    }
}