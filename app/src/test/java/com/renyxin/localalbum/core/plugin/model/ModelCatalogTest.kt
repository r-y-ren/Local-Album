package com.renyxin.localalbum.core.plugin.model

import com.renyxin.localalbum.core.plugin.PluginManifest.ModelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelInfo], [ModelCatalog], [LocalModelCatalog] 单元测试（Phase 4）。
 *
 * 测试模型目录的核心数据结构与查询逻辑。
 */
class ModelCatalogTest {

    // ---- ModelInfo 测试 ----

    @Test
    fun `ModelInfo formattedSize returns B for small files`() {
        val info = ModelInfo(
            modelId = "test",
            displayName = "Test",
            fileSizeBytes = 512,
        )
        assertEquals("512 B", info.formattedSize)
    }

    @Test
    fun `ModelInfo formattedSize returns KB`() {
        val info = ModelInfo(
            modelId = "test",
            displayName = "Test",
            fileSizeBytes = 2048,
        )
        assertEquals("2 KB", info.formattedSize)
    }

    @Test
    fun `ModelInfo formattedSize returns MB`() {
        val info = ModelInfo(
            modelId = "test",
            displayName = "Test",
            fileSizeBytes = 5 * 1024 * 1024,
        )
        assertEquals("5.0 MB", info.formattedSize)
    }

    @Test
    fun `ModelInfo formattedSize returns unknown for negative`() {
        val info = ModelInfo(
            modelId = "test",
            displayName = "Test",
            fileSizeBytes = -1,
        )
        assertEquals("未知", info.formattedSize)
    }

    @Test
    fun `ModelInfo all fields populated`() {
        val info = ModelInfo(
            modelId = "arcface_v2",
            displayName = "ArcFace V2",
            description = "Face recognition model",
            format = ModelFormat.TFLITE,
            fileSizeBytes = 45_000_000,
            version = "2.1.0",
            author = "InsightFace",
            tags = listOf("face", "recognition"),
            rating = 4.5f,
            downloadCount = 12000,
            source = ModelSource.REMOTE_DOWNLOAD,
            downloadUrl = "https://example.com/arcface.tflite",
            sha256 = "abc123def456",
            requiredBy = listOf("face", "face_cluster"),
        )

        assertEquals("arcface_v2", info.modelId)
        assertEquals("ArcFace V2", info.displayName)
        assertEquals(ModelFormat.TFLITE, info.format)
        assertEquals("42.9 MB", info.formattedSize)
        assertEquals(ModelSource.REMOTE_DOWNLOAD, info.source)
        assertEquals(2, info.requiredBy.size)
    }

    // ---- ModelSource 测试 ----

    @Test
    fun `ModelSource enum values`() {
        assertEquals(4, ModelSource.values().size)
        assertEquals(ModelSource.BUILTIN, ModelSource.valueOf("BUILTIN"))
        assertEquals(ModelSource.USER_IMPORTED, ModelSource.valueOf("USER_IMPORTED"))
        assertEquals(ModelSource.REMOTE_DOWNLOAD, ModelSource.valueOf("REMOTE_DOWNLOAD"))
        assertEquals(ModelSource.LOCAL, ModelSource.valueOf("LOCAL"))
    }

    // ---- ModelStorageManager.formatBytes 测试 ----

    @Test
    fun `formatBytes returns B`() {
        assertEquals("512 B", ModelStorageManager.formatBytes(512))
    }

    @Test
    fun `formatBytes returns KB`() {
        assertEquals("2 KB", ModelStorageManager.formatBytes(2048))
    }

    @Test
    fun `formatBytes returns MB`() {
        assertEquals("1.0 MB", ModelStorageManager.formatBytes(1024 * 1024))
    }

    @Test
    fun `formatBytes returns unknown for negative`() {
        assertEquals("未知", ModelStorageManager.formatBytes(-1))
    }

    @Test
    fun `formatBytes returns zero`() {
        assertEquals("0 B", ModelStorageManager.formatBytes(0))
    }

    // ---- StorageStats 测试 ----

    @Test
    fun `StorageStats isLowStorage below threshold`() {
        val stats = ModelStorageManager.StorageStats(
            totalBytes = 1024 * 1024,
            availableStorageBytes = 50L * 1024 * 1024, // 50MB < 100MB threshold
        )
        assertTrue(stats.isLowStorage)
    }

    @Test
    fun `StorageStats isLowStorage above threshold`() {
        val stats = ModelStorageManager.StorageStats(
            totalBytes = 1024 * 1024,
            availableStorageBytes = 500L * 1024 * 1024, // 500MB > 100MB threshold
        )
        assertFalse(stats.isLowStorage)
    }

    @Test
    fun `StorageStats isLowStorage unknown available`() {
        val stats = ModelStorageManager.StorageStats(
            totalBytes = 1024 * 1024,
            availableStorageBytes = -1,
        )
        assertFalse(stats.isLowStorage)
    }

    // ---- ModelStorageInfo 测试 ----

    @Test
    fun `ModelStorageInfo formatted size`() {
        val info = ModelStorageManager.ModelStorageInfo(
            modelId = "test",
            displayName = "Test Model",
            fileSizeBytes = 10 * 1024 * 1024,
            format = "TFLite",
            lastModifiedMs = System.currentTimeMillis(),
        )
        assertEquals("10.0 MB", info.formattedSize)
        assertEquals("TFLite", info.format)
    }
}
