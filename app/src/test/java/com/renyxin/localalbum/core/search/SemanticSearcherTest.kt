package com.renyxin.localalbum.core.search

import com.renyxin.localalbum.core.plugin.capability.builtin.ConceptEmbedProvider
import java.util.Locale
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SemanticSearcher 单元测试（Phase 4.1 语义搜索 / Provider 驱动重构）。
 *
 * 使用 [ConceptEmbedProvider]（零模型依赖的概念向量）验证文本查询编码 → 余弦相似度检索 → Top-K 排序逻辑。
 */
class SemanticSearcherTest {

    private val provider = ConceptEmbedProvider()

    /**
     * 内存模拟 EmbeddingDao，用于单元测试。
     */
    private class FakeEmbeddingDao : EmbeddingDao {
        private val store = mutableMapOf<String, MediaEmbedding>()

        override suspend fun insertEmbedding(embedding: MediaEmbedding) {
            store[embedding.filePath] = embedding
        }

        override suspend fun insertEmbeddings(embeddings: List<MediaEmbedding>) {
            for (e in embeddings) store[e.filePath] = e
        }

        override suspend fun getByFilePath(filePath: String): MediaEmbedding? = store[filePath]

        override suspend fun getAll(): List<MediaEmbedding> = store.values.toList()

        override suspend fun getAllFilePaths(): List<String> = store.keys.toList()

        override suspend fun getCount(): Int = store.size

        override suspend fun getCountByModelVersion(modelVersion: Int): Int =
            store.values.count { it.modelVersion == modelVersion }

        override suspend fun deleteByFilePath(filePath: String) {
            store.remove(filePath)
        }

        override suspend fun deleteByFilePaths(filePaths: List<String>) {
            for (p in filePaths) store.remove(p)
        }

        override suspend fun clearAll() {
            store.clear()
        }

        override suspend fun getMissingEmbeddings(limit: Int): List<String> = emptyList()

        override suspend fun getStaleEmbeddings(currentVersion: Int): List<String> =
            store.values.filter { it.modelVersion != currentVersion }.map { it.filePath }
    }

    private fun makeEmbedding(searcher: SemanticSearcher, filePath: String, query: String): MediaEmbedding {
        val vec = runBlocking { provider.embedText(query) }
        return MediaEmbedding(
            filePath = filePath,
            embedding = searcher.serialize(vec),
            modelVersion = 1,
            source = "concept",
        )
    }

    private fun newSearcher(dao: EmbeddingDao): SemanticSearcher =
        SemanticSearcher(provider, dao)

    @Test
    fun `search returns empty for blank query`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        val results = searcher.search("")
        assertTrue("空查询应返回空结果", results.isEmpty())
    }

    @Test
    fun `search returns empty when no embeddings indexed`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        val results = searcher.search("日落")
        assertTrue("无索引时应返回空结果", results.isEmpty())
    }

    @Test
    fun `search returns matching results sorted by similarity`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        // 插入 3 条嵌入：日落、美食、夜景
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/sunset1.jpg", "日落"))
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/food1.jpg", "美食"))
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/night1.jpg", "夜景"))

        val results = searcher.search("日落")

        assertTrue("应至少返回 1 条结果", results.isNotEmpty())
        // 日落图片应排在最前
        assertEquals("/photos/sunset1.jpg", results.first().filePath)
        // 最高相似度应 > 0.5
        assertTrue("最高相似度应较高 (sim=${results.first().similarity})",
            results.first().similarity > 0.5f)
    }

    @Test
    fun `search respects topK limit`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        // 插入 10 条日落嵌入
        for (i in 1..10) {
            dao.insertEmbedding(makeEmbedding(searcher, "/photos/sunset$i.jpg", "日落"))
        }

        val results = searcher.search("日落", topK = 3)
        assertEquals("应返回最多 3 条结果", 3, results.size)
    }

    @Test
    fun `search filters by minimum similarity`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/sunset.jpg", "日落"))
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/food.jpg", "美食"))

        // 用高阈值搜索，美食不应出现
        val results = searcher.search("日落", minSimilarity = 0.9f)
        assertTrue("高阈值下美食不应出现",
            results.none { it.filePath.contains("food") })
    }

    @Test
    fun `cross-lingual search matches Chinese query to English embedding`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        // 用英文 "sunset" 生成嵌入
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/sunset.jpg", "sunset"))

        // 用中文 "日落" 搜索
        val results = searcher.search("日落")
        assertTrue("中文查询应匹配英文嵌入", results.isNotEmpty())
        assertEquals("/photos/sunset.jpg", results.first().filePath)
    }

    @Test
    fun `search results are sorted by similarity descending`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/sunset.jpg", "日落"))
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/beach.jpg", "海边"))
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/food.jpg", "美食"))

        val results = searcher.search("日落")
        // 验证结果按相似度降序排列
        for (i in 0 until results.size - 1) {
            assertTrue(
                "结果应按相似度降序排列",
                results[i].similarity >= results[i + 1].similarity,
            )
        }
    }

    @Test
    fun `getStats returns correct embedding count`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/a.jpg", "日落"))
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/b.jpg", "美食"))

        val stats = searcher.getStats()
        assertEquals(2, stats.totalEmbeddings)
        assertEquals(provider.embeddingDim, stats.embeddingDim)
    }

    @Test
    fun `serialize remains parseable when device locale uses comma decimal separator`() {
        val searcher = newSearcher(FakeEmbeddingDao())
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val serialized = searcher.serialize(floatArrayOf(1.25f, -0.5f, 0f))
            assertEquals("1.250000,-0.500000,0.000000", serialized)
            assertTrue(searcher.deserialize(serialized).contentEquals(floatArrayOf(1.25f, -0.5f, 0f)))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deserialize rejects malformed vector instead of silently dropping values`() {
        newSearcher(FakeEmbeddingDao()).deserialize("0.1,not-a-number,0.3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `serialize rejects non finite vector values`() {
        newSearcher(FakeEmbeddingDao()).serialize(floatArrayOf(0f, Float.NaN))
    }

    @Test
    fun `findSimilar returns similar images excluding self`() = runBlocking {
        val dao = FakeEmbeddingDao()
        val searcher = newSearcher(dao)
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/sunset1.jpg", "日落"))
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/sunset2.jpg", "黄昏"))
        dao.insertEmbedding(makeEmbedding(searcher, "/photos/food.jpg", "美食"))

        val results = searcher.findSimilar("/photos/sunset1.jpg", excludeSelf = true)
        assertTrue("应排除自身", results.none { it.filePath == "/photos/sunset1.jpg" })
        // 黄昏（sunset 同义词）应排在最前
        assertTrue("黄昏应匹配日落", results.any { it.filePath.contains("sunset2") })
    }
}
