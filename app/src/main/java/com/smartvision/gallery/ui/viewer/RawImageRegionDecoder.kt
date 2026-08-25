package com.smartvision.gallery.ui.viewer

import android.graphics.Bitmap
import com.smartvision.gallery.util.AppLog
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import me.saket.telephoto.subsamplingimage.SubSamplingImageSource
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Telephoto [SubSamplingImageSource] that reads tiles from a raw RGBA/RGB pixel
 * file produced by [jxl_to_raw] native decoder. Preserves the original pixel
 * data (including alpha) without any quality loss from JPEG/PNG encoding.
 *
 * File format (20-byte header + pixel data):
 *   [4B] magic "SVRA" (first 4 chars of "SVRAW" — native jxl_to_raw memcpy limit)
 *   [4B] width (LE int32)
 *   [4B] height (LE int32)
 *   [4B] num_channels (3 or 4, LE int32)
 *   [4B] bytes_per_channel (1 for now, LE int32)
 *   [N]  pixels row-major, interleaved RGB/RGBA, top-down
 */
class RawImageSource(private val rawFile: File) : SubSamplingImageSource {

    override val preview: ImageBitmap? = null

    override suspend fun decoder(): ImageRegionDecoder.Factory {
        return ImageRegionDecoder.Factory { _ ->
            RawImageRegionDecoder(rawFile)
        }
    }

    override fun close() {
        // No resources to close.
    }
}

/**
 * [ImageRegionDecoder] that reads pixel regions from a raw file produced by
 * [jxl_to_raw] native decoder. Converts RGBA→ARGB_8888 Bitmap on the fly.
 */
class RawImageRegionDecoder(private val rawFile: File) : ImageRegionDecoder {

    private var header: RawHeader? = null

    data class RawHeader(
        val width: Int,
        val height: Int,
        val channels: Int,
        val bytesPerChannel: Int,
    ) {
        val headerSize: Int = 20
        val pixelDataOffset: Long get() = headerSize.toLong()
    }

    override val imageSize: IntSize
        get() {
            val h = readHeader()
            return IntSize(h.width, h.height)
        }

    override suspend fun decodeRegion(
        region: IntRect,
        sampleSize: Int
    ): ImageRegionDecoder.DecodeResult {
        val h = readHeader()
        val bpp = h.channels * h.bytesPerChannel   // 3/4 channels × 1 (8-bit) or 2 (10/12-bit) bytes

        // Clamp region to image bounds
        val left = region.left.coerceIn(0, h.width - 1)
        val top = region.top.coerceIn(0, h.height - 1)
        val right = (region.right).coerceIn(left + 1, h.width)
        val bottom = (region.bottom).coerceIn(top + 1, h.height)
        val rw = right - left
        val rh = bottom - top

        val ss = if (sampleSize < 1) 1 else sampleSize
        val outW = (rw + ss - 1) / ss
        val outH = (rh + ss - 1) / ss

        val pixels = IntArray(outW * outH)
        val rowBytes = h.width.toLong() * bpp
        val baseOffset = h.pixelDataOffset

        RandomAccessFile(rawFile, "r").use { raf ->
            try {
                if (ss == 1) {
                    // Fast path: no downsampling, read rows directly
                    val rowBuf = ByteArray(rw * bpp)
                    for (outY in 0 until outH) {
                        val srcY = top + outY
                        val fileOffset = baseOffset + srcY * rowBytes + left.toLong() * bpp
                        raf.seek(fileOffset)
                        raf.readFully(rowBuf)
                        rowToArgb(rowBuf, 0, rw, h, pixels, outY * outW)
                    }
                } else {
                    // Downsampling: read full region, decimate
                    val fullRowBuf = ByteArray(rw * bpp)
                    for (outY in 0 until outH) {
                        val srcY = top + outY * ss
                        val fileOffset = baseOffset + srcY * rowBytes + left.toLong() * bpp
                        raf.seek(fileOffset)
                        raf.readFully(fullRowBuf)
                        for (outX in 0 until outW) {
                            val srcX = outX * ss
                            pixels[outY * outW + outX] = toArgb(fullRowBuf, srcX * bpp, h)
                        }
                    }
                }
            } catch (e: EOFException) {
                // Partial/truncated raw (shouldn't happen post rawValid, but disk
                // races / concurrent cache eviction can). Log with coordinates so the
                // failure is diagnosable instead of a silent black tile.
                AppLog.e(TAG, "decodeRegion EOF region=$region ss=$ss at row outY", e)
                throw IOException("Truncated raw reading region $region", e)
            }
        }

        val bitmap = Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
        val painter = BitmapPainter(bitmap.asImageBitmap())
        // bpc=2 → source is 10/12-bit HDR content (rendered down to 8-bit ARGB_8888 here).
        return ImageRegionDecoder.DecodeResult(painter, hasUltraHdrContent = h.bytesPerChannel == 2)
    }

    // Convert a row of interleaved RGB/RGBA bytes to ARGB ints.
    // bpc=1: 1B/channel. bpc=2: 2B LE/channel, low 16 bits hold 10/12-bit value, >>2 → 8-bit.
    private fun rowToArgb(
        buf: ByteArray, start: Int, count: Int, h: RawHeader,
        out: IntArray, outOffset: Int
    ) {
        val bpp = h.channels * h.bytesPerChannel
        for (i in 0 until count) {
            out[outOffset + i] = toArgb(buf, start + i * bpp, h)
        }
    }

    /** Read one pixel at [pi] (pixel byte offset) → packed ARGB_8888 int. */
    private fun toArgb(buf: ByteArray, pi: Int, h: RawHeader): Int {
        val ch = h.channels
        if (h.bytesPerChannel == 2) {
            // 2B LE per channel; low 16 bits hold 10/12-bit value, downscale via >>2
            fun c16(o: Int): Int = (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8)
            fun cnv(v16: Int): Int = (v16 ushr 2) and 0xFF
            val r = cnv(c16(pi))
            val g = cnv(c16(pi + 2))
            val b = cnv(c16(pi + 4))
            val a = if (ch == 4) cnv(c16(pi + 6)) else 0xFF
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        // bpc=1
        val r = buf[pi].toInt() and 0xFF
        val g = buf[pi + 1].toInt() and 0xFF
        val b = buf[pi + 2].toInt() and 0xFF
        val a = if (ch == 4) buf[pi + 3].toInt() and 0xFF else 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    override fun close() {
        // No resources to close.
    }

    private fun readHeader(): RawHeader {
        header?.let { return it }

        RandomAccessFile(rawFile, "r").use { raf ->
            // Magic is 4 bytes: native jxl_to_raw writes "SVRAW"[0..3] = "SVRA"
            // (memcpy(...,4)), so we match the first 4 chars, not the 5-char literal.
            val magic = ByteArray(4)
            raf.readFully(magic)
            val magicStr = String(magic, Charsets.US_ASCII)
            if (magicStr != "SVRA") {
                AppLog.e(TAG, "readHeader: bad magic '$magicStr' len=${rawFile.length()}")
                throw IOException("Invalid raw file: bad magic '$magicStr'")
            }

            fun readInt32(): Int {
                val b = ByteArray(4)
                raf.readFully(b)
                return (b[0].toInt() and 0xFF) or
                        ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16) or
                        ((b[3].toInt() and 0xFF) shl 24)
            }

            val w = readInt32()
            val h = readInt32()
            val ch = readInt32()
            val bpc = readInt32()

            if (ch !in 3..4 || bpc !in 1..2) {
                AppLog.e(TAG, "readHeader: bad dims w=$w h=$h ch=$ch bpc=$bpc len=${rawFile.length()}")
                throw IOException("Invalid raw header: w=$w h=$h ch=$ch bpc=$bpc")
            }
            val want = 20L + w.toLong() * h.toLong() * ch.toLong() * bpc.toLong()
            if (rawFile.length() < want) {
                AppLog.e(TAG, "readHeader: truncated want=$want have=${rawFile.length()} w=$w h=$h ch=$ch bpc=$bpc")
                throw IOException("Truncated raw: have ${rawFile.length()} of $want")
            }

            val hdr = RawHeader(
                width = w,
                height = h,
                channels = ch,
                bytesPerChannel = bpc,
            )
            header = hdr
            return hdr
        }
    }

    private companion object {
        private const val TAG = "RawImageRegionDecoder"
    }
}