package com.smartvision.gallery.ui.viewer

import android.content.Context
import android.net.Uri
import com.smartvision.gallery.decoder.bridge.NativeBridge
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Single-level JXL → raw RGBA pixel cache writer.
 *
 * Decodes [uri] at full source resolution via libjxl, writing the pixel data
 * directly to a custom raw file (20-byte header + RGBA/RGB pixels). No encoding
 * step, no quality loss — preserves original JXL pixel data including alpha and
 * HDR when the native decoder supports it.
 *
 * Telephoto's [SubSamplingImage] reads tiles from the raw file via
 * [RawImageSource], which performs region decoding directly from the raw pixel
 * buffer (no BitmapRegionDecoder needed).
 *
 * Memory: the decode allocates a full-resolution buffer (~w×h×4 bytes max).
 * Freed after the file is written. Subsequent opens read only the visible tiles
 * from the raw file — no full-resolution decode.
 */
class JxlFullResPrecacher(private val context: Context) {

    companion object {
        const val JPEG_QUALITY = 95
    }

    /** Legacy JPEG cache path. */
    fun cacheFile(uri: Uri): File =
        File(context.cacheDir, "JXL_FullRes/${md5(uri.toString())}.jpg")

    /** New raw RGBA pixel cache path. */
    fun rawFile(uri: Uri): File =
        File(context.cacheDir, "JXL_FullRes/${md5(uri.toString())}.raw")

    fun cacheExists(uri: Uri): Boolean {
        val f = cacheFile(uri)
        return f.exists() && f.length() > 0
    }

    fun rawExists(uri: Uri): Boolean = rawValid(rawFile(uri))

    /**
     * Validate a SVRAW cache file end-to-end: magic "SVRA", channels ∈ {3,4},
     * bpc ∈ {1,2}, and file length == 20 + w*h*ch*bpc. Strict equality so a
     * stale cache written by an older native decoder (pre-downscale: full-res
     * 16384×12288 → 805MB) is rejected and re-decoded at the new downscaled
     * size. Mirrors [AvifRawPrecacher.rawValid].
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
     * present, otherwise decoding the JXL natively to the raw file. Returns
     * null on failure.
     *
     * The raw file uses [RawImageSource] for Telephoto tile decoding,
     * preserving original pixel data without JPEG compression.
     */
    suspend fun decodeToRaw(uri: Uri): File? {
        if (rawExists(uri)) return rawFile(uri)
        val target = rawFile(uri)
        return try {
            target.parentFile?.mkdirs()
            val dims = NativeBridge.jxlDecodeToRawFile(
                uri, target.absolutePath
            )
            if (dims == null || dims.size < 3 || !target.exists() || target.length() <= 20) {
                target.delete()
                null
            } else {
                target
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    /**
     * Legacy JPEG decode path. Returns the 1:1 JPEG file.
     * Falls back to this when raw decode is unavailable.
     */
    suspend fun decodeToJpeg(uri: Uri): File? {
        if (cacheExists(uri)) return cacheFile(uri)
        val target = cacheFile(uri)
        return try {
            target.parentFile?.mkdirs()
            val dims = NativeBridge.jxlDecodeToJpegFile(
                uri, target.absolutePath, JPEG_QUALITY
            )
            if (dims == null || dims.size < 2 || !target.exists() || target.length() <= 0) {
                target.delete()
                null
            } else {
                target
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}