package com.smartvision.gallery.ui.components

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.annotation.RequiresApi
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.smartvision.gallery.decoder.Decoder
import com.smartvision.gallery.decoder.image.AvifNativeDecoder
import com.smartvision.gallery.decoder.image.JxlNativeDecoder
import java.io.File
import java.security.MessageDigest

/**
 * Coil [Fetcher.Factory] for [Uri] models that prefers the system's pre-generated
 * thumbnail cache via [ContentResolver.loadThumbnail] (API 29+) before falling
 * back to Coil's default Uri fetch path.
 *
 * Why: AOSP's MediaProvider already maintains a thumbnail table for every image
 * in MediaStore — the system Photos app reads those MINI/Micro thumbnails
 * directly, getting a 512x512 (or smaller) bitmap with zero decode cost and
 * zero file I/O on cache hits. Coil's default UriFetcher instead opens the
 * full-size file and runs ImageDecoder on every request, which is ~10x slower
 * for a 4K photo and serializes through binder when 10+ grid cells load at
 * once. Picking the system cache is the single biggest lever for "media types
 * card grid feels instant on the curated page".
 *
 * Behavior:
 *  - `content://media/.../images/...` (MediaStore image content uri) on
 *    API 29+ ⇒ call `loadThumbnail(uri, Size(w, h), null)`. Return as
 *    BitmapDrawable with `DataSource.DISK` (system cache is persistent).
 *  - Any other uri (file://, content:// from a 3rd party provider, etc.) ⇒
 *    return `null` to let Coil's default UriFetcher handle it.
 *  - On API < 29 ⇒ return `null` (no `loadThumbnail`).
 *
 * Notes:
 *  - `loadThumbnail` blocks the calling thread; Coil runs fetchers on its
 *    fetcher dispatcher (see [AppImageLoaderFactory] — 16 threads), so this
 *    won't stall the main thread.
 *  - The(Size we pass is a hint — the system may return a smaller MINI thumb
 *    (512x512) or upscale to the requested size. We pass the Coil request
 *    size so the system can pick the right cached bitmap.
 *  - We deliberately skip video uris here — Coil's registered VideoFrameDecoder
 *    handles those via `MediaMetadataRetriever` (and `loadThumbnail` for video
 *    is Q+ only with a different code path).
 */
class SystemThumbnailFetcherFactory(
    private val context: Context,
) : Fetcher.Factory<Uri> {

    override fun create(
        data: Uri,
        options: Options,
        imageLoader: ImageLoader,
    ): Fetcher? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (data.scheme != ContentResolver.SCHEME_CONTENT) return null
        val authority = data.authority
        // MediaStore images authority is "media" on Q+. Skip video和 and
        // arbitrary content providers — let Coil's default UriFetcher handle
        // them. We don't pass 3rd-party content:// through loadThumbnail
        // because that call fails for providers which don't implement
        // MediaProvider's thumbnail contract.
        if (authority != "media") return null
        val path = data.path ?: return null
        if (!path.startsWith("/external/", ignoreCase = true) &&
            !path.contains("/images/", ignoreCase = true)
        ) return null

        return SystemThumbnailFetcher(context.applicationContext, data, options)
    }

    private class SystemThumbnailFetcher(
        private val context: Context,
        private val uri: Uri,
        private val options: Options,
    ) : Fetcher {
        private companion object {
            const val TAG = "SystemThumb"
        }

        // Lazily instantiated natives — only created when a JXL/AVIF cell hits.
        private val jxlDecoder: Decoder by lazy { JxlNativeDecoder(context) }
        private val avifDecoder: Decoder by lazy { AvifNativeDecoder(context) }

        /** WebP snapshot of native-decoded next-gen thumbs, keyed by uri+size.
         *  Coil's DrawableResult path never touches its disk cache, so without
         *  this the JXL cell "re-decodes every visit" (1s+ each pass) and
         *  reads as permanently blank. Writing a WebP replica lets Coil's
         *  default UriFetcher→ImageDecoder path replay it instantly forever. */
        private fun thumbCacheDir(): File =
            File(context.cacheDir, "native_thumbs").also { it.mkdirs() }


        @RequiresApi(Build.VERSION_CODES.Q)
        override suspend fun fetch(): FetchResult? {
            // Probe MIME from MediaStore first. Extension (from DISPLAY_NAME)
            // is authoritative for next-gen formats the system mislabels;
            // stored MIME otherwise. If the query itself fails (MediaProvider
            // hiccup), fall back to a per-format probe by reading the file header.
            val mime = mimeOf(uri) ?: probeMagic(uri)
            // Animated formats MUST NOT use the system thumbnail cache:
            // loadThumbnail returns the first frame as a static bitmap, which
            // freezes GIFs (the "GIF 播放不了" bug). GIFs are small enough that
            // Coil's own GifDecoder (registered in AppImageLoaderFactory) decodes
            // them from the source file cheaply; WebP/AVIF animations too.
            if (isAnimated(mime)) return null
            val isJxl = mime.equals("image/jxl", ignoreCase = true)
            val isAvif = mime.equals("image/avif", ignoreCase = true)

            val w = options.size?.width?.let { (it as? coil.size.Dimension.Pixels)?.px } ?: 512
            val h = options.size?.height?.let { (it as? coil.size.Dimension.Pixels)?.px } ?: 384
            val targetW = w.coerceIn(96, 1024)
            val targetH = h.coerceIn(96, 1024)

            // JXL: MediaProvider has no prebaked thumbnail — loadThumbnail returns
            // null and Coil's UriFetcher / ImageDecoder can't decode JXL either,
            // so the cell goes blank. Go straight to the native DC-layer decode.
            if (isJxl) {
                return nativeDecode(uri, targetW, targetH, "jxl", jxlDecoder)
            }

            val bmp = runCatching {
                context.contentResolver.loadThumbnail(uri, Size(targetW, targetH), null)
            }.getOrNull()
            if (bmp != null) {
                val t0 = System.nanoTime()
                val ms = (System.nanoTime() - t0) / 1_000_000
                val srcSize = querySourceSize(uri)
                com.smartvision.gallery.util.AppLog.i(
                    TAG,
                    "thumb uri=${uri.path} src=${srcSize} req=${targetW}x${targetH} bmp=${bmp.width}x${bmp.height} ${ms}ms"
                )
                return DrawableResult(
                    drawable = BitmapDrawable(context.resources, bmp),
                    isSampled = true,
                    dataSource = DataSource.DISK,
                )
            }

            // AVIF static has a system decoder on 12+ but loadThumbnail can still
            // miss (no cache entry); fall back to native so the cell isn't blank.
            if (isAvif) {
                return nativeDecode(uri, targetW, targetH, "avif", avifDecoder)
            }
            return null
        }

        /** Native decode fallback for next-gen formats the system can't thumbnail.
         *  Result is persisted as WebP so subsequent fetches hit disk (≈0ms)
         *  instead of re-running the 1s+ native decode every visit — that
         *  re-decode-on-every-scroll was the real reason JXL cells read as
         *  permanently blank ("无法保存缩略图"). */
        private suspend fun nativeDecode(
            uri: Uri,
            targetW: Int,
            targetH: Int,
            label: String,
            decoder: com.smartvision.gallery.decoder.Decoder,
        ): FetchResult? {
            val cacheFile = thumbCacheFile(uri, targetW, targetH, label)
            // Disk cache hit — decode the stored WebP replica (~0ms).
            val cached = runCatching {
                if (cacheFile.exists() && cacheFile.length() > 0)
                    BitmapFactory.decodeFile(cacheFile.absolutePath) else null
            }.getOrNull()
            if (cached != null) {
                com.smartvision.gallery.util.AppLog.i(
                    TAG, "${label} cached uri=${uri.path} ${cacheFile.length()}B"
                )
                return DrawableResult(
                    drawable = BitmapDrawable(context.resources, cached),
                    isSampled = true,
                    dataSource = DataSource.DISK,
                )
            }
            val t0 = System.nanoTime()
            val bmp = decoder.decodeThumbnail(uri, targetW, targetH)
            val ms = (System.nanoTime() - t0) / 1_000_000
            if (bmp == null) {
                com.smartvision.gallery.util.AppLog.i(
                    TAG,
                    "${label} native uri=${uri.path} req=${targetW}x${targetH} FAILED ${ms}ms"
                )
                return null
            }
            // Persist as WebP (q90). Temp file + atomic rename so a concurrent
            // fetch never reads a half-written replica.
            runCatching {
                cacheFile.parentFile?.mkdirs()
                val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
                tmp.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.WEBP, 90, out)
                }
                if (!tmp.renameTo(cacheFile)) tmp.delete()
            }.onFailure {
                com.smartvision.gallery.util.AppLog.w(TAG, "${label} cache write failed", it)
            }
            com.smartvision.gallery.util.AppLog.i(
                TAG,
                "${label} native uri=${uri.path} req=${targetW}x${targetH} bmp=${bmp.width}x${bmp.height} ${ms}ms"
            )
            return DrawableResult(
                drawable = BitmapDrawable(context.resources, bmp),
                isSampled = true,
                dataSource = DataSource.DISK,
            )
        }

        /** Stable cache key: uri + size + format, sha-1 (no MessageDigest rand). */
        private fun thumbCacheFile(uri: Uri, w: Int, h: Int, label: String): File {
            val md = MessageDigest.getInstance("SHA-1")
            val raw = "${uri}|${w}x${h}|${label}"
            val hex = md.digest(raw.toByteArray()).joinToString("") {
                "%02x".format(it)
            }
            return File(thumbCacheDir(), "${label}_${hex}.webp")
        }

        /** MIME type resolved from MediaStore, with the on-disk file extension
         *  taking precedence for next-gen formats. AOSP's MediaProvider does not
         *  recognise JXL/AVIF and often stores a wrong MIME_TYPE for them
         *  (`image/jpeg`, `octet-stream`), which would mis-route JXL covers to
         *  `loadThumbnail` → null → blank. The extension (from DISPLAY_NAME,
         *  reliable even for id-only `content://media/.../images/media/<id>`
         *  uris) is the source of truth for these. */
        private fun mimeOf(uri: Uri): String? {
            val (storedMime, displayName) = try {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.MediaStore.Images.Media.MIME_TYPE,
                        android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val m = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.MIME_TYPE))
                        val d = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media.DISPLAY_NAME))
                        m to d
                    } else null to null
                } ?: (null to null)
            } catch (t: Throwable) { null to null }
            val ext = displayName?.substringAfterLast('.', "")?.lowercase()
            // Extension is authoritative for formats the system mislabels.
            return when (ext) {
                "jxl" -> "image/jxl"
                "avif" -> "image/avif"
                "gif" -> "image/gif"
                else -> storedMime
            }
        }

        /** True for animated formats Coil's default pipeline must handle
         *  (its registered GifDecoder / VideoFrameDecoder). The system
         *  `loadThumbnail` would freeze the first frame; let Coil animate.
         *
         *  AVIF is NOT in this list: a static AVIF (the overwhelmingly common
         *  case, including 16K/32K stills) must reach loadThumbnail / nativeDecode
         *  below, or the gallery cell renders blank. A rare animated AVIF
         *  sequence would freeze on frame 0 via loadThumbnail — acceptable
         *  tradeoff vs. blanking every AVIF thumbnail. */
        private fun isAnimated(mime: String?): Boolean = when (mime?.lowercase()) {
            "image/gif" -> true
            "image/webp" -> true   // could be an animated webp
            else -> false
        }

        /** Header-magic MIME probe used only when the MediaStore query in
         *  [mimeOf] fails (should never happen on a healthy media DB, but a
         *  transient provider error must not blank the whole cover grid). */
        private fun probeMagic(uri: Uri): String? {
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val head = ByteArray(16)
                    val n = input.read(head)
                    when {
                        n >= 12 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> "image/jpeg"
                        n >= 8 && head[0] == 0x89.toByte() && head[1] == 0x50.toByte() &&
                            head[2] == 0x4E.toByte() && head[3] == 0x47.toByte() -> "image/png"
                        n >= 12 && head[0] == 0x00.toByte() && head[1] == 0x00.toByte() &&
                            head[2] == 0x00.toByte() && head[3] == 0x1C.toByte() &&
                            "ftypavei".indexOf(head[8].toInt().and(0xFF).toChar().lowercaseChar()) >= 0 -> "image/avif"
                        n >= 3 && head[0] == 'G'.code.toByte() && head[1] == 'I'.code.toByte() &&
                            head[2] == 'F'.code.toByte() -> "image/gif"
                        n >= 12 && head[0] == 0xFF.toByte() && head[1] == 0x0A.toByte() -> "image/jxl"
                        else -> null
                    }
                }
            } catch (t: Throwable) { null }
        }

        /** 原图尺寸 (MediaStore WIDTH/HEIGHT 列)。查不到时返回 "0x0"。 */
        private fun querySourceSize(uri: Uri): String {
            return try {
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        android.provider.MediaStore.Images.Media.WIDTH,
                        android.provider.MediaStore.Images.Media.HEIGHT,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val wI = c.getColumnIndex(android.provider.MediaStore.Images.Media.WIDTH)
                        val hI = c.getColumnIndex(android.provider.MediaStore.Images.Media.HEIGHT)
                        "${c.getInt(wI)}x${c.getInt(hI)}"
                    } else "0x0"
                } ?: "0x0"
            } catch (t: Throwable) {
                "0x0"
            }
        }
    }
}
