package com.smartvision.gallery.lan

/**
 * 局域网中发现的设备，运行着 Liquid Gallery 的 HTTP 照片服务。
 */
data class LanPhotoDevice(
    val deviceName: String,
    val host: String,
    val port: Int,
    val lastSeen: Long = System.currentTimeMillis(),
)

/**
 * 远程设备上的一张照片元数据。
 * 对应 JSON API `/api/photos` 返回的数组元素。
 */
data class RemotePhoto(
    val id: Long,
    val displayName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val modifiedAt: Long,
)

/**
 * 远程设备 `/api/photos` 的完整响应。
 */
data class PhotoListResponse(
    val deviceName: String,
    val photos: List<RemotePhoto>,
)

/**
 * 远程设备 `/api/info` 的响应。
 */
data class DeviceInfoResponse(
    val deviceName: String,
    val appVersion: String,
    val photoCount: Int,
)