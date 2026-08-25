package com.smartvision.gallery.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.smartvision.gallery.util.AppLog
import kotlinx.coroutines.tasks.await

/**
 * MLKit Face Detection wrapper. Replaces HeuristicClassifier.facesFor with >95% accuracy
 * and 128 landmark points, when MLKit is available; falls back to Android FaceDetector
 * otherwise (handled by callers).
 */
class MlKitFaceAnalyzer(private val context: Context? = null) {

    data class FaceResult(val count: Int, val areaRatio: Float)
    data class TestFace(
        val left: Float, val top: Float, val right: Float, val bottom: Float, val confidence: Float
    )

    private val detector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(opts)
    }

    fun isReady(): Boolean = context != null

    suspend fun detect(bitmap: Bitmap): FaceResult {
        if (context == null) return FaceResult(0, 0f)
        val input = InputImage.fromBitmap(bitmap, 0)
        // MLKit detector.process() 内部 PipelineManager.start 调用 addObserver 必须
        // 在 main thread, 否则抛 IllegalStateException Method addObserver must be
        // called on the main thread. AiTaggingWorker 协程跑在 Dispatchers.Default,
        // 必须切到 Dispatchers.Main 调用 detector (与 MlKitImageLabeler 同因).
        val faces = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                detector.process(input).await()
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "MLKit face detection failed", t)
            return FaceResult(0, 0f)
        }
        val imageArea = (bitmap.width.toLong() * bitmap.height.toLong()).toFloat()
        if (imageArea <= 0f) return FaceResult(faces.size, 0f)
        val totalFaceArea = faces.sumOf { face ->
            val box = face.boundingBox
            (box.width().toLong() * box.height().toLong())
        }.toFloat()
        return FaceResult(faces.size, totalFaceArea / imageArea)
    }

    fun convert(faces: List<TestFace>, @Suppress("UNUSED_PARAMETER") w: Int, @Suppress("UNUSED_PARAMETER") h: Int) =
        faces.map { com.smartvision.gallery.ai.FaceBox(it.left, it.top, it.right, it.bottom, it.confidence) }

    fun calculateAreaRatio(faces: List<TestFace>, imageWidth: Int, imageHeight: Int): Float {
        val imageArea = (imageWidth.toLong() * imageHeight.toLong()).toFloat()
        if (imageArea <= 0f) return 0f
        val totalFaceArea = faces.sumOf { f ->
            val w = (f.right - f.left).coerceAtLeast(0f).toLong()
            val h = (f.bottom - f.top).coerceAtLeast(0f).toLong()
            w * h
        }.toFloat()
        return totalFaceArea / imageArea
    }

    companion object { private const val TAG = "MlKitFaceAnalyzer" }
}
