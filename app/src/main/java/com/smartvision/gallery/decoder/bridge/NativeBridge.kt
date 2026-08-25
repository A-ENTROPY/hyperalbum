package com.smartvision.gallery.decoder.bridge

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin Kotlin surface for the native `libsmartvision_decoder.so`. All JNI calls go through
 * here so the rest of the codebase can stay Kotlin-pure.
 *
 * Lifecycle: initialise once from [com.smartvision.gallery.SmartVisionApp.onCreate].
 * Subsequent loads are best-effort; if the library fails to load we surface a clear
 * "decoder unavailable" path so callers can fall back to the system decoder.
 */
object NativeBridge {

    @Volatile
    private var available: Boolean = false

    @Volatile
    private var initialised: Boolean = false

    @Volatile
    private var appContext: Context? = null

    val isReady: Boolean get() = available

    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        appContext = context.applicationContext
        try {
            System.loadLibrary("smartvision_decoder")
            nativeInit()
            available = true
            AppLog.i(TAG, "Native decoder bridge initialised")
        } catch (t: Throwable) {
            available = false
            AppLog.e(TAG, "Failed to load libsmartvision_decoder", t)
        }
    }

    // -- AVIF -----------------------------------------------------------------------

    /**
     * Decode an AVIF file (URI) into a thumbnail Bitmap of roughly the requested
     * pixel size. Returns null if the decoder is unavailable or the file can't
     * be opened.
     */
    suspend fun decodeAvifThumbnail(uri: Uri, targetWidthPx: Int, targetHeightPx: Int): Bitmap? {
        if (!available) return null
        val bytes = readBytes(uri) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { nativeDecodeAvifThumb(bytes, targetWidthPx, targetHeightPx) }
                .onFailure { AppLog.w(TAG, "nativeDecodeAvifThumb failed", it) }
                .getOrNull()
        }
    }

    suspend fun decodeAvifFull(uri: Uri): NativeDecodeResult? {
        if (!available) return null
        val bytes = readBytes(uri) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { nativeDecodeAvifFull(bytes) }
                .onFailure { AppLog.w(TAG, "nativeDecodeAvifFull failed", it) }
                .getOrNull()
        }
    }

    // -- JPEG XL --------------------------------------------------------------------

    /**
     * Decode a JPEG XL file at a target long-edge resolution. Returns null on
     * failure (decoder unavailable, mmap failed AND readBytes also failed, decode
     * error).
     *
     * The native bridge streams pixels directly into the output bitmap via
     * AndroidBitmap_lockPixels — no intermediate RGBA buffer. File I/O goes
     * through mmap first (zero-copy), with readBytes as a fallback for
     * non-file-backed providers (cloud drives, pipes).
     *
     * @param targetLongEdgePx desired output long edge. Native computes the
     *   smallest power-of-two stride that brings the source below this size;
     *   see spec §"stride 映射 + 最高档取整". Pass 4096 for the safe high-res tier.
     */
    suspend fun decodeJxlScaled(uri: Uri, targetLongEdgePx: Int = 4096): NativeDecodeResult? {
        if (!available) return null
        return withContext(Dispatchers.IO) {
            // Primary path: mmap via file descriptor.
            runCatching { decodeJxlViaMmap(uri, targetLongEdgePx) }.getOrNull()
                ?: run {
                    // Fallback: readBytes (for non-file-backed providers).
                    val bytes = readBytes(uri, maxBytes = 200 * 1024 * 1024) ?: return@withContext null
                    runCatching { nativeDecodeJxlScaledBytes(bytes, targetLongEdgePx) }
                        .onFailure { AppLog.w(TAG, "nativeDecodeJxlScaledBytes failed", it) }
                        .getOrNull()
                }
        }
    }

    private fun decodeJxlViaMmap(uri: Uri, targetLongEdgePx: Int): NativeDecodeResult? {
        val ctx = appContext ?: return null
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return pfd.use {
            val fd = it.fd
            val len = it.statSize.takeIf { s -> s > 0 } ?: run {
                // statSize unavailable on some providers — best effort skip.
                return null
            }
            nativeDecodeJxlFd(fd, len, targetLongEdgePx)
        }
    }

    /**
     * Probe the real pixel long edge of a JXL file without decoding pixels.
     * Runs the native decoder to BASIC_INFO only (≈1ms for 8K). Returns null
     * when mmap/probe fails.
     *
     * Use this instead of "decode 256 / ×8" so the controller knows the true
     * source long edge — `computeZoomTarget` can then ask for the 4096 tier
     * when zoomed without being clamped to an underestimate.
     */
    suspend fun jxlProbeLongEdge(uri: Uri): Long? {
        if (!available) return null
        val ctx = appContext ?: return null
        return withContext(Dispatchers.IO) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            pfd.use {
                val fd = it.fd
                val len = it.statSize.takeIf { s -> s > 0 } ?: return@use null
                val longEdge = nativeJxlProbeSize(fd, len, /* wantWidth = */ true)
                if (longEdge > 0) longEdge.toLong() else null
            }
        }
    }

    /**
     * Probe the true {width, height} of a JXL file without decoding pixels.
     * Returns int[2] {w, h} on success, null on failure. Fallback for
     * MediaStore WIDTH/HEIGHT which the system MediaProvider leaves 0 for JXL
     * (it does not parse the format), so the info panel can still show it.
     */
    suspend fun jxlProbeDims(uri: Uri): IntArray? {
        if (!available) return null
        val ctx = appContext ?: return null
        return withContext(Dispatchers.IO) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            pfd.use {
                val fd = it.fd
                val len = it.statSize.takeIf { s -> s > 0 } ?: return@use null
                val w = nativeJxlProbeSize(fd, len, /* wantWidth = */ true)
                val h = nativeJxlProbeSize(fd, len, /* wantWidth = */ false)
                if (w > 0 && h > 0) intArrayOf(w, h) else null
            }
        }
    }

    /**
     * Stream-decode [uri] (JXL) into a 1:1 JPEG at [jpegPath].
     * Pair with jxlProbeLongEdge to anticipate the output dimensions.
     * Returns int[2] {w, h} on success, null on failure.
     */
    suspend fun jxlDecodeToJpegFile(uri: Uri, jpegPath: String, quality: Int): IntArray? {
        if (!available) return null
        val ctx = appContext ?: return null
        return withContext(Dispatchers.IO) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            pfd.use {
                val fd = it.fd
                val len = it.statSize.takeIf { s -> s > 0 } ?: return@use null
                runCatching { nativeJxlDecodeToJpegFile(fd, len, jpegPath, quality) }
                    .onFailure { AppLog.w(TAG, "nativeJxlDecodeToJpegFile failed", it) }
                    .getOrNull()
            }
        }
    }

    // -- Probe ----------------------------------------------------------------------

    suspend fun probeFormat(uri: Uri): String? {
        if (!available) return null
        val bytes = readBytes(uri) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { nativeProbeFormat(bytes) }
                .onFailure { AppLog.w(TAG, "nativeProbeFormat failed", it) }
                .getOrNull()
        }
    }

    // -- Helpers --------------------------------------------------------------------

    /** Read the file's raw bytes (bounded to a sane max to avoid OOM on huge URIs). */
    private fun readBytes(uri: Uri, maxBytes: Int = 64 * 1024 * 1024): ByteArray? {
        val ctx = appContext ?: return null
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                val all = ArrayList<ByteArray>(4)
                var total = 0
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    if (total + n > maxBytes) return null
                    all += chunk.copyOf(n)
                    total += n
                }
                val out = ByteArray(total)
                var off = 0
                for (b in all) {
                    System.arraycopy(b, 0, out, off, b.size)
                    off += b.size
                }
                out
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "readBytes failed for $uri", t)
            null
        }
    }

    // -- JNI -----------------------------------------------------------------------

    @JvmStatic private external fun nativeInit(): Boolean
    @JvmStatic private external fun nativeDecodeAvifThumb(data: ByteArray, w: Int, h: Int): Bitmap?
    @JvmStatic private external fun nativeDecodeAvifFull(data: ByteArray): NativeDecodeResult?
    @JvmStatic private external fun nativeDecodeJxlScaledBytes(data: ByteArray, targetLongEdgePx: Int): NativeDecodeResult?
    @JvmStatic private external fun nativeDecodeJxlFd(fd: Int, len: Long, targetLongEdgePx: Int): NativeDecodeResult?
    @JvmStatic private external fun nativeJxlProbeSize(fd: Int, len: Long, wantWidth: Boolean): Int
    @JvmStatic private external fun nativeProbeFormat(data: ByteArray): String?

    /**
     * Stream-decode a JXL into a 1:1-resolution JPEG file at [jpegPath].
     * Returns int[2] {width, height} on success, null on failure/unsupported.
     * Runs entirely in native code (libjxl scanline → libjpeg encoder),
     * peak heap is a single scanline → safe for 32K+ sources.
     */
    @JvmStatic private external fun nativeJxlDecodeToJpegFile(
        fd: Int, len: Long, jpegPath: String, quality: Int
    ): IntArray?

    /**
     * Decode a JXL into a raw RGBA pixel file at [rawPath].
     * Returns int[3] {width, height, channels} on success, null on failure.
     * The raw file has a 20-byte header followed by row-major interleaved
     * pixel data (RGB or RGBA depending on source alpha). No encoding step,
     * no quality loss — preserves original JXL pixel data including alpha.
     *
     * Requires a full-resolution buffer (~w×h×4 bytes) during decoding,
     * freed after the file is written. Caller should probe first.
     */
    suspend fun jxlDecodeToRawFile(uri: Uri, rawPath: String): IntArray? {
        if (!available) return null
        val ctx = appContext ?: return null
        return withContext(Dispatchers.IO) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            pfd.use {
                val fd = it.fd
                val len = it.statSize.takeIf { s -> s > 0 } ?: return@use null
                runCatching { nativeJxlDecodeToRawFile(fd, len, rawPath) }
                    .onFailure { AppLog.w(TAG, "nativeJxlDecodeToRawFile failed", it) }
                    .getOrNull()
            }
        }
    }

    @JvmStatic private external fun nativeJxlDecodeToRawFile(
        fd: Int, len: Long, rawPath: String
    ): IntArray?

    /**
     * Decode an AVIF into a raw RGBA pixel file at [rawPath]. Mirrors
     * [jxlDecodeToRawFile] but uses libavif. Returns int[3] {w,h,channels}
     * on success, null on failure/unsupported.
     *
     * Exists because the system BitmapRegionDecoder cannot region-decode AVIF
     * on many OEMs ("Image format not supported"), so Telephoto's
     * AndroidImageRegionDecoder returns null → blurry preview + dead gestures.
     * Decoding the whole AVIF once into an SVRAW file lets RawImageRegionDecoder
     * tile-read from it, sidestepping the system codec entirely.
     *
     * Requires a full w×h×4 RGBA buffer during decode (freed after write).
     * Caller must probe source size first and reject sources exceeding the
     * native-heap ceiling (same as jxl_to_raw).
     */
    suspend fun avifDecodeToRawFile(uri: Uri, rawPath: String): IntArray? {
        if (!available) { AppLog.w(TAG, "avifDecodeToRawFile: native unavailable"); return null }
        val ctx = appContext ?: return null
        return withContext(Dispatchers.IO) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
                ?: run { AppLog.w(TAG, "avifDecodeToRawFile: openFileDescriptor null $uri"); return@withContext null }
            pfd.use {
                val fd = it.fd
                val len = it.statSize
                AppLog.d(TAG, "avifDecodeToRawFile: fd=$fd statSize=$len rawPath=$rawPath")
                if (len <= 0) { AppLog.w(TAG, "avifDecodeToRawFile: statSize=$len, skip"); return@use null }
                val result = try {
                    nativeAvifDecodeToRawFile(fd, len, rawPath)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "nativeAvifDecodeToRawFile exception fd=$fd len=$len", t)
                    null
                }
                if (result == null) AppLog.w(TAG, "nativeAvifDecodeToRawFile returned null fd=$fd len=$len")
                result
            }
        }
    }

    @JvmStatic private external fun nativeAvifDecodeToRawFile(
        fd: Int, len: Long, rawPath: String
    ): IntArray?

    /** DC-only thumbnail decode (1/64 work of full decode). Returns null to fall back. */
    @JvmStatic private external fun nativeDecodeJxlDcFd(fd: Int, len: Long): android.graphics.Bitmap?

    /** Try DC-only decode, null on failure (caller falls back to full stride decode). */
    suspend fun decodeJxlDc(uri: Uri): android.graphics.Bitmap? {
        if (!available) return null
        val ctx = appContext ?: return null
        return withContext(Dispatchers.IO) {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
            pfd.use {
                val fd = it.fd
                val len = it.statSize.takeIf { s -> s > 0 } ?: return@use null
                runCatching { nativeDecodeJxlDcFd(fd, len) }
                    .onFailure { AppLog.w(TAG, "nativeDecodeJxlDcFd failed", it) }
                    .getOrNull()
            }
        }
    }

    private const val TAG = "NativeBridge"
}

/** Returned by the native decoder when a full image is requested. */
data class NativeDecodeResult(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val colorDepth: Int,
    val isHdr: Boolean
)