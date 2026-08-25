package com.smartvision.gallery.lan

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端，从远程设备获取照片列表和文件。
 *
 * 使用项目中已有的 OkHttp 库。
 */
class LanPhotoClient {

    companion object {
        private const val TAG = "LanPhotoClient"
        private const val TIMEOUT_SEC = 10L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(30L, TimeUnit.SECONDS)
        .build()

    /** 获取远程设备信息 */
    suspend fun fetchDeviceInfo(host: String, port: Int): DeviceInfoResponse? {
        return try {
            val url = "http://$host:$port/api/info"
            val request = Request.Builder().url(url).get().build()
            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            DeviceInfoResponse(
                deviceName = json.optString("deviceName", host),
                appVersion = json.optString("appVersion", "unknown"),
                photoCount = json.optInt("photoCount", 0),
            )
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fetchDeviceInfo failed: $host:$port", e)
            null
        }
    }

    /** 获取远程照片列表 */
    suspend fun fetchPhotos(
        host: String,
        port: Int,
        limit: Int = 50,
        offset: Int = 0,
    ): List<RemotePhoto> {
        return try {
            val url = "http://$host:$port/api/photos?limit=$limit&offset=$offset"
            val request = Request.Builder().url(url).get().build()
            val response = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val json = JSONObject(body)
            val photosArray = json.optJSONArray("photos") ?: return emptyList()
            val result = mutableListOf<RemotePhoto>()
            for (i in 0 until photosArray.length()) {
                val p = photosArray.getJSONObject(i)
                result.add(
                    RemotePhoto(
                        id = p.optLong("id", 0),
                        displayName = p.optString("displayName", "unknown"),
                        mimeType = p.optString("mimeType", "image/jpeg"),
                        width = p.optInt("width", 0),
                        height = p.optInt("height", 0),
                        size = p.optLong("size", 0),
                        modifiedAt = p.optLong("modifiedAt", 0),
                    )
                )
            }
            result
        } catch (e: Exception) {
            android.util.Log.w(TAG, "fetchPhotos failed: $host:$port", e)
            emptyList()
        }
    }

    /** 构建下载 URL */
    fun photoUrl(host: String, port: Int, photoId: Long): String {
        return "http://$host:$port/photo/$photoId"
    }

    /** 构建缩略图 URL */
    fun thumbUrl(host: String, port: Int, photoId: Long): String {
        return "http://$host:$port/thumb/$photoId"
    }

    /** 下载照片到本地文件 */
    suspend fun downloadPhoto(
        host: String,
        port: Int,
        photoId: Long,
        destPath: String,
    ): Boolean {
        return try {
            val url = photoUrl(host, port, photoId)
            val request = Request.Builder().url(url).get().build()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                val file = java.io.File(destPath)
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "downloadPhoto failed: $host:$port/$photoId", e)
            false
        }
    }
}