package com.smartvision.gallery.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the production [bucketFromPath] (MediaStoreDataSource.kt) — tests the
 * real implementation, not an inline copy, so the two can never drift again.
 */
class BucketFromPathTest {
    @Test fun `DCIM Camera path returns DCIM-Camera`() {
        assertEquals("DCIM/Camera", bucketFromPath("/storage/emulated/0/DCIM/Camera/IMG_001.jpg"))
    }

    @Test fun `standard Android storage path returns last two directories`() {
        // dropLast(1) drops the filename → dirs=[storage, emulated, 0, Download],
        // last 2 = "0/Download" (the user-facing namespace, not /storage/...).
        assertEquals("0/Download", bucketFromPath("/storage/emulated/0/Download/file.zip"))
    }

    @Test fun `empty path returns root`() {
        assertEquals("root", bucketFromPath(""))
    }

    @Test fun `single directory in root returns itself`() {
        // dirs=[Download] hits the 1-segment branch → return dirs[0] verbatim.
        assertEquals("Download", bucketFromPath("/Download/file.zip"))
    }
}