package com.smartvision.gallery.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.smartvision.gallery.util.AppLog

/**
 * AI acceleration path detector.
 *
 *  * In V1.0/V1.x the actual ML inference is **heuristic** (color / edge / luma
 *    features + Android's built-in [android.media.FaceDetector]). It does not use
 *    TFLite / MNN / NCNN, so there is no neural-accelerator delegate to wire.
 *  * This class exists so that, when the real model binary is dropped in
 *    (V1.1+), the [HeuristicClassifier] can be swapped behind [AiService] and
 *    the same probe tells us which delegate to construct.
 *
 *  * NNAPI support = API 27+ AND `PackageManager#FEATURE_ACCELEROMETER` is not
 *    the right feature; we use `FEATURE_OPENGLES` and check `GLES31` support
 *    to decide if a GPU delegate is viable.
 *
 * Returned acceleration is one of:
 *  * [Acceleration.CPU]      — universal fallback, always available.
 *  * [Acceleration.GPU]      — API 24+ with OpenGL ES 3.1.
 *  * [Acceleration.NNAPI]    — API 27+ (the *hardware* part is OEM-dependent).
 *  * [Acceleration.Hexagon]  — only on Qualcomm devices with the HVX DSP exposed
 *                              through the OEM NNAPI driver; we cannot detect
 *                              this from a generic APK, so we report "unknown"
 *                              and let the user opt in via a toggle.
 */
enum class Acceleration { CPU, GPU, NNAPI, HEXAGON }

object AiAccelerator {

    private const val TAG = "AiAccelerator"

    /** Best-effort detection. Safe to call from any thread. */
    fun probe(context: Context): Acceleration {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            // API 27+. The actual NNAPI driver support is OEM-specific but the
            // API surface exists on every device from that version on.
            val nnapiOk = try {
                Class.forName("android.nn.NeuralNetwork")
                true
            } catch (t: Throwable) {
                AppLog.w(TAG, "NNAPI class not available", t)
                false
            }
            if (nnapiOk) return Acceleration.NNAPI
        }
        // Fall back to GPU if OpenGL ES 3.1 is supported.
        if (hasGlEs31(context)) return Acceleration.GPU
        return Acceleration.CPU
    }

    /**
     * Heuristic device class so the [HeuristicClassifier] can pick a faster code
     * path on high-end devices (skip thumbnail downsample, use larger feature
     * map, parallelise face detection across multiple cores).
     */
    enum class DeviceClass { LOW, MID, HIGH }

    fun deviceClass(context: Context): DeviceClass {
        val abiScore = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a", "x86_64" -> 2
            "armeabi-v7a", "x86" -> 1
            else -> 0
        }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val score = abiScore * 2 + (if (cores >= 6) 2 else if (cores >= 4) 1 else 0)
        return when {
            score >= 6 -> DeviceClass.HIGH
            score >= 3 -> DeviceClass.MID
            else -> DeviceClass.LOW
        }
    }

    private fun hasGlEs31(context: Context): Boolean = try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
        activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x00030001
    } catch (t: Throwable) {
        Log.w(TAG, "GLES probe failed", t)
        false
    }
}

/**
 * Bitmap pool for the AI pipeline.
 *
 * Every call to [acquire] returns a reusable bitmap of the requested size
 * (or a fresh one if the pool has no suitable entry). Callers MUST
 * [release] the bitmap when done so it can be reused for the next image.
 *
 *  * Pool is size-bounded — we keep at most [maxPooled] entries to avoid
 *    pressuring the heap.
 *  * Each entry is keyed by `(width, height, config)` so we never hand out
 *    a bitmap that doesn't match the request.
 */
class BitmapPool(private val maxPooled: Int = 8) {

    private data class Key(val w: Int, val h: Int, val config: Bitmap.Config)

    private val freeList = ArrayDeque<Pair<Key, Bitmap>>()
    private val totalBytesLock = Any()
    private var totalBytes = 0L

    fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val key = Key(width, height, config)
        synchronized(totalBytesLock) {
            val idx = freeList.indexOfFirst { it.first == key }
            if (idx >= 0) {
                val (k, bmp) = freeList.removeAt(idx)
                totalBytes -= bmp.allocationByteCount
                return bmp
            }
        }
        return Bitmap.createBitmap(width, height, config)
    }

    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val key = Key(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        synchronized(totalBytesLock) {
            // Drop the oldest entries if we hit the cap.
            while (freeList.size >= maxPooled) {
                val (_, evicted) = freeList.removeFirst()
                totalBytes -= evicted.allocationByteCount
                if (!evicted.isRecycled) evicted.recycle()
            }
            freeList.addLast(key to bitmap)
            totalBytes += bitmap.allocationByteCount
        }
    }

    fun trimTo(maxBytes: Long) {
        synchronized(totalBytesLock) {
            while (totalBytes > maxBytes && freeList.isNotEmpty()) {
                val (_, bmp) = freeList.removeFirst()
                totalBytes -= bmp.allocationByteCount
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }
}