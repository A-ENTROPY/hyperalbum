package com.smartvision.gallery.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.decoder.format.MediaFormat
import com.smartvision.gallery.util.AppLog

/**
 * Export pipeline.
 *
 *  * [suggestFormat] — given a target use case, returns the most compatible format for
 *    sharing. For example, sending an AVIF over WeChat might auto-downgrade to JPEG.
 *  * [exportAs] — encode [bitmap] to the requested format. Real encoding (AVIF/JXL)
 *    delegates to the native bridge in later versions; JPEG/PNG/WebP are produced by
 *    [Bitmap.compress].
 */
class ExportPipeline(private val context: Context) {

    enum class UseCase { SHARE_GENERIC, SHARE_ON_SOCIAL, ARCHIVE, EDIT_FRIENDLY }

    fun suggestFormat(
        item: MediaItem,
        use: UseCase,
        preferNextGen: Boolean = false
    ): MediaFormat = when (use) {
        UseCase.SHARE_GENERIC ->
            when {
                // 动图 (WEBP_ANIMATED/GIF/HEIC_SEQ) 不退化 — 分享/归档保留动画,
                // 否则压缩成静态首帧丢失全部动画帧.
                item.format.isAnimated -> item.format
                preferNextGen && (item.format in SAFELY_SHAREABLE || item.format.isNextGen) -> item.format
                item.format in SAFELY_SHAREABLE -> item.format
                else -> MediaFormat.JPEG
            }
        UseCase.SHARE_ON_SOCIAL -> if (item.format.isAnimated) item.format else MediaFormat.JPEG
        UseCase.ARCHIVE ->
            when {
                item.format.isAnimated -> item.format
                preferNextGen && (item.format.isLossless || item.format.isNextGen) -> item.format
                item.format.isLossless -> item.format
                else -> MediaFormat.PNG
            }
        UseCase.EDIT_FRIENDLY -> if (item.format in EDIT_FRIENDLY) item.format else MediaFormat.PNG
    }

    /**
     * Encode [bitmap] as the given format. Returns null on failure.
     *
     * V1.0 supports JPEG/PNG/WebP via [Bitmap.compress]; AVIF/JXL fall back to PNG.
     */
    fun exportAs(
        bitmap: Bitmap,
        target: MediaFormat,
        quality: Int = 92,
        outFileName: String = "export_${System.currentTimeMillis()}"
    ): Uri? {
        // animated 目标无法用 Bitmap.compress 保留动画帧 (只压首帧 → 丢动画).
        // 一律回退 PNG: 单一 bitmap 只能表达静态, PNG 无损不误导. 调用方若需
        // 保留动图应直接用源文件 URI, 不走 bitmap 重编码.
        if (target.isAnimated) {
            AppLog.w(TAG, "exportAs($target) animated target can't preserve frames via Bitmap.compress — falling back to PNG")
            return exportAs(bitmap, MediaFormat.PNG, quality, outFileName)
        }
        val format = if (target in COMPRESS_NATIVE) target else MediaFormat.PNG
        val (androidFormat, ext) = when (format) {
            MediaFormat.JPEG -> Bitmap.CompressFormat.JPEG to "jpg"
            MediaFormat.PNG -> Bitmap.CompressFormat.PNG to "png"
            MediaFormat.WEBP_STATIC -> Bitmap.CompressFormat.WEBP to "webp"
            else -> Bitmap.CompressFormat.PNG to "png"
        }
        val outFile = java.io.File(context.cacheDir, "$outFileName.$ext")
        val ok = try {
            java.io.FileOutputStream(outFile).use { bitmap.compress(androidFormat, quality, it) }
        } catch (t: Throwable) {
            AppLog.e(TAG, "exportAs($target) failed", t)
            false
        }
        // Bitmap.compress 返回 false = 编码器失败 (OOM/格式不支持). 若忽略,
        // 会向分享器提交一个空/损坏的临时文件. 失败必须删文件并返回 null.
        if (!ok) {
            if (outFile.exists()) outFile.delete()
            return null
        }
        return try {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "exportAs($target) getUriForFile failed", t)
            if (outFile.exists()) outFile.delete()
            null
        }
    }

    private companion object {
        const val TAG = "ExportPipeline"

        val SAFELY_SHAREABLE = setOf(
            MediaFormat.JPEG, MediaFormat.PNG, MediaFormat.WEBP_STATIC, MediaFormat.HEIC
        )
        val EDIT_FRIENDLY = setOf(
            MediaFormat.PNG, MediaFormat.TIFF
        )
        val COMPRESS_NATIVE = setOf(
            MediaFormat.JPEG, MediaFormat.PNG, MediaFormat.WEBP_STATIC
        )
    }
}