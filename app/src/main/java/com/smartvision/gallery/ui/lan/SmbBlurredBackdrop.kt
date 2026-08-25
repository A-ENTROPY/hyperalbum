package com.smartvision.gallery.ui.lan

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SMB 版 letterbox 模糊快照——[rememberBlurredPhotoBackdrop] 的 SMB 对应物。
 *
 * 从 [jcifs.SmbResource] 直接读字节流 + [BitmapFactory] 解码小图（inSampleSize
 * 按 targetPx 降采样），不走 Coil SmbFetcher（流式全量下载对 letterbox 太重）。
 * 解码出的 bitmap 供 viewer Z=0 letterbox 层 [Modifier.blur] 渲染色场。
 *
 * 与 [rememberBlurredPhotoBackdrop] 同：保留旧帧直到新帧就绪，page 切换
 * 平滑过渡（旧→新）而非闪到黑底。
 *
 * @param smbResource  当前页 SmbResource（null 时返回 null）
 * @param targetPx     目标短边像素（默认 256，填满整屏 letterbox 足够）
 */
@Composable
fun rememberSmbBlurredBackdrop(
    smbResource: jcifs.SmbResource?,
    targetPx: Int = 256,
): Bitmap? {
    var bitmap by remember(smbResource, targetPx) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(smbResource, targetPx) {
        // 保留旧帧：page 切换时 letterbox 渐变到新图而非闪黑
        if (smbResource == null) return@LaunchedEffect
        loadSmallSmbBitmap(smbResource, targetPx)?.let { bitmap = it }
    }

    return bitmap
}

/**
 * 从 SmbResource 解码降采样小图。
 *
 * 两遍 BitmapFactory：先 inJustDecodeBounds 算 inSampleSize，再真解码。
 * inSampleSize 选 2 的幂使短边 ≥ [targetPx]。ARGB_8888 保证可被 blur 读取。
 */
private suspend fun loadSmallSmbBitmap(
    resource: jcifs.SmbResource,
    targetPx: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        // 先读 bounds
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resource.openInputStream().use { input ->
            val bytes = input.readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
            // 复用同一份 bytes 解码真图，避免二次网络读
            val sample = calcInSampleSize(boundsOpts.outWidth, boundsOpts.outHeight, targetPx)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        }
    } catch (t: Throwable) {
        null
    }
}

/** 选 2 的幂使解码后短边 ≥ targetPx。 */
private fun calcInSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    val shortEdge = minOf(width, height)
    while (shortEdge / sample > targetPx * 2) sample *= 2
    return sample
}
