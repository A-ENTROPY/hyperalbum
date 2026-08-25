package com.smartvision.gallery.lan.smb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/** 媒体文件类型 */
enum class SmbMediaType {
    IMAGE, VIDEO, GIF, NONE
}

/** 媒体文件条目 */
data class SmbMediaFile(
    val name: String,
    val path: String,
    val type: SmbMediaType,
    val size: Long,
    val lastModified: Long,
    val mimeType: String,
)

/** 扫描进度 */
data class ScanProgress(
    val scannedCount: Int = 0,
    val foundCount: Int = 0,
    val currentPath: String = "",
    val isComplete: Boolean = false,
)

/**
 * 共享文件夹媒体文件扫描器。
 *
 * 仅扫描常见图片/视频/动图扩展名，跳过程序文件。
 * 递归深度限制：10 层（防止循环引用）。
 */
class SmbAlbumIndex(
    private val shareManager: SmbShareManager,
) {
    companion object {
        private const val TAG = "SmbAlbumIndex"
        private const val MAX_DEPTH = 10
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
        private val GIF_EXTENSIONS = setOf("gif")
    }

    /**
     * 扫描共享文件夹。
     *
     * @param device SMB 设备
     * @param path 起始路径（默认根目录）
     * @param depth 当前递归深度
     * @param scope 取消/激活检查作用域（调用方协程）
     * @param onProgress 进度回调（在调用线程执行）
     * @return 发现的媒体文件列表
     */
    suspend fun scan(
        device: SmbDevice,
        path: String = "",
        depth: Int = 0,
        scope: CoroutineScope,
        onProgress: (ScanProgress) -> Unit = {},
    ): List<SmbMediaFile> {
        if (depth > MAX_DEPTH || !scope.isActive) return emptyList()

        val result = mutableListOf<SmbMediaFile>()
        var scanned = 0

        try {
            val entries = shareManager.listFiles(device, path)
            for (entry in entries) {
                if (!scope.isActive) return result

                scanned++
                onProgress(ScanProgress(
                    scannedCount = scanned,
                    foundCount = result.size,
                    currentPath = entry.path,
                ))

                if (entry.isDirectory) {
                    // 跳过 Windows 系统隐藏目录
                    if (entry.name.startsWith("\\$") || entry.name.startsWith(".")) continue
                    val subFiles = scan(device, entry.path, depth + 1, scope, onProgress)
                    result.addAll(subFiles)
                } else {
                    val mediaType = detectMediaType(entry.name)
                    if (mediaType != SmbMediaType.NONE) {
                        result.add(SmbMediaFile(
                            name = entry.name,
                            path = entry.path,
                            type = mediaType,
                            size = entry.size,
                            lastModified = entry.lastModified,
                            mimeType = mimeTypeFor(entry.name),
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Scan error at $path: ${e.message}")
        }

        onProgress(ScanProgress(
            scannedCount = scanned,
            foundCount = result.size,
            currentPath = "",
            isComplete = true,
        ))
        return result
    }

    private fun detectMediaType(fileName: String): SmbMediaType {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext in IMAGE_EXTENSIONS -> SmbMediaType.IMAGE
            ext in VIDEO_EXTENSIONS -> SmbMediaType.VIDEO
            ext in GIF_EXTENSIONS -> SmbMediaType.GIF
            else -> SmbMediaType.NONE
        }
    }

    private fun mimeTypeFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "m4v" -> "video/mp4"
            "3gp" -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }
}