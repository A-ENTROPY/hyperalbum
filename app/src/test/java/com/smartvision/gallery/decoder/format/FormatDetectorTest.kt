package com.smartvision.gallery.decoder.format

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class FormatDetectorTest {

    @Test
    fun detects_jpeg() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(60)
        assertEquals(MediaFormat.JPEG, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun detects_png() {
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        ) + ByteArray(56)
        assertEquals(MediaFormat.PNG, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun detects_gif() {
        val bytes = "GIF89a".toByteArray() + ByteArray(58)
        assertEquals(MediaFormat.GIF, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun detects_avif_via_ftyp() {
        // ISOBMFF header: size(4)=32, 'ftyp', major_brand='avif', minor=0
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x20,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'a'.code.toByte(), 'v'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(),
            0x00, 0x00, 0x00, 0x00
        ) + ByteArray(48)
        assertEquals(MediaFormat.AVIF_STATIC, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun detects_heic_via_ftyp() {
        val bytes = byteArrayOf(
            0x00, 0x00, 0x00, 0x18,
            'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
            'h'.code.toByte(), 'e'.code.toByte(), 'i'.code.toByte(), 'c'.code.toByte(),
            0x00, 0x00, 0x00, 0x00
        ) + ByteArray(48)
        assertEquals(MediaFormat.HEIC, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun detects_jxl_bare_codestream() {
        // JXL bare codestream starts with 0xFF 0x0A
        val bytes = byteArrayOf(0xFF.toByte(), 0x0A.toByte()) + ByteArray(62)
        assertEquals(MediaFormat.JXL, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun detects_jxl_container() {
        val bytes = "JXL ".toByteArray() + 0x0D.toByte() + ByteArray(59)
        assertEquals(MediaFormat.JXL, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun detects_riff_webp() {
        val bytes = "RIFF".toByteArray() +
            byteArrayOf(0x00, 0x00, 0x00, 0x00) +
            "WEBP".toByteArray() + ByteArray(48)
        assertEquals(MediaFormat.WEBP_STATIC, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun detects_bmp() {
        val bytes = byteArrayOf('B'.code.toByte(), 'M'.code.toByte()) + ByteArray(62)
        assertEquals(MediaFormat.BMP, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }

    @Test
    fun unknown_returns_unknown() {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04) + ByteArray(60)
        assertEquals(MediaFormat.UNKNOWN, FormatDetector.detectFromBytes(ByteBuffer.wrap(bytes)))
    }
}