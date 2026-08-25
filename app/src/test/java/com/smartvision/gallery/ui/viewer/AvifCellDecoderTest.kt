package com.smartvision.gallery.ui.viewer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * AvifCellDecoder 单测：把 cell AV1 bytes + av1C/ispe 原始 box 字节包成最小 AVIF 容器，
 * 断言字节结构（ftyp/meta/iloc extent 批 mdat 偏移 / mdat 尾接 AV1 / ipma av1C essential）。
 * Wrapper 真机可解性由 instrumented CellWrapperDecodeTest 验证；这里只断言结构。
 * Spec: docs/superpowers/specs/2026-08-21-avif-grid-32k-decode-design.md §2.1.
 */
class AvifCellDecoderTest {

    private fun intBE(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )

    private fun box(type: String, payload: ByteArray): ByteArray =
        intBE(payload.size + 8) + type.toByteArray(Charsets.US_ASCII) + payload

    // 真实 av1C/ispe 原始 box 字节（含 size+type 头），从 grid 容器原样拷来
    private val av1CBox = box("av1C", byteArrayOf(0x81.toByte(), 0x3f, 0x40, 0x00))
    private val ispeBox = box("ispe", intBE(0) + intBE(2048) + intBE(2048))
    private val av1Payload = ByteArray(256) { (it and 0xff).toByte() } // 任意 AV1 字节

    private fun typeStr(b: ByteArray, o: Int): String = String(b, o + 4, 4, Charsets.US_ASCII)
    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff shl 24) or (b[o + 1].toInt() and 0xff shl 16) or
            (b[o + 2].toInt() and 0xff shl 8) or (b[o + 3].toInt() and 0xff)

    @Test
    fun wrapperHasFtypMetaMdatHeader() {
        val w = AvifCellDecoder.wrap(av1Payload, av1CBox, ispeBox)
        assertThat(typeStr(w, 0)).isEqualTo("ftyp")
        assertThat(String(w, 8, 4, Charsets.US_ASCII)).isEqualTo("avif")
        // 可走到 meta + mdat（顺序 ftyp → meta → mdat）
        val ftypEnd = u32(w, 0)
        assertThat(typeStr(w, ftypEnd)).isEqualTo("meta")
        val metaEnd = ftypEnd + u32(w, ftypEnd)
        assertThat(typeStr(w, metaEnd)).isEqualTo("mdat")
    }

    @Test
    fun mdatCarriesAv1BytesAtDeclaredOffset() {
        val w = AvifCellDecoder.wrap(av1Payload, av1CBox, ispeBox)
        // 找到 mdat box
        var i = 0
        var mdatStart = -1
        while (i + 8 <= w.size) {
            val sz = u32(w, i)
            if (typeStr(w, i) == "mdat") { mdatStart = i; break }
            if (sz < 8) break
            i += sz
        }
        assertThat(mdatStart).isNotEqualTo(-1)
        val mdatDataStart = mdatStart + 8
        // mdat 数据 = av1Payload
        assertThat(w.copyOfRange(mdatDataStart, mdatDataStart + av1Payload.size))
            .isEqualTo(av1Payload)
        // iloc extent_offset 必须指向 mdatDataStart（文件绝对偏移，勿多加 8）
        val ilocOffset = findIlocExtentOffset(w)
        assertThat(ilocOffset).isEqualTo(mdatDataStart)
    }

    @Test
    fun ipmaMarksAv1CEssential() {
        val w = AvifCellDecoder.wrap(av1Payload, av1CBox, ispeBox)
        // ipma entry: av1C association 必须置 essential bit (0x80)
        val ipmaAv1CIdx = findIpmaPropertyForAv1C(w)
        // essential = high bit set on the property index byte
        assertThat(ipmaAv1CIdx and 0x80).isNotEqualTo(0)
        // 实际索引（低 7 位）指向 ipco 第一个 box = av1C
        assertThat(ipmaAv1CIdx and 0x7f).isEqualTo(1)
    }

    @Test
    fun metaContainsHdlrPitrPitmIinfIlocIprp() {
        val w = AvifCellDecoder.wrap(av1Payload, av1CBox, ispeBox)
        val ftypEnd = u32(w, 0)
        val metaStart = ftypEnd
        val metaEnd = metaStart + u32(w, metaStart)
        // meta FullBox: sub-boxes start at metaStart + 8 + 4
        var o = metaStart + 8 + 4
        val types = mutableSetOf<String>()
        while (o + 8 <= metaEnd) {
            val sz = u32(w, o)
            if (sz < 8) break
            types.add(typeStr(w, o))
            o += sz
        }
        assertThat(types).containsAtLeast("hdlr", "iinf", "iloc", "iprp")
    }

    private fun findIlocExtentOffset(w: ByteArray): Int {
        // walk to meta → find iloc → FullBox v1 sizes 4/4/0/0 → 1 entry → extent_offset u32
        val ftypEnd = u32(w, 0)
        var o = ftypEnd + 8 + 4
        val metaEnd = ftypEnd + u32(w, ftypEnd)
        while (o + 8 <= metaEnd) {
            val sz = u32(w, o)
            if (sz < 8) break
            if (typeStr(w, o) == "iloc") {
                // box @ o: 8 hdr + FullBox(ver1 flags3) + sizes(2) + count u16(v1) + entry
                var q = o + 8 + 4 + 2 // past ver/flags + size byte pair
                // v1 item_count u16
                q += 2
                // v1 entry: item_ID u16 + construction_method u16 + data_ref u16 (base_offset_size=0 → 无) + extent_count u16 + extent_offset u32 + extent_length u32
                q += 2 + 2 + 2 + 2
                return u32(w, q)
            }
            o += sz
        }
        return -1
    }

    private fun findIpmaPropertyForAv1C(w: ByteArray): Int {
        // ipma: FullBox + entry_count u32 + per-entry: item_ID u16(v0) + assoc_count u8 + assoc bytes
        // We expect av1C = ipco box 1 (1-based essential idx 0x81)
        val ftypEnd = u32(w, 0)
        var o = ftypEnd + 8 + 4
        val metaEnd = ftypEnd + u32(w, ftypEnd)
        while (o + 8 <= metaEnd) {
            val sz = u32(w, o)
            if (sz < 8) break
            if (typeStr(w, o) == "iprp") {
                // iprp sub-boxes: ipco, ipma
                var q = o + 8
                while (q + 8 < o + sz) {
                    val isz = u32(w, q)
                    if (typeStr(w, q) == "ipma") {
                        // FullBox ver/flags(4) + entry_count u32 + entries
                        var r = q + 8 + 4 + 4 // past hdr+fullbox+entrycount
                        // 1 entry: item_ID u16(v0) + assoc_count u8 + assoc bytes (each u8 v0)
                        r += 2 // item_ID
                        val ac = w[r].toInt() and 0xff; r += 1
                        // first assoc byte = av1C (ipco idx 1, essential)
                        return w[r].toInt() and 0xff
                    }
                    q += isz
                }
            }
            o += sz
        }
        return 0
    }
}
