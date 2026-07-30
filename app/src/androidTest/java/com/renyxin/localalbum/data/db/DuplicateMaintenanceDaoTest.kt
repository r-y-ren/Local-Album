package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.data.db.entity.DuplicateHashStagingEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DuplicateMaintenanceDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun generation隔离且发布时原子切换active结果() = runBlocking {
        val dao = db.duplicateMaintenanceDao()
        val task = MaintenanceRunEntity.TASK_DUPLICATE_EXACT
        dao.insertRun(MaintenanceRunEntity(taskType = task, generation = 1))
        dao.insertHashes(duplicateHashes(1, "hash-old", "/a", "/b"))
        dao.markFinalizing(task, 1, 10)
        assertTrue(dao.finalizeAndPublish(task, 1, 11))
        assertEquals(1, dao.observeActiveGroups(task, 10, 0).first().single().generation)

        dao.insertRun(MaintenanceRunEntity(taskType = task, generation = 2))
        dao.insertHashes(duplicateHashes(2, "hash-new", "/c", "/d"))
        // generation 2 未完成时，UI 仍只能观察 generation 1。
        assertEquals(1, dao.observeActiveGroups(task, 10, 0).first().single().generation)
        dao.markFinalizing(task, 2, 20)
        assertTrue(dao.finalizeAndPublish(task, 2, 21))
        assertEquals(2, dao.observeActiveGroups(task, 10, 0).first().single().generation)
        assertFalse(requireNotNull(dao.getRun(task, 1)).isActive)
    }

    @Test
    fun cursor恢复且组失效后不再出现在active结果() = runBlocking {
        val dao = db.duplicateMaintenanceDao()
        val task = MaintenanceRunEntity.TASK_DUPLICATE_EXACT
        dao.insertRun(MaintenanceRunEntity(taskType = task, generation = 3))
        assertEquals(1, dao.advanceCursor(task, 3, "/cursor", 4, 3, 1, 10))
        assertEquals("/cursor", requireNotNull(dao.resumableRun(task)).cursorPath)
        dao.insertHashes(duplicateHashes(3, "hash", "/x", "/y"))
        dao.markFinalizing(task, 3, 11)
        dao.finalizeAndPublish(task, 3, 12)
        assertEquals(1, dao.invalidateGroup(3, "hash"))
        assertTrue(dao.observeActiveGroups(task, 10, 0).first().isEmpty())
    }

    @Test
    fun 旧generation永不混入新active成员() = runBlocking {
        val dao = db.duplicateMaintenanceDao()
        val task = MaintenanceRunEntity.TASK_DUPLICATE_EXACT
        dao.insertRun(MaintenanceRunEntity(taskType = task, generation = 7))
        dao.insertHashes(duplicateHashes(7, "same-hash", "/old-a", "/old-b"))
        dao.markFinalizing(task, 7, 1)
        dao.finalizeAndPublish(task, 7, 2)
        dao.insertRun(MaintenanceRunEntity(taskType = task, generation = 8))
        dao.insertHashes(duplicateHashes(8, "same-hash", "/new-a", "/new-b"))
        dao.markFinalizing(task, 8, 3)
        dao.finalizeAndPublish(task, 8, 4)
        val members = dao.getMembersBounded(8, "same-hash", 10)
        assertEquals(listOf("/new-a", "/new-b"), members.map { it.filePath }.sorted())
        assertNull(dao.getRun(task, 99))
    }

    private fun duplicateHashes(generation: Long, hash: String, vararg paths: String) = paths.map {
        DuplicateHashStagingEntity(generation, it, 10, hash, 0f, 1)
    }
}
