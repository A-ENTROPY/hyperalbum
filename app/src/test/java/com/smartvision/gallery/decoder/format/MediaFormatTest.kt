package com.smartvision.gallery.decoder.format

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaFormatTest {

    @Test
    fun extension_lookup_jpeg() {
        assertEquals(MediaFormat.JPEG, MediaFormat.fromFilename("foo.jpg"))
        assertEquals(MediaFormat.JPEG, MediaFormat.fromFilename("FOO.JPEG"))
    }

    @Test
    fun extension_lookup_avif() {
        assertEquals(MediaFormat.AVIF_STATIC, MediaFormat.fromFilename("photo.avif"))
        assertEquals(MediaFormat.AVIF_STATIC, MediaFormat.fromFilename("photo.avifs"))
    }

    @Test
    fun extension_lookup_unknown() {
        assertEquals(MediaFormat.UNKNOWN, MediaFormat.fromFilename("foo.xyz"))
        assertEquals(MediaFormat.UNKNOWN, MediaFormat.fromFilename(null))
        assertEquals(MediaFormat.UNKNOWN, MediaFormat.fromFilename(""))
    }
}