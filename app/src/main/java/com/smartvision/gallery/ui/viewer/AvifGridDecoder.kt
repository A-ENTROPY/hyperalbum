package com.smartvision.gallery.ui.viewer

import java.io.InputStream

/**
 * Parses an AVIF grid container (ISOBMFF) into [GridInfo]: grid layout + per-cell AV1 byte
 * locations, so [AvifRawPrecacher] can decode each cell independently and tile it into SVRAW.
 *
 * Pure Kotlin, no Android dependency. Layout validated against the avif2.avif spike
 * (3×4 grid, 8160×6050, 2048×2048 cells, YUV444p10 PQ). All multi-byte fields big-endian.
 * Spec: `docs/superpowers/specs/2026-08-21-avif-grid-32k-decode-design.md`.
 */
object AvifGridDecoder {

    data class GridInfo(
        val rows: Int, val cols: Int,
        val cellW: Int, val cellH: Int,
        val outW: Int, val outH: Int,
        val bpc: Int,                // 1=8-bit SDR, 2=10/12-bit HDR
        val cells: List<CellMeta>,   // row-major; cells[0]=top-left
    )

    data class CellMeta(
        val itemId: Int,
        val fileOffset: Long,        // absolute offset of cell AV1 bytes in the file
        val length: Long,
        val av1CBytes: ByteArray,    // raw av1C box bytes (copied into wrapper)
        val ispeBytes: ByteArray,    // raw ispe box bytes
    )

    /** Box: absolute start offset, type, and end (exclusive). payloadStart = start + hdr. */
    private class Box(val start: Int, val type: ByteArray, val end: Int) {
        val payloadStart: Int get() = start + 8 // largesize (size==1) not used by avif grid meta
    }

    /** Returns [GridInfo] iff [input] is a grid AVIF; null otherwise. Buffers whole stream. */
    fun parse(input: InputStream): GridInfo? = try {
        parseBytes(input.readBytes())
    } catch (t: Throwable) { null }

    private fun parseBytes(data: ByteArray): GridInfo? {
        var isAvif = false
        var metaBox: Box? = null
        for (b in walkBoxes(data, 0, data.size)) {
            when (typeStr(b.type)) {
                "ftyp" -> {
                    val major = String(data, b.start + 8, 4, Charsets.US_ASCII)
                    if (major == "avif" || major == "avis") isAvif = true
                }
                "meta" -> metaBox = b
            }
        }
        if (!isAvif || metaBox == null) return null
        val meta = metaBox!!
        // meta is FullBox: payload[0..4) = ver+flags; sub-boxes start at payloadStart+4
        val subStart = meta.payloadStart + 4
        val sub = walkBoxes(data, subStart, meta.end).associateBy { typeStr(it.type) }

        val pitm = sub["pitm"] ?: return null
        val primary = pitmItemId(data, pitm.start)

        val iloc = sub["iloc"] ?: return null
        val ilocs = readIloc(data, iloc.start)
        if (primary !in ilocs) return null

        val iinf = sub["iinf"]
        val itemTypes = iinf?.let { readIinf(data, it.start) } ?: emptyMap()
        if (itemTypes[primary] != "grid") return null

        val (gOff, gLen) = ilocs[primary]!!.first()
        val gp = slice(data, gOff, gLen) ?: return null
        val grid = parseGridPayload(gp) ?: return null

        val iref = sub["iref"] ?: return null
        val dimg = readDimg(data, iref.start)
        val cellItemIds = dimg?.get(primary) ?: return null
        if (cellItemIds.size != grid.rows * grid.cols) return null

        val iprp = sub["iprp"] ?: return null
        val propBoxes = readIpcoBoxes(data, iprp.start)
        val assoc = readIpma(data, iprp.start)

        val cells = ArrayList<CellMeta>(cellItemIds.size)
        var bpc = 1
        for (cid in cellItemIds) {
            val exts = ilocs[cid] ?: return null
            if (exts.isEmpty()) return null
            val (co, cl) = exts.first()
            val idxs = assoc[cid] ?: return null
            var av1C: ByteArray? = null
            var ispe: ByteArray? = null
            var hdrBit = false
            var transfer = 1
            for (oneBased in idxs) {
                val ri = oneBased - 1
                if (ri !in propBoxes.indices) continue
                val pb = propBoxes[ri]
                when (typeStr(pb.type)) {
                    "av1C" -> av1C = sliceBox(data, pb)
                    "ispe" -> ispe = sliceBox(data, pb)
                    "pixi" -> hdrBit = hdrBit || readPixiBits(data, pb).any { it >= 10 }
                    "colr" -> readColrTransfer(data, pb)?.let { transfer = it }
                }
            }
            if (av1C == null || ispe == null) return null
            if (hdrBit && (transfer == 16 || transfer == 18)) bpc = 2
            cells.add(CellMeta(cid, co, cl, av1C, ispe))
        }
        val (cellW, cellH) = readIspeDims(cells.first().ispeBytes) ?: return null
        return GridInfo(grid.rows, grid.cols, cellW, cellH, grid.outW, grid.outH, bpc, cells)
    }

    private class Grid(val rows: Int, val cols: Int, val outW: Int, val outH: Int)
    private fun parseGridPayload(g: ByteArray): Grid? {
        if (g.size < 8) return null
        if (g[0].toInt() != 0) return null
        val flags = g[1].toInt() and 0xff
        val rows = (g[2].toInt() and 0xff) + 1
        val cols = (g[3].toInt() and 0xff) + 1
        val outW: Int; val outH: Int
        if (flags and 1 == 0) {
            outW = u16(g, 4); outH = u16(g, 6)
        } else {
            if (g.size < 12) return null
            outW = u32(g, 4); outH = u32(g, 8)
        }
        return Grid(rows, cols, outW, outH)
    }

    // ---------- box walker & header ----------
    private fun walkBoxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var i = start
        while (i + 8 <= end) {
            val size = u32(data, i)
            val type = data.copyOfRange(i + 4, i + 8)
            val boxEnd = when {
                size == 1 -> {
                    if (i + 16 > end) break
                    (i + u64(data, i + 8)).toInt().coerceAtMost(end)
                }
                size == 0 -> end
                else -> if (size < 8) break else i + size
            }
            if (boxEnd <= i || boxEnd > end) break
            out.add(Box(i, type, boxEnd))
            i = boxEnd
        }
        return out
    }

    // ---------- pitm ----------
    private fun pitmItemId(data: ByteArray, boxStart: Int): Int {
        val ver = data[boxStart + 8].toInt() and 0xff
        return if (ver == 0) u16(data, boxStart + 12) else u32(data, boxStart + 12)
    }

    // ---------- iloc ----------
    private fun readIloc(data: ByteArray, boxStart: Int): Map<Int, List<Pair<Long, Long>>> {
        val ver = data[boxStart + 8].toInt() and 0xff
        val offSize = (data[boxStart + 12].toInt() and 0xff) ushr 4
        val lenSize = (data[boxStart + 12].toInt() and 0xff) and 0xf
        val boSize = (data[boxStart + 13].toInt() and 0xff) ushr 4
        val idxSize = (data[boxStart + 13].toInt() and 0xff) and 0xf
        var o = boxStart + 14
        val count = if (ver == 2) u32(data, o).also { o += 4 } else u16(data, o).also { o += 2 }
        val map = HashMap<Int, ArrayList<Pair<Long, Long>>>()
        repeat(count) {
            val iid = if (ver == 2) u32(data, o).also { o += 4 } else u16(data, o).also { o += 2 }
            if (ver >= 1) o += 2 // construction_method
            o += 2 // data_reference_index
            val base = if (boSize > 0) readN(data, o, boSize).also { o += boSize } else 0L
            val extCount = u16(data, o); o += 2
            val exts = ArrayList<Pair<Long, Long>>()
            repeat(extCount) {
                if (ver >= 1 && idxSize > 0) o += idxSize
                val eoff = if (offSize > 0) readN(data, o, offSize).also { o += offSize } else 0L
                val elen = if (lenSize > 0) readN(data, o, lenSize).also { o += lenSize } else 0L
                exts.add((base + eoff) to elen)
            }
            map.getOrPut(iid) { ArrayList() }.addAll(exts)
        }
        return map
    }

    private fun readN(b: ByteArray, o: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = (v shl 8) or (b[o + k].toLong() and 0xff)
        return v
    }

    // ---------- iinf ----------
    private fun readIinf(data: ByteArray, boxStart: Int): Map<Int, String> {
        // FullBox: ver(1)+flags(3) + entry_count(u16) then infe sub-boxes
        val end = boxEnd(data, boxStart)
        var o = boxStart + 8 + 4 + 2
        val types = HashMap<Int, String>()
        while (o + 8 <= end) {
            val sz = u32(data, o)
            if (sz < 8) break
            if (typeStr(data.copyOfRange(o + 4, o + 8)) != "infe") { o += sz; continue }
            val ps = o + 8
            val ver = data[ps].toInt() and 0xff
            var q = ps + 4
            // infe: ver 0/1 → u16 item_ID; ver 2/3 → u32. Item IDs here are small.
            // We use u16 for v0/v1 and u32 for v2/v3, per ISO/IEC 14496-12.
            val iid = if (ver >= 2) u32(data, q).also { q += 4 } else u16(data, q).also { q += 2 }
            q += 2 // protection_index
            val itype = String(data, q, 4, Charsets.US_ASCII)
            types[iid] = itype
            o += sz
        }
        return types
    }

    // ---------- iref / dimg ----------
    private fun readDimg(data: ByteArray, irefStart: Int): Map<Int, List<Int>> {
        val ver = data[irefStart + 8].toInt() and 0xff
        val end = boxEnd(data, irefStart)
        var o = irefStart + 8 + 4
        val map = HashMap<Int, List<Int>>()
        while (o + 8 <= end) {
            val sz = u32(data, o)
            if (sz < 8) break
            if (typeStr(data.copyOfRange(o + 4, o + 8)) != "dimg") { o += sz; continue }
            val ps = o + 8
            val from: Int; val cnt: Int
            if (ver == 0) {
                from = u16(data, ps); cnt = u16(data, ps + 2)
                var q = ps + 4
                map[from] = (0 until cnt).map { u16(data, q).also { q += 2 } }
            } else {
                from = u32(data, ps); cnt = u16(data, ps + 4)
                var q = ps + 6
                map[from] = (0 until cnt).map { u32(data, q).also { q += 4 } }
            }
            o += sz
        }
        return map
    }

    // ---------- ipco + ipma ----------
    private fun readIpcoBoxes(data: ByteArray, iprpStart: Int): List<Box> {
        // iprp NOT FullBox: sub-boxes start right after 8B header
        var o = iprpStart + 8
        val end = boxEnd(data, iprpStart)
        val iprpSubs = walkBoxes(data, o, end)
        val ipco = iprpSubs.firstOrNull { typeStr(it.type) == "ipco" } ?: return emptyList()
        return walkBoxes(data, ipco.payloadStart, ipco.end)
    }

    private fun readIpma(data: ByteArray, iprpStart: Int): Map<Int, List<Int>> {
        var o = iprpStart + 8
        val end = boxEnd(data, iprpStart)
        val ipma = walkBoxes(data, o, end).firstOrNull { typeStr(it.type) == "ipma" } ?: return emptyMap()
        val p = ipma.payloadStart
        val ver = data[p].toInt() and 0xff
        var q = p + 4
        val entryCount = u32(data, q); q += 4
        val map = HashMap<Int, List<Int>>()
        repeat(entryCount) {
            val iid = if (ver < 1) u16(data, q).also { q += 2 } else u32(data, q).also { q += 4 }
            val ac = if (ver < 1) (data[q].toInt() and 0xff).also { q += 1 } else u16(data, q).also { q += 2 }
            val idxs = ArrayList<Int>(ac)
            repeat(ac) {
                val v = if (ver < 1) (data[q].toInt() and 0xff).also { q += 1 } else u16(data, q).also { q += 2 }
                idxs.add(v and 0x7f)
            }
            map[iid] = idxs
        }
        return map
    }

    private fun readPixiBits(data: ByteArray, b: Box): List<Int> {
        // FullBox ver(4) + num_channels(1) + bits[count]
        val ps = b.payloadStart
        val cnt = data[ps + 4].toInt() and 0xff
        return (0 until cnt).map { data[ps + 5 + it].toInt() and 0xff }
    }

    private fun readColrTransfer(data: ByteArray, b: Box): Int? {
        val ps = b.payloadStart
        val method = String(data, ps, 4, Charsets.US_ASCII)
        if (method != "nclx") return null
        return u16(data, ps + 6) // skip colour_primaries(u16), then transfer(u16)
    }

    private fun readIspeDims(ispeBoxBytes: ByteArray): Pair<Int, Int>? {
        // full box: 8B hdr + ver(4) + w(u32) + h(u32) = 20B
        if (ispeBoxBytes.size < 20) return null
        return u32(ispeBoxBytes, 12) to u32(ispeBoxBytes, 16)
    }

    // ---------- primitives ----------
    private fun boxEnd(data: ByteArray, boxStart: Int): Int {
        val s = u32(data, boxStart)
        return when {
            s == 1 -> (boxStart + u64(data, boxStart + 8)).toInt()
            s == 0 -> data.size
            else -> boxStart + s
        }
    }
    private fun slice(data: ByteArray, off: Long, len: Long): ByteArray? {
        val o = off.toInt(); val l = len.toInt()
        if (o < 0 || l < 0 || o + l > data.size) return null
        return data.copyOfRange(o, o + l)
    }
    /** Full box bytes (size+type+payload) — copied verbatim into the cell wrapper's ipco. */
    private fun sliceBox(data: ByteArray, b: Box): ByteArray =
        data.copyOfRange(b.start, b.end)
    private fun typeStr(t: ByteArray): String = String(t, Charsets.US_ASCII)
    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff shl 24) or (b[o + 1].toInt() and 0xff shl 16) or
            (b[o + 2].toInt() and 0xff shl 8) or (b[o + 3].toInt() and 0xff)
    private fun u64(b: ByteArray, o: Int): Long =
        (u32(b, o).toLong() and 0xffffffffL shl 32) or (u32(b, o + 4).toLong() and 0xffffffffL)
    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff shl 8) or (b[o + 1].toInt() and 0xff)
}
