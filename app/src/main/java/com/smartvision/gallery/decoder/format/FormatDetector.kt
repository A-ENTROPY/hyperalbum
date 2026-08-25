package com.smartvision.gallery.decoder.format

import android.content.Context
import android.net.Uri
import com.smartvision.gallery.util.AppLog
import com.smartvision.gallery.util.IoUtils
import java.nio.ByteBuffer

/**
 * Magic-byte based format detection. Filename extensions lie; container signatures don't.
 *
 * The detector operates on the first ~64 bytes of a stream. Where possible (e.g. AVIF,
 * JXL) we look at the brand/type boxes; where the format shares the ISOBMFF shell with
 * HEIC we double-check by sniffing for the ftyp box's major brand.
 */
object FormatDetector {

    private const val TAG = "FormatDetector"

    /**
     * Detect the actual format of [uri] from its content. Falls back to filename heuristic
     * if the stream cannot be opened or the head bytes are insufficient.
     */
    fun detect(context: Context, uri: Uri, hintFilename: String? = null): MediaFormat {
        val head = IoUtils.readHead(context, uri, length = 64)
        val detected = detectFromBytes(head)
        if (detected != MediaFormat.UNKNOWN) return detected
        val fallback = MediaFormat.fromFilename(hintFilename ?: uri.lastPathSegment)
        AppLog.d(TAG, "Falling back to extension for $uri → $fallback")
        return fallback
    }

    /**
     * Quick gate: does the URI's first bytes look like a recognizable media container?
     *
     * Used at scan time and at click time to keep garbage files (placeholder
     * `null<hex>.<ext>` from cloud sync, half-downloaded `DSC_*.jpg` from MiShare,
     * truncated files with valid MediaStore metadata but invalid body) out of
     * the native decoder. We deliberately do NOT compare against the claimed
     * format — false positives here would block legitimate files. We just want
     * to catch files whose content is unrecognizable to the magic-byte table.
     *
     * Returns false if the file can't be read or its first 4 bytes don't match
     * any known image/video container signature.
     */
    fun isRecognizable(context: Context, uri: Uri): Boolean {
        return runCatching {
            val head = IoUtils.readHead(context, uri, length = 16)
            if (head.remaining() < 4) return@runCatching false
            detectFromBytes(head) != MediaFormat.UNKNOWN
        }.getOrDefault(false)
    }

    /** Pure-byte detector; exposed for unit testing. */
    fun detectFromBytes(head: ByteBuffer): MediaFormat {
        if (head.remaining() < 4) return MediaFormat.UNKNOWN
        val b0 = head.get(0).toInt() and 0xFF
        val b1 = head.get(1).toInt() and 0xFF
        val b2 = head.get(2).toInt() and 0xFF
        val b3 = head.get(3).toInt() and 0xFF

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return MediaFormat.PNG

        // JPEG: FF D8 FF
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return MediaFormat.JPEG

        // GIF: 'GIF8'
        if (b0 == 0x47 && b1 == 0x49 && b2 == 0x46 && b3 == 0x38) return MediaFormat.GIF

        // BMP: 'BM'
        if (b0 == 0x42 && b1 == 0x4D) return MediaFormat.BMP

        // TIFF: 'II*\0' or 'MM\0*'
        if ((b0 == 0x49 && b1 == 0x49 && b2 == 0x2A && b3 == 0x00) ||
            (b0 == 0x4D && b1 == 0x4D && b2 == 0x00 && b3 == 0x2A)
        ) return MediaFormat.TIFF

        // RIFF container: 'RIFF' ... 'WEBP' / 'AVI ' / 'WAVE'
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 && head.remaining() >= 12) {
            val form = String(
                byteArrayOf(
                    head.get(8),
                    head.get(9),
                    head.get(10),
                    head.get(11)
                ),
                Charsets.US_ASCII
            )
            return when (form) {
                "WEBP" -> MediaFormat.WEBP_STATIC // animation re-checked at decode time
                "AVI " -> MediaFormat.AVI
                "WAVE" -> MediaFormat.UNKNOWN
                else -> MediaFormat.UNKNOWN
            }
        }

        // ISOBMFF / HEIF / AVIF family — start with 'ftyp' at offset 4.
        if (b0 == 0x00 && b1 == 0x00 && b2 == 0x00 &&
            (b3 == 0x18 || b3 == 0x20 || b3 == 0x24 || b3 == 0x2C)) {
            // Could be MP4/MOV/HEIF. We need to peek at offset 4 to find 'ftyp' and then brand.
            if (head.remaining() >= 12 &&
                head.get(4) == 'f'.code.toByte() &&
                head.get(5) == 't'.code.toByte() &&
                head.get(6) == 'y'.code.toByte() &&
                head.get(7) == 'p'.code.toByte()
            ) {
                return detectFromIsobmffBrand(head)
            }
        }

        // JXL bare container: 0xFF0A
        if (b0 == 0xFF && b1 == 0x0A) return MediaFormat.JXL

        // JXL codestream container: 'JXL ' ... 0x0D
        if (b0 == 'J'.code && b1 == 'X'.code && b2 == 'L'.code && b3 == ' '.code) {
            return MediaFormat.JXL
        }

        return MediaFormat.UNKNOWN
    }

    private fun detectFromIsobmffBrand(head: ByteBuffer): MediaFormat {
        // Major brand lives at offset 8 (4 bytes). Compatible brands follow.
        if (head.remaining() < 12) return MediaFormat.UNKNOWN
        val brand = String(
            byteArrayOf(head.get(8), head.get(9), head.get(10), head.get(11)),
            Charsets.US_ASCII
        ).trim()
        return when (brand) {
            "avif", "avis" -> MediaFormat.AVIF_STATIC
            "heic", "heix", "heim", "heis", "hevc", "hevx", "mif1" -> MediaFormat.HEIC
            "heics", "heis" -> MediaFormat.HEIC_SEQ
            "mp41", "mp42", "isom", "iso2", "dash" -> MediaFormat.MP4
            "qt  " -> MediaFormat.MOV
            "MKV ", "matroska" -> MediaFormat.MKV
            else -> {
                // Sniff compatible brands too, in case the major brand is generic.
                val haystack = (8 until head.remaining()).map { head.get(it).toInt().toChar() }.joinToString("")
                when {
                    "avif" in haystack || "avis" in haystack -> MediaFormat.AVIF_STATIC
                    "heic" in haystack || "mif1" in haystack -> MediaFormat.HEIC
                    "mp4" in haystack.lowercase() -> MediaFormat.MP4
                    else -> MediaFormat.UNKNOWN
                }
            }
        }
    }
}