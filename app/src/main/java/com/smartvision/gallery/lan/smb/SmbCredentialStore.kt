package com.smartvision.gallery.lan.smb

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import org.json.JSONArray
import org.json.JSONObject

/**
 * SMB 凭据加密存储。
 *
 * 使用 AndroidX Security 的 EncryptedSharedPreferences（AES-256 GCM）加密存储。
 * 存储格式：JSON 数组，每个条目包含 deviceId, username, password。
 */
class SmbCredentialStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "smb_credentials"
        private const val KEY_DEVICES = "smb_devices"
    }

    // security-crypto 1.0.0 稳定版：仅 MasterKeys 旧版 API（无 MasterKey 类）。
    // MasterKeys.getOrCreate 返回 keystore 别名 String。
    private val masterKeyAlias: String = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        masterKeyAlias,
        PREFS_NAME,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** 保存设备列表到加密存储 */
    fun saveDevices(devices: List<SmbDevice>) {
        val jsonArray = JSONArray()
        for (device in devices) {
            val obj = JSONObject().apply {
                put("id", device.id)
                put("displayName", device.displayName)
                put("host", device.host)
                put("shareName", device.shareName)
                put("domain", device.domain)
                put("port", device.port)
                put("username", device.credentials?.username ?: "")
                put("password", device.credentials?.password ?: "")
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_DEVICES, jsonArray.toString()).apply()
    }

    /** 从加密存储加载设备列表 */
    fun loadDevices(): List<SmbDevice> {
        val json = prefs.getString(KEY_DEVICES, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                val username = obj.optString("username", "")
                SmbDevice(
                    id = obj.getString("id"),
                    displayName = obj.optString("displayName", ""),
                    host = obj.getString("host"),
                    shareName = obj.getString("shareName"),
                    domain = obj.optString("domain", ""),
                    port = obj.optInt("port", 445),
                    credentials = if (username.isNotBlank()) {
                        SmbCredentials(username, obj.optString("password", ""))
                    } else null,
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("SmbCredentialStore", "loadDevices failed", e)
            emptyList()
        }
    }

    /** 清除所有存储的凭据 */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}