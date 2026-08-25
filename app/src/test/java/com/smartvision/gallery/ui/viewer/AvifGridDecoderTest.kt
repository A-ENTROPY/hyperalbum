package com.smartvision.gallery.ui.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * AvifGridDecoder 单测：合成最小 grid AVIF（3×4, out 8160×6050, cell 2048×2048,
 * pixi 3×10bit, colr PQ, iloc v1），断言 GridInfo 各字段。另覆盖 iloc v0 布局。
 */
class AvifGridDecoderTest {

    private fun intBE(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun shortBE(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(intBE(payload.size + 8))
        out.write(type.toByteArray())
        out.write(payload)
        return out.toByteArray()
    }

    /** 构建最小 grid AVIF。cell 数/尺寸由参数定，cell AV1 字节为全 0xAA（decoder 只关心 offset/length）。 */
    private fun buildGrid(
        rows: Int, cols: Int, outW: Int, outH: Int, cellW: Int, cellH: Int,
        pixiBits: List<Int> = listOf(8, 8, 8), // nclx transfer 16=PQ
        ilocVer: Int = 1,
        colrTransfer: Int = 16,
        hasColr: Boolean = true,
    ): ByteArray {
        val cellCount = rows * cols
        val ftyp = box("ftyp", "avif".toByteArray() + intBE(0) + "avifmif1miafMA1B".toByteArray())
        val hdlr = box("hdlr", intBE(0) + "pict".toByteArray() + ByteArray(13))
        val pitm = box("pitm", intBE(0) + shortBE(1)) // primary = grid item 1

        // iinf: item 1 = grid, items 2..1+cellCount = av01
        val infeGrid = box("infe", intBE(2) + shortBE(1) + shortBE(0) + "grid".toByteArray() + byteArrayOf(0x47, 0))
        val infeCells = (0 until cellCount).map { i ->
            box("infe", intBE(2) + shortBE(2 + i) + shortBE(0) + "av01".toByteArray() + byteArrayOf(0x43, 0))
        }
        val iinf = box("iinf", intBE(0) + shortBE(cellCount + 1) + infeGrid + infeCells.fold(ByteArray(0)) { a, b -> a + b })

        // ipco props in order. colr optional; ipma indices shift when absent.
        val av1C = box("av1C", byteArrayOf(0x81.toByte(), 0x3f, 0x40, 0x00))
        val ispe = box("ispe", intBE(0) + intBE(cellW) + intBE(cellH))
        val pixi = box("pixi", intBE(0) + byteArrayOf(pixiBits.size.toByte()) + pixiBits.map { it.toByte() }.toByteArray())
        val propList = if (hasColr) {
            // colr nclx: primaries(u16) + transfer(u16) + matrix(u16)
            val colr = box("colr", "nclx".toByteArray() + shortBE(1) + shortBE(colrTransfer) + shortBE(0))
            listOf(av1C, ispe, pixi, colr)
        } else {
            listOf(av1C, ispe, pixi)
        }
        // iref v0: from=1 (grid), count=cellCount, to=2..1+cellCount (row-major)
        val dimgBody = ByteArrayOutputStream()
        dimgBody.write(shortBE(1))
        dimgBody.write(shortBE(cellCount))
        for (i in 0 until cellCount) dimgBody.write(shortBE(2 + i))
        val iref = box("iref", intBE(0) + box("dimg", dimgBody.toByteArray()))
        val ipco = box("ipco", propList.fold(ByteArray(0)) { a, b -> a + b })
        val assocBytes = if (hasColr) byteArrayOf(4, 0x81.toByte(), 0x02, 0x03, 0x04)
                         else byteArrayOf(3, 0x81.toByte(), 0x02, 0x03)
        val ipmaBody = ByteArrayOutputStream()
        ipmaBody.write(intBE(0)) // version+flags
        ipmaBody.write(intBE(cellCount)) // entry count = 每个 cell 一条
        for (i in 0 until cellCount) {
            ipmaBody.write(shortBE(2 + i)) // item id = cell item
            ipmaBody.write(assocBytes)
        }
        val ipma = box("ipma", ipmaBody.toByteArray())
        val iprp = box("iprp", ipco + ipma)

        // grid payload (item 1 data) = ImageGrid: ver0 flags0 rows-1 cols-1 outW outH
        val gridPayload = byteArrayOf(0, 0, (rows - 1).toByte(), (cols - 1).toByte()) + shortBE(outW) + shortBE(outH)

        // mdat data: [gridPayload][cell0 0x100][cell1 0x100]...
        val mdatData = ByteArrayOutputStream()
        mdatData.write(gridPayload)
        repeat(cellCount) { mdatData.write(ByteArray(0x100) { -0x56 }) } // 0xAA

        // Patch iloc extents: rebuild with real offsets (meta2 now contains iloc2 + iref)
        // meta2 size depends on iloc2 which depends on gridOff which depends on meta2 size →
        // bootstrap: compute meta2 size first with a dummy gridOff, then recompute.
        val dummyIloc = buildIloc(ilocVer, cellCount, 0, gridPayload.size, 0)
        val meta2Dummy = box("meta", intBE(0) + hdlr + pitm + iinf + iprp + dummyIloc + iref)
        val gridOff = ftyp.size + meta2Dummy.size + 8
        val cellStart = gridOff + gridPayload.size
        val iloc2 = buildIloc(ilocVer, cellCount, gridOff, gridPayload.size, cellStart)
        val meta2 = box("meta", intBE(0) + hdlr + pitm + iinf + iprp + iloc2 + iref)

        val out = ByteArrayOutputStream()
        out.write(ftyp)
        out.write(meta2)
        out.write(box("mdat", mdatData.toByteArray()))
        return out.toByteArray()
    }

    private fun buildIloc(ver: Int, cellCount: Int, gridOff: Int, gridLen: Int, cellStart: Int): ByteArray {
        val body = ByteArrayOutputStream()
        // ver + flags + sizes (offset_size=4 length_size=4 base_offset_size=0 index_size=0)
        body.write(byteArrayOf(ver.toByte(), 0, 0, 0, 0x44, 0))
        body.write(shortBE(cellCount + 1))
        body.write(shortBE(1))
        if (ver >= 1) body.write(shortBE(0))
        body.write(shortBE(0)) // data_reference_index; base_offset_size=0 → 无 base 字段
        body.write(shortBE(1)) // extent_count
        body.write(intBE(gridOff)); body.write(intBE(gridLen))
        for (i in 0 until cellCount) {
            body.write(shortBE(2 + i))
            if (ver >= 1) body.write(shortBE(0))
            body.write(shortBE(0)) // data_reference_index
            body.write(shortBE(1)) // extent_count
            body.write(intBE(cellStart + i * 0x100)); body.write(intBE(0x100))
        }
        return box("iloc", body.toByteArray())
    }

    // ---- tests ----

    @Test
    fun parsesGridInfo() {
        val bytes = buildGrid(3, 4, 8160, 6050, 2048, 2048, pixiBits = listOf(10, 10, 10), colrTransfer = 16)
        val g = AvifGridDecoder.parse(ByteArrayInputStream(bytes))
        assertThat(g).isNotNull()
        assertThat(g!!.rows).isEqualTo(3)
        assertThat(g.cols).isEqualTo(4)
        assertThat(g.cellW).isEqualTo(2048)
        assertThat(g.cellH).isEqualTo(2048)
        assertThat(g.outW).isEqualTo(8160)
        assertThat(g.outH).isEqualTo(6050)
        assertThat(g.cells).hasSize(12)
        assertThat(g.cells[0].length).isEqualTo(0x100)
        // 各 cell offset 严格递增且连续
        for (i in 1 until 12) {
            assertThat(g.cells[i].fileOffset).isEqualTo(g.cells[i - 1].fileOffset + g.cells[i - 1].length)
        }
    }

    @Test
    fun detectsHdr10bitFromPixiAndColr() {
        val bytes = buildGrid(1, 1, 2048, 2048, 2048, 2048, pixiBits = listOf(10, 10, 10), colrTransfer = 16)
        val g = AvifGridDecoder.parse(ByteArrayInputStream(bytes))!!
        assertThat(g.bpc).isEqualTo(2)
    }

    @Test
    fun detectsSdr8bit() {
        val bytes = buildGrid(1, 1, 2048, 2048, 2048, 2048, pixiBits = listOf(8, 8, 8), colrTransfer = 1)
        val g = AvifGridDecoder.parse(ByteArrayInputStream(bytes))!!
        assertThat(g.bpc).isEqualTo(1)
    }

    @Test
    fun missingColrFallsBackTo8bit() {
        val bytes = buildGrid(1, 1, 2048, 2048, 2048, 2048, hasColr = false)
        val g = AvifGridDecoder.parse(ByteArrayInputStream(bytes))!!
        assertThat(g.bpc).isEqualTo(1)
    }

    @Test
    fun parsesIlocV0() {
        // iloc v0 无 construction_method 字段，布局不同——必须按 version 分支
        val bytes = buildGrid(1, 1, 1024, 1024, 1024, 1024, ilocVer = 0)
        val g = AvifGridDecoder.parse(ByteArrayInputStream(bytes))
        assertThat(g).isNotNull()
        assertThat(g!!.cells).hasSize(1)
        assertThat(g.cells[0].length).isEqualTo(0x100)
    }

    @Test
    fun returnsNullOnMalformed() {
        // 截断文件
        val bytes = buildGrid(2, 2, 4096, 4096, 2048, 2048).copyOf(100)
        val g = AvifGridDecoder.parse(ByteArrayInputStream(bytes))
        assertThat(g).isNull()
    }
}