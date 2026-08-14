package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.QualityScoreUpdate
import com.renyxin.localalbum.data.db.dao.SceneTypeUpdate
import com.renyxin.localalbum.data.db.entity.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class EnhancementBatchWriteDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun sceneAndQualityResultsPersistInBoundedBatchesWithoutOverwritingEachOther() = runBlocking {
        val items = (0 until 251).map { index -> media("/batch-$index.jpg") }
        database.mediaDao().insertAll(items)

        items.map { SceneTypeUpdate(it.filePath, "landscape") }
            .chunked(MediaDao.ENHANCEMENT_WRITE_BATCH_SIZE)
            .forEach { database.mediaDao().setSceneTypes(it) }
        items.mapIndexed { index, item -> QualityScoreUpdate(item.filePath, index / 250f) }
            .chunked(MediaDao.ENHANCEMENT_WRITE_BATCH_SIZE)
            .forEach { database.mediaDao().setQualityScores(it) }

        val first = requireNotNull(database.mediaDao().getByFilePathLight(items.first().filePath))
        val last = requireNotNull(database.mediaDao().getByFilePathLight(items.last().filePath))
        assertEquals("landscape", first.sceneType)
        assertEquals("landscape", last.sceneType)
        assertEquals(0f, first.qualityScore)
        assertEquals(1f, last.qualityScore)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sceneDaoRejectsTransactionLargerThanAdmissionBudget() = runBlocking {
        database.mediaDao().setSceneTypes(
            (0..MediaDao.ENHANCEMENT_WRITE_BATCH_SIZE).map { index ->
                SceneTypeUpdate("/too-large-$index.jpg", "other")
            },
        )
    }

    private fun media(path: String) = MediaEntity(
        filePath = path,
        fileName = path.substringAfterLast('/'),
        mediaType = MediaType.IMAGE,
        capturedAtMs = 1L,
        modifiedAtMs = 1L,
        parentPath = "/",
    )
}
