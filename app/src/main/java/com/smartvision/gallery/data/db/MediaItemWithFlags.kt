package com.smartvision.gallery.data.db

import androidx.room.Embedded

data class MediaItemWithFlags(
    @Embedded val media: MediaEntity,
    @Embedded(prefix = "flag_") val flags: MediaFlagEntity? = null,
) {
    val isHidden: Boolean get() = flags?.isHidden ?: false
    val isFavorite: Boolean get() = flags?.isFavorite ?: false
    val isInTrash: Boolean get() = flags?.isTrash ?: false
    val vaultId: String? get() = flags?.vaultId
    /** Wall-clock ms when the flag row was last touched. Used by the trash page
     *  to render per-item day-countdown badges ("还剩 X 天"). 0 if no flag row. */
    val flagUpdatedAt: Long get() = flags?.updatedAt ?: 0L
}
