package com.smartvision.gallery.data.model

import java.util.concurrent.TimeUnit

/**
 * One row of the trash page grid. Wraps [MediaItem] with the wall-clock timestamp
 * the trash flag was applied, so the UI can render a per-item day-countdown badge
 * ("29 天后删除") and compute retention-aware affordances.
 */
data class TrashEntry(
    val item: MediaItem,
    val trashedAtMs: Long,
) {
    /** Days remaining before iOS-style 30-day auto-purge. Floors at 0. */
    fun daysRemaining(nowMs: Long = System.currentTimeMillis()): Int {
        if (trashedAtMs <= 0L) return 0
        val ageMs = nowMs - trashedAtMs
        val remainingMs = TimeUnit.DAYS.toMillis(TRASH_RETENTION_DAYS) - ageMs
        return (remainingMs / TimeUnit.DAYS.toMillis(1)).toInt().coerceAtLeast(0)
    }

    companion object {
        const val TRASH_RETENTION_DAYS = 30L
    }
}
