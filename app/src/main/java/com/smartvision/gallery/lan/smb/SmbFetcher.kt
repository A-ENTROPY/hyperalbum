package com.smartvision.gallery.lan.smb

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import jcifs.SmbResource
import okio.buffer
import okio.source

/**
 * Coil 自定义 Fetcher，通过 SMB 加载图片。
 *
 * Model 类型：jcifs-ng 的 SmbResource（代表一个 SMB 文件路径）。
 * 注册方式：在 AppImageLoaderFactory 的 components { add(SmbFetcher.Factory(app)) }
 *
 * 注意：项目实际解析到 Coil 2.7.0（catalog 声明 2.6.0 但受 {strictly 2.7.0} 约束），
 * 2.7.0 移除了 SourceFetchResult，必须返回 SourceResult + 顶层工厂函数 ImageSource(source, context)。
 * ImageSource.kt 标了 @file:JvmName("ImageSources")，JVM 类是 ImageSources，但 Kotlin 侧只认 ImageSource 函数。
 */
class SmbFetcher(
    private val smbResource: SmbResource,
    private val appContext: Context,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        return try {
            val inputStream = smbResource.openInputStream()
            val source = inputStream.source().buffer()
            SourceResult(
                source = ImageSource(source, appContext),
                mimeType = detectMimeType(smbResource.name),
                dataSource = DataSource.DISK, // SMB 视为本地网络，用 DISK 策略
            )
        } catch (e: Exception) {
            android.util.Log.w("SmbFetcher", "fetch failed: ${smbResource.locator.canonicalURL}", e)
            null
        }
    }

    private fun detectMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "avif" -> "image/avif"
            "gif" -> "image/gif"
            else -> "image/jpeg"
        }
    }

    /** Fetcher.Factory 注册入口 */
    class Factory(
        private val appContext: Context,
    ) : Fetcher.Factory<SmbResource> {
        override fun create(data: SmbResource, options: Options, imageLoader: ImageLoader): Fetcher {
            return SmbFetcher(data, appContext)
        }
    }
}
