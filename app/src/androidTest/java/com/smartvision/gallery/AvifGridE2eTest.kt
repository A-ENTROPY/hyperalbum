package com.smartvision.gallery

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.IntRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.smartvision.gallery.ui.viewer.AvifRawPrecacher
import com.smartvision.gallery.ui.viewer.RawImageRegionDecoder
import kotlinx.coroutines.runBlocking
import me.saket.telephoto.subsamplingimage.internal.ImageRegionDecoder
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device grid e2e: a grid AVIF (>8K, e.g. 8160×6050) end-to-end through
 * AvifRawPrecacher.decodeToRaw → SVRAW → RawImageRegionDecoder tile read.
 * Confirms the per-cell path tiles correctly and the region decoder reads the
 * full bounds, so a 16K–32K grid shows sharp instead of "cannot display".
 *
 * Requires /sdcard/Download/avif_grid.avif (push a real grid AVIF before running).
 */
@RunWith(AndroidJUnit4::class)
class AvifGridE2eTest {

    @Test
    fun gridDecodesToRawAndTilesBack() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val path = "/sdcard/Download/avif_grid.avif"
        val f = java.io.File(path)
        if (!f.exists()) {
            android.util.Log.i("AvifGridE2e", "SKIP: $path absent (push a grid AVIF to run)")
            return@runBlocking
        }
        val uri = Uri.fromFile(f)
        val precacher = AvifRawPrecacher(ctx)
        precacher.rawFile(uri).delete() // force fresh decode

        val raw = precacher.decodeToRaw(uri)
        assertThat(raw).isNotNull()
        assertThat(raw!!.length()).isGreaterThan(20L)

        val dec = RawImageRegionDecoder(raw)
        val size = dec.imageSize
        android.util.Log.i("AvifGridE2e", "decoded raw imageSize=${size.width}x${size.height}")
        assertThat(size.width).isGreaterThan(8000) // grid AVIFs are >8K by definition

        // Read a center tile at full res; should succeed and produce a non-trivial bitmap.
        val cx = size.width / 2; val cy = size.height / 2
        val tile = IntRect(cx - 64, cy - 64, cx + 64, cy + 64)
        val res: ImageRegionDecoder.DecodeResult = dec.decodeRegion(tile, 1)
        val bmpField = res::class.java.getDeclaredField("painter")
        bmpField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val painter = bmpField.get(res) as androidx.compose.ui.graphics.painter.BitmapPainter
        val imgField = painter::class.java.getDeclaredField("image")
        imgField.isAccessible = true
        val img = imgField.get(painter) as androidx.compose.ui.graphics.ImageBitmap
        val bmp = img.asAndroidBitmap()
        android.util.Log.i("AvifGridE2e", "tile ${bmp.width}x${bmp.height} config=${bmp.config} " +
            "center px=0x${Integer.toHexString(bmp.getPixel(64, 64))}")
        assertThat(bmp.width).isEqualTo(128)
        assertThat(bmp.height).isEqualTo(128)
    }
}
