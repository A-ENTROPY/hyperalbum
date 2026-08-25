package com.smartvision.gallery.data.repo

import android.net.Uri

/**
 * One spacetime-cluster of photos (used by 回忆之旅). `bucketLabel` is a human-readable
 * label such as "2024-03"; `geoLabel` is the rounded lat/lng when available.
 */
data class MemoryCluster(
    val id: String,
    val bucketLabel: String,
    val heroUri: Uri,
    val photoUris: List<Uri>,
    val count: Int,
    val dateRangeStart: Long,
    val dateRangeEnd: Long,
    val geoLabel: String?
)
