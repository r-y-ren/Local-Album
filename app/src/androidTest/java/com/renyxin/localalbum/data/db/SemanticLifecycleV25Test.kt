package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.core.search.SemanticVectorSpace
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticLifecycleV25Test {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test
    fun legacyAndCurrentSpacesAreIsolatedByDao() = kotlinx.coroutines.runBlocking {
        val current = SemanticVectorSpace("provider", "model", 1, 2)
        db.embeddingDao().insertEmbeddings(listOf(
            MediaEmbedding("/legacy.jpg", "1,0", 1),
            MediaEmbedding("/current.jpg", "1,0", 1, providerId = current.providerId, modelId = current.modelId, dimension = 2, spaceId = current.spaceId),
        ))
        assertEquals(listOf("/current.jpg"), db.embeddingDao().getPagedAfterInSpace(current.spaceId, null, 10).map { it.filePath })
        assertEquals(listOf("/legacy.jpg"), db.embeddingDao().getLegacyBackfillPage(SemanticVectorSpace.LEGACY_SPACE_ID, null, 10).map { it.filePath })
    }

    @Test
    fun v25SchemaContainsLifecycleTablesAndColumns() {
        val sqlite = db.openHelper.readableDatabase
        val tables = listOf("semantic_index_meta", "semantic_maintenance_runs", "semantic_cluster_generations", "semantic_cluster_meta", "semantic_cluster_members")
        tables.forEach { table ->
            sqlite.query("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'").use { assertTrue(it.moveToFirst()) }
        }
        sqlite.query("PRAGMA table_info(media_embeddings)").use { cursor ->
            val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
            assertTrue(names.containsAll(listOf("providerId", "modelId", "dimension", "spaceId", "generation", "codecId", "formatVersion")))
        }
        assertNotNull(sqlite)
    }
}
