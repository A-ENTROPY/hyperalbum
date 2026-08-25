package com.smartvision.gallery.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.smartvision.gallery.data.model.MediaItem
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Heuristic image classifier that runs **without any TFLite / MNN binary**.
 *
 * Extracts low-level features from a downsampled thumbnail (mean color, color
 * variance, edge density, dark/light ratio) and pattern-matches against
 * heuristic rules to produce coarse scene tags like:
 *
 *  * "outdoor" / "sunset" / "sky"        — colour/hue cues
 *  * "portrait" / "people"              — warm skin-tone midtones + soft edges
 *  * "document"                         — high contrast + low color variance
 *  * "night"                            — very low mean luminance
 *  * "screenshot"                       — saturated UI palette + sharp edges
 *
 * Runs entirely on-device. Once a packaged TFLite / MNN model is available,
 * swap this implementation behind [AiService] with zero call-site changes.
 */
@Deprecated("AiTagger + AiModelHub 已替代所有功能")
class HeuristicClassifier(
    private val context: Context
) : AiService {

    private val bitmapPool = BitmapPool(maxPooled = 6)
    private val featuresCache = android.util.LruCache<String, Features>(64)
    private val deviceClass: AiAccelerator.DeviceClass by lazy {
        AiAccelerator.deviceClass(context)
    }

    override suspend fun tagsFor(item: MediaItem): List<String> {
        if (item.isVideo) return emptyList()
        return withContext(Dispatchers.Default) {
            // Downsample size depends on device class — high-end phones can
            // afford a larger feature map.
            val downsample = when (deviceClass) {
                AiAccelerator.DeviceClass.HIGH -> 96
                AiAccelerator.DeviceClass.MID -> 80
                AiAccelerator.DeviceClass.LOW -> 64
            }
            val key = "${item.uri}@$downsample"
            val cached = featuresCache.get(key)
            val features = cached ?: run {
                val bmp = decodeSmallThumbnail(item.uri, downsample) ?: return@withContext emptyList()
                try {
                    val f = extractFeatures(bmp, downsample)
                    featuresCache.put(key, f)
                    f
                } finally {
                    bitmapPool.release(bmp)
                }
            }
            classify(features)
        }
    }

    override suspend fun ocrFor(item: MediaItem): String {
        // Heuristic fallback: use the filename as pseudo-OCR text so search can
        // still find things by name when no model is loaded.
        val parts = mutableListOf<String>()
        parts += item.displayName.substringBeforeLast('.', "")
        return parts.joinToString(" ")
    }

    override suspend fun facesFor(item: MediaItem): List<FaceBox> {
        if (item.isVideo) return emptyList()
        // Use Android's built-in FaceDetector via reflection-free path: the API
        // is public on every platform version. We convert to RGB_565 first as
        // that's the only format FaceDetector supports reliably.
        return withContext(Dispatchers.Default) {
            val src = decodeSmallThumbnail(item.uri, downsample = 256) ?: return@withContext emptyList()
            try {
                val maxDim = 256
                val w: Int
                val h: Int
                if (src.width >= src.height) {
                    w = min(src.width, maxDim)
                    h = (src.height.toFloat() / src.width * w).toInt().coerceAtLeast(8)
                } else {
                    h = min(src.height, maxDim)
                    w = (src.width.toFloat() / src.height * h).toInt().coerceAtLeast(8)
                }
                val small = if (src.width != w || src.height != h) {
                    Bitmap.createScaledBitmap(src, w, h, true).also { if (it !== src) src.recycle() }
                } else src
                val rgb = if (small.config != Bitmap.Config.RGB_565) {
                    small.copy(Bitmap.Config.RGB_565, false).also { if (it !== small) small.recycle() }
                } else small
                val detector = android.media.FaceDetector(rgb.width, rgb.height, 4)
                val array = arrayOfNulls<android.media.FaceDetector.Face>(4)
                val count = detector.findFaces(rgb, array)
                val results = (0 until count).mapNotNull { i ->
                    val f = array[i] ?: return@mapNotNull null
                    val midPoint = android.graphics.PointF()
                    f.getMidPoint(midPoint)
                    val eyesDistance = f.eyesDistance()
                    val half = (eyesDistance * 1.8f).coerceAtLeast(8f)
                    FaceBox(
                        left = (midPoint.x - half).coerceAtLeast(0f),
                        top = (midPoint.y - half).coerceAtLeast(0f),
                        right = (midPoint.x + half).coerceAtMost(rgb.width.toFloat()),
                        bottom = (midPoint.y + half).coerceAtMost(rgb.height.toFloat()),
                        confidence = f.confidence() / 100f
                    )
                }
                if (rgb !== small) rgb.recycle()
                results
            } catch (t: Throwable) {
                AppLog.w(TAG, "FaceDetector failed for ${item.uri}", t)
                emptyList()
            }
        }
    }

    /**
     * Image features used by the classifier.
     */
    data class Features(
        val meanLuma: Float,
        val lumaStd: Float,
        val redMean: Float,
        val greenMean: Float,
        val blueMean: Float,
        val hueDominant: Float,
        val edgeDensity: Float,
        val darkRatio: Float,
        val brightRatio: Float
    )

    private fun extractFeatures(src: Bitmap, downsample: Int): Features {
        val w = downsample
        val h = (src.height.toFloat() / src.width * w).toInt().coerceAtLeast(8)
        val small = if (src.width == w && src.height == h) src
        else Bitmap.createScaledBitmap(src, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        var lumaSum = 0.0
        var lumaSqSum = 0.0
        var redSum = 0.0
        var greenSum = 0.0
        var blueSum = 0.0
        var darkCount = 0
        var brightCount = 0
        var edgeCount = 0
        val lastLumaRow = FloatArray(w)

        for (y in 0 until h) {
            var lastLuma = 0f
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val luma = 0.299 * r + 0.587 * g + 0.114 * b
                lumaSum += luma
                lumaSqSum += luma * luma
                redSum += r
                greenSum += g
                blueSum += b
                if (luma < 40) darkCount++
                if (luma > 215) brightCount++
                if (x > 0 && abs(luma - lastLuma) > 40) edgeCount++
                if (y > 0 && abs(luma - lastLumaRow[x]) > 40) edgeCount++
                lastLuma = luma.toFloat()
                lastLumaRow[x] = luma.toFloat()
            }
        }
        small.recycle()
        val n = (w * h).toDouble()
        val meanLuma = (lumaSum / n).toFloat()
        val lumaStd = sqrt((lumaSqSum / n) - meanLuma * meanLuma).toFloat()
        val redMean = (redSum / n).toFloat()
        val greenMean = (greenSum / n).toFloat()
        val blueMean = (blueSum / n).toFloat()
        val hue = dominantHue(redMean, greenMean, blueMean)
        val nf = n.toFloat()
        return Features(
            meanLuma = meanLuma,
            lumaStd = lumaStd,
            redMean = redMean,
            greenMean = greenMean,
            blueMean = blueMean,
            hueDominant = hue,
            edgeDensity = edgeCount / (nf * 2f),
            darkRatio = darkCount / nf,
            brightRatio = brightCount / nf
        )
    }

    private fun dominantHue(r: Float, g: Float, b: Float): Float {
        val rn = r / 255f; val gn = g / 255f; val bn = b / 255f
        val mx = max(rn, max(gn, bn))
        val mn = min(rn, min(gn, bn))
        if (mx - mn < 0.05f) return -1f
        var h = when (mx) {
            rn -> 60f * (((gn - bn) / (mx - mn)) % 6f)
            gn -> 60f * (((bn - rn) / (mx - mn)) + 2f)
            else -> 60f * (((rn - gn) / (mx - mn)) + 4f)
        }
        if (h < 0f) h += 360f
        return h
    }

    private fun classify(f: Features): List<String> {
        val tags = mutableListOf<String>()

        when {
            f.meanLuma < 35f -> tags += "night"
            f.meanLuma > 200f -> tags += "bright"
        }

        when {
            f.hueDominant in 0f..25f || f.hueDominant in 330f..360f -> tags += "sunset"
            f.hueDominant in 180f..240f -> tags += "sky"
            f.hueDominant in 60f..100f -> tags += "foliage"
        }

        val warmth = f.redMean - f.blueMean
        if (warmth > 30 && f.lumaStd < 50 && f.meanLuma in 80f..170f) tags += "portrait"
        if (warmth > 40 && f.hueDominant in 10f..50f) tags += "warm"

        if (f.lumaStd > 70 && abs(warmth) < 15 && f.hueDominant < 0) tags += "document"

        val saturation = if (f.meanLuma > 0) abs(f.redMean - f.blueMean) / f.meanLuma else 0f
        if (f.edgeDensity > 0.18f && saturation > 0.4f) tags += "screenshot"

        if (f.greenMean > 100 && f.blueMean > 80 && f.meanLuma in 100f..200f) tags += "outdoor"

        if (warmth in 5f..30f && f.meanLuma in 100f..200f) tags += "people"

        return tags.distinct()
    }

    private fun decodeSmallThumbnail(uri: Uri, downsample: Int = 64): Bitmap? {
        // Use BitmapFactory with inSampleSize so we only allocate the bytes
        // we actually need; the resulting bitmap is still full-quality.
        return try {
            val bounds = readBounds(uri) ?: return null
            val sample = computeInSampleSize(bounds.first, bounds.second, downsample)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (t: Throwable) {
            AppLog.w(TAG, "decodeSmallThumbnail failed for $uri", t)
            null
        }
    }

    /** Read the image width/height without allocating the full bitmap. */
    private fun readBounds(uri: Uri): Pair<Int, Int>? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    } catch (t: Throwable) {
        null
    }

    private fun computeInSampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while ((halfW / sample) >= target && (halfH / sample) >= target) sample *= 2
        return sample.coerceAtLeast(1)
    }

    private companion object {
        private const val TAG = "HeuristicClassifier"
    }
}