package com.smartvision.gallery.ui.viewer

import java.io.ByteArrayOutputStream

/**
 * Wraps a single cell's AV1 bytes into a minimal single-item AVIF container that the Android
 * system BitmapFactory can decode (its format sniffer hard-requires a `ftyp` box).
 *
 * Layout (validated against spike cell0_wrap3.avif, spec §2.1):
 *   ftyp(avif,mif1,miaf,MA1B) + meta{hdlr=pict, pitm, iinf(infe av01), iprp{ipco[av1C,ispe]+ipma}, iloc v1}
 *   + mdat(cell AV1 bytes). iloc extent_offset = absolute mdat data offset = len(ftyp)+len(meta)+8.
 *
 * av1C/ispe are the original box bytes copied verbatim from the grid container's ipco.
 */
object AvifCellDecoder {

    /** @return minimal AVIF wrapper bytes containing [av1Bytes] as the single item. */
    fun wrap(av1Bytes: ByteArray, av1CBox: ByteArray, ispeBox: ByteArray): ByteArray {
        val ftyp = box("ftyp", "avif".toByteArray() + intBE(0) + "avifmif1miafMA1B".toByteArray())
        val hdlr = box("hdlr", intBE(0) + intBE(0) + "pict".toByteArray() + ByteArray(12) + byteArrayOf(0))
        val pitm = box("pitm", intBE(0) + shortBE(1)) // primary item = 1
        val iinf = box("iinf", intBE(0) + shortBE(1) + infeBox())
        val ipco = box("ipco", av1CBox + ispeBox)
        val ipma = box("ipma", ipmaBody())
        val iprp = box("iprp", ipco + ipma)
        // iloc box width is fixed (all fields fixed-width) → meta size is offset/length-invariant.
        // Bootstrap offset with a placeholder, then rebuild with the real mdat data offset.
        val iloc0 = ilocBox(0, 0)
        val meta0 = box("meta", intBE(0) + hdlr + pitm + iinf + iprp + iloc0)
        val mdatDataOffset = ftyp.size + meta0.size + 8 // 8B mdat box header
        val iloc = ilocBox(mdatDataOffset, av1Bytes.size)
        val meta = box("meta", intBE(0) + hdlr + pitm + iinf + iprp + iloc)
        return ftyp + meta + box("mdat", av1Bytes)
    }

    // ---------- sub-box builders ----------

    private fun infeBox(): ByteArray {
        // infe v2: ver/flags(4) + item_ID u16 + protection u16 + item_type(4) + name\0
        return box("infe", intBE(2) + shortBE(1) + shortBE(0) + "av01".toByteArray() + byteArrayOf(0))
    }

    private fun ipmaBody(): ByteArray {
        // ipma v0: FullBox + entry_count u32 + per entry: item_ID u16 + assoc_count u8 + assoc[]
        // assoc: 0x81 = essential(0x80) | ipco idx 1 (av1C); 0x02 = ipco idx 2 (ispe)
        return intBE(0) + intBE(1) + shortBE(1) + byteArrayOf(2, 0x81.toByte(), 0x02)
    }

    private fun ilocBox(mdatDataOffset: Int, extentLength: Int): ByteArray {
        // iloc v1 sizes 4/4/0/0: ver/flags(4) + 0x44 0x00 + item_count u16 + entry:
        //   item_ID u16 + construction_method u16 + data_ref u16 (base_offset_size=0 → 无 base)
        //   + extent_count u16 + extent_offset u32 + extent_length u32
        val body = ByteArrayOutputStream()
        body.write(byteArrayOf(1, 0, 0, 0, 0x44, 0))
        body.write(shortBE(1))
        body.write(shortBE(1))
        body.write(shortBE(0)) // construction_method
        body.write(shortBE(0)) // data_reference_index
        body.write(shortBE(1)) // extent_count
        body.write(intBE(mdatDataOffset))
        body.write(intBE(extentLength))
        return box("iloc", body.toByteArray())
    }

    private fun box(type: String, payload: ByteArray): ByteArray =
        intBE(payload.size + 8) + type.toByteArray(Charsets.US_ASCII) + payload

    private fun intBE(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()
    )
    private fun shortBE(v: Int): ByteArray = byteArrayOf((v ushr 8).toByte(), v.toByte())
}
