package com.renyxin.localalbum.data.backup

import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.FaceClusterSummary
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DatabaseExporter / DatabaseImporter 单元测试（Phase 4.2 数据库导入导出）。
 *
 * 使用内存模拟 DAO 验证导出 → 导入的完整往返（round-trip）数据一致性。
 */
class DatabaseExporterTest {

    // ---- Fake DAOs ----

    private class FakeMediaDao : MediaDao {
        private val store = mutableMapOf<String, MediaEntity>()
        private val ftsStore = mutableMapOf<String, MediaFts>()

        override fun getAllFlow(): Flow<List<MediaEntity>> = flowOf(store.values.toList())
        override suspend fun getAll(): List<MediaEntity> = store.values.toList()
        override suspend fun getPaged(limit: Int, offset: Int): List<MediaEntity> =
            store.values.toList().drop(offset).take(limit)
        override fun pagingSource(): androidx.paging.PagingSource<Int, MediaEntity> =
            throw NotImplementedError("Not needed for test")
        override suspend fun getCount(): Int = store.size
        override suspend fun search(query: String): List<MediaEntity> = emptyList()
        override suspend fun searchWithOcr(query: String): List<MediaEntity> = emptyList()
        override suspend fun searchPaged(query: String, limit: Int, offset: Int): List<MediaEntity> = emptyList()
        override suspend fun searchByDateRange(startMs: Long, endMs: Long): List<MediaEntity> = emptyList()
        override suspend fun getBySceneType(sceneType: String): List<MediaEntity> = emptyList()
        override fun getFavorites(): Flow<List<MediaEntity>> = flowOf(emptyList())
        override suspend fun setFavorite(path: String, favorite: Boolean) {}
        override suspend fun getFavoriteByPath(path: String): Boolean? = store[path]?.isFavorite
        override suspend fun batchSetFavorite(paths: List<String>, favorite: Boolean) {}
        override suspend fun getTrashed(): List<MediaEntity> = emptyList()
        override suspend fun getTrashedCount(): Int = store.values.count { it.isTrashed }
        override fun getTrashedFlow(): Flow<List<MediaEntity>> = flowOf(emptyList())
        override suspend fun moveToTrash(paths: List<String>, deletedAtMs: Long) {}
        override suspend fun restoreFromTrash(paths: List<String>) {}
        override suspend fun insertAll(items: List<MediaEntity>) {
            for (item in items) store[item.filePath] = item
        }
        override suspend fun deleteByPaths(paths: List<String>) {
            for (p in paths) store.remove(p)
        }
        override suspend fun clearTrash() {}
        override suspend fun clearAll() {
            store.clear()
            ftsStore.clear()
        }
        override suspend fun getModifiedTimeMap(): List<com.renyxin.localalbum.data.db.dao.PathModifiedTime> = emptyList()
        override suspend fun getModifiedTimeForPath(path: String): com.renyxin.localalbum.data.db.dao.PathModifiedTime? = null
        override suspend fun getAllPaths(): List<String> = store.keys.toList()
        override suspend fun getImagePaths(): List<String> = store.values
            .filter { it.mediaType == MediaType.IMAGE }
            .map { it.filePath }
        override suspend fun getAllTimelineItems(): List<MediaEntity> = store.values.toList()
        override suspend fun getTimelineItemsByRange(startMs: Long, endMs: Long): List<MediaEntity> = emptyList()
        override suspend fun getCountByDateRange(startMs: Long, endMs: Long): Int = 0
        override suspend fun searchFts(ftsQuery: String, limit: Int, offset: Int): List<MediaEntity> = emptyList()
        override suspend fun countFts(ftsQuery: String): Int = 0
        override suspend fun deleteFtsEntry(filePath: String) { ftsStore.remove(filePath) }
        override suspend fun deleteFtsEntries(filePaths: List<String>) {
            for (p in filePaths) ftsStore.remove(p)
        }
        override suspend fun getAllFtsEntries(): List<MediaFts> = ftsStore.values.toList()
        override suspend fun clearAllFts() { ftsStore.clear() }
        override suspend fun insertFtsEntry(filePath: String, fileName: String, ocrText: String?, make: String?, model: String?) {
            ftsStore[filePath] = MediaFts(filePath, fileName, ocrText, make, model)
        }
        override suspend fun insertFtsAll(entries: List<MediaFts>) {
            for (e in entries) ftsStore[e.filePath] = e
        }
        override suspend fun updateQualityScore(path: String, score: Float) {}
        override suspend fun setQualityScore(path: String, score: Float) {}
        override suspend fun updatePerceptualHash(path: String, hash: Long) {}
        override suspend fun getWithPerceptualHash(): List<MediaEntity> = emptyList()
        override suspend fun setSceneType(path: String, sceneType: String) {}
        override suspend fun setOcrText(path: String, ocrText: String?) {}
        override suspend fun setAnalysisFields(path: String, score: Float, sceneType: String, ocrText: String?) {}
        override suspend fun getGeoPaths(): List<String> = emptyList()
        override suspend fun getYearBuckets(): List<com.renyxin.localalbum.data.db.dao.TimelineBucket> = emptyList()
        override suspend fun getWithLocation(): List<MediaEntity> = store.values.filter { it.latitude != null }
        override suspend fun getSceneTypeStats(): List<com.renyxin.localalbum.data.db.dao.SceneTypeStat> = emptyList()
        override fun getTotalCountFlow(): Flow<Int> = flowOf(store.size)
        override fun getFavoriteCountFlow(): Flow<Int> = flowOf(0)
        override suspend fun markCorrupted(path: String) {}
        override suspend fun getMissingThumbnails(limit: Int): List<MediaEntity> = emptyList()
        override suspend fun updateThumbnail(filePath: String, thumbPath: String) {
            store[filePath]?.let { store[filePath] = it.copy(thumbnailPath = thumbPath) }
        }
        override suspend fun getByFilePathLight(path: String): MediaEntity? = store[path]
        override suspend fun getByFilePathsLight(paths: List<String>): List<MediaEntity> =
            paths.mapNotNull { store[it] }
        override suspend fun setFaceClusterId(path: String, clusterId: String) {}
        override suspend fun updateFaceClusterId(paths: List<String>, clusterId: String) {}
        override suspend fun clearFaceClusterId(paths: List<String>) {}
        override suspend fun getFaceClusterIds(): List<String> = emptyList()
        override suspend fun getByFaceCluster(clusterId: String): List<MediaEntity> = emptyList()
        override suspend fun getWithFaceCluster(): List<MediaEntity> = emptyList()
    }

    private class FakeFaceDao : FaceDao {
        private val store = mutableListOf<FaceEntity>()
        override suspend fun insertFaces(faces: List<FaceEntity>) { store.addAll(faces) }
        override suspend fun insertFace(face: FaceEntity): Long {
            store.add(face); return face.faceId
        }
        override suspend fun deleteByFilePath(filePath: String) { store.removeAll { it.filePath == filePath } }
        override suspend fun deleteByFilePaths(filePaths: List<String>) {
            store.removeAll { it.filePath in filePaths }
        }
        override suspend fun clearAll() { store.clear() }
        override suspend fun getAll(): List<FaceEntity> = store.toList()
        override suspend fun getByFilePath(filePath: String): List<FaceEntity> = store.filter { it.filePath == filePath }
        override suspend fun getClustered(): List<FaceEntity> = store.filter { it.clusterId != null }
        override suspend fun getByCluster(clusterId: String): List<FaceEntity> = store.filter { it.clusterId == clusterId }
        override fun getByClusterFlow(clusterId: String): Flow<List<FaceEntity>> = flowOf(emptyList())
        override suspend fun getClusterIds(): List<String> = store.mapNotNull { it.clusterId }.distinct()
        override suspend fun getClusterSummaries(): List<FaceClusterSummary> = emptyList()
        override fun getClusterSummariesFlow(): Flow<List<FaceClusterSummary>> = flowOf(emptyList())
        override suspend fun updateClusterIds(faceIds: List<Long>, clusterId: String) {}
        override suspend fun updateClusterId(faceId: Long, clusterId: String) {}
        override suspend fun clearAllClusterIds() {}
        override suspend fun setPersonName(clusterId: String, name: String?) {}
        override suspend fun getCount(): Int = store.size
        override suspend fun getClusteredCount(): Int = store.count { it.clusterId != null }
        override suspend fun getClusterCount(): Int = store.mapNotNull { it.clusterId }.distinct().size
    }

    private class FakeEmbeddingDao : EmbeddingDao {
        private val store = mutableMapOf<String, MediaEmbedding>()
        override suspend fun insertEmbedding(embedding: MediaEmbedding) { store[embedding.filePath] = embedding }
        override suspend fun insertEmbeddings(embeddings: List<MediaEmbedding>) {
            for (e in embeddings) store[e.filePath] = e
        }
        override suspend fun getByFilePath(filePath: String): MediaEmbedding? = store[filePath]
        override suspend fun getAll(): List<MediaEmbedding> = store.values.toList()
        override suspend fun getAllFilePaths(): List<String> = store.keys.toList()
        override suspend fun getCount(): Int = store.size
        override suspend fun getCountByModelVersion(modelVersion: Int): Int =
            store.values.count { it.modelVersion == modelVersion }
        override suspend fun deleteByFilePath(filePath: String) { store.remove(filePath) }
        override suspend fun deleteByFilePaths(filePaths: List<String>) {
            for (p in filePaths) store.remove(p)
        }
        override suspend fun clearAll() { store.clear() }
        override suspend fun getMissingEmbeddings(limit: Int): List<String> = emptyList()
        override suspend fun getStaleEmbeddings(currentVersion: Int): List<String> = emptyList()
    }

    // ---- Test data ----

    private fun makeMediaEntity(path: String): MediaEntity = MediaEntity(
        filePath = path,
        fileName = path.substringAfterLast('/'),
        mediaType = MediaType.IMAGE,
        capturedAtMs = 1719129600000L,
        modifiedAtMs = 1719129600000L,
        indexedAtMs = 1719129600000L,
        parentPath = path.substringBeforeLast('/'),
        fileSize = 1024L,
        isFavorite = true,
        isTrashed = false,
        width = 1920,
        height = 1080,
        mimeType = "image/jpeg",
        durationMs = 0L,
        latitude = 31.2304,
        longitude = 121.4737,
        make = "Google",
        model = "Pixel 8",
        sceneType = "landscape",
        qualityScore = 85.5f,
        faceClusterId = "face-1",
    )

    private fun makeFaceEntity(path: String): FaceEntity = FaceEntity(
        faceId = 1L,
        filePath = path,
        clusterId = "face-1",
        personName = "Alice",
        embedding = "0.1,0.2,0.3",
        boxLeft = 0.1f,
        boxTop = 0.2f,
        boxRight = 0.5f,
        boxBottom = 0.6f,
        detectedAtMs = 1719129600000L,
    )

    private fun makeEmbedding(path: String): MediaEmbedding = MediaEmbedding(
        filePath = path,
        embedding = "0.1,0.2,0.3,0.4",
        modelVersion = 1,
        generatedAtMs = 1719129600000L,
        source = "concept",
    )

    private fun makeFts(path: String): MediaFts = MediaFts(
        filePath = path,
        fileName = path.substringAfterLast('/'),
        ocrText = "hello world",
        make = "Google",
        model = "Pixel 8",
    )

    // ---- Tests ----

    @Test
    fun `export to JSON produces valid structure with correct counts`() = runBlocking {
        val mediaDao = FakeMediaDao()
        val faceDao = FakeFaceDao()
        val embeddingDao = FakeEmbeddingDao()

        mediaDao.insertAll(listOf(makeMediaEntity("/photos/a.jpg"), makeMediaEntity("/photos/b.jpg")))
        mediaDao.insertFtsEntry("/photos/a.jpg", "a.jpg", "hello world", "Google", "Pixel 8")
        mediaDao.insertFtsEntry("/photos/b.jpg", "b.jpg", "hello world", "Google", "Pixel 8")
        faceDao.insertFaces(listOf(makeFaceEntity("/photos/a.jpg")))
        embeddingDao.insertEmbeddings(listOf(makeEmbedding("/photos/a.jpg"), makeEmbedding("/photos/b.jpg")))

        val exporter = DatabaseExporter(mediaDao, faceDao, embeddingDao)
        val json = exporter.exportToJson("TestDevice")

        val root = org.json.JSONObject(json)
        assertEquals(DatabaseExporter.EXPORT_FORMAT_VERSION, root.getInt("version"))
        assertEquals("TestDevice", root.getString("deviceModel"))
        assertNotNull(root.getString("exportedAt"))

        val counts = root.getJSONObject("counts")
        assertEquals(2, counts.getInt("media"))
        assertEquals(1, counts.getInt("faces"))
        assertEquals(2, counts.getInt("embeddings"))
        assertEquals(2, counts.getInt("fts"))
    }

    @Test
    fun `export to file writes valid JSON`() = runBlocking {
        val mediaDao = FakeMediaDao()
        mediaDao.insertAll(listOf(makeMediaEntity("/photos/test.jpg")))

        val exporter = DatabaseExporter(mediaDao, null, null)
        val tempFile = File.createTempFile("export_test", ".json")
        tempFile.deleteOnExit()

        val result = exporter.exportToFile(tempFile, "TestDevice")
        assertTrue(result.success)
        assertEquals(tempFile.absolutePath, result.filePath)
        assertEquals(1, result.mediaCount)
        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() > 0)
    }

    @Test
    fun `import from JSON restores all data correctly`() = runBlocking {
        val sourceMediaDao = FakeMediaDao()
        val sourceFaceDao = FakeFaceDao()
        val sourceEmbeddingDao = FakeEmbeddingDao()

        sourceMediaDao.insertAll(listOf(makeMediaEntity("/photos/a.jpg"), makeMediaEntity("/photos/b.jpg")))
        sourceMediaDao.insertFtsEntry("/photos/a.jpg", "a.jpg", "hello world", "Google", "Pixel 8")
        sourceMediaDao.insertFtsEntry("/photos/b.jpg", "b.jpg", "hello world", "Google", "Pixel 8")
        sourceFaceDao.insertFaces(listOf(makeFaceEntity("/photos/a.jpg")))
        sourceEmbeddingDao.insertEmbeddings(listOf(makeEmbedding("/photos/a.jpg")))

        val exporter = DatabaseExporter(sourceMediaDao, sourceFaceDao, sourceEmbeddingDao)
        val json = exporter.exportToJson("SourceDevice")

        // Import into fresh DAOs
        val targetMediaDao = FakeMediaDao()
        val targetFaceDao = FakeFaceDao()
        val targetEmbeddingDao = FakeEmbeddingDao()
        val importer = DatabaseImporter(targetMediaDao, targetFaceDao, targetEmbeddingDao)

        val result = importer.importFromJson(json)

        assertTrue(result.success)
        assertEquals(2, result.mediaCount)
        assertEquals(1, result.faceCount)
        assertEquals(1, result.embeddingCount)
        assertEquals(2, result.ftsCount)

        // Verify media data
        val restoredMedia = targetMediaDao.getAll()
        assertEquals(2, restoredMedia.size)
        val entity = restoredMedia.find { it.filePath == "/photos/a.jpg" }
        assertNotNull(entity)
        assertEquals("Pixel 8", entity!!.model)
        assertEquals("landscape", entity.sceneType)
        assertEquals(85.5f, entity.qualityScore, 0.01f)
        assertTrue(entity.isFavorite)
        assertEquals("face-1", entity.faceClusterId)

        // Verify face data
        val restoredFaces = targetFaceDao.getAll()
        assertEquals(1, restoredFaces.size)
        assertEquals("Alice", restoredFaces[0].personName)

        // Verify embedding data
        val restoredEmbeddings = targetEmbeddingDao.getAll()
        assertEquals(1, restoredEmbeddings.size)
        assertEquals("0.1,0.2,0.3,0.4", restoredEmbeddings[0].embedding)

        // Verify FTS data
        val restoredFts = targetMediaDao.getAllFtsEntries()
        assertEquals(2, restoredFts.size)
        assertEquals("hello world", restoredFts.find { it.filePath == "/photos/a.jpg" }?.ocrText)
    }

    @Test
    fun `import rejects unsupported format version`() = runBlocking {
        val mediaDao = FakeMediaDao()
        val importer = DatabaseImporter(mediaDao, null, null)

        val badJson = """{"version": 999, "mediaItems": []}"""
        val result = importer.importFromJson(badJson)

        assertFalse(result.success)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("版本"))
    }

    @Test
    fun `import from file reads and restores data`() = runBlocking {
        val sourceMediaDao = FakeMediaDao()
        sourceMediaDao.insertAll(listOf(makeMediaEntity("/photos/file_test.jpg")))

        val exporter = DatabaseExporter(sourceMediaDao, null, null)
        val tempFile = File.createTempFile("import_test", ".json")
        tempFile.deleteOnExit()
        exporter.exportToFile(tempFile, "FileTestDevice")

        val targetMediaDao = FakeMediaDao()
        val importer = DatabaseImporter(targetMediaDao, null, null)
        val result = importer.importFromFile(tempFile)

        assertTrue(result.success)
        assertEquals(1, result.mediaCount)
        assertEquals(1, targetMediaDao.getAll().size)
    }

    @Test
    fun `validate file returns counts for valid JSON`() = runBlocking {
        val sourceMediaDao = FakeMediaDao()
        sourceMediaDao.insertAll(listOf(makeMediaEntity("/photos/validate.jpg")))

        val exporter = DatabaseExporter(sourceMediaDao, null, null)
        val tempFile = File.createTempFile("validate_test", ".json")
        tempFile.deleteOnExit()
        exporter.exportToFile(tempFile, "ValidateDevice")

        val targetMediaDao = FakeMediaDao()
        val importer = DatabaseImporter(targetMediaDao, null, null)
        val (counts, error) = importer.validateFile(tempFile)

        assertNull(error)
        assertNotNull(counts)
        assertEquals(1, counts!!.getInt("media"))
    }

    @Test
    fun `validate file returns error for invalid JSON`() {
        val tempFile = File.createTempFile("invalid_test", ".json")
        tempFile.deleteOnExit()
        tempFile.writeText("not valid json {{{")

        val mediaDao = FakeMediaDao()
        val importer = DatabaseImporter(mediaDao, null, null)
        val (counts, error) = importer.validateFile(tempFile)

        assertNull(counts)
        assertNotNull(error)
    }

    @Test
    fun `export with empty database produces valid empty JSON`() = runBlocking {
        val mediaDao = FakeMediaDao()
        val exporter = DatabaseExporter(mediaDao, null, null)
        val json = exporter.exportToJson("EmptyDevice")

        val root = org.json.JSONObject(json)
        val counts = root.getJSONObject("counts")
        assertEquals(0, counts.getInt("media"))
        assertEquals(0, counts.getInt("faces"))
        assertEquals(0, counts.getInt("embeddings"))
        assertEquals(0, counts.getInt("fts"))
    }

    @Test
    fun `round trip preserves nullable fields correctly`() = runBlocking {
        val mediaDao = FakeMediaDao()
        // Entity with null fields
        val entity = MediaEntity(
            filePath = "/photos/nulls.jpg",
            fileName = "nulls.jpg",
            mediaType = MediaType.VIDEO,
            capturedAtMs = 1000L,
            modifiedAtMs = 2000L,
            parentPath = "/photos",
            latitude = null,
            longitude = null,
            make = null,
            model = null,
            sceneType = null,
            ocrText = null,
            faceClusterId = null,
        )
        mediaDao.insertAll(listOf(entity))

        val exporter = DatabaseExporter(mediaDao, null, null)
        val json = exporter.exportToJson("NullTest")

        val targetMediaDao = FakeMediaDao()
        val importer = DatabaseImporter(targetMediaDao, null, null)
        val result = importer.importFromJson(json)

        assertTrue(result.success)
        val restored = targetMediaDao.getByFilePathLight("/photos/nulls.jpg")
        assertNotNull(restored)
        assertNull(restored!!.latitude)
        assertNull(restored.make)
        assertNull(restored.sceneType)
        assertNull(restored.ocrText)
        assertEquals(MediaType.VIDEO, restored.mediaType)
    }
}
