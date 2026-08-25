package com.smartvision.gallery.lan.smb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * SMB 缩略图 LRU 磁盘缓存。
 *
 * 每台主机 500 条目，总上限 2000 条目，30 天 TTL。
 * 缓存键 = "host:share:path" 的 MD5 哈希。
 * 缓存值 = JPEG 文件（质量 85，最大 512x512）。
 */
class SmbThumbnailCache(context: Context) {
    companion object {
        private const val TAG = "SmbThumbnailCache"
        private const val MAX_ENTRIES_PER_HOST = 500
        private const val TOTAL_MAX_ENTRIES = 2000
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000 // 30 days
        private const val THUMB_QUALITY = 85
        private const val THUMB_MAX_SIZE = 512
    }

    private val cacheDir = File(context.cacheDir, "smb_thumbnails").apply { mkdirs() }

    /**
     * 获取缓存缩略图。如果不存在或已过期返回 null。
     */
    fun get(host: String, share: String, path: String): Bitmap? {
        val file = cacheFile(host, share, path)
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > TTL_MS) {
            file.delete()
            return null
        }
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * 保存缩略图到缓存。如果缓存超限，淘汰最旧条目。
     */
    fun put(host: String, share: String, path: String, bitmap: Bitmap) {
        // 先检查总上限
        evictIfNeeded()

        val file = cacheFile(host, share, path)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache thumbnail: $path", e)
        }
    }

    /** 清除所有缓存 */
    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun cacheFile(host: String, share: String, path: String): File {
        val key = "${host}:${share}:${path}"
        val hash = key.md5()
        return File(cacheDir, hash)
    }

    /**
     * 淘汰缓存：检查总条目数，超过 [TOTAL_MAX_ENTRIES] 时删除最旧文件。
     */
    private fun evictIfNeeded() {
        val files = cacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= TOTAL_MAX_ENTRIES) return
        val toDelete = files.size - TOTAL_MAX_ENTRIES
        files.take(toDelete).forEach { it.delete() }
    }

    private fun String.md5(): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        val bytes = digest.digest(this.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}