package com.smartvision.gallery.ai

import android.content.Context
import com.smartvision.gallery.data.model.MediaItem

/**
 * AI service interface. Implementations can plug in different engines (heuristic,
 * TFLite, MNN, ML Kit, server-side, etc.) without changing call sites.
 *
 *  * [tagsFor]    — high-level semantic tags ("outdoor", "portrait", "document").
 *  * [ocrFor]     — extracted text content for full-text search.
 *  * [facesFor]   — bounding boxes for detected faces.
 */
interface AiService {
    suspend fun tagsFor(item: MediaItem): List<String>
    suspend fun ocrFor(item: MediaItem): String
    suspend fun facesFor(item: MediaItem): List<FaceBox>
}

data class FaceBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float
)

/** Default no-op implementation used when no AI model is loaded. */
class StubAiService : AiService {
    override suspend fun tagsFor(item: MediaItem): List<String> = emptyList()
    override suspend fun ocrFor(item: MediaItem): String = ""
    override suspend fun facesFor(item: MediaItem): List<FaceBox> = emptyList()
}

object AiServiceLocator {
    @Volatile private var current: AiService = StubAiService()

    fun get(@Suppress("UNUSED_PARAMETER") context: Context): AiService = current
    fun set(service: AiService) { current = service }
}