package com.smartvision.gallery.ui.viewer

import android.graphics.Bitmap
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes grid cell bitmaps into a tiled SVRAW file (spec §4).
 *
 * SVRAW layout: 20B header (magic "SVRA" + w/h/channels/bpc, LE int32) + flat
 * RGBA rows, row stride = totalW × 4 × bpc, no padding.
 *
 * Edge cells (right column / bottom row) carry padding pixels in their AV1
 * frame; only the pixels inside the output bounds are written, so padding never
 * leaks into the output region (sparse holes read as 0 = transparent black).
 *
 * Batch-write strategy (spec review #1): one seek per cell row, not per output
 * row. For a single-column grid rows are contiguous and one seek would cover the
 * whole cell, but a single seek per cell-row bounds seeks to cells × cellH.
 */
object AvifCellWriter {

    /** Write the 20-byte SVRAW header (channels always 4). */
    fun writeHeader(file: File, outW: Int, outH: Int, bpc: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("SVRA".toByteArray(Charsets.US_ASCII), 0, 4)
            writeIntLE(raf, outW)
            writeIntLE(raf, outH)
            writeIntLE(raf, 4) // channels
            writeIntLE(raf, bpc)
        }
    }

    /**
     * Write [cell] (a decoded grid cell bitmap) into its (gr,gc) tile region of
     * [file]. [cellW]/[cellH] are the grid cell dimensions; edge cells trim to
     * the output bounds. [cell] may be ARGB_8888 (bpc=1) or RGBA_1010102 (bpc=2).
     */
    fun writeCell(
        file: File, cell: Bitmap, gr: Int, gc: Int,
        cellW: Int, cellH: Int, outW: Int, outH: Int, bpc: Int,
    ) {
        val cols = minOf(cellW, outW - gc * cellW) // right-column edge: trim width
        val rows = minOf(cellH, outH - gr * cellH) // bottom-row edge: trim height
        if (cols <= 0 || rows <= 0) return
        val bpp = 4 * bpc
        val stride = outW * bpp
        val rowBuf = ByteArray(cols * bpp)
        val row = IntArray(cols)

        RandomAccessFile(file, "rw").use { raf ->
            val baseRowStart = 20L + gr.toLong() * cellH * stride + gc.toLong() * cellW * bpp
            for (r in 0 until rows) {
                cell.getPixels(row, 0, cols, 0, r, cols, 1)
                encodeRow(row, cols, cell.config, bpc, rowBuf)
                raf.seek(baseRowStart + r.toLong() * stride)
                raf.write(rowBuf)
            }
        }
    }

    private fun encodeRow(row: IntArray, cols: Int, config: Bitmap.Config?, bpc: Int, out: ByteArray) {
        val is1010102 = config == Bitmap.Config.RGBA_1010102
        var bi = 0
        for (i in 0 until cols) {
            val c = row[i]
            val r: Int; val g: Int; val b: Int; val a: Int
            if (is1010102) {
                r = c and 0x3FF
                g = (c ushr 10) and 0x3FF
                b = (c ushr 20) and 0x3FF
                a = ((c ushr 30) and 0x3) * 85 // 2-bit alpha → 0..255 scale
            } else {
                r = (c ushr 16) and 0xFF
                g = (c ushr 8) and 0xFF
                b = c and 0xFF
                a = (c ushr 24) and 0xFF
            }
            if (bpc == 1) {
                out[bi++] = r.toByte(); out[bi++] = g.toByte(); out[bi++] = b.toByte(); out[bi++] = a.toByte()
            } else {
                write16(out, bi, r); bi += 2
                write16(out, bi, g); bi += 2
                write16(out, bi, b); bi += 2
                write16(out, bi, a); bi += 2
            }
        }
    }

    /** 2B LE: low 16 bits hold the value (10/12-bit HDR channel). */
    private fun write16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF)
        raf.write((v ushr 8) and 0xFF)
        raf.write((v ushr 16) and 0xFF)
        raf.write((v ushr 24) and 0xFF)
    }
}
