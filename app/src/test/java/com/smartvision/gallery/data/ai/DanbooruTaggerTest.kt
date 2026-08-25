package com.smartvision.gallery.data.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DanbooruTaggerTest {

    @Test
    fun `extractCharacterTag returns first underscore tag not in exclude list`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("1girl", 0.99f),
            DanbooruTagger.TaggedTag("hatsune_miku", 0.95f),
            DanbooruTagger.TaggedTag("solo", 0.90f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isEqualTo("hatsune_miku")
    }

    @Test
    fun `extractCharacterTag skips exclude list tags`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("thick_eyebrows", 0.98f),
            DanbooruTagger.TaggedTag("rem_(re:zero)", 0.85f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isEqualTo("rem_(re:zero)")
    }

    @Test
    fun `extractCharacterTag returns null when no underscore tag found`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("1girl", 0.99f),
            DanbooruTagger.TaggedTag("solo", 0.90f),
            DanbooruTagger.TaggedTag("blush", 0.80f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isNull()
    }

    @Test
    fun `extractCharacterTag returns null when only exclude list tags match`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("thick_eyebrows", 0.95f),
            DanbooruTagger.TaggedTag("animal_ear_fluff", 0.90f),
            DanbooruTagger.TaggedTag("swept_bangs", 0.85f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isNull()
    }

    @Test
    fun `extractCharacterTag returns null on empty list`() {
        val result = DanbooruTagger.extractCharacterTag(emptyList())
        assertThat(result).isNull()
    }

    @Test
    fun `extractCharacterTag handles parenthesized character names`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("rem_(re:zero)", 0.95f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isEqualTo("rem_(re:zero)")
    }

    @Test
    fun `extractCharacterTag skips hair suffix tags`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("long_hair", 0.99f),
            DanbooruTagger.TaggedTag("hatsune_miku", 0.95f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isEqualTo("hatsune_miku")
    }

    @Test
    fun `extractCharacterTag skips eyes suffix tags`() {
        val tags = listOf(
            DanbooruTagger.TaggedTag("blue_eyes", 0.98f),
            DanbooruTagger.TaggedTag("solo", 0.90f),
        )
        val result = DanbooruTagger.extractCharacterTag(tags)
        assertThat(result).isNull()
    }
}