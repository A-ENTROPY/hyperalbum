package com.smartvision.gallery.data.model

import android.net.Uri
import android.os.Parcelable
import com.smartvision.gallery.decoder.format.MediaFormat
import kotlinx.parcelize.Parcelize

/**
 * A user-visible grouping of [MediaItem]s.
 *
 *  - [kind] = [AlbumKind.SMART_*] are derived on the fly from the media table.
 *  - [kind] = [AlbumKind.USER] is an explicit pin/collection.
 *  - [kind] = [AlbumKind.FORMAT_FILTER] is a virtual album backed by a `format` filter.
 */
@Parcelize
data class Album(
    val id: String,
    val name: String,
    val kind: AlbumKind,
    val coverUri: Uri?,
    val itemCount: Int,
    val latestDateMs: Long,
    val formatFilter: MediaFormat? = null,
    val bucketPath: String? = null
) : Parcelable

enum class AlbumKind {
    SMART_TIMELINE,
    SMART_PEOPLE,
    SMART_SCENE,
    SMART_FORMAT,
    USER,
    FORMAT_FILTER,
    BUCKET,
    FAVORITES,
    HIDDEN_VAULT,
    TRASH
}