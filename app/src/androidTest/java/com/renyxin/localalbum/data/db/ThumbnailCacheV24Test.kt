package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailCacheV24Test {
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    @Test
    fun migration保留旧任务与grid路径并创建独立metadata表() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-23-24-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE media_items(filePath TEXT PRIMARY KEY NOT NULL, modifiedAtMs INTEGER NOT NULL, fileSize INTEGER NOT NULL, thumbnailPath TEXT)")
            db.execSQL("CREATE TABLE thumbnail_tasks(taskId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
            db.execSQL("INSERT INTO media_items VALUES('/a.jpg', 12, 34, '/cache/a.webp')")
            AppDatabase.MIGRATION_23_24.migrate(db)
            db.query("SELECT COUNT(*) FROM thumbnail_cache_entries").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            db.query("SELECT thumbnailPath FROM media_items WHERE filePath='/a.jpg'").use {
                assertTrue(it.moveToFirst())
                assertEquals("/cache/a.webp", it.getString(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='thumbnail_tasks'").use {
                assertTrue(it.moveToFirst())
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun 唯一任务提升优先级且领取高优先级优先() = runBlocking {
        val dao = database.thumbnailTaskDao()
        dao.enqueueOrPromote(ThumbnailTaskEntity(filePath = "/a", sourceVersion = "1", mediaType = "IMAGE", priority = 0, createdAt = 1, updatedAt = 1))
        dao.enqueueOrPromote(ThumbnailTaskEntity(filePath = "/a", sourceVersion = "1", mediaType = "IMAGE", priority = 100, createdAt = 2, updatedAt = 2))
        dao.enqueueOrPromote(ThumbnailTaskEntity(filePath = "/b", sourceVersion = "1", mediaType = "IMAGE", priority = 50, createdAt = 1, updatedAt = 1))
        val claimed = dao.claimBatch(10, 2, "lease", 1000)
        assertEquals(listOf("/a", "/b"), claimed.map { it.filePath })
    }

    @Test
    fun touch节流版本隔离且淘汰候选保护最近访问和租约() = runBlocking {
        val dao = database.thumbnailCacheDao()
        dao.upsert(ThumbnailCacheEntryEntity("/old", "grid", "v1", "/cache/old", 10, 1, 1))
        dao.upsert(ThumbnailCacheEntryEntity("/recent", "grid", "v1", "/cache/recent", 10, 950, 1))
        dao.upsert(ThumbnailCacheEntryEntity("/leased", "grid", "v1", "/cache/leased", 10, 2, 1, leaseUntil = 2000))
        assertNotNull(dao.getReady("/old", "grid", "v1"))
        assertNull(dao.getReady("/old", "grid", "v2"))
        assertEquals(0, dao.touchThrottled("/old", "grid", "v1", 1000, 0))
        assertEquals(1, dao.touchThrottled("/old", "grid", "v1", 1000, 10))
        val candidates = dao.evictionCandidatesAfter(1000, 900, Long.MIN_VALUE, "", "", "", 10)
        assertEquals(emptyList<String>(), candidates.map { it.filePath })
    }
}
