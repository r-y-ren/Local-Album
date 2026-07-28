package com.renyxin.localalbum.core.plugin

import com.renyxin.localalbum.data.db.dao.FeatureStoreDao
import com.renyxin.localalbum.data.db.entity.FeatureStoreEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FeatureStoreDao] 单元测试（Phase 1.5）。
 *
 * 使用内存模拟 DAO 验证异构特征存储的增删改查逻辑，
 * 包括多插件、多特征类型、增量补齐等场景。
 *
 * 同时验证 [FeatureSchema] 与 [FeatureStoreEntity] 的端到端编解码流程：
 * 插件输出 → schema 序列化 → 存入 feature_store → 查询 → 反序列化 → 还原。
 */
class FeatureStoreDaoTest {

    // ---- Fake DAO ----

    private class FakeFeatureStoreDao : FeatureStoreDao {
        private val store = mutableListOf<FeatureStoreEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: FeatureStoreEntity): Long {
            val existing = store.find { it.filePath == entity.filePath && it.pluginId == entity.pluginId }
            if (existing != null) {
                val idx = store.indexOf(existing)
                store[idx] = entity.copy(id = existing.id)
                return existing.id
            }
            val newEntity = entity.copy(id = nextId++.toLong())
            store.add(newEntity)
            return newEntity.id
        }

        override suspend fun insertAll(entities: List<FeatureStoreEntity>) {
            for (e in entities) insert(e)
        }

        override suspend fun deleteByFilePath(filePath: String) {
            store.removeAll { it.filePath == filePath }
        }

        override suspend fun deleteByFilePaths(filePaths: List<String>) {
            store.removeAll { it.filePath in filePaths }
        }

        override suspend fun deleteByPlugin(pluginId: String) {
            store.removeAll { it.pluginId == pluginId }
        }

        override suspend fun deleteByPluginAndFilePaths(pluginId: String, filePaths: List<String>) {
            store.removeAll { it.pluginId == pluginId && it.filePath in filePaths }
        }

        override suspend fun clearAll() {
            store.clear()
        }

        override suspend fun getByFilePath(filePath: String): List<FeatureStoreEntity> {
            return store.filter { it.filePath == filePath }
        }

        override fun getByFilePathFlow(filePath: String): Flow<List<FeatureStoreEntity>> {
            return flowOf(store.filter { it.filePath == filePath })
        }

        override suspend fun getByPlugin(pluginId: String): List<FeatureStoreEntity> {
            return store.filter { it.pluginId == pluginId }
        }

        override fun getByPluginFlow(pluginId: String): Flow<List<FeatureStoreEntity>> {
            return flowOf(store.filter { it.pluginId == pluginId })
        }

        override suspend fun getByFeatureType(featureType: String): List<FeatureStoreEntity> {
            return store.filter { it.featureType == featureType }
        }

        override suspend fun getByFilePathAndPlugin(filePath: String, pluginId: String): FeatureStoreEntity? {
            return store.find { it.filePath == filePath && it.pluginId == pluginId }
        }

        override suspend fun getAll(): List<FeatureStoreEntity> = store.toList()

        override suspend fun getCount(): Int = store.size

        override suspend fun getCountByPlugin(pluginId: String): Int =
            store.count { it.pluginId == pluginId }

        override suspend fun getCountByPluginAndVersion(pluginId: String, modelVersion: Int): Int =
            store.count { it.pluginId == pluginId && it.modelVersion == modelVersion }

        override suspend fun getPluginIds(): List<String> =
            store.map { it.pluginId }.distinct()

        override suspend fun getFeatureTypes(): List<String> =
            store.map { it.featureType }.distinct()

        override suspend fun getMissingFeatures(pluginId: String, limit: Int): List<String> {
            // Fake 实现：返回 store 中没有该 pluginId 特征的 filePath
            // 由于没有 media_items 表，这里简化为返回空列表
            return emptyList()
        }

        override suspend fun getStaleFeatures(pluginId: String, currentVersion: Int): List<String> {
            return store.filter { it.pluginId == pluginId && it.modelVersion != currentVersion }
                .map { it.filePath }
        }
    }

    // ---- 测试 ----

    @Test
    fun `insert and getByFilePath returns inserted entity`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        val entity = createFeatureEntity(
            filePath = "/photos/img1.jpg",
            pluginId = "clip_v1",
            featureType = "embedding",
        )
        dao.insert(entity)

        val results = dao.getByFilePath("/photos/img1.jpg")
        assertEquals(1, results.size)
        assertEquals("clip_v1", results[0].pluginId)
        assertEquals("embedding", results[0].featureType)
    }

    @Test
    fun `insert same filePath and pluginId replaces existing`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        val entity1 = createFeatureEntity(
            filePath = "/photos/img1.jpg",
            pluginId = "clip_v1",
            modelVersion = 1,
        )
        val entity2 = createFeatureEntity(
            filePath = "/photos/img1.jpg",
            pluginId = "clip_v1",
            modelVersion = 2,
        )
        dao.insert(entity1)
        dao.insert(entity2)

        val results = dao.getByFilePath("/photos/img1.jpg")
        assertEquals("同一 filePath+pluginId 应替换而非新增", 1, results.size)
        assertEquals(2, results[0].modelVersion)
    }

    @Test
    fun `insert different plugins for same filePath creates separate entries`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "facenet_v1"))

        val results = dao.getByFilePath("/photos/img1.jpg")
        assertEquals("不同插件应各自独立存储", 2, results.size)
    }

    @Test
    fun `getByPlugin returns all entries for that plugin`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "facenet_v1"))

        val clipResults = dao.getByPlugin("clip_v1")
        assertEquals(2, clipResults.size)
        assertTrue(clipResults.all { it.pluginId == "clip_v1" })
    }

    @Test
    fun `getByFeatureType returns all entries of that type`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1", featureType = "embedding"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "facenet_v1", featureType = "embedding"))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "yolo_v1", featureType = "detection_box"))

        val embeddings = dao.getByFeatureType("embedding")
        assertEquals(2, embeddings.size)
        assertTrue(embeddings.all { it.featureType == "embedding" })
    }

    @Test
    fun `getByFilePathAndPlugin returns exact match`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))

        val result = dao.getByFilePathAndPlugin("/photos/img1.jpg", "clip_v1")
        assertNotNull("精确匹配应返回结果", result)
        assertEquals("clip_v1", result!!.pluginId)

        val notFound = dao.getByFilePathAndPlugin("/photos/img1.jpg", "other_plugin")
        assertNull("不匹配的插件应返回 null", notFound)
    }

    @Test
    fun `deleteByFilePath removes all entries for that file`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "facenet_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))

        dao.deleteByFilePath("/photos/img1.jpg")

        assertEquals("img1 的所有特征应被删除", 0, dao.getByFilePath("/photos/img1.jpg").size)
        assertEquals("img2 不受影响", 1, dao.getByFilePath("/photos/img2.jpg").size)
    }

    @Test
    fun `deleteByPlugin removes all entries for that plugin`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "facenet_v1"))

        dao.deleteByPlugin("clip_v1")

        assertEquals("clip_v1 的所有特征应被删除", 0, dao.getByPlugin("clip_v1").size)
        assertEquals("facenet_v1 不受影响", 1, dao.getByPlugin("facenet_v1").size)
    }

    @Test
    fun `getCount returns total count`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "facenet_v1"))

        assertEquals(3, dao.getCount())
        assertEquals(2, dao.getCountByPlugin("clip_v1"))
        assertEquals(1, dao.getCountByPlugin("facenet_v1"))
    }

    @Test
    fun `getPluginIds returns distinct plugin ids`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "facenet_v1"))

        val ids = dao.getPluginIds()
        assertEquals(2, ids.size)
        assertTrue(ids.contains("clip_v1"))
        assertTrue(ids.contains("facenet_v1"))
    }

    @Test
    fun `getStaleFeatures returns paths with outdated model version`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1", modelVersion = 1))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1", modelVersion = 2))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "clip_v1", modelVersion = 1))

        val stale = dao.getStaleFeatures("clip_v1", 2)
        assertEquals("版本 1 的记录应被标记为过期", 2, stale.size)
        assertTrue(stale.contains("/photos/img1.jpg"))
        assertTrue(stale.contains("/photos/img3.jpg"))
    }

    @Test
    fun `getCountByPluginAndVersion filters correctly`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1", modelVersion = 1))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1", modelVersion = 2))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "clip_v1", modelVersion = 2))

        assertEquals(1, dao.getCountByPluginAndVersion("clip_v1", 1))
        assertEquals(2, dao.getCountByPluginAndVersion("clip_v1", 2))
    }

    @Test
    fun `clearAll removes everything`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "facenet_v1"))

        dao.clearAll()

        assertEquals(0, dao.getCount())
    }

    // ---- 端到端编解码测试 ----

    @Test
    fun `end-to-end feature storage round-trip preserves schema and data`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        val codec = PluginJsonCodec

        // 1. 构造插件输出
        val schema = FeatureSchema(
            pluginId = "clip_v1",
            featureType = "embedding",
            fields = listOf(
                FeatureSchema.FieldSpec(
                    name = "embedding",
                    dataType = FeatureSchema.DataType.FLOAT_ARRAY,
                    dims = listOf(512),
                    description = "CLIP 语义嵌入",
                ),
            ),
            modelVersion = 2,
        )
        val vector = FloatArray(512) { (it + 1) * 0.001f }
        val metadata = mapOf("source" to "clip", "normalized" to "true")

        // 2. 序列化并存储
        val schemaJson = codec.schemaToJson(schema)
        val dataJson = codec.featureDataToJson(vector, metadata)
        val entity = FeatureStoreEntity(
            filePath = "/photos/img1.jpg",
            pluginId = "clip_v1",
            featureType = "embedding",
            dataJson = dataJson,
            schemaJson = schemaJson,
            modelVersion = 2,
        )
        dao.insert(entity)

        // 3. 查询并反序列化
        val stored = dao.getByFilePathAndPlugin("/photos/img1.jpg", "clip_v1")
        assertNotNull("存储的记录应可查询", stored)

        val restoredSchema = codec.schemaFromJson(stored!!.schemaJson)
        val (restoredVector, restoredMetadata) = codec.featureDataFromJson(stored.dataJson)

        // 4. 验证还原
        assertEquals(schema.pluginId, restoredSchema.pluginId)
        assertEquals(schema.featureType, restoredSchema.featureType)
        assertEquals(schema.modelVersion, restoredSchema.modelVersion)
        assertEquals(schema.fields[0].name, restoredSchema.fields[0].name)
        assertEquals(schema.fields[0].dims, restoredSchema.fields[0].dims)
        assertEquals(vector.size, restoredVector.size)
        for (i in vector.indices) {
            assertEquals(vector[i], restoredVector[i], 0.0001f)
        }
        assertEquals(metadata, restoredMetadata)
    }

    @Test
    fun `end-to-end with heterogeneous multi-field schema`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        val codec = PluginJsonCodec

        // 异构 schema：检测框 + 标签 + 置信度 + 嵌入向量
        val schema = FeatureSchema(
            pluginId = "face_detector_v1",
            featureType = "detection_result",
            fields = listOf(
                FeatureSchema.FieldSpec("box", FeatureSchema.DataType.FLOAT_ARRAY, dims = listOf(4)),
                FeatureSchema.FieldSpec("label", FeatureSchema.DataType.STRING),
                FeatureSchema.FieldSpec("confidence", FeatureSchema.DataType.FLOAT),
                FeatureSchema.FieldSpec("embedding", FeatureSchema.DataType.FLOAT_ARRAY, dims = listOf(128)),
            ),
        )

        val schemaJson = codec.schemaToJson(schema)
        val dataJson = codec.featureDataToJson(
            floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f),
            mapOf("label" to "person", "confidence" to "0.95"),
        )

        dao.insert(FeatureStoreEntity(
            filePath = "/photos/group.jpg",
            pluginId = "face_detector_v1",
            featureType = "detection_result",
            dataJson = dataJson,
            schemaJson = schemaJson,
        ))

        val stored = dao.getByFilePathAndPlugin("/photos/group.jpg", "face_detector_v1")
        assertNotNull(stored)

        val restoredSchema = codec.schemaFromJson(stored!!.schemaJson)
        assertEquals(4, restoredSchema.fields.size)
        assertEquals("box", restoredSchema.fields[0].name)
        assertEquals(FeatureSchema.DataType.FLOAT_ARRAY, restoredSchema.fields[0].dataType)
        assertEquals(listOf(4), restoredSchema.fields[0].dims)
        assertEquals("embedding", restoredSchema.fields[3].name)
        assertEquals(listOf(128), restoredSchema.fields[3].dims)
        assertTrue("多字段 schema 不应是向量类型", !restoredSchema.isVectorType)
    }

    @Test
    fun `insertAll batch inserts multiple entities`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        val entities = listOf(
            createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"),
            createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"),
            createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "facenet_v1"),
        )
        dao.insertAll(entities)

        assertEquals(3, dao.getCount())
        assertEquals(2, dao.getByPlugin("clip_v1").size)
        assertEquals(1, dao.getByPlugin("facenet_v1").size)
    }

    @Test
    fun `insertAll with same filePath and pluginId replaces existing`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1", modelVersion = 1))
        dao.insertAll(listOf(
            createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1", modelVersion = 2),
        ))

        val results = dao.getByFilePath("/photos/img1.jpg")
        assertEquals(1, results.size)
        assertEquals(2, results[0].modelVersion)
    }

    @Test
    fun `deleteByFilePaths batch deletes`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "facenet_v1"))

        dao.deleteByFilePaths(listOf("/photos/img1.jpg", "/photos/img2.jpg"))

        assertEquals(1, dao.getCount())
        assertEquals("facenet_v1", dao.getAll()[0].pluginId)
    }

    @Test
    fun `deleteByPluginAndFilePaths removes matching entries only`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "facenet_v1"))

        dao.deleteByPluginAndFilePaths("clip_v1", listOf("/photos/img1.jpg"))

        assertEquals(2, dao.getCount())
        assertEquals(1, dao.getByPlugin("clip_v1").size)
        assertEquals(1, dao.getByPlugin("facenet_v1").size)
        assertEquals("/photos/img2.jpg", dao.getByPlugin("clip_v1")[0].filePath)
    }

    @Test
    fun `getByFilePathFlow emits current state`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))

        val flow = dao.getByFilePathFlow("/photos/img1.jpg")
        flow.collect { results ->
            assertEquals(1, results.size)
            assertEquals("clip_v1", results[0].pluginId)
            return@collect  // 仅收集一次
        }
    }

    @Test
    fun `getByPluginFlow emits current state`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))

        val flow = dao.getByPluginFlow("clip_v1")
        flow.collect { results ->
            assertEquals(2, results.size)
            assertTrue(results.all { it.pluginId == "clip_v1" })
            return@collect
        }
    }

    @Test
    fun `getAll returns all entities`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "facenet_v1"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1"))

        val all = dao.getAll()
        assertEquals(3, all.size)
    }

    @Test
    fun `getFeatureTypes returns distinct types`() = runBlocking {
        val dao = FakeFeatureStoreDao()
        dao.insert(createFeatureEntity(filePath = "/photos/img1.jpg", pluginId = "clip_v1", featureType = "embedding"))
        dao.insert(createFeatureEntity(filePath = "/photos/img2.jpg", pluginId = "clip_v1", featureType = "embedding"))
        dao.insert(createFeatureEntity(filePath = "/photos/img3.jpg", pluginId = "yolo_v1", featureType = "detection_box"))
        dao.insert(createFeatureEntity(filePath = "/photos/img4.jpg", pluginId = "sd_v1", featureType = "generated_image"))

        val types = dao.getFeatureTypes()
        assertEquals(3, types.size)
        assertTrue(types.contains("embedding"))
        assertTrue(types.contains("detection_box"))
        assertTrue(types.contains("generated_image"))
    }

    // ---- 辅助方法 ----

    private fun createFeatureEntity(
        filePath: String,
        pluginId: String,
        featureType: String = "embedding",
        modelVersion: Int = 1,
    ): FeatureStoreEntity {
        return FeatureStoreEntity(
            filePath = filePath,
            pluginId = pluginId,
            featureType = featureType,
            dataJson = """{"vector":[0.1,0.2,0.3]}""",
            schemaJson = """{"pluginId":"$pluginId","featureType":"$featureType","modelVersion":$modelVersion,"fields":[{"name":"embedding","dataType":"FLOAT_ARRAY","dims":[3]}]}""",
            modelVersion = modelVersion,
        )
    }
}
