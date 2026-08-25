package com.smartvision.gallery.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IoUtilsPathTest {

    @Test
    fun bucket_name_extracted_from_path() {
        val path = "/storage/emulated/0/DCIM/Camera/IMG_001.jpg"
        val segments = path.split('/')
        val bucket = segments[segments.size - 2]
        assertEquals("Camera", bucket)
    }
}