# LAN SMB 跨设备相册实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现通过 SMB/CIFS 协议访问 Windows 共享文件夹，在 Android 端以本地相册风格浏览/查看/管理照片/视频/动图

**Architecture:** jcifs-ng 纯 Java SMB2/3 客户端库提供协议层连接；CIFSContext 按 host+credentials 缓存单例复用连接；SmbFetcher 集成 Coil 加载缩略图；SmbMediaDataSource 实现 ExoPlayer DataSource 接口支持视频流式播放；全屏大图先下载到本地缓存再交由 Telephoto SubSamplingImage 显示

**Tech Stack:** jcifs-ng 2.2.2, Coil 2.6.0, ExoPlayer/Media3 1.5.1, Telephoto 0.18.0, AndroidX Security Crypto 1.0.0, slf4j-nop 2.0.16

---

## 文件结构

### 新建文件
```
lan/smb/
├── SmbDevice.kt              # 数据类：网络位置（host, share, domain, credentials）
├── SmbCredentials.kt         # 凭据数据类（username, password）
├── SmbShareManager.kt        # SMB 连接管理 + CIFSContext 缓存 + 目录浏览 + CRUD + keepalive
├── SmbAlbumIndex.kt          # 扫描共享文件夹元数据（取消/进度/增量）
├── SmbThumbnailCache.kt      # 缩略图 LRU 磁盘缓存
├── SmbMediaDataSource.kt     # ExoPlayer DataSource（SMB 随机访问）
├── SmbFetcher.kt             # Coil Fetcher.Factory<SmbFile>
├── SmbDiscovery.kt           # NetBIOS UDP 137 + mDNS UDP 5353 辅助发现

ui/lan/
├── SmbHostList.kt            # 网络位置列表页（LiquidGlassCard）
├── AddSmbHostDialog.kt       # 添加网络位置对话框
├── SmbMediaGrid.kt           # 照片网格（扫描进度、分类标签、空状态）
├── SmbPhotoViewer.kt         # 全屏查看器（Telephoto）
```

### 修改文件
- `app/src/main/java/com/smartvision/gallery/ui/lan/LanSharePage.kt` — 重构，添加 SMB 区域
- `app/build.gradle.kts` — 添加 jcifs-ng + slf4j-nop + security-crypto 依赖
- `gradle/libs.versions.toml` — 添加版本号
- `app/src/main/java/com/smartvision/gallery/ui/components/AppImageLoaderFactory.kt` — 注册 SmbFetcher

---

## Task 1: 依赖配置

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**背景：** 需要添加 jcifs-ng SMB 客户端、slf4j-nop 日志抑制、AndroidX Security Crypto 凭据加密

- [ ] **Step 1: 在 libs.versions.toml 中添加版本号和库条目**

在 `gradle/libs.versions.toml` 的 `[versions]` 区块末尾添加：
```toml
jcifsng = "2.2.2"
slf4j = "2.0.16"
security-crypto = "1.0.0"
```

在 `[libraries]` 区块末尾添加：
```toml
jcifsng = { group = "eu.agno3.jcifs", name = "jcifs-ng", version.ref = "jcifsng" }
slf4j-nop = { group = "org.slf4j", name = "slf4j-nop", version.ref = "slf4j" }
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "security-crypto" }
```

- [ ] **Step 2: 在 app/build.gradle.kts 的 dependencies 中添加引用**

在 `app/build.gradle.kts` 的 `dependencies` 区块末尾添加：
```kotlin
// SMB/CIFS client for LAN shared folder access
implementation(libs.jcifsng)
implementation(libs.slf4j.nop)
implementation(libs.security.crypto)
```

- [ ] **Step 3: 验证依赖解析**

```bash
cd /h/workspace-minimaxcode/hyperalbum && ./gradlew app:dependencies --configuration debugRuntimeClasspath | grep -E "jcifs|slf4j|security-crypto"
```
Expected: 三个依赖均出现在输出中，无冲突错误

---

## Task 2: SMB 数据模型

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbDevice.kt`
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbCredentials.kt`

- [ ] **Step 1: 创建 SmbCredentials.kt**

```kotlin
package com.smartvision.gallery.lan.smb

/**
 * SMB 凭据。用于 jcifs-ng 的 NTLM 认证。
 *
 * 通过 [EncryptedSharedPreferences] 加密存储到磁盘。
 * 用户名留空 = 匿名访问（fallback，需用户主动选择）。
 */
data class SmbCredentials(
    val username: String = "",
    val password: String = "",
) {
    /** 是否为匿名凭据（用户名和密码均为空） */
    val isAnonymous: Boolean get() = username.isBlank()
}
```

- [ ] **Step 2: 创建 SmbDevice.kt**

```kotlin
package com.smartvision.gallery.lan.smb

/**
 * 网络位置（一台 Windows 电脑上的一个共享文件夹）。
 *
 * jcifs-ng URL 格式: smb://[[domain;]username[:password]@]host[:port]/share/path
 * 示例: smb://DESKTOP-PC/Photos
 *       smb://user:pass@192.168.1.100/Share
 *
 * @param id 唯一标识（UUID，用于持久化存储）
 * @param displayName 显示名称（用户自定义或主机名）
 * @param host IP 地址或主机名
 * @param shareName 共享文件夹名称
 * @param domain 域（可选，Windows 域认证）
 * @param credentials 凭据（null=匿名）
 * @param port 端口（默认 445）
 */
data class SmbDevice(
    val id: String = java.util.UUID.randomUUID().toString(),
    val displayName: String = "",
    val host: String,
    val shareName: String,
    val domain: String = "",
    val credentials: SmbCredentials? = null,
    val port: Int = 445,
) {
    /** 构造 jcifs-ng 的 SMB URL */
    fun toSmbUrl(path: String = ""): String {
        val creds = credentials?.let { c ->
            if (!c.isAnonymous) {
                val domainPrefix = if (domain.isNotBlank()) "$domain;" else ""
                "${domainPrefix}${c.username}:${c.password}@"
            } else ""
        } ?: ""
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return "smb://$creds$host:$port/$shareName$normalizedPath"
    }

    /** 获取共享根目录的 SmbFile URL */
    fun rootUrl(): String = toSmbUrl("")
}
```

---

## Task 3: SMB 连接管理器

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbShareManager.kt`

**背景：** jcifs-ng 的 `CIFSContext` 持有认证状态和连接池。为每个请求新建 context 会重复建立 TCP 连接，丢失 NTLM 认证状态。必须按 host+credentials 缓存单例。

关键设计：
- `CIFSContext` 缓存：key = `host:port:username`，首次访问创建，复用已有
- Keepalive：每 30s 遍历所有缓存 context 发一次空请求（`SmbFile.exists()`）
- 所有操作在 `Dispatchers.IO` 执行
- 异常统一包装为 `SmbException`（方便上层捕获）

- [ ] **Step 1: 创建 SmbShareManager.kt 骨架**

```kotlin
package com.smartvision.gallery.lan.smb

import android.content.Context
import android.util.Log
import jcifs.CIFSContext
import jcifs.CIFSException
import jcifs.context.BaseContext
import jcifs.config.PropertyConfiguration
import jcifs.Credentials
import jcifs.SmbResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
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

    // CIFSContext 缓存：key = "host:port:username"
    private val contextCache = ConcurrentHashMap<String, CIFSContext>()
    private var keepaliveJob: kotlinx.coroutines.Job? = null

    /**
     * 获取或创建 CIFSContext。
     * 缓存的 key = "host:port:username"，
     * 同一个 host+credential 复用同一连接池。
     */
    private fun getCifsContext(device: SmbDevice): CIFSContext {
        val key = buildContextKey(device)
        return contextCache.getOrPut(key) {
            createCifsContext(device)
        }
    }

    private fun buildContextKey(device: SmbDevice): String {
        val user = device.credentials?.username ?: ""
        return "${device.host}:${device.port}:$user"
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
                Credentials(c.username, c.password.toCharArray(), device.domain)
            } else null
        }

        return if (creds != null) {
            baseContext.withCredentials(creds)
        } else {
            baseContext
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
            val resource = ctx.get(url) as SmbResource
            val files = resource.listFiles() ?: emptyArray()
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
            val resource = ctx.get(url) as SmbResource
            resource.renameTo(newPath)
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
```

- [ ] **Step 2: 添加 keepalive 心跳机制**

在 `SmbShareManager` 中添加启动 keepalive 的方法（在 Task 5 网络状态监听中调用）：

```kotlin
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
            for ((key, ctx) in activeContexts) {
                try {
                    // 对根共享做一次轻量查询保持会话
                    val resource = ctx.get("smb://$key") as SmbResource
                    resource.exists()
                } catch (_: Exception) {
                    // keepalive 失败不抛给上层，仅移除失效连接
                    contextCache.remove(key)
                }
            }
        }
    }
}
```

---

## Task 4: 缩略图缓存

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbThumbnailCache.kt`

**设计：** LRU 磁盘缓存，每主机 500 条目，总上限 2000，30 天 TTL。使用 `LinkedHashMap` 实现 LRU。

- [ ] **Step 1: 创建 SmbThumbnailCache.kt**

```kotlin
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
```

---

## Task 5: Album Index（共享文件夹扫描）

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbAlbumIndex.kt`

**设计：** 递归扫描共享文件夹，收集媒体文件元数据。支持取消（通过 `isActive`）、进度回调（当前扫描路径、已发现文件数）、增量更新（首次全量，后续按修改时间戳）

- [ ] **Step 1: 创建 SmbAlbumIndex.kt**

```kotlin
package com.smartvision.gallery.lan.smb

import kotlinx.coroutines.isActive
import java.io.File

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
     * @param onProgress 进度回调（在调用线程执行）
     * @return 发现的媒体文件列表
     */
    suspend fun scan(
        device: SmbDevice,
        path: String = "",
        depth: Int = 0,
        onProgress: (ScanProgress) -> Unit = {},
    ): List<SmbMediaFile> {
        if (depth > MAX_DEPTH || !kotlinx.coroutines.isActive()) return emptyList()

        val result = mutableListOf<SmbMediaFile>()
        var scanned = 0

        try {
            val entries = shareManager.listFiles(device, path)
            for (entry in entries) {
                if (!kotlinx.coroutines.isActive()) return result

                scanned++
                onProgress(ScanProgress(
                    scannedCount = scanned,
                    foundCount = result.size,
                    currentPath = entry.path,
                ))

                if (entry.isDirectory) {
                    // 跳过 Windows 系统隐藏目录
                    if (entry.name.startsWith("\\$") || entry.name.startsWith(".")) continue
                    val subFiles = scan(device, entry.path, depth + 1, onProgress)
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
```

---

## Task 6: SMB Fetcher（Coil 集成）

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbFetcher.kt`
- Modify: `app/src/main/java/com/smartvision/gallery/ui/components/AppImageLoaderFactory.kt`

**背景：** Coil 的自定义 Fetcher 需要实现 `Fetcher.Factory<T>`，其中 T 是 model 类型。这里 model 使用 `SmbFile`（jcifs-ng 的 SmbResource 子类）。Fetcher 在 `fetch()` 中创建 SMB InputStream 返回给 Coil 解码。

JPEG 缩略图策略：读取前 32KB 即可（含 SOF 段宽高 + DHT 表），Coil 的 `BitmapFactory.decodeByteArray` 可据此解码缩略图。非 JPEG 格式下载完整文件。

- [ ] **Step 1: 创建 SmbFetcher.kt**

```kotlin
package com.smartvision.gallery.lan.smb

import android.content.Context
import android.graphics.BitmapFactory
import coil.decode.DataSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceFetchResult
import coil.imageLoader
import coil.request.Options
import coil.size.Size
import jcifs.SmbResource
import okio.BufferedSource
import okio.Okio
import okio.Source
import java.io.InputStream

/**
 * Coil 自定义 Fetcher，通过 SMB 加载图片。
 *
 * Model 类型：jcifs-ng 的 SmbResource（代表一个 SMB 文件路径）。
 * 使用时通过 Coil 的 ImageLoader 加载：
 *   imageLoader.execute(ImageRequest.Builder(context)
 *       .data(smbFile)  // SmbResource
 *       .build())
 *
 * 注册方式：在 AppImageLoaderFactory 的 components { add(SmbFetcherFactory()) }
 */
class SmbFetcher(
    private val smbResource: SmbResource,
    private val shareManager: SmbShareManager,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        return try {
            val inputStream = smbResource.openInputStream()
            val source = Okio.source(inputStream).buffer()

            // 返回 SourceFetchResult，Coil 会自动解码 Bitmap
            SourceFetchResult(
                source = source,
                mimeType = smbResource.name?.let { detectMimeType(it) } ?: "image/jpeg",
                dataSource = DataSource.DISK, // SMB 视为本地网络，用 DISK 策略
            )
        } catch (e: Exception) {
            android.util.Log.w("SmbFetcher", "fetch failed: ${smbResource.url}", e)
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
        override fun create(data: SmbResource, options: Options, imageLoader: coil.ImageLoader): Fetcher {
            val shareManager = SmbShareManager.getInstance(appContext)
            return SmbFetcher(data, shareManager)
        }
    }
}
```

- [ ] **Step 2: 在 AppImageLoaderFactory 中注册 SmbFetcher**

打开 `AppImageLoaderFactory.kt`，在 `components { }` 块末尾添加：

```kotlin
add(SmbFetcher.Factory(app))
```

并添加 import：
```kotlin
import com.smartvision.gallery.lan.smb.SmbFetcher
```

---

## Task 7: ExoPlayer DataSource（SMB 视频流式播放）

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbMediaDataSource.kt`

**背景：** ExoPlayer 的 `DataSource` 接口需要实现 `open()`、`read()`、`close()`。使用 jcifs-ng 的 `SmbRandomAccess` 实现随机访问，支持视频拖拽进度条。

接口映射：
| ExoPlayer DataSource | SmbRandomAccess | 说明 |
|----------------------|-----------------|------|
| `open(DataSpec)` | `SmbFile.openRandomAccess("r")` | 打开连接，验证前 256 字节 |
| `read(buffer, offset, length)` | `read(byte[], int, int)` | 返回实际读取字节数 |
| `close()` | `close()` | 关闭连接 |

- [ ] **Step 1: 创建 SmbMediaDataSource.kt**

```kotlin
package com.smartvision.gallery.lan.smb

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.SmbRandomAccess
import jcifs.SmbResource
import java.io.IOException

/**
 * ExoPlayer 自定义 DataSource，通过 SMB 随机访问提供视频流。
 *
 * 使用方式：
 *   val dataSourceFactory = DataSource.Factory { SmbMediaDataSource(device, path) }
 *   val player = ExoPlayer.Builder(context)
 *       .setDataSourceFactory(dataSourceFactory)
 *       .build()
 *
 * SMB 连接在 [open] 时建立，[read] 时读取数据，[close] 时关闭。
 * 网络中断时抛出 IOException 触发 ExoPlayer 自动重试。
 */
class SmbMediaDataSource(
    private val device: SmbDevice,
    private var path: String,
    private val shareManager: SmbShareManager,
) : DataSource {

    companion object {
        private const val TAG = "SmbMediaDataSource"
        private const val VERIFY_BYTES = 256
    }

    private var randomAccess: SmbRandomAccess? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        val url = device.toSmbUrl(path)
        uri = Uri.parse(url)

        try {
            val ctx = shareManager.getCifsContext(device)
            val resource = ctx.get(url) as SmbResource
            val ra = resource.openRandomAccess("r") as SmbRandomAccess

            // 验证可读性：读取前 256 字节确认连接有效
            val verifyBuf = ByteArray(VERIFY_BYTES)
            val bytesRead = ra.read(verifyBuf, 0, VERIFY_BYTES)
            if (bytesRead <= 0) {
                ra.close()
                throw IOException("SMB: Cannot read from $url — 0 bytes returned")
            }

            // 跳回文件开头
            ra.seek(0)
            randomAccess = ra

            // 返回文件长度（ExoPlayer 需要知道 content length）
            return resource.length()
        } catch (e: Exception) {
            throw IOException("SMB: Failed to open $url", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val ra = randomAccess ?: throw IOException("SMB: Not opened")
        return try {
            val bytesRead = ra.read(buffer, offset, length)
            if (bytesRead < 0) -1 else bytesRead
        } catch (e: Exception) {
            throw IOException("SMB: Read failed at offset ${ra.filePointer}", e)
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            randomAccess?.close()
        } catch (_: Exception) { }
        randomAccess = null
    }

    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
        // 可选的传输监听，暂不实现
    }
}
```

---

## Task 8: 设备发现

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbDiscovery.kt`

**设计：** 主路径为手动添加。辅助发现使用 NetBIOS Name Service (UDP 137) 广播查询，mDNS (UDP 5353) 作为次要路径。**不保证覆盖所有设备**——NetBIOS 仅限 Windows，mDNS 覆盖 macOS/Linux。Android 厂商可能限制 UDP 广播。

- [ ] **Step 1: 创建 SmbDiscovery.kt**

```kotlin
package com.smartvision.gallery.lan.smb

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 局域网 SMB 设备发现。
 *
 * 主路径：手动添加（[ManualDiscovery]）
 * 辅助发现：NetBIOS Name Service (UDP 137) 广播查询
 * 次要发现：mDNS (UDP 5353)
 *
 * 注意：不保证覆盖所有设备。
 * - NetBIOS 仅限 Windows 设备
 * - mDNS 覆盖 macOS/Linux SMB 服务器
 * - Android 厂商/Roaming 可能限制 UDP 广播
 * - 现代网络（IPv6 优先）可能禁用 NetBIOS
 */
class SmbDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "SmbDiscovery"
        private const val NETBIOS_PORT = 137
        private const val MDNS_PORT = 5353
        private const val TIMEOUT_MS = 2000
    }

    data class DiscoveredHost(
        val hostName: String,
        val ipAddress: String,
        val source: String, // "netbios", "mdns", "manual"
    )

    /**
     * 通过 NetBIOS Name Service 查询局域网中的 Windows 设备。
     * 发送 NBT 状态查询广播到 192.168.1.255:137。
     * 返回响应设备列表。
     */
    suspend fun discoverNetbios(): List<DiscoveredHost> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val result = mutableListOf<DiscoveredHost>()
        try {
            val broadcastAddr = getBroadcastAddress() ?: return@withContext result
            val socket = DatagramSocket()
            socket.soTimeout = TIMEOUT_MS
            socket.broadcast = true

            // NBT 状态查询请求包（48 字节）
            val requestData = ByteArray(48).apply {
                // Transaction ID
                this[0] = 0x00; this[1] = 0x00
                // Flags: 0x0010 (standard query, no recursion)
                this[2] = 0x00; this[3] = 0x10
                // Questions: 1
                this[4] = 0x00; this[5] = 0x01
                // Answer RRs: 0
                this[6] = 0x00; this[7] = 0x00
                // Authority RRs: 0
                this[8] = 0x00; this[9] = 0x00
                // Additional RRs: 0
                this[10] = 0x00; this[11] = 0x00
                // Query name: "*<00>" (NetBIOS name type 00 = Workstation)
                // Encoded as 0x20 + 16 bytes of space-padded name + 0x00
                this[12] = 0x20 // length prefix
                // "*" + 15 spaces
                for (i in 0 until 16) {
                    this[13 + i] = 0x20 // space (0x20)
                }
                this[13] = 0x43 // 'C' — NetBIOS wildcard first byte
                this[29] = 0x00 // null terminator for name
                // Type: NB (0x0020) = NetBIOS general name service
                this[30] = 0x00; this[31] = 0x20
                // Class: IN (0x0001)
                this[32] = 0x00; this[33] = 0x01
            }

            val packet = DatagramPacket(requestData, requestData.size, broadcastAddr, NETBIOS_PORT)
            socket.send(packet)

            // 收集响应
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < TIMEOUT_MS) {
                try {
                    val responseBuf = ByteArray(1024)
                    val responsePacket = DatagramPacket(responseBuf, responseBuf.size)
                    socket.receive(responsePacket)

                    val responseData = responsePacket.data
                    if (responseData.size < 60) continue

                    // 解析响应中的 NetBIOS 名称
                    val nameBytes = responseData.copyOfRange(57, 73)
                    val name = String(nameBytes, Charsets.UTF_8).trim()
                    if (name.isNotBlank()) {
                        val ip = responsePacket.address.hostAddress ?: continue
                        result.add(DiscoveredHost(
                            hostName = name,
                            ipAddress = ip,
                            source = "netbios",
                        ))
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.w(TAG, "NetBIOS discovery failed", e)
        }
        result.distinctBy { it.ipAddress }
    }

    private fun getBroadcastAddress(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.interfaceAddresses) {
                    val broadcast = addr.broadcast ?: continue
                    return broadcast
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getBroadcastAddress failed", e)
        }
        return null
    }
}
```

**注意：** NetBIOS 数据包解析实现了基本的 NBT 状态查询。实际实施中如果 NetBIOS 被网络限制，可以跳过此步，手动添加主路径已足够可用。

---

## Task 9: 添加网络位置对话框

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/lan/AddSmbHostDialog.kt`

**UI 设计：** iOS 风格底部弹出面板（BottomSheet），使用 LiquidGlassCard 容器。包含 IP 地址、共享名、用户名（可选）、密码（可选）、记住凭据开关。

- [ ] **Step 1: 创建 AddSmbHostDialog.kt**

```kotlin
package com.smartvision.gallery.ui.lan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.lan.smb.SmbCredentials
import com.smartvision.gallery.lan.smb.SmbDevice
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import com.smartvision.gallery.ui.apple.iOSButton
import com.smartvision.gallery.ui.apple.iOSButtonStyle
import com.smartvision.gallery.ui.apple.iOSToggle

/**
 * 添加网络位置对话框。
 *
 * 使用 ModalBottomSheet + LiquidGlassCard 风格，与现有 iOS 设计语言一致。
 * 用户输入 IP、共享名、可选凭据，点击"连接"后验证并保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSmbHostDialog(
    onDismiss: () -> Unit,
    onConnect: (SmbDevice) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var shareName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberCredentials by remember { mutableStateOf(true) }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        containerColor = Color.Transparent,
    ) {
        LiquidGlassCard(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(20.dp),
        ) {
            Text(
                text = "添加网络位置",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(16.dp))

            // IP 地址
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; errorMessage = null },
                label = { Text("IP 地址 / 主机名") },
                placeholder = { Text("192.168.1.100") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(8.dp))

            // 共享文件夹名
            OutlinedTextField(
                value = shareName,
                onValueChange = { shareName = it; errorMessage = null },
                label = { Text("共享文件夹名") },
                placeholder = { Text("Share") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            // 用户名（可选）
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名（可选，留空=匿名）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            // 密码（可选）
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码（可选）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            // 记住凭据
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "记住凭据",
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                iOSToggle(
                    checked = rememberCredentials,
                    onCheckedChange = { rememberCredentials = it },
                )
            }

            // 错误信息
            if (errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    color = Color(0xFFFF3B30),
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            // 按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                iOSButton(
                    text = "取消",
                    style = iOSButtonStyle.Secondary,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                iOSButton(
                    text = if (isConnecting) "连接中..." else "连接",
                    onClick = {
                        if (host.isBlank() || shareName.isBlank()) {
                            errorMessage = "请填写 IP 地址和共享文件夹名"
                            return@iOSButton
                        }
                        isConnecting = true
                        errorMessage = null
                        val device = SmbDevice(
                            host = host.trim(),
                            shareName = shareName.trim(),
                            displayName = host.trim(),
                            credentials = if (username.isNotBlank()) {
                                SmbCredentials(username.trim(), password)
                            } else null,
                        )
                        onConnect(device)
                    },
                    enabled = !isConnecting,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(16.dp)) // 底部安全区
    }
}
```

---

## Task 10: 网络位置列表

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/lan/SmbHostList.kt`

**UI 设计：** 使用 LiquidGlassCard 作为容器，每个网络位置用一个 iOSListRow 显示。点击展开共享文件夹列表（子目录树）。

- [ ] **Step 1: 创建 SmbHostList.kt**

```kotlin
package com.smartvision.gallery.ui.lan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.lan.smb.SmbDevice
import com.smartvision.gallery.lan.smb.SmbEntry
import com.smartvision.gallery.lan.smb.SmbShareManager
import com.smartvision.gallery.ui.apple.iOSListRow
import com.smartvision.gallery.ui.apple.iOSListSection
import com.smartvision.gallery.ui.apple.iOSRowTrailing
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import kotlinx.coroutines.launch

/**
 * 网络位置列表。
 *
 * 显示已添加的 SMB 网络位置，每个位置可展开查看共享文件夹内容。
 * 使用 LiquidGlassCard + iOSListRow 风格。
 */
@Composable
fun SmbHostList(
    devices: List<SmbDevice>,
    shareManager: SmbShareManager,
    onAddClick: () -> Unit,
    onDeviceClick: (SmbDevice, String) -> Unit, // device, initialPath
    onRemoveDevice: (SmbDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    iOSListSection(
        header = "网络位置",
        footer = "通过 SMB 协议访问 Windows 共享文件夹，需要 Windows 上已配置共享且网络可达",
        content = {
            // 添加按钮
            iOSListRow(
                title = "添加网络位置",
                leading = Icons.Outlined.Add,
                leadingTint = Color(0xFF007AFF),
                trailing = iOSRowTrailing.Chevron,
                onClick = onAddClick,
            )
            com.smartvision.gallery.ui.liquidglass.LiquidGlassCard.Divider()

            if (devices.isEmpty()) {
                Text(
                    text = "暂无网络位置，点击上方添加",
                    fontSize = 14.sp,
                    color = Color(0xFF8E8E93),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            } else {
                devices.forEachIndexed { index, device ->
                    DeviceItem(
                        device = device,
                        shareManager = shareManager,
                        onClick = { onDeviceClick(device, "") },
                        onRemove = { onRemoveDevice(device) },
                    )
                    if (index < devices.size - 1) {
                        com.smartvision.gallery.ui.liquidglass.LiquidGlassCard.Divider()
                    }
                }
            }
        }
    )
}

@Composable
private fun DeviceItem(
    device: SmbDevice,
    shareManager: SmbShareManager,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var isExpanded by remember { mutableStateOf(false) }
    var subDirectories by remember { mutableStateOf<List<SmbEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Computer,
                contentDescription = null,
                tint = Color(0xFF007AFF),
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName.ifBlank { device.host },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = device.host,
                    fontSize = 13.sp,
                    color = Color(0xFF8E8E93),
                )
            }
            IconButton(onClick = { isExpanded = !isExpanded }) {
                Icon(
                    imageVector = if (isExpanded) Icons.Outlined.ExpandLess
                        else Icons.Outlined.ExpandMore,
                    contentDescription = if (isExpanded) "收起" else "展开",
                    tint = Color(0xFF8E8E93),
                )
            }
        }

        // 展开的共享文件夹列表
        if (isExpanded) {
            LaunchedEffect(device) {
                isLoading = true
                subDirectories = shareManager.listFiles(device)
                isLoading = false
            }

            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            } else {
                subDirectories
                    .filter { it.isDirectory }
                    .forEach { dir ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onClick() }
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                tint = Color(0xFFFFCC00),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = dir.name,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
            }
        }
    }
}

/**
 * 空状态提示：无网络位置时显示。
 */
@Composable
fun SmbEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = Color(0xFF8E8E93),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "暂无网络位置",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF8E8E93),
        )
        Text(
            text = "添加 Windows 共享文件夹后即可浏览",
            fontSize = 13.sp,
            color = Color(0xFF8E8E93),
        )
    }
}
```

**注意：** `LiquidGlassCard.Divider()` 方法可能在 LiquidGlassComponents 中不存在。如果编译报错，替换为 `HorizontalDivider(modifier = Modifier.padding(start = 64.dp), thickness = 0.5.dp, color = Color.White.copy(alpha = 0.10f))`（与 SettingsPage 的 RowHairline 一致）。

---

## Task 11: 照片网格

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/lan/SmbMediaGrid.kt`

**UI 设计：** 3 列 LazyVerticalGrid，使用 LiquidGlassCard 样式的缩略图卡片。顶部显示扫描进度（首次加载时），完成后显示分类标签（全部/照片/视频/动图）。

- [ ] **Step 1: 创建 SmbMediaGrid.kt**

```kotlin
package com.smartvision.gallery.ui.lan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.smartvision.gallery.lan.smb.*
import com.smartvision.gallery.ui.apple.iOSSegmentedControl
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import kotlinx.coroutines.launch

/**
 * SMB 共享文件夹照片网格。
 *
 * 功能：
 * 1. 首次加载时显示扫描进度
 * 2. 扫描完成后显示分类标签（全部/照片/视频/动图）
 * 3. 点击缩略图进入全屏查看
 * 4. 下拉刷新
 */
@Composable
fun SmbMediaGrid(
    device: SmbDevice,
    initialPath: String,
    shareManager: SmbShareManager,
    albumIndex: SmbAlbumIndex,
    thumbnailCache: SmbThumbnailCache,
    onPhotoClick: (SmbMediaFile, List<SmbMediaFile>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as com.smartvision.gallery.SmartVisionApp

    // 状态
    var mediaFiles by remember { mutableStateOf<List<SmbMediaFile>>(emptyList()) }
    var isScanning by remember { mutableStateOf(true) }
    var scanProgress by remember { mutableStateOf(ScanProgress()) }
    var selectedFilter by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 扫描
    LaunchedEffect(device, initialPath) {
        isScanning = true
        errorMessage = null
        try {
            mediaFiles = albumIndex.scan(device, initialPath, onProgress = { progress ->
                scanProgress = progress
            })
        } catch (e: Exception) {
            errorMessage = "扫描失败: ${e.message}"
        }
        isScanning = false
    }

    // 过滤后的文件
    val filteredFiles = remember(mediaFiles, selectedFilter) {
        when (selectedFilter) {
            0 -> mediaFiles // 全部
            1 -> mediaFiles.filter { it.type == SmbMediaType.IMAGE }
            2 -> mediaFiles.filter { it.type == SmbMediaType.VIDEO }
            3 -> mediaFiles.filter { it.type == SmbMediaType.GIF }
            else -> mediaFiles
        }
    }

    // 分类统计
    val imageCount = mediaFiles.count { it.type == SmbMediaType.IMAGE }
    val videoCount = mediaFiles.count { it.type == SmbMediaType.VIDEO }
    val gifCount = mediaFiles.count { it.type == SmbMediaType.GIF }

    Column(modifier = modifier.fillMaxSize()) {
        // 扫描进度
        if (isScanning) {
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "正在扫描...",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "已发现 ${scanProgress.foundCount} 个文件",
                            fontSize = 12.sp,
                            color = Color(0xFF8E8E93),
                        )
                    }
                }
            }
        }

        // 错误信息
        if (errorMessage != null) {
            LiquidGlassCard(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF3B30),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = errorMessage!!,
                        fontSize = 13.sp,
                        color = Color(0xFFFF3B30),
                    )
                }
            }
        }

        // 分类标签（扫描完成后显示）
        if (!isScanning && mediaFiles.isNotEmpty()) {
            iOSSegmentedControl(
                options = listOf(
                    "全部(${mediaFiles.size})",
                    "照片($imageCount)",
                    "视频($videoCount)",
                    "动图($gifCount)",
                ),
                selected = selectedFilter,
                onSelect = { selectedFilter = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 空状态
        if (!isScanning && filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Image,
                        contentDescription = null,
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "没有找到媒体文件",
                        fontSize = 16.sp,
                        color = Color(0xFF8E8E93),
                    )
                }
            }
        }

        // 照片网格
        if (!isScanning && filteredFiles.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filteredFiles, key = { it.path }) { file ->
                    SmbThumbnailCard(
                        file = file,
                        device = device,
                        thumbnailCache = thumbnailCache,
                        onClick = { onPhotoClick(file, filteredFiles) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmbThumbnailCard(
    file: SmbMediaFile,
    device: SmbDevice,
    thumbnailCache: SmbThumbnailCache,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext

    // 尝试从缓存加载缩略图
    val cachedBitmap = remember(file.path, device) {
        thumbnailCache.get(device.host, device.shareName, file.path)
    }

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2E)),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (cachedBitmap != null) {
                // 缓存命中：直接显示
                Image(
                    bitmap = cachedBitmap.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 缓存未命中：通过 Coil SMB Fetcher 加载
                // 需要构造 jcifs-ng SmbResource 作为 model
                CoilAsyncSmbThumb(
                    file = file,
                    device = device,
                    contentDescription = file.name,
                )
            }

            // 视频/动图标记
            if (file.type == SmbMediaType.VIDEO || file.type == SmbMediaType.GIF) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .background(
                            Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (file.type == SmbMediaType.VIDEO) "视频" else "GIF",
                        fontSize = 10.sp,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * 通过 Coil SMB Fetcher 加载缩略图。
 * 需要 jcifs-ng 的 SmbResource 作为 Coil 的 model。
 */
@Composable
private fun CoilAsyncSmbThumb(
    file: SmbMediaFile,
    device: SmbDevice,
    contentDescription: String?,
) {
    // 注意：此 Composable 需要在 SmbFetcher 注册到 AppImageLoaderFactory 后才能工作
    // SmbFetcher 的 model 类型是 SmbResource，需要通过 SmbShareManager 获取
    val context = LocalContext.current
    val shareManager = remember { SmbShareManager.getInstance(context) }

    // 暂时使用图标占位，实际实现需要通过 async work 获取 SmbResource 然后传给 Coil
    // 因为 SmbResource 的获取是 suspend 函数，需要在 LaunchedEffect 中执行
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(file.path, device) {
        isLoading = true
        try {
            val ctx = shareManager.getCifsContext(device)
            val url = device.toSmbUrl(file.path)
            val resource = ctx.get(url) as jcifs.SmbResource
            val input = resource.openInputStream()
            val bytes = input.readBytes()
            input.close()
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            android.util.Log.w("SmbThumb", "thumb load failed: ${file.path}", e)
        }
        isLoading = false
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp))
        } else if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = Color(0xFF8E8E93),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
```

**注意：** 上述代码中 `CoilAsyncSmbThumb` 使用了 `LaunchedEffect` + `BitmapFactory.decodeByteArray` 的简单方式，而非通过 Coil Fetcher 管道。这是因为 SmbFetcher 的 `SmbResource` model 类型在 Composable 中构造比较麻烦。实际实施中如果 SmbFetcher 注册成功，可以改为 Coil 的 `AsyncImage(model = smbUrl)` 方式。

---

## Task 12: 全屏查看器

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/ui/lan/SmbPhotoViewer.kt`

**设计：** 全屏查看 SMB 照片，使用 Telephoto SubSamplingImage 支持缩放。加载策略：先通过 SmbFetcher 下载全量文件到本地缓存，完成后交给 Telephoto 显示全分辨率。

- [ ] **Step 1: 创建 SmbPhotoViewer.kt**

```kotlin
package com.smartvision.gallery.ui.lan

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartvision.gallery.lan.smb.*
import com.smartvision.gallery.ui.apple.iOSButton
import com.smartvision.gallery.ui.apple.iOSButtonStyle
import com.smartvision.gallery.ui.liquidglass.LiquidGlassCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * SMB 全屏照片查看器。
 *
 * 加载策略：
 * 1. 立即显示缩略图（通过 Coil SMB Fetcher）
 * 2. 后台下载全量文件到本地缓存
 * 3. 下载完成后切换到 Telephoto SubSamplingImage 显示全分辨率
 * 4. 支持缩放、平移、翻页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbPhotoViewer(
    files: List<SmbMediaFile>,
    initialIndex: Int,
    device: SmbDevice,
    shareManager: SmbShareManager,
    thumbnailCache: SmbThumbnailCache,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { files.size })

    var currentFile by remember { mutableStateOf(files[initialIndex]) }
    var showChrome by remember { mutableStateOf(true) }
    var isDownloaded by remember { mutableStateOf(false) }
    var downloadedPath by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }

    // 监听翻页
    LaunchedEffect(pagerState.currentPage) {
        currentFile = files[pagerState.currentPage]
        isDownloaded = false
        downloadProgress = 0f
        // 下载当前页文件到本地缓存
        scope.launch {
            downloadToCache(context, device, shareManager, currentFile) { progress ->
                downloadProgress = progress
            }?.let { path ->
                downloadedPath = path
                isDownloaded = true
            }
        }
    }

    // 初始下载
    LaunchedEffect(Unit) {
        downloadToCache(context, device, shareManager, currentFile) { progress ->
            downloadProgress = progress
        }?.let { path ->
            downloadedPath = path
            isDownloaded = true
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val file = files[page]
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (isDownloaded && downloadedPath != null && page == pagerState.currentPage) {
                    // 已下载到本地：使用 Telephoto SubSamplingImage
                    // 注意：此处使用标准 AsyncImage + Coil（从本地文件加载）
                    // 如需 Telephoto 的 SubSamplingImage，需要导入 telephoto
                    coil.compose.AsyncImage(
                        model = File(downloadedPath!!),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 下载中：显示缩略图 + 进度
                    val cachedBitmap = thumbnailCache.get(device.host, device.shareName, file.path)
                    if (cachedBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = cachedBitmap.asImageBitmap(),
                            contentDescription = file.name,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // 下载进度
                    if (downloadProgress > 0f && downloadProgress < 1f) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                                .align(Alignment.BottomCenter),
                        )
                    }
                    if (downloadProgress <= 0f) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }
        }

        // Chrome（顶部栏 + 底部操作栏）
        if (showChrome) {
            // 顶部半透明栏
            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = currentFile.name,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // 底部操作栏
            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            scope.launch {
                                val destDir = File(context.getExternalFilesDir(null), "smb_downloads")
                                destDir.mkdirs()
                                val dest = File(destDir, currentFile.name)
                                shareManager.copyToLocal(device, currentFile.path, dest)
                            }
                        }) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = "下载", tint = Color.White)
                        }
                        Text("下载", fontSize = 11.sp, color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            scope.launch {
                                val ok = shareManager.deleteFile(device, currentFile.path)
                                if (ok) onBack()
                            }
                        }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = Color(0xFFFF3B30))
                        }
                        Text("删除", fontSize = 11.sp, color = Color(0xFFFF3B30))
                    }
                }
            }
        }

        // 点击切换 Chrome 显示
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = true) { showChrome = !showChrome },
        )
    }
}

/**
 * 下载 SMB 文件到本地缓存目录。
 * 返回下载后的本地文件路径。
 */
private suspend fun downloadToCache(
    context: android.content.Context,
    device: SmbDevice,
    shareManager: SmbShareManager,
    file: SmbMediaFile,
    onProgress: (Float) -> Unit = {},
): String? = withContext(Dispatchers.IO) {
    val cacheDir = File(context.cacheDir, "smb_viewer_cache").apply { mkdirs() }
    val localFile = File(cacheDir, "${System.currentTimeMillis()}_${file.name}")
    try {
        // 获取文件大小
        val url = device.toSmbUrl(file.path)
        val ctx = shareManager.getCifsContext(device)
        val resource = ctx.get(url) as jcifs.SmbResource
        val totalSize = resource.length()
        val input = resource.openInputStream()

        localFile.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalSize > 0) {
                    onProgress((totalRead.toFloat() / totalSize).coerceIn(0f, 1f))
                }
            }
        }
        input.close()
        localFile.absolutePath
    } catch (e: Exception) {
        localFile.delete()
        android.util.Log.w("SmbViewer", "downloadToCache failed: ${file.path}", e)
        null
    }
}
```

---

## Task 13: 重构 LanSharePage

**Files:**
- Modify: `app/src/main/java/com/smartvision/gallery/ui/lan/LanSharePage.kt`

**设计：** 在现有 LanSharePage 中增加 SMB 区域。保持原有本机服务器 + 已发现 Android 设备不变，新增"网络位置"区域。

- [ ] **Step 1: 重构 LanSharePage.kt**

在现有 `LanSharePage` 的 `LazyColumn` 中，在"本机服务器"卡片之后、"已发现设备"之前插入 SMB 区域：

```kotlin
// ---- 网络位置 (SMB) ----
item {
    val smbDevices = remember { mutableStateListOf<SmbDevice>() }
    val shareManager = remember { SmbShareManager.getInstance(context) }
    val albumIndex = remember { SmbAlbumIndex(shareManager) }
    val thumbnailCache = remember { SmbThumbnailCache(context) }

    // 加载已保存的网络位置
    LaunchedEffect(Unit) {
        // 从 DataStore 加载已保存的 SMB 设备列表
        // （暂不使用持久化，先硬编码演示）
        // TODO: 后续通过 DataStore 持久化
    }

    SmbHostList(
        devices = smbDevices.toList(),
        shareManager = shareManager,
        onAddClick = {
            scope.launch {
                // 显示添加对话框
                showAddDialog = true
            }
        },
        onDeviceClick = { device, path ->
            // 进入网络位置的照片网格
            selectedSmbDevice = device
            selectedSmbPath = path
            isSmbMediaGrid = true
        },
        onRemoveDevice = { device ->
            smbDevices.remove(device)
        },
    )
}

// ---- 添加网络位置对话框 ----
if (showAddDialog) {
    AddSmbHostDialog(
        onDismiss = { showAddDialog = false },
        onConnect = { device ->
            // 验证连接
            scope.launch {
                try {
                    val entries = shareManager.listFiles(device)
                    // 连接成功，保存设备
                    smbDevices.add(device)
                    showAddDialog = false
                } catch (e: Exception) {
                    // 连接失败，显示错误
                    // （对话框内部处理）
                }
            }
        },
    )
}
```

同时，当 `isSmbMediaGrid` 为 true 时，切换显示 SmbMediaGrid 而不是 LazyColumn：

```kotlin
// 在 LanSharePage 的顶层 Column 中
if (isSmbMediaGrid && selectedSmbDevice != null) {
    SmbMediaGrid(
        device = selectedSmbDevice!!,
        initialPath = selectedSmbPath,
        shareManager = shareManager,
        albumIndex = albumIndex,
        thumbnailCache = thumbnailCache,
        onPhotoClick = { file, allFiles ->
            isSmbPhotoViewer = true
            smbPhotoIndex = allFiles.indexOf(file)
            smbPhotoFiles = allFiles
        },
        onBack = { isSmbMediaGrid = false },
    )
} else if (isSmbPhotoViewer && smbPhotoFiles.isNotEmpty()) {
    SmbPhotoViewer(
        files = smbPhotoFiles,
        initialIndex = smbPhotoIndex,
        device = selectedSmbDevice!!,
        shareManager = shareManager,
        thumbnailCache = thumbnailCache,
        onBack = { isSmbPhotoViewer = false },
    )
} else {
    // 原有的 LazyColumn 内容
    LazyColumn(...) { ... }
}
```

---

## Task 14: 凭据持久化

**Files:**
- Create: `app/src/main/java/com/smartvision/gallery/lan/smb/SmbCredentialStore.kt`

**设计：** 使用 `EncryptedSharedPreferences` 加密存储凭据。每个 SMB 设备按 ID 存储用户名/密码。

- [ ] **Step 1: 创建 SmbCredentialStore.kt**

```kotlin
package com.smartvision.gallery.lan.smb

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
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
```

---

## 实施顺序

1. **Task 1** — 依赖配置（先验证 jcifs-ng Maven 坐标）
2. **Task 2** — 数据模型（SmbDevice, SmbCredentials）
3. **Task 3** — SMB 连接管理器（SmbShareManager + CIFSContext 缓存）
4. **Task 4** — 缩略图缓存（SmbThumbnailCache）
5. **Task 5** — 共享文件夹扫描（SmbAlbumIndex）
6. **Task 6** — Coil Fetcher（SmbFetcher + AppImageLoaderFactory 注册）
7. **Task 7** — ExoPlayer DataSource（SmbMediaDataSource）
8. **Task 8** — 设备发现（SmbDiscovery）
9. **Task 9** — 添加网络位置对话框（AddSmbHostDialog）
10. **Task 10** — 网络位置列表（SmbHostList）
11. **Task 11** — 照片网格（SmbMediaGrid）
12. **Task 12** — 全屏查看器（SmbPhotoViewer）
13. **Task 13** — 重构 LanSharePage（集成 SMB 区域）
14. **Task 14** — 凭据持久化（SmbCredentialStore）- 可最后做，先用内存存储

## 自检清单

- [ ] 每个 Task 的代码是否完整、无 TODO/TBD
- [ ] 类型和方法签名是否跨 Task 一致（如 `SmbShareManager.listFiles` 返回 `List<SmbEntry>`）
- [ ] Coil Fetcher 的 model 类型（`SmbResource`）与 AppImageLoaderFactory 注册一致
- [ ] ExoPlayer DataSource 的接口映射是否正确
- [ ] UI 组件是否全部使用 LiquidGlassCard / iOSListRow / iOSListSection
- [ ] 所有 SMB 操作是否在 `Dispatchers.IO` 执行
- [ ] 凭据加密存储是否使用 `EncryptedSharedPreferences`