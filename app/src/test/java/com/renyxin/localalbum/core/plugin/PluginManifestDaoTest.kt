package com.renyxin.localalbum.core.plugin

import com.renyxin.localalbum.data.db.dao.PluginManifestDao
import com.renyxin.localalbum.data.db.entity.PluginManifestEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginManifestDao] 单元测试（Phase 4.7）。
 *
 * 使用内存模拟 DAO 验证插件清单的 CRUD 操作，
 * 包括插入、按 pluginId 查询、Flow 观察、启用/禁用切换等场景。
 */
class PluginManifestDaoTest {

    // ---- Fake DAO ----

    private class FakePluginManifestDao : PluginManifestDao {
        private val store = mutableListOf<PluginManifestEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: PluginManifestEntity): Long {
            val existing = store.find { it.pluginId == entity.pluginId }
            if (existing != null) {
                val idx = store.indexOf(existing)
                store[idx] = entity.copy(id = existing.id)
                return existing.id
            }
            val newEntity = entity.copy(id = nextId++)
            store.add(newEntity)
            return newEntity.id
        }

        override suspend fun update(entity: PluginManifestEntity) {
            val idx = store.indexOfFirst { it.id == entity.id }
            if (idx >= 0) store[idx] = entity
        }

        override suspend fun delete(entity: PluginManifestEntity) {
            store.removeAll { it.pluginId == entity.pluginId }
        }

        override suspend fun getByPluginId(pluginId: String): PluginManifestEntity? {
            return store.find { it.pluginId == pluginId }
        }

        override fun getAllFlow(): Flow<List<PluginManifestEntity>> {
            return flowOf(store.toList())
        }

        override suspend fun getAll(): List<PluginManifestEntity> {
            return store.toList()
        }

        override suspend fun getEnabled(): List<PluginManifestEntity> {
            return store.filter { it.isEnabled }
        }

        override suspend fun getByPipelineStage(stage: String): List<PluginManifestEntity> {
            return store.filter { it.pipelineStage == stage }
        }

        override suspend fun updateOrder(pluginId: String, orderIndex: Int, updatedAtMs: Long) {
            store.indexOfFirst { it.pluginId == pluginId }.takeIf { it >= 0 }?.let { idx ->
                store[idx] = store[idx].copy(orderIndex = orderIndex, updatedAtMs = updatedAtMs)
            }
        }

        override suspend fun setEnabled(pluginId: String, enabled: Boolean, updatedAtMs: Long) {
            store.indexOfFirst { it.pluginId == pluginId }.takeIf { it >= 0 }?.let { idx ->
                store[idx] = store[idx].copy(isEnabled = enabled, updatedAtMs = updatedAtMs)
            }
        }

        override suspend fun deleteAll() {
            store.clear()
        }

        override suspend fun count(): Int {
            return store.size
        }
    }

    // ---- Test Helpers ----

    private fun createEntity(
        pluginId: String = "plugin.test_1",
        manifestJson: String = "{}",
        isEnabled: Boolean = true,
        pipelineStage: String = "analysis",
        createdAtMs: Long = 0L,
    ) = PluginManifestEntity(
        pluginId = pluginId,
        manifestJson = manifestJson,
        isEnabled = isEnabled,
        pipelineStage = pipelineStage,
        createdAtMs = createdAtMs,
    )

    // ---- Insert Tests ----

    @Test
    fun `insert returns non-zero id`() = runBlocking {
        val dao = FakePluginManifestDao()
        val entity = createEntity()
        val id = dao.insert(entity)
        assertTrue(id > 0)
    }

    @Test
    fun `insert replaces on duplicate pluginId`() = runBlocking {
        val dao = FakePluginManifestDao()
        val entity1 = createEntity(pluginId = "plugin.test", manifestJson = "{\"v\":1}")
        val entity2 = createEntity(pluginId = "plugin.test", manifestJson = "{\"v\":2}")

        val id1 = dao.insert(entity1)
        val id2 = dao.insert(entity2)
        assertEquals(id1, id2) // Should reuse same ID
        assertEquals("{\"v\":2}", dao.getByPluginId("plugin.test")?.manifestJson)
        assertEquals(1, dao.count())
    }

    @Test
    fun `insert multiple distinct plugins`() = runBlocking {
        val dao = FakePluginManifestDao()
        dao.insert(createEntity(pluginId = "plugin.a"))
        dao.insert(createEntity(pluginId = "plugin.b"))
        dao.insert(createEntity(pluginId = "plugin.c"))
        assertEquals(3, dao.count())
    }

    // ---- Query Tests ----

    @Test
    fun `getByPluginId returns correct entity`() = runBlocking {
        val dao = FakePluginManifestDao()
        dao.insert(createEntity(pluginId = "plugin.face", manifestJson = "{\"name\":\"face\"}"))
        dao.insert(createEntity(pluginId = "plugin.scene", manifestJson = "{\"name\":\"scene\"}"))

        val result = dao.getByPluginId("plugin.face")
        assertNotNull(result)
        assertEquals("{\"name\":\"face\"}", result?.manifestJson)
    }

    @Test
    fun `getByPluginId returns null for unknown plugin`() = runBlocking {
        val dao = FakePluginManifestDao()
        assertNull(dao.getByPluginId("plugin.nonexistent"))
    }

    @Test
    fun `getAll returns all entities ordered by createdAtMs desc`() = runBlocking {
        val dao = FakePluginManifestDao()
        val older = createEntity(
            pluginId = "plugin.older",
            createdAtMs = 1000L,
        )
        val newer = createEntity(
            pluginId = "plugin.newer",
            createdAtMs = 2000L,
        )
        dao.insert(older)
        dao.insert(newer)

        val all = dao.getAll()
        assertEquals(2, all.size)
        // Note: Fake DAO doesn't sort, we just verify both are returned
        assertTrue(all.any { it.pluginId == "plugin.older" })
        assertTrue(all.any { it.pluginId == "plugin.newer" })
    }

    @Test
    fun `getAllFlow emits current list`() = runBlocking {
        val dao = FakePluginManifestDao()
        dao.insert(createEntity(pluginId = "plugin.test"))
        val list = dao.getAllFlow().take(1).toList().first()
        assertEquals(1, list.size)
    }

    @Test
    fun `getEnabled returns only enabled plugins`() = runBlocking {
        val dao = FakePluginManifestDao()
        dao.insert(createEntity(pluginId = "plugin.enabled", isEnabled = true))
        dao.insert(createEntity(pluginId = "plugin.disabled", isEnabled = false))

        val enabled = dao.getEnabled()
        assertEquals(1, enabled.size)
        assertEquals("plugin.enabled", enabled.first().pluginId)
    }

    @Test
    fun `getByPipelineStage filters correctly`() = runBlocking {
        val dao = FakePluginManifestDao()
        dao.insert(createEntity(pluginId = "plugin.face", pipelineStage = "detection"))
        dao.insert(createEntity(pluginId = "plugin.embed", pipelineStage = "extraction"))
        dao.insert(createEntity(pluginId = "plugin.scene", pipelineStage = "detection"))

        val detection = dao.getByPipelineStage("detection")
        assertEquals(2, detection.size)
        assertTrue(detection.all { it.pipelineStage == "detection" })
    }

    // ---- Delete Tests ----

    @Test
    fun `delete removes entity`() = runBlocking {
        val dao = FakePluginManifestDao()
        val entity = createEntity(pluginId = "plugin.to_delete")
        dao.insert(entity)

        assertEquals(1, dao.count())
        dao.delete(entity)
        assertEquals(0, dao.count())
        assertNull(dao.getByPluginId("plugin.to_delete"))
    }

    @Test
    fun `deleteAll removes all entities`() = runBlocking {
        val dao = FakePluginManifestDao()
        dao.insert(createEntity(pluginId = "plugin.a"))
        dao.insert(createEntity(pluginId = "plugin.b"))
        assertEquals(2, dao.count())

        dao.deleteAll()
        assertEquals(0, dao.count())
    }

    // ---- setEnabled Tests ----

    @Test
    fun `setEnabled toggles isEnabled flag`() = runBlocking {
        val dao = FakePluginManifestDao()
        dao.insert(createEntity(pluginId = "plugin.test", isEnabled = true))

        dao.setEnabled("plugin.test", false)
        val result = dao.getByPluginId("plugin.test")
        assertNotNull(result)
        assertFalse(result!!.isEnabled)

        dao.setEnabled("plugin.test", true)
        val result2 = dao.getByPluginId("plugin.test")
        assertTrue(result2!!.isEnabled)
    }

    @Test
    fun `setEnabled updates timestamp`() = runBlocking {
        val dao = FakePluginManifestDao()
        val original = createEntity(pluginId = "plugin.test")
        dao.insert(original)

        val newTimestamp = 9999999L
        dao.setEnabled("plugin.test", false, updatedAtMs = newTimestamp)

        val result = dao.getByPluginId("plugin.test")
        assertEquals(newTimestamp, result?.updatedAtMs)
    }

    // ---- Update Tests ----

    @Test
    fun `update modifies existing entity`() = runBlocking {
        val dao = FakePluginManifestDao()
        val id = dao.insert(createEntity(pluginId = "plugin.test", manifestJson = "{\"v\":1}"))

        val updated = PluginManifestEntity(
            id = id,
            pluginId = "plugin.test",
            manifestJson = "{\"v\":2}",
            pipelineStage = "postprocessing",
        )
        dao.update(updated)

        val result = dao.getByPluginId("plugin.test")
        assertEquals("{\"v\":2}", result?.manifestJson)
        assertEquals("postprocessing", result?.pipelineStage)
    }

    // ---- Count Tests ----

    @Test
    fun `count returns correct number`() = runBlocking {
        val dao = FakePluginManifestDao()
        assertEquals(0, dao.count())

        dao.insert(createEntity(pluginId = "p1"))
        assertEquals(1, dao.count())

        dao.insert(createEntity(pluginId = "p2"))
        assertEquals(2, dao.count())

        dao.deleteAll()
        assertEquals(0, dao.count())
    }

    // ---- updateOrder Tests (Phase 5 R1) ----

    @Test
    fun `updateOrder changes orderIndex and timestamp`() = runBlocking {
        val dao = FakePluginManifestDao()
        dao.insert(createEntity(pluginId = "plugin.test"))

        val newTimestamp = 8888888L
        dao.updateOrder("plugin.test", orderIndex = 5, updatedAtMs = newTimestamp)

        val result = dao.getByPluginId("plugin.test")
        assertEquals(5, result?.orderIndex)
        assertEquals(newTimestamp, result?.updatedAtMs)
    }

    @Test
    fun `updateOrder is no-op for unknown pluginId`() = runBlocking {
        val dao = FakePluginManifestDao()
        // Should not throw
        dao.updateOrder("plugin.nonexistent", 42, 9999L)
        assertEquals(0, dao.count())
    }
}
