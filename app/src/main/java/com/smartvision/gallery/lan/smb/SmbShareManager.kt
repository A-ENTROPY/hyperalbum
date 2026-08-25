package com.smartvision.gallery.lan.smb

import android.content.Context
import android.util.Log
import jcifs.CIFSContext
import jcifs.CIFSException
import jcifs.context.BaseContext
import jcifs.config.PropertyConfiguration
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.SmbResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.MalformedURLException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * SMB 连接管理器。管理 CIFSContext 连接池和文件 CRUD 操作。
 *
 * 单例模式，通过 [getInstance] 获取。
 */
class SmbShareManager private constructor(private val appContext: Context) {

    companion object {
        private const val TAG = "SmbShareManager"
        private const val KEEPALIVE_INTERVAL_MS = 30_000L

        @Volatile private var instance: SmbShareManager? = null

        fun getInstance(context: Context): SmbShareManager {
            return instance ?: synchronized(this) {
                instance ?: SmbShareManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // CIFSContext 缓存：key = "host:port:username"，值 = (连接, 共享根 URL)
    private val contextCache = ConcurrentHashMap<String, Pair<CIFSContext, String>>()
    private var keepaliveJob: kotlinx.coroutines.Job? = null

    /**
     * 获取或创建 CIFSContext。
     * 缓存的 key = "host:port:username"，
     * 同一个 host+credential 复用同一连接池。
     */
    fun getCifsContext(device: SmbDevice): CIFSContext {
        val key = buildContextKey(device)
        return contextCache.getOrPut(key) {
            createCifsContext(device) to device.toSmbUrl("")
        }.first
    }

    private fun buildContextKey(device: SmbDevice): String {
        val user = device.credentials?.username ?: ""
        // 密码参与 key：改密码后不复用旧连接（否则旧凭据的缓存 context 会被新请求命中）
        val pass = device.credentials?.password.orEmpty()
        return "${device.host}:${device.port}:$user:$pass"
    }

    private fun createCifsContext(device: SmbDevice): CIFSContext {
        val props = Properties()
        // SMB2/3 only; no SMB1 (security risk)
        props.setProperty("jcifs.smb.client.enableSMB1", "false")
        // 超时配置
        props.setProperty("jcifs.smb.client.connTimeout", "10000")
        props.setProperty("jcifs.smb.client.soTimeout", "30000")
        // 最大连接数
        props.setProperty("jcifs.smb.client.maxBuffered", "16")

        val config = PropertyConfiguration(props)
        val baseContext = BaseContext(config)

        val creds = device.credentials?.let { c ->
            if (!c.isAnonymous) {
                NtlmPasswordAuthenticator(device.domain, c.username, c.password)
            } else null
        }

        return if (creds != null) {
            baseContext.withCredentials(creds)
        } else {
            baseContext
        }
    }

    /**
     * 枚举主机上所有共享文件夹（标准 SMB 浏览流程）。
     * 对 host 级 URL（smb://host:port/）调用 children()，返回共享名列表。
     */
    suspend fun listShares(device: SmbDevice): List<String> = withContext(Dispatchers.IO) {
        val url = "smb://${device.host}:${device.port}/"
        val ctx = getCifsContext(device)
        try {
            val resource = ctx.get(url)
            val shares = resource.children().use { iter ->
                buildList {
                    while (iter.hasNext()) add(iter.next().name)
                }
            }
            // 过滤系统共享（以 $ 结尾：ADMIN$, C$, IPC$ 等）
            shares.filter { !it.endsWith("\$") }
                .sortedBy { it.lowercase() }
        } catch (e: CIFSException) {
            Log.w(TAG, "listShares failed: $url", e)
            throw SmbOperationException("无法列出共享: ${e.message}", e)
        }
    }

    /**
     * 列出共享文件夹根目录下的文件和子目录。
     * 返回 [SmbEntry] 列表，包含文件名、大小、修改时间、是否为目录。
     */
    suspend fun listFiles(device: SmbDevice, path: String = ""): List<SmbEntry> = withContext(Dispatchers.IO) {
        val url = device.toSmbUrl(path)
        val ctx = getCifsContext(device)
        try {
            val resource = ctx.get(url)
            // jcifs-ng 2.1.x: SmbResource.children() 返回 CloseableIterator<SmbResource>
            val files = resource.children().use { iter ->
                buildList {
                    while (iter.hasNext()) add(iter.next())
                }
            }
            // 过滤系统文件（以 $ 结尾的隐藏共享、Thumbs.db 等）
            files.filter { !it.name.endsWith("\$") && it.name != "Thumbs.db" }
                .map { file ->
                    SmbEntry(
                        name = file.name,
                        path = if (path.isBlank()) file.name else "$path/${file.name}",
                        isDirectory = file.isDirectory,
                        size = file.length(),
                        lastModified = file.lastModified(),
                    )
                }
                .sortedBy { entry ->
                    // 目录排在前面，文件按名称排序
                    if (entry.isDirectory) 0 else 1
                }
        } catch (e: MalformedURLException) {
            Log.e(TAG, "Invalid URL: $url", e)
            emptyList()
        } catch (e: CIFSException) {
            Log.w(TAG, "SMB list failed: $url", e)
            throw SmbOperationException("无法列出目录: ${e.message}", e)
        }
    }

    /**
     * 打开 SMB 文件的 InputStream。
     */
    suspend fun openInputStream(device: SmbDevice, path: String): InputStream = withContext(Dispatchers.IO) {
        val url = device.toSmbUrl(path)
        val ctx = getCifsContext(device)
        try {
            val resource = ctx.get(url) as SmbResource
            resource.openInputStream()
        } catch (e: Exception) {
            Log.w(TAG, "openInputStream failed: $url", e)
            throw SmbOperationException("无法打开文件: ${e.message}", e)
        }
    }

    /**
     * 打开 SMB 文件的 RandomAccess（用于 ExoPlayer 视频流式播放）。
     */
    suspend fun openRandomAccess(device: SmbDevice, path: String): jcifs.SmbRandomAccess = withContext(Dispatchers.IO) {
        val url = device.toSmbUrl(path)
        val ctx = getCifsContext(device)
        try {
            val resource = ctx.get(url) as jcifs.SmbResource
            resource.openRandomAccess("r")
        } catch (e: Exception) {
            Log.w(TAG, "openRandomAccess failed: $url", e)
            throw SmbOperationException("无法打开文件随机访问: ${e.message}", e)
        }
    }

    /** 删除远程文件 */
    suspend fun deleteFile(device: SmbDevice, path: String): Boolean = withContext(Dispatchers.IO) {
        val url = device.toSmbUrl(path)
        val ctx = getCifsContext(device)
        try {
            val resource = ctx.get(url) as SmbResource
            resource.delete()
            true
        } catch (e: Exception) {
            Log.w(TAG, "delete failed: $url", e)
            false
        }
    }

    /** 重命名远程文件 */
    suspend fun renameFile(device: SmbDevice, oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val url = device.toSmbUrl(oldPath)
        val ctx = getCifsContext(device)
        try {
            val resource = ctx.get(url)
            val target = ctx.get(device.toSmbUrl(newPath))
            resource.renameTo(target)
            true
        } catch (e: Exception) {
            Log.w(TAG, "rename failed: $url -> $newPath", e)
            false
        }
    }

    /** 复制远程文件到本地 */
    suspend fun copyToLocal(device: SmbDevice, path: String, localFile: java.io.File): Boolean = withContext(Dispatchers.IO) {
        try {
            openInputStream(device, path).use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "copyToLocal failed: $path -> ${localFile.path}", e)
            false
        }
    }

    /**
     * 启动 keepalive 心跳。每 30s 遍历所有缓存的 CIFSContext，
     * 对根目录执行 exists() 保持连接活跃。
     */
    fun startKeepalive(scope: kotlinx.coroutines.CoroutineScope) {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                delay(KEEPALIVE_INTERVAL_MS)
                val activeContexts = contextCache.entries.toList()
                for ((key, entry) in activeContexts) {
                    try {
                        // 对共享根 URL 做一次轻量 exists() 保持会话活跃
                        entry.first.get(entry.second).exists()
                    } catch (_: Exception) {
                        // keepalive 失败不抛给上层，仅移除失效连接
                        contextCache.remove(key)
                    }
                }
            }
        }
    }

    /** 释放所有 CIFSContext 连接 */
    fun releaseAll() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        contextCache.clear()
        Log.i(TAG, "All SMB connections released")
    }
}

/** SMB 文件/目录条目 */
data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

/** SMB 操作异常 */
class SmbOperationException(message: String, cause: Throwable? = null) : Exception(message, cause)