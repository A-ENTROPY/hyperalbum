package com.smartvision.gallery.decoder

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.smartvision.gallery.data.model.DecodedPayload

/**
 * Strategy interface implemented by every decoder the loader can route to.
 *
 * Implementations MUST be safe to call from the IO dispatcher; they MUST NOT touch the
 * main thread. They MUST return null (not throw) on recoverable failure so the loader can
 * fall through to the next decoder in the chain.
 */
interface Decoder {
    val id: String

    suspend fun decodeThumbnail(uri: Uri, targetWidthPx: Int, targetHeightPx: Int): Bitmap?

    suspend fun decodeFull(uri: Uri, maxWidthPx: Int?, maxHeightPx: Int?): DecodedPayload?

    /** For animated formats: yields individual frames as bitmaps. Default: empty flow. */
    suspend fun frameStream(uri: Uri): List<Bitmap> = emptyList()
}