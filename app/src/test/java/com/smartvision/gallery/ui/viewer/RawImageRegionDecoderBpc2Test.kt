package com.smartvision.gallery.ui.viewer

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.unit.IntRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * RawImageRegionDecoder bpc=2 支持：10-bit HDR SVRAW 渲染降级为 8-bit ARGB_8888。
 * 2B/通道 LE 低 16 位存 10-bit 值；渲染时 >>2 降到 8-bit（1023→255）。
 * Robolectric 提供 Android Bitmap 实现。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RawImageRegionDecoderBpc2Test {

    private fun writeSvarw(w: Int, h: Int, bpc: Int, pixelData: ByteArray): File {
        val f = File.createTempFile("bpc2test", ".raw").apply { deleteOnExit() }
        val header = ByteArray(20)
        "SVRA".toByteArray().copyInto(header, 0)
        fun putLE(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v ushr 8) and 0xFF).toByte()
            header[off + 2] = ((v ushr 16) and 0xFF).toByte()
            header[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        putLE(4, w); putLE(8, h); putLE(12, 4); putLE(16, bpc)
        f.writeBytes(header + pixelData)
        return f
    }

    private fun pixelOf(res: me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder.DecodeResult, x: Int, y: Int): Int {
        val bmpField = res::class.java.getDeclaredField("painter")
        bmpField.isAccessible = true
        val painter = bmpField.get(res) as BitmapPainter
        // BitmapPainter.image is private with no getter — reflect it.
        val imgField = BitmapPainter::class.java.getDeclaredField("image")
        imgField.isAccessible = true
        val img = imgField.get(painter) as androidx.compose.ui.graphics.ImageBitmap
        return img.asAndroidBitmap().getPixel(x, y)
    }

    @Test
    fun bpc2Downscales10bitTo8bit() = runBlocking {
        fun ch16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
        val px = ByteArray(2 * 2 * 8) // 4 pixels × 8B (2x2 image, RGB)
        // (0,0) R=1023 G=0 B=0 A=1023
        ch16(1023).copyInto(px, 0); ch16(0).copyInto(px, 2); ch16(0).copyInto(px, 4); ch16(1023).copyInto(px, 6)
        // (1,0) R=0 G=512 B=0 A=1023
        ch16(0).copyInto(px, 8); ch16(512).copyInto(px, 10); ch16(0).copyInto(px, 12); ch16(1023).copyInto(px, 14)
        // (0,1) all 0 A=1023
        ch16(0).copyInto(px, 16); ch16(0).copyInto(px, 18); ch16(0).copyInto(px, 20); ch16(1023).copyInto(px, 22)
        // (1,1) R=G=B=1023 A=1023
        ch16(1023).copyInto(px, 24); ch16(1023).copyInto(px, 26); ch16(1023).copyInto(px, 28); ch16(1023).copyInto(px, 30)
        val raw = writeSvarw(2, 2, 2, px)
        val dec = RawImageRegionDecoder(raw)
        val res = dec.decodeRegion(IntRect(0, 0, 2, 2), 1)
        // 1023 >> 2 = 255
        val p00 = pixelOf(res, 0, 0)
        assertThat((p00 shr 16) and 0xFF).isEqualTo(255) // R
        assertThat((p00 ushr 24) and 0xFF).isEqualTo(255) // A
        // 512 >> 2 = 128
        val p10 = pixelOf(res, 1, 0)
        assertThat((p10 shr 8) and 0xFF).isEqualTo(128)
        val p11 = pixelOf(res, 1, 1)
        assertThat((p11 shr 16) and 0xFF).isEqualTo(255)
        assertThat((p11 shr 8) and 0xFF).isEqualTo(255)
    }

    @Test
    fun bpc1UnchangedAfterBpc2Support() = runBlocking {
        val px = ByteArray(1 * 1 * 4)
        px[0] = 0x40.toByte(); px[1] = 0x80.toByte(); px[2] = 0xC0.toByte(); px[3] = 0xFF.toByte()
        val raw = writeSvarw(1, 1, 1, px)
        val dec = RawImageRegionDecoder(raw)
        val res = dec.decodeRegion(IntRect(0, 0, 1, 1), 1)
        val p = pixelOf(res, 0, 0)
        assertThat((p shr 16) and 0xFF).isEqualTo(0x40)
        assertThat((p shr 8) and 0xFF).isEqualTo(0x80)
        assertThat(p and 0xFF).isEqualTo(0xC0)
    }
}
