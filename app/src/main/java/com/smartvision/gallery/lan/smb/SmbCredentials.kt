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