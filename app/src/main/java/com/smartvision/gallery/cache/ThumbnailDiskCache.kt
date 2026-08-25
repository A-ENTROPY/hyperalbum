package com.smartvision.gallery.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.smartvision.gallery.util.AppLog
import java.io.File
import java.io.FileOutputStream

/**
 * On-disk cache for already-decoded thumbnails. The key strategy mirrors the in-memory
 * cache so a single (uri, w, h) lookup hits both layers in the same order.
 */
class ThumbnailDiskCache(private val context: Context) {

    private val baseDir: File by lazy {
        File(context.cacheDir, "smartvision_thumbs").also { it.mkdirs() }
    }

    fun get(uri: android.net.Uri, w: Int, h: Int): Bitmap? {
        val f = fileFor(uri, w, h)
        if (!f.exists()) return null
        return runCatching {
            BitmapFactory.decodeFile(f.absolutePath)
        }.onFailure { AppLog.w(TAG, "Disk cache decode failed for ${f.name}", it) }.getOrNull()
    }

    fun put(uri: android.net.Uri, w: Int, h: Int, bitmap: Bitmap) {
        val f = fileFor(uri, w, h)
        f.parentFile?.mkdirs()
        try {
            FileOutputStream(f).use { out ->
                // WebP 有损 ~85 — 缩略图无需无损。PNG 存缩略图体积大 3-5x 且写入慢;
                // 每次滚动重新解码 PNG 反而更费。WebP 解码快、体积小,同画质。
                // API 30+ 用显式 WEBP_LOSSY;30 以下 `WEBP` 常量自动选型(14+ 支持)。
                val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                bitmap.compress(format, 85, out)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "Disk cache write failed for ${f.name}", t)
        }
    }

    fun clear() {
        baseDir.deleteRecursively()
        baseDir.mkdirs()
    }

    /** Trim older-than [olderThanMs] entries to keep the cache bounded. */
    fun trim(olderThanMs: Long) {
        val threshold = System.currentTimeMillis() - olderThanMs
        baseDir.walkTopDown().forEach { f ->
            if (f.isFile) {
                // 迁移期:旧 .png 缩略图已不再被写入(get/put 只认 .webp),
                // 属死数据,直接删。新 .webp 按 age 裁剪。
                if (f.name.endsWith(".png") || f.lastModified() < threshold) f.delete()
            }
        }
    }

    private fun fileFor(uri: android.net.Uri, w: Int, h: Int): File {
        // Sanitise the URI string so it's filesystem-safe.
        val safe = uri.toString().replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return File(baseDir, "${safe}_${w}x${h}.webp")
    }

    private companion object {
        private const val TAG = "ThumbnailDiskCache"
    }
}