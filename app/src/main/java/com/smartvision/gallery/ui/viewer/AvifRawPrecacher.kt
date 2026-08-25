package com.smartvision.gallery.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.smartvision.gallery.decoder.bridge.NativeBridge
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * AVIF → raw RGBA pixel cache writer.
 *
 * Decodes [uri] at full source resolution via the system [BitmapFactory]
 * codec, writing the pixel data to a custom raw file (20-byte SVRAW header +
 * RGBA pixels). No encoding step, no quality loss — preserves original AVIF
 * pixel data including alpha.
 *
 * Why: the system BitmapRegionDecoder cannot region-decode AVIF on most OEMs
 * (throws "Image format not supported"), so Telephoto's SubSamplingImage →
 * AndroidImageRegionDecoder path returns null → blurry 256px preview + dead
 * gestures. Writing the SVRAW file once lets [RawImageSource] /
 * [RawImageRegionDecoder] tile-read from it, sidestepping the system codec.
 *
 * Why not libavif: the vendored libavif.a was built without the AOM codec
 * (`-DAVIF_CODEC_AOM=ON` is an invalid value in libavif 1.4.x — only
 * LOCAL/SYSTEM are accepted, so codec_aom.c never compiled → runtime
 * "No codec available"). Rebuilding it correctly needs a fresh aom fetch +
 * config-header generation. The system decoder is already proven on this
 * device (MediaFetcher whole-decodes AVIF fine), so we route through it.
 * Grid AVIFs (32K+): the ISOBMFF grid container is parsed and each cell is
 * wrapped as a minimal single-item AVIF, decoded via the system codec one cell
 * at a time, then tiled into SVRAW — so peak memory stays at one cell instead
 * of the full image (spec `docs/superpowers/specs/2026-08-21-avif-grid-32k-decode-design.md`).
 * ponytail: whole-decode sources over [MAX_PX] (or grid cells over
 * [MAX_CELL_PX]) return null → viewer shows "cannot display". Non-grid >8K
 * single-coded AVIFs and pathological grids revisit when the aom build is fixed
 * or via a downsampled-to-raw partial rebuild.
 *
 * Memory: bitmap is w×h×4 bytes (freed after write) + one row buffer.
 */
class AvifRawPrecacher(private val context: Context) {

    /** Per-uri decode lock. Concurrent decodeToRaw(uri) calls (recomposition,
     *  rapid back-and-forth in LaunchedEffect) can both miss the cache and
     *  fopen 'wb' the same target → interleaved/corrupt SVRAW → rawValid
     *  fails → deleted → repeated re-decodes and random failures. Serializing
     *  per-uri makes the first writer win and the rest hit the cache. */
    private val decodeLocks = mutableMapOf<String, Mutex>()

    /** Raw RGBA pixel cache path. */
    fun rawFile(uri: Uri): File =
        File(context.cacheDir, "AVIF_Raw/${md5(uri.toString())}.raw")

    fun rawExists(uri: Uri): Boolean = rawValid(rawFile(uri))

    /**
     * Validate a SVRAW cache file end-to-end: magic "SVRA", channels ∈ {3,4},
     * bpc ∈ {1,2}, and file length == 20 + w*h*ch*bpc. A partial file (native
     * fork accepts ≥90% to survive an aom destructor crash) would otherwise
     * hit EOFException in RawImageRegionDecoder.readFully → telephoto swallows
     * it (IOException is caught) → tile stuck InFlight forever → black screen.
     * Strict equality deletes the poisoned cache so it rebuilds clean.
     */
    private fun rawValid(f: File): Boolean {
        if (!f.exists() || f.length() < 20) return false
        return try {
            RandomAccessFile(f, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "SVRA") return false
                fun r32(): Int {
                    val b = ByteArray(4)
                    raf.readFully(b)
                    return (b[0].toInt() and 0xFF) or
                        ((b[1].toInt() and 0xFF) shl 8) or
                        ((b[2].toInt() and 0xFF) shl 16) or
                        ((b[3].toInt() and 0xFF) shl 24)
                }
                val w = r32(); val h = r32(); val ch = r32(); val bpc = r32()
                if (w <= 0 || h <= 0 || ch !in 3..4 || bpc !in 1..2) return false
                val want = 20L + w.toLong() * h.toLong() * ch.toLong() * bpc.toLong()
                f.length() == want
            }
        } catch (e: Exception) { false }
    }

    /**
     * Returns the raw pixel file for [uri] — reusing a prior cache entry when
     * present, otherwise decoding the AVIF via the system codec to the raw
     * file. Returns null on failure (unreadable, over [MAX_PX], decode error).
     */
    suspend fun decodeToRaw(uri: Uri): File? {
        // Serialize per-uri; see decodeLocks kdoc.
        val lock = synchronized(decodeLocks) { decodeLocks.getOrPut(uri.toString()) { Mutex() } }
        return lock.withLock { decodeToRawLocked(uri) }
    }

    private suspend fun decodeToRawLocked(uri: Uri): File? {
        if (rawExists(uri)) {
            AppLog.i(TAG, "decodeToRaw: cache hit $uri")
            return rawFile(uri)
        }
        val target = rawFile(uri)
        return try {
            target.parentFile?.mkdirs()

            // Grid path: parse ISOBMFF grid container, decode each cell via the
            // system codec after wrapping it as a minimal single-item AVIF, and
            // tile the cells into SVRAW. Falls through to whole-decode for non-grid
            // AVIFs and small grids that fit the whole-decode memory ceiling.
            val grid = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { AvifGridDecoder.parse(it) }
            }
            if (grid != null) {
                val gridPx = grid.outW.toLong() * grid.outH
                AppLog.i(TAG, "decodeToRaw: grid ${grid.cols}x${grid.rows} cells=${grid.cells.size} " +
                    "out=${grid.outW}x${grid.outH} bpc=${grid.bpc} px=$gridPx -> ${target.absolutePath}")
                if (gridPx > MAX_GRID_PX) {
                    AppLog.w(TAG, "decodeToRaw: grid ${grid.outW}x${grid.outH} ($gridPx px) over MAX_GRID_PX=$MAX_GRID_PX")
                    return null
                }
                val ok = withContext(Dispatchers.IO) { decodeGridToRaw(grid, uri, target) }
                logResult(ok, target)
                return if (ok && rawValid(target)) target
                    else { target.delete(); null }
            }

            // Whole-decode path (non-grid AVIF or small grid the parser skipped).
            AppLog.i(TAG, "decodeToRaw: whole decode for $uri -> ${target.absolutePath}")

            // System codec first (hardware AV1 — fast). On null (HW caps at
            // ~4K×4K configure() reject for 16K+, or OEM unsupported), fall
            // back to native libavif (dav1d software) which has no dim limit.
            // Native writes the full SVRAW (header at offset 0 + streamed RGBA
            // rows) directly to [target], auto-downscaling >128Mpx to fit
            // native heap. On success we skip the Bitmap path entirely — no
            // 614MB Bitmap needed for 16K.
            val bmp = decodeSystemFull(uri)
            if (bmp != null) {
                val ok = try {
                    writeSvarw(bmp, target)
                    true
                } finally {
                    bmp.recycle()
                }
                logResult(ok, target)
                if (ok && rawValid(target)) target
                else { target.delete(); null }
            } else if (NativeBridge.isReady) {
                AppLog.i(TAG, "decodeToRaw: system codec null, trying native dav1d")
                val rawDims = withContext(Dispatchers.IO) {
                    NativeBridge.avifDecodeToRawFile(uri, target.absolutePath)
                }
                val ok = rawDims != null && rawValid(target)
                logResult(ok, target)
                if (ok) {
                    AppLog.i(TAG, "decodeToRaw: native dav1d wrote raw ${rawDims!![0]}x${rawDims[1]}")
                    target
                } else { target.delete(); null }
            } else {
                AppLog.w(TAG, "decodeToRaw: system null and native unavailable")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "decodeToRaw: exception", e)
            target.delete()
            null
        }
    }

    private fun logResult(ok: Boolean, target: File) {
        AppLog.i(TAG, "decodeToRaw: w=${if (ok) readWidth(target) else 0} " +
            "exists=${target.exists()} len=${target.length()}")
    }

    /**
     * Decode each grid cell via the system AVIF codec (after wrapping its AV1
     * bytes as a minimal AVIF) and tile it into [target] via [AvifCellWriter].
     * Cells over [MAX_CELL_PX] abort the whole decode (corrupt/inflated).
     *
     * Batched parallel decode: cells processed in batches of availableProcessors/2.
     * Each batch decoded concurrently, then written + recycled before the next.
     * Peak memory = batch × cellW × cellH × 4 (bounded), not all-cells-at-once
     * (40×16MB = 640MB → OOM for 16K grids). Wall-clock still 2-4x multi-core.
     */
    private suspend fun decodeGridToRaw(grid: AvifGridDecoder.GridInfo, uri: Uri, target: File): Boolean {
        if (grid.cellW.toLong() * grid.cellH > MAX_CELL_PX) {
            AppLog.w(TAG, "decodeGridToRaw: cell ${grid.cellW}x${grid.cellH} over MAX_CELL_PX=$MAX_CELL_PX")
            return false
        }
        val config = if (grid.bpc == 2) Bitmap.Config.RGBA_1010102 else Bitmap.Config.ARGB_8888
        val cellOpts = BitmapFactory.Options().apply { inPreferredConfig = config }
        AvifCellWriter.writeHeader(target, grid.outW, grid.outH, grid.bpc)

        // Read the whole source once: cells reference absolute file offsets that
        // ContentResolver will not serve per-slice. For 8160×6050 this is ~6MB;
        // for 16K 16000×9600 this is ~40MB — acceptable for one-shot grid decode.
        val srcBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return false

        // Batch decode: hold only `batch` cells' bitmaps + byte slices in memory
        // at once. Without bounding, all cells (40 for 16K, 256 for 32K) would
        // be held concurrently — 16000×9600 grid with 2048×2048 cells → 40×16MB
        // = 640MB peak → OOM → "无法显示". Batched peak = batch × cell_px × 4.
        data class CellResult(val bmp: Bitmap, val gr: Int, val gc: Int)

        // Memory-bound the batch, not core-bound: a cell bitmap is cellW×cellH×4
        // bytes. For a 16K grid (2048×2048 cells) that's 16MB/cell; for a 32K
        // grid with 4096×4096 cells it's 64MB/cell. A fixed core-count batch
        // (procs/2=8) would hold 8×64MB = 512MB of concurrent bitmaps → OOM on
        // 4-6GB devices — an actual 32K failure mode. Cap total batch bytes at
        // ~2GB (comfortable under Android's 4GB+ heap budget with room for the
        // decoded AV1 frame + source slice), falling back to core-bound when
        // cells are small enough that cores are the binding constraint.
        val cellBytes = grid.cellW.toLong() * grid.cellH * 4
        val batch = (2L * 1024 * 1024 * 1024 / cellBytes)
            .coerceIn(1L, maxOf(2, Runtime.getRuntime().availableProcessors() / 2).toLong())
            .toInt()
        for (batchStart in grid.cells.indices step batch) {
            val batchEnd = minOf(batchStart + batch, grid.cells.size)
            val batchResults: List<CellResult>
            try {
                batchResults = coroutineScope {
                    val deferreds = (batchStart until batchEnd).mapIndexed { localI, i ->
                        async(Dispatchers.Default) {
                            val gr = i / grid.cols
                            val gc = i % grid.cols
                            val cell = grid.cells[i]
                            val av1 = try {
                                // iloc offsets are Long (can exceed Int range on
                                // >2GB lossless grids). copyOfRange(Int) truncates
                                // above 2.1GB → wrong cells / silent corruption.
                                // Reject cells whose offset+len exceeds Int range
                                // rather than silently slicing garbage. A true 32K
                                // lossy grid source is ~20MB so this never fires
                                // on real-world test files; it guards the edge.
                                val end = cell.fileOffset + cell.length
                                if (cell.fileOffset < 0 || cell.length < 0 ||
                                    cell.fileOffset > Int.MAX_VALUE || end > Int.MAX_VALUE ||
                                    end > srcBytes.size) {
                                    AppLog.e(TAG, "decodeGridToRaw: cell $i offset ${cell.fileOffset}+${cell.length} OOB (>Int.MAX or >src)")
                                    return@async null
                                }
                                srcBytes.copyOfRange(cell.fileOffset.toInt(), end.toInt())
                            } catch (t: Exception) {
                                AppLog.e(TAG, "decodeGridToRaw: cell $i slice OOB", t)
                                return@async null
                            }
                            val wrapped = AvifCellDecoder.wrap(av1, cell.av1CBytes, cell.ispeBytes)
                            val bmp = BitmapFactory.decodeByteArray(wrapped, 0, wrapped.size, cellOpts)
                            if (bmp == null) {
                                AppLog.e(TAG, "decodeGridToRaw: cell $i decode null")
                                return@async null
                            }
                            CellResult(bmp, gr, gc)
                        }
                    }
                    deferreds.mapNotNull { it.await() }
                }
            } catch (t: Exception) {
                AppLog.e(TAG, "decodeGridToRaw: batch decode failed at $batchStart..$batchEnd", t)
                return false
            }
            if (batchResults.size != (batchEnd - batchStart)) {
                batchResults.forEach { it.bmp.recycle() }
                AppLog.e(TAG, "decodeGridToRaw: $batchStart..$batchEnd got ${batchResults.size} of ${batchEnd - batchStart}")
                return false
            }
            for (result in batchResults) {
                try {
                    AvifCellWriter.writeCell(target, result.bmp, result.gr, result.gc,
                        grid.cellW, grid.cellH, grid.outW, grid.outH, grid.bpc)
                } finally {
                    result.bmp.recycle()
                }
            }
        }
        return true
    }

    /** Whole-image decode via the system AVIF codec. No bounds pre-check — some
     *  AVIF authors over-claim ispe (ReaScale "4x" declares 16K×9.6K but the AV1
     *  frame is far smaller; 952KB can't hold 153M px), so the declared size is
     *  not trusted to reject. Two-phase decode: (1) full-res — succeeds for
     *  ispe-over-claiming files whose real AV1 frame is small; (2) on null/OOM,
     *  retry with [inSampleSize] (power-of-2) so genuine 16K single-frames
     *  (614MB ARGB_8888) downsample to ≤ [MAX_PX] instead of OOMing. Decode null
     *  (corrupt/unsupported AV1) is logged at both phases, never swallowed. */
    private fun decodeSystemFull(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsOk = try {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            bounds.outWidth > 0 && bounds.outHeight > 0
        } catch (t: Throwable) {
            AppLog.w(TAG, "decodeSystemFull: bounds probe failed", t)
            false
        }
        if (!boundsOk) {
            AppLog.w(TAG, "decodeSystemFull: no dimensions from bounds probe")
            return null
        }
        val px = bounds.outWidth.toLong() * bounds.outHeight
        AppLog.i(TAG, "decodeSystemFull: declared ${bounds.outWidth}x${bounds.outHeight}=$px px")

        // Oversized: try progressive sample sizes (2,4,8,16). Android libavif may
        // reject based on input-level check (av1C seq_level=6.0 → max 69.9M samples);
        // if px > 69.9M the check fires on ispe dims regardless of sample. Try anyway
        // in case the check is on output size, and try decodeFileDescriptor as an
        // alternate code path (different JNI entry).
        if (px > MAX_PX) {
            // System decoder caps at ~4K×4K (configure() rejects bigger). Try
            // progressive sample-size retries (system libavif may succeed on
            // smaller outputs). Native dav1d fallback lives in decodeToRaw,
            // not here — it writes SVRAW directly and bypasses this Bitmap path.
            for (sample in listOf(2, 4, 8, 16)) {
                AppLog.i(TAG, "decodeSystemFull: oversize, try sample=$sample")
                tryDecodeStreamAndFd(uri, sample, bounds.outWidth, bounds.outHeight)?.let { return it }
            }
            AppLog.w(TAG, "decodeSystemFull: all sample sizes failed for $px px — " +
                "HW AV1 decoder dimension limit (av1C max 4K) plus system libavif level check")
            return null
        }

        // Phase 1: full-res (fits within MAX_PX). Null/OOM falls through to phase 2.
        tryDecodeStreamAndFd(uri, 1, bounds.outWidth, bounds.outHeight)?.let { return it }

        // Phase 2: fallback downsample (decoder rejected full-res).
        var sample = 2
        while (px / (sample.toLong() * sample) > MAX_PX) sample *= 2
        AppLog.i(TAG, "decodeSystemFull: phase-1 null, retry sample=$sample")
        return tryDecodeStreamAndFd(uri, sample, bounds.outWidth, bounds.outHeight)
    }

    /** Try [decodeStream] then [decodeFileDescriptor]. Return first non-null. */
    private fun tryDecodeStreamAndFd(uri: Uri, sample: Int, declW: Int, declH: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sample
        }
        return tryDecodeStream(uri, opts, sample, declW, declH)
            ?: tryDecodeFd(uri, opts, sample, declW, declH)
    }

    private fun tryDecodeStream(uri: Uri, opts: BitmapFactory.Options, sample: Int, declW: Int, declH: Int): Bitmap? =
        try {
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bmp == null) AppLog.d(TAG, "tryDecode: stream null sample=$sample (${declW}x${declH})")
            else AppLog.i(TAG, "tryDecode: stream ${bmp.width}x${bmp.height} sample=$sample")
            bmp
        } catch (t: Throwable) {
            AppLog.w(TAG, "tryDecode: stream throw sample=$sample", t); null
        }

    private fun tryDecodeFd(uri: Uri, opts: BitmapFactory.Options, sample: Int, declW: Int, declH: Int): Bitmap? =
        try {
            AppLog.d(TAG, "tryDecode: FD fallback sample=$sample")
            val bmp = context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, opts)
            }
            if (bmp == null) AppLog.d(TAG, "tryDecode: FD null sample=$sample")
            else AppLog.i(TAG, "tryDecode: FD ${bmp.width}x${bmp.height} sample=$sample")
            bmp
        } catch (t: Throwable) {
            AppLog.w(TAG, "tryDecode: FD throw sample=$sample", t); null
        }

    /** Write [bmp] as an SVRAW file: 20-byte header + RGBA rows, matching the
     *  native [jxl_to_raw] layout exactly ([RawImageRegionDecoder] reads it):
     *  magic "SVRAW" (4 bytes) + w(4) + h(4) + channels(4) + bpc(4). */
    private fun writeSvarw(bmp: Bitmap, target: File) {
        RandomAccessFile(target, "rw").use { raf ->
            raf.write("SVRAW".toByteArray(Charsets.US_ASCII), 0, 4)
            writeIntLE(raf, bmp.width)
            writeIntLE(raf, bmp.height)
            writeIntLE(raf, CHANNELS)
            writeIntLE(raf, BYTES_PER_CHANNEL)

            val row = IntArray(bmp.width)
            val buf = ByteArray(bmp.width * CHANNELS)
            for (y in 0 until bmp.height) {
                bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
                var bi = 0
                for (x in 0 until bmp.width) {
                    val c = row[x]
                    buf[bi++] = (c ushr 16).toByte() // R
                    buf[bi++] = (c ushr 8).toByte()  // G
                    buf[bi++] = c.toByte()           // B
                    buf[bi++] = (c ushr 24).toByte() // A
                }
                raf.write(buf)
            }
        }
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF)
        raf.write((v ushr 8) and 0xFF)
        raf.write((v ushr 16) and 0xFF)
        raf.write((v ushr 24) and 0xFF)
    }

    /** Read back the header width for the success log line. */
    private fun readWidth(file: File): Int {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.skipBytes(4) // magic "SVRAW"
                var w = 0
                for (i in 0 until 4) w = w or ((raf.read() and 0xFF) shl (8 * i))
                w
            }
        } catch (t: Throwable) {
            -1
        }
    }

    private companion object {
        private const val TAG = "AvifRawPrecacher"
        private const val CHANNELS = 4
        private const val BYTES_PER_CHANNEL = 1
        // 64M px = 256MB ARGB_8888 bitmap (≈8K×8K). Covers typical large AVIFs
        // like 8160×6050. Above this whole-decode risks native OOM; grid AVIFs
        // take the per-cell path instead (see class kdoc).
        private const val MAX_PX = 64_000_000L
        // Grid AVIFs decode cell-by-cell then tile to SVRAW: peak memory is one
        // cell, not the full output. 32K×32K = 1,073,741,824 px (4.3GB 8-bit
        // SVRAW) — the grid path is the ONLY path that can hold it (single-cell
        // peak RAM). Gate at 1.1G px (~4.4GB cache; a true 32K lossy grid source
        // is ~20MB, but output storage is the cost). Downstream consumers all use
        // Long offsets (AvifCellWriter.writeCell, RawImageRegionDecoder fileOffset,
        // rawValid Long want=20+w*h*4) so a 4.3GB file is well-formed.
        private const val MAX_GRID_PX = 1_100_000_000L
        // Per-cell ceiling: AV1 Advanced level 6.0 max = 2^22 sample/s = 4,194,304
        // sample of an 8x8 block → 35,651,584 px. A single grid cell can never
        // exceed this in a conformant file; abort decode if one does (corrupt).
        private const val MAX_CELL_PX = 35_651_584L
    }

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
