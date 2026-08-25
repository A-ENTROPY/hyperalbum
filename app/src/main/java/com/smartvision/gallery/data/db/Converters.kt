package com.smartvision.gallery.data.db

import androidx.room.TypeConverter

private const val AI_TAGS_SEPARATOR = "" // ASCII Unit Separator (U+001F)

class Converters {
    @TypeConverter
    fun fromAiTags(tags: List<String>?): String =
        tags?.joinToString(AI_TAGS_SEPARATOR) ?: ""

    @TypeConverter
    fun toAiTags(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList()
        else value.split(AI_TAGS_SEPARATOR).filter { it.isNotEmpty() }
}