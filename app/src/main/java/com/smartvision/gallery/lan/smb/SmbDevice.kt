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