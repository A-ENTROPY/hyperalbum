package com.smartvision.gallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "media_flags",
    indices = [
        Index(value = ["is_hidden"]),
        Index(value = ["is_trash"]),
        Index(value = ["is_favorite"]),
        Index(value = ["ai_score"]),
        Index(value = ["ai_domain"]),
    ]
)
data class MediaFlagEntity(
    @PrimaryKey @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean = false,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_trash") val isTrash: Boolean = false,
    @ColumnInfo(name = "vault_id") val vaultId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0L,
    @ColumnInfo(name = "ai_domain") val aiDomain: String? = null,
    @ColumnInfo(name = "ai_sub_domain") val aiSubDomain: String? = null,
    @ColumnInfo(name = "ai_copyright") val aiCopyright: String? = null,
    @ColumnInfo(name = "ai_face_count", defaultValue = "0") val aiFaceCount: Int? = 0,
    @ColumnInfo(name = "ai_face_area", defaultValue = "0") val aiFaceArea: Float? = 0f,
    @ColumnInfo(name = "ai_score", defaultValue = "0") val aiScore: Float? = 0f,
    @ColumnInfo(name = "ai_version", defaultValue = "0") val aiVersion: Int? = 0,
    @ColumnInfo(name = "ai_tagged_at", defaultValue = "0") val aiTaggedAt: Long? = 0L,
    // v9: 二次元分类栏目 — 存 Danbooru 模型 top-20 tags + character + style 标签
    // JSON 字符串: [{"t":"1girl","s":0.99},...]. 仅 domain==anime 时写入.
    @ColumnInfo(name = "ai_danbooru_tags", defaultValue = "NULL") val aiDanbooruTags: String? = null,
)
