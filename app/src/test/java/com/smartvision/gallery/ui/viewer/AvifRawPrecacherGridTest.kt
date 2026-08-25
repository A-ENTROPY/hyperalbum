package com.smartvision.gallery.ui.viewer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.RandomAccessFile

/**
 * AvifRawPrecacher CellWriter 单测：
 * - CellWriter 把一个 cell Bitmap 平铺写入 SVRAW 的 (gr,gc) 区域
 * - bpc=1 全格 + 右/底边缘 padding 裁切（只写 output 内像素，外区域留 0）
 * - bpc=2 每通道 2B LE，低 16 位存值
 *
 * Robolectric 提供 android.graphics.Bitmap。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AvifRawPrecacherGridTest {

    private fun readIntLE(f: RandomAccessFile, off: Long): Int {
        f.seek(off)
        var v = 0
        for (i in 0 until 4) v = v or ((f.read() and 0xFF) shl (8 * i))
        return v
    }

    private fun readPixel(f: RandomAccessFile, dataBase: Long, totalW: Int, bpc: Int, x: Int, y: Int): IntArray {
        val stride = totalW * 4 * bpc
        val rowOff = dataBase + y.toLong() * stride
        f.seek(rowOff + x.toLong() * 4 * bpc)
        return if (bpc == 1) {
            intArrayOf(f.read() and 0xFF, f.read() and 0xFF, f.read() and 0xFF, f.read() and 0xFF)
        } else {
            fun c16(): Int = (f.read() and 0xFF) or ((f.read() and 0xFF) shl 8)
            intArrayOf(c16(), c16(), c16(), c16())
        }
    }

    @Test
    fun cellWriterBpc1TilesFullCell() {
        // 1×2 grid: outW=12 outH=8 cellW=12 cellH=8（无 padding，单 cell 满铺）
        val outW = 12; val outH = 8; val cw = 12; val ch = 8
        val tmp = java.io.File.createTempFile("cw1", ".raw").apply { deleteOnExit() }
        // cell 全红 R=0xA0
        val cell = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until ch) for (x in 0 until cw) cell.setPixel(x, y, 0xFFA00000.toInt())
        AvifCellWriter.writeHeader(tmp, outW, outH, bpc = 1)
        AvifCellWriter.writeCell(tmp, cell, gr = 0, gc = 0, cellW = cw, cellH = ch,
            outW = outW, outH = outH, bpc = 1)
        RandomAccessFile(tmp, "r").use { f ->
            assertThat(readIntLE(f, 4)).isEqualTo(outW)
            assertThat(readIntLE(f, 8)).isEqualTo(outH)
            assertThat(readIntLE(f, 12)).isEqualTo(4)  // channels
            assertThat(readIntLE(f, 16)).isEqualTo(1)  // bpc
            val p = readPixel(f, 20, outW, 1, 3, 2)
            assertThat(p[0]).isEqualTo(0xA0) // R
            assertThat(p[3]).isEqualTo(0xFF) // A
        }
    }

    @Test
    fun cellWriterBpc1TrimsRightEdgePadding() {
        // 2×1 grid: outW=10 outH=8, cellW=6 cellH=8 → 右 cell (0,1) 只写 10-6=4 列
        val outW = 10; val outH = 8; val cw = 6; val ch = 8
        val tmp = java.io.File.createTempFile("cw2", ".raw").apply { deleteOnExit() }
        val cell0 = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until ch) for (x in 0 until cw) cell0.setPixel(x, y, 0xFF0000FF.toInt()) // 蓝
        val cell1 = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until ch) for (x in 0 until cw) cell1.setPixel(x, y, 0xFF00FF00.toInt()) // 绿
        AvifCellWriter.writeHeader(tmp, outW, outH, bpc = 1)
        AvifCellWriter.writeCell(tmp, cell0, 0, 0, cw, ch, outW, outH, 1)
        AvifCellWriter.writeCell(tmp, cell1, 0, 1, cw, ch, outW, outH, 1)
        RandomAccessFile(tmp, "r").use { f ->
            // x=5 (cell0 最右, 蓝色) → G=0
            assertThat(readPixel(f, 20, outW, 1, 5, 0)[1]).isEqualTo(0x00)
            // x=6 (右 cell 起点) = 绿
            assertThat(readPixel(f, 20, outW, 1, 6, 0)[1]).isEqualTo(0xFF)
            // x=9 (右 cell output 最右列) = 绿
            assertThat(readPixel(f, 20, outW, 1, 9, 0)[1]).isEqualTo(0xFF)
        }
    }

    @Test
    fun cellWriterBpc1TrimsBottomEdgePadding() {
        // 1×2 grid vertical: outW=8 outH=6, cellW=8 cellH=4 → 底 cell (1,0) 只写 6-4=2 行
        val outW = 8; val outH = 6; val cw = 8; val ch = 4
        val tmp = java.io.File.createTempFile("cw3", ".raw").apply { deleteOnExit() }
        val cell1 = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
        for (y in 0 until ch) for (x in 0 until cw) cell1.setPixel(x, y, 0xFFFF0000.toInt()) // 红
        AvifCellWriter.writeHeader(tmp, outW, outH, bpc = 1)
        AvifCellWriter.writeCell(tmp, cell1, 1, 0, cw, ch, outW, outH, 1)
        RandomAccessFile(tmp, "r").use { f ->
            // y=5 (output 最底行, 底 cell 第 1 行) = 红
            assertThat(readPixel(f, 20, outW, 1, 0, 5)[0]).isEqualTo(0xFF)
        }
    }

    @Test
    fun cellWriterBpc2Stores16bitLE() {
        // 用 10-bit 值填 cell，bpc=2 存 2B LE
        val outW = 4; val outH = 4; val cw = 4; val ch = 4
        val tmp = java.io.File.createTempFile("cw4", ".raw").apply { deleteOnExit() }
        val cell = android.graphics.Bitmap.createBitmap(cw, ch, android.graphics.Bitmap.Config.ARGB_8888)
        // 全像素 R=0xFF(8bit)，bpc=2 应存 0x00FF (LE: FF 00)
        for (y in 0 until ch) for (x in 0 until cw) cell.setPixel(x, y, 0xFFFF0000.toInt())
        AvifCellWriter.writeHeader(tmp, outW, outH, bpc = 2)
        AvifCellWriter.writeCell(tmp, cell, 0, 0, cw, ch, outW, outH, bpc = 2)
        RandomAccessFile(tmp, "r").use { f ->
            assertThat(readIntLE(f, 16)).isEqualTo(2) // bpc
            val p = readPixel(f, 20, outW, 2, 0, 0)
            assertThat(p[0]).isEqualTo(0xFF) // R = 0x00FF
            assertThat(p[3]).isEqualTo(0xFF) // A
        }
    }
}
