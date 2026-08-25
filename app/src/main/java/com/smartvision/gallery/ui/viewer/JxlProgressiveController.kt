package com.smartvision.gallery.ui.viewer

import android.net.Uri
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.MediaLoader
import com.smartvision.gallery.decoder.format.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JxlProgressiveController(
    private val mediaLoader: MediaLoader,
    private val scope: CoroutineScope,
    private val sourceUri: Uri,
    private val sourceLongEdgePx: Long,
    private val maxTargetPx: Int = 6144
) {
    @Volatile var currentTargetPx: Int = 0
        private set

    @Volatile private var currentStride: Int = 1

    private var pending: Job? = null

    fun requestTarget(
        targetLongEdgePx: Int,
        onBitmapReady: (DecodedPayload?) -> Unit
    ) {
        val clamped = targetLongEdgePx.coerceAtLeast(256).coerceAtMost(maxTargetPx)
        val newStride = computeStrideForTarget(sourceLongEdgePx, clamped)
        // Only reload when the 2^n stride actually changes — saves a full
        // decode on every small scale tick (1.0× → 1.05× → 1.1× all share
        // the same native-tile stride).
        if (currentTargetPx != 0 && newStride == currentStride) return
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(10)
            val payload = mediaLoader.loadFullUri(
                uri = sourceUri,
                format = MediaFormat.JXL,
                maxDimensionPx = clamped
            )
            currentTargetPx = clamped
            currentStride = newStride
            withContext(Dispatchers.Main) { onBitmapReady(payload) }
        }
    }

    fun seed(targetPx: Int) {
        currentTargetPx = targetPx.coerceAtLeast(256).coerceAtMost(maxTargetPx)
        currentStride = computeStrideForTarget(sourceLongEdgePx, currentTargetPx)
    }

    companion object {
        /**
         * Upper safety bound on the source JXL long edge. Sources above this
         * size skip the libjxl pipeline entirely and the viewer renders only
         * a tiny preview — there is no ROI/crop API in libjxl 0.10.3, so
         * area-restricted re-decode for the visible viewport would cost 5-30s
         * per pan and is non-responsive on a phone. Beyond this size the
         * industry (Google Photos, Apple Photos, Lightroom Mobile) also
         * downsamples; we follow suit.
         */
        const val MAX_SUPPORTED_LONG_EDGE_PX: Long = 32768L

        @JvmStatic
        fun computeStrideForTarget(sourceLongEdge: Long, targetLongEdge: Int): Int {
            require(sourceLongEdge > 0 && targetLongEdge > 0)
            // Smallest 2^n stride with output ≤ target. Guarantees output
            // never exceeds the 6144 Canvas-safe cap.
            var s = 1
            while (sourceLongEdge / s > targetLongEdge) s *= 2
            return s
        }

        @JvmStatic
        fun computeInitialTargetPx(sourceLongEdge: Long): Int {
            // Ask for source/4 on first paint. That keeps the output at most
            // source/4 long edge, which for any source ≤ 24K stays under the
            // 6144 cap so the stride is the native 2^n halving and the output
            // is comfortably Canvas-safe (24K/4 = 6K). For 32K source the
            // output is 8192×6144 = 50MP — still over the Canvas ceiling, so
            // the 6144 clamp kicks in and the stride loop bumps to s=8,
            // yielding 4096 — safe.
            val raw = (sourceLongEdge / 4).toInt()
            return raw.coerceAtLeast(256).coerceAtMost(6144)
        }

        @JvmStatic
        fun shouldReload(sourceLongEdge: Long, currentStride: Int, targetLongEdgePx: Int): Boolean =
            computeStrideForTarget(sourceLongEdge, targetLongEdgePx) != currentStride

        @JvmStatic
        fun isTooLarge(sourceLongEdge: Long): Boolean =
            sourceLongEdge > MAX_SUPPORTED_LONG_EDGE_PX
    }
}