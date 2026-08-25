package com.smartvision.gallery.decoder.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.smartvision.gallery.data.model.DecodedPayload
import com.smartvision.gallery.decoder.Decoder
import com.smartvision.gallery.decoder.bridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JxlNativeDecoder(private val context: Context) : Decoder {

    override val id = "jxl"

    override suspend fun decodeThumbnail(
        uri: Uri,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        // v46: DC-layer decode first — ~1/64 full cost for a thumbnail-quality
        // image. Falls back to the full stride decode only when DC fails.
        NativeBridge.decodeJxlDc(uri)
            ?: NativeBridge.decodeJxlScaled(uri, maxOf(targetWidthPx, targetHeightPx))?.bitmap
    }

    override suspend fun decodeFull(
        uri: Uri,
        maxWidthPx: Int?,
        maxHeightPx: Int?
    ): DecodedPayload? = withContext(Dispatchers.IO) {
        // Pass maxLongEdge to native; halving picks smallest 2^n stride
        // that yields an output not larger than the source AND at most the
        // requested long edge. 4096 is the Canvas-safe target — see spec.
        val longEdge = maxOf(maxWidthPx ?: 4096, maxHeightPx ?: 4096)
        val res = NativeBridge.decodeJxlScaled(uri, longEdge)
            ?: return@withContext null
        DecodedPayload.BitmapPayload(
            width = res.width,
            height = res.height,
            sourceUri = uri,
            decoderId = id,
            bitmap = res.bitmap,
            colorDepth = res.colorDepth,
            isHdr = res.isHdr
        )
    }
}
