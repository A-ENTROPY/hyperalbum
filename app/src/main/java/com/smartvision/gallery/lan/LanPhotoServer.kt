package com.smartvision.gallery.lan

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.contentValuesOf
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * 嵌入式 HTTP 服务器，通过局域网提供本机照片的浏览和下载。
 *
 * API 端点:
 *  - GET /api/info        → 设备信息 JSON
 *  - GET /api/photos?limit=N&offset=M → 照片列表 JSON（分页）
 *  - GET /photo/{id}      → 原始照片文件（二进制）
 *  - GET /thumb/{id}      → 缩略图（二进制）
 *
 * 构造参数:
 *  @param context  Application context（用于 MediaStore 查询）
 *  @param port     监听端口（默认 8080）
 *  @param deviceName 设备显示名，在 /api/info 中返回
 */
class LanPhotoServer(
    private val context: Context,
    port: Int = DEFAULT_PORT,
    private val deviceName: String = android.os.Build.MODEL,
) : NanoHTTPD(port) {

    companion object {
        const val DEFAULT_PORT = 8080
        private const val TAG = "LanPhotoServer"
    }

    private val contentResolver = context.contentResolver

    /**
     * 为每个传入的 HTTP 请求提供服务。
     * 路由：/api/info, /api/photos, /photo/{id}, /thumb/{id}
     */
    override fun serve(session: IHTTPSession): Response {
        return try {
            val uri = session.uri
            val method = session.method

            when {
                method == Method.GET && uri == "/api/info" -> handleInfo()
                method == Method.GET && uri == "/api/photos" -> {
                    val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
                    val offset = session.parameters["offset"]?.firstOrNull()?.toIntOrNull() ?: 0
                    handlePhotos(limit, offset)
                }
                method == Method.GET && uri.startsWith("/photo/") -> {
                    val id = uri.removePrefix("/photo/").toLongOrNull()
                    if (id != null) handlePhoto(id) else notFound("Invalid photo ID")
                }
                method == Method.GET && uri.startsWith("/thumb/") -> {
                    val id = uri.removePrefix("/thumb/").toLongOrNull()
                    if (id != null) handleThumbnail(id) else notFound("Invalid thumb ID")
                }
                method == Method.GET && uri == "/" -> handleInfo()
                else -> notFound("Not found: $uri")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "serve error: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_PLAINTEXT,
                "Server error: ${e.message}"
            )
        }
    }

    /** GET /api/info — 设备信息 */
    private fun handleInfo(): Response {
        val json = JSONObject().apply {
            put("deviceName", deviceName)
            put("appVersion", "1.0.0")
            put("photoCount", queryPhotoCount())
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /** GET /api/photos?limit=N&offset=M — 照片列表 */
    private fun handlePhotos(limit: Int, offset: Int): Response {
        val photos = queryPhotos(limit, offset)
        val json = JSONObject().apply {
            put("deviceName", deviceName)
            put("photos", JSONArray().apply {
                photos.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("displayName", p.displayName)
                        put("mimeType", p.mimeType)
                        put("width", p.width)
                        put("height", p.height)
                        put("size", p.size)
                        put("modifiedAt", p.modifiedAt)
                    })
                }
            })
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /** GET /photo/{id} — 原始照片文件 */
    private fun handlePhoto(id: Long): Response {
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
        )
        val mimeType = contentResolver.getType(uri) ?: "image/jpeg"
        val inputStream = contentResolver.openInputStream(uri)
            ?: return notFound("Photo not found: $id")
        return newChunkedResponse(Response.Status.OK, mimeType, inputStream)
    }

    /** GET /thumb/{id} — 缩略图 */
    private fun handleThumbnail(id: Long): Response {
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
        )
        // 使用 MediaStore.Thumbnails 获取缩略图
        val thumbUri = MediaStore.Images.Thumbnails.EXTERNAL_CONTENT_URI
        val cursor = contentResolver.query(
            thumbUri,
            arrayOf(MediaStore.Images.Thumbnails.DATA),
            "${MediaStore.Images.Thumbnails.IMAGE_ID} = ?",
            arrayOf(id.toString()),
            null
        )
        cursor?.use { c ->
            if (c.moveToFirst()) {
                val dataColumn = c.getColumnIndex(MediaStore.Images.Thumbnails.DATA)
                if (dataColumn >= 0) {
                    val path = c.getString(dataColumn)
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            return newChunkedResponse(
                                Response.Status.OK, "image/jpeg", FileInputStream(file)
                            )
                        }
                    }
                }
            }
        }
        // 缩略图不可用，fallback 到原图
        return handlePhoto(id)
    }

    /** 查询 MediaStore 照片总数 */
    private fun queryPhotoCount(): Int {
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf("COUNT(*)"),
            null, null, null
        )
        return cursor?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0
    }

    /** 查询 MediaStore 照片列表 */
    private fun queryPhotos(limit: Int, offset: Int): List<RemotePhoto> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC LIMIT $limit OFFSET $offset"
        val cursor = contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )
        val result = mutableListOf<RemotePhoto>()
        cursor?.use { c ->
            val idCol = c.getColumnIndex(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val wCol = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val hCol = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val sizeCol = c.getColumnIndex(MediaStore.Images.Media.SIZE)
            val modCol = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                result.add(
                    RemotePhoto(
                        id = c.getLong(idCol),
                        displayName = c.getString(nameCol) ?: "unknown",
                        mimeType = c.getString(mimeCol) ?: "image/jpeg",
                        width = if (wCol >= 0) c.getInt(wCol) else 0,
                        height = if (hCol >= 0) c.getInt(hCol) else 0,
                        size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L,
                        modifiedAt = if (modCol >= 0) c.getLong(modCol) * 1000L else 0L,
                    )
                )
            }
        }
        return result
    }

    private fun notFound(msg: String): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, msg)
}

/**
 * 辅助类：构建带 ID 的 content:// URI。
 * 相当于 ContentUris.withAppendedId 的简单实现。
 */
private object ContentUris {
    fun withAppendedId(baseUri: Uri, id: Long): Uri =
        Uri.parse("${baseUri.toString()}/$id")
}