package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.data.db.entity.BackupImportStagingEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration26To27Test {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun genericBackupStagingIsGenerationIsolatedAndKeysetPaged() = runBlocking {
        val dao = db.backupStagingDao()
        dao.insertAll(listOf(
            BackupImportStagingEntity("g1", "media.ndjson", 1, "a", "{}"),
            BackupImportStagingEntity("g1", "media.ndjson", 2, "b", "{}"),
            BackupImportStagingEntity("g2", "media.ndjson", 1, "c", "{}"),
        ))
        assertEquals(listOf(2L), dao.getAfter("g1", "media.ndjson", 1, 10).map { it.lineNumber })
        assertEquals(2, dao.count("g1", "media.ndjson"))
        dao.clearGeneration("g1")
        assertEquals(0, dao.count("g1", "media.ndjson"))
        assertEquals(1, dao.count("g2", "media.ndjson"))
    }
}
