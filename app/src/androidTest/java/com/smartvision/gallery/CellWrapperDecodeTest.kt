package com.smartvision.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Spike 验证：minimal single-item AVIF wrapper 能否被系统 BitmapFactory 解码。
 * cell0_wrap3.avif = 真实 grid cell(item 6, 2048x2048 YUV444p10 PQ）包成最小容器。
 */
@RunWith(AndroidJUnit4::class)
class CellWrapperDecodeTest {

    @Test
    fun decodeCellWrapper() {
        // 真机若无 spike 产物可跳过 — 单 cell wrapper 解码已由
        // AvifGridE2eTest.gridDecodesToRawAndTilesBack 的每 cell 路径覆盖。
        val path = "/sdcard/Download/cell0_wrap3.avif"
        val f = File(path)
        if (!f.exists()) {
            Log.i("CellSpike", "SKIP: $path absent (spike artifact; cell wrapper decode covered by AvifGridE2eTest)")
            return
        }
        assertTrue("wrapper file missing at $path (${f.length()}B)", f.exists())
        val bmp = BitmapFactory.decodeFile(path)
        assertNotNull("BitmapFactory returned null — system AVIF decoder rejected wrapper", bmp)
        Log.i("CellSpike", "wrapper decoded ${bmp!!.width}x${bmp.height} config=${bmp.config}")

        // 10-bit precision probe: sample a center pixel, see if high bits carry 10-bit data
        val px = getPixelSafe(bmp, bmp.width / 2, bmp.height / 2)
        val r = (px shr 6) and 0x3ff
        val g = (px shr 16) and 0x3ff
        val b = (px shr 26) and 0x3ff
        Log.i("CellSpike", "center pixel 1010102 raw=0x${px.toString(16)} r10=$r g10=$g b10=$b")
        bmp.recycle()
    }

    @Test
    fun decodeFullGridAsWhole() {
        // 验证系统是否能整幅解码 grid AVIF（8160x6050, 49.4M px）
        val path = "/sdcard/Download/avif_grid.avif"
        val f = File(path)
        assertTrue("grid file missing at $path (${f.length()}B)", f.exists())
        val bmp = BitmapFactory.decodeFile(path)
        if (bmp == null) {
            Log.i("CellSpike", "grid whole-decode returned null (expected for >MAX_PX grid — confirms need for per-cell)")
            return
        }
        Log.i("CellSpike", "grid whole-decoded ${bmp.width}x${bmp.height} config=${bmp.config}")
        bmp.recycle()
    }

    private fun getPixelSafe(bmp: Bitmap, x: Int, y: Int): Int {
        val w = intArrayOf(0)
        bmp.getPixels(w, 0, 1, x, y, 1, 1)
        return w[0]
    }
}