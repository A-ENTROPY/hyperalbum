package com.smartvision.gallery.privacy

import android.content.Context
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.data.repo.MediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Privacy vault facade.
 *
 * 隐藏策略（V2 — 安全版）：仅维护 app 内部 DB flag `isHidden = true`。
 *
 * **不再修改 MediaStore 行（IS_PENDING=1）。**
 *
 * Why IS_PENDING is wrong:
 *  - `MediaStore.MediaColumns.IS_PENDING` 的官方语义是"应用写入文件未完成"，
 *    NOT a hiding mechanism。Google 明确禁止第三方 app 用 IS_PENDING 隐藏已存在媒体。
 *  - 在 scoped storage (Android 10+) 下，`contentResolver.update(uri, IS_PENDING, 1)`
 *    对非自有媒体抛 SecurityException/RecoverableSecurityException，常被吞掉
 *    → 状态不一致。
 *  - 部分 OEM ROM (ColorOS/MIUI) 在 IS_PENDING 转换时会重置 MediaStore 的 DATA
 *    列指针，再次 IS_PENDING=0 时找不到原文件路径 → 用户感知 = "照片损毁"。
 *
 * 安全方案：**原文件绝对不动**。仅设 DB flag：
 *  - app timeline / album 缩略图列表过滤掉 isHidden=true 的项 → 用户感知"已隐藏"
 *  - 系统相册仍可见该文件（这是 Android 安全模型限制，第三方 app 无法屏蔽）
 *  - 取消隐藏：仅 DB flag 设为 false → 文件始终安全
 *
 * 这是用"安全感稍弱但永远不会损毁文件"换"绝对不会损毁"——是正确的取舍。
 * 真正的"系统相册也看不到"需要 EncryptedPrivacyVault 把文件读到 .vault 加密 +
 * 调用 MediaStore.createDeleteRequest 让用户系统级确认删除原文件 (V3 计划)。
 */
class PrivacyVault(
    private val repository: MediaRepository,
    private val context: Context,
) {

    fun observeHiddenItems(): Flow<List<MediaItem>> = repository.observeHidden()

    suspend fun hide(item: MediaItem) = withContext(Dispatchers.IO) {
        // 仅设 DB flag — 原文件不动，永远不损毁
        repository.setHidden(item.uri, true)
    }

    suspend fun unhide(item: MediaItem) = withContext(Dispatchers.IO) {
        repository.setHidden(item.uri, false)
    }

    suspend fun isHidden(item: MediaItem): Boolean = item.isHidden

    companion object {
        private const val TAG = "PrivacyVault"

        @Volatile var requireBiometric: Boolean = false

        fun newInstance(context: Context): PrivacyVault {
            val app = com.smartvision.gallery.SmartVisionApp.from(context)
            return PrivacyVault(app.mediaRepository, context.applicationContext)
        }
    }
}
