package com.smartvision.gallery.data.db

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MediaFlagDaoAiTest {

    private lateinit var db: SmartVisionDatabase
    private lateinit var dao: MediaFlagDao

    @Before
    fun setUp() {
        db = TestDbFactory.buildInMemory()
        dao = db.mediaFlagDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** findPendingAi/countPendingAi JOIN media — each flag row needs a matching media row. */
    private suspend fun insertMedia(uri: String, width: Int = 100, height: Int = 100) {
        db.mediaDao().upsertMedia(
            mediaId = 0, uri = uri, displayName = uri.substringAfterLast('/'),
            mimeType = "image/jpeg", format = "JPEG", sizeBytes = 1000L,
            width = width, height = height, dateTakenMs = 0L, dateModifiedMs = 0L,
            durationMs = null, bucketName = null, bucketPath = null,
            latitude = null, longitude = null, aiTags = listOf(""),
            ocrText = null, hashSha1 = null, isLivePhoto = false
        )
    }

    @Test
    fun `updateAiFields writes all 7 columns`() = runTest {
        dao.upsert(MediaFlagEntity(uri = "content://media/1"))

        dao.updateAiFields(
            uri = "content://media/1",
            domain = "people",
            subDomain = "portrait",
            copyright = "anime",
            faceCount = 2,
            faceArea = 0.42f,
            score = 0.87f,
            version = 1,
            taggedAt = 1_700_000_000_000L
        )

        val row = dao.findByUri("content://media/1")!!
        assertThat(row.aiDomain).isEqualTo("people")
        assertThat(row.aiSubDomain).isEqualTo("portrait")
        assertThat(row.aiCopyright).isEqualTo("anime")
        assertThat(row.aiFaceCount).isEqualTo(2)
        assertThat(row.aiFaceArea).isEqualTo(0.42f)
        assertThat(row.aiScore).isEqualTo(0.87f)
        assertThat(row.aiVersion).isEqualTo(1)
        assertThat(row.aiTaggedAt).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `findPendingAi excludes trash and rows at target version`() = runTest {
        listOf("u1", "u2", "u3", "u4").forEach { insertMedia(it) }
        dao.upsert(MediaFlagEntity(uri = "u1", aiVersion = 0))
        dao.upsert(MediaFlagEntity(uri = "u2", aiVersion = 0))
        dao.upsert(MediaFlagEntity(uri = "u3", aiVersion = 1))
        dao.upsert(MediaFlagEntity(uri = "u4", aiVersion = 0, isTrash = true))

        val pending = dao.findPendingAi(version = 1, limit = 10)

        assertThat(pending.map { it.uri }).containsExactly("u1", "u2")
    }

    @Test
    fun `countPendingAi and countDoneAi are consistent`() = runTest {
        listOf("u1", "u2", "u3", "u4").forEach { insertMedia(it) }
        dao.upsert(MediaFlagEntity(uri = "u1", aiVersion = 0))
        dao.upsert(MediaFlagEntity(uri = "u2", aiVersion = 0))
        dao.upsert(MediaFlagEntity(uri = "u3", aiVersion = 2))
        dao.upsert(MediaFlagEntity(uri = "u4", aiVersion = 2, isTrash = true))

        assertThat(dao.countPendingAi(version = 2)).isEqualTo(2)
        assertThat(dao.countDoneAi(version = 2)).isEqualTo(1)
    }

    @Test
    fun `setHidden does not clobber ai columns`() = runTest {
        dao.upsert(
            MediaFlagEntity(
                uri = "u1",
                aiDomain = "people",
                aiScore = 0.5f,
                aiVersion = 1
            )
        )

        dao.setHidden(uri = "u1", isHidden = true, updatedAt = 100L)

        val row = dao.findByUri("u1")!!
        assertThat(row.isHidden).isTrue()
        assertThat(row.aiDomain).isEqualTo("people")
        assertThat(row.aiScore).isEqualTo(0.5f)
        assertThat(row.aiVersion).isEqualTo(1)
    }
}