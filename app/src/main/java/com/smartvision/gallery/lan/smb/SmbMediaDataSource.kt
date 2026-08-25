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