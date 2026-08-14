package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.search.FtsQueryBuilder
import com.renyxin.localalbum.core.search.KeywordSearchProfile
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.edition.EditionConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Executes the edition-owned, column-qualified MATCH expressions against Android SQLite FTS4. */
class KeywordSearchProfileFtsTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        database.mediaDao().insertAll(
            listOf(
                MediaEntity(
                    filePath = FILE_PATH,
                    fileName = FILE_NAME,
                    mediaType = MediaType.IMAGE,
                    capturedAtMs = 1L,
                    modifiedAtMs = 2L,
                    parentPath = PARENT_PATH,
                    make = "Google",
                    model = "Pixel 8",
                    // Treat this as inert AI data retained from an imported Full backup.
                    ocrText = OCR_ONLY_TOKEN,
                ),
            ),
        )
        database.mediaDao().insertFtsEntry(
            filePath = FILE_PATH,
            fileName = FILE_NAME,
            parentPath = PARENT_PATH,
            ocrText = OCR_ONLY_TOKEN,
            make = "Google",
            model = "Pixel 8",
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun compiledEditionExecutesFileNameAndParentPathQueries() = runBlocking {
        val profile = EditionConfiguration.features.keywordSearchProfile

        assertEquals(1, count("trip", profile))
        assertEquals(1, count("CameraTrips", profile))
        assertEquals(1, count("Pix", profile))
    }

    @Test
    fun importedOcrTextIsSearchableOnlyWhenCompiledEditionAllowsOcr() = runBlocking {
        val profile = EditionConfiguration.features.keywordSearchProfile
        val expected = if (profile == KeywordSearchProfile.FULL) 1 else 0

        assertEquals(expected, count(OCR_ONLY_TOKEN, profile))
    }

    private suspend fun count(input: String, profile: KeywordSearchProfile): Int =
        database.mediaDao().countFts(FtsQueryBuilder.build(input, profile))

    private companion object {
        const val FILE_PATH = "/storage/emulated/0/Pictures/CameraTrips/trip.jpg"
        const val FILE_NAME = "trip.jpg"
        const val PARENT_PATH = "/storage/emulated/0/Pictures/CameraTrips"
        const val OCR_ONLY_TOKEN = "ultravioletcipher"
    }
}
