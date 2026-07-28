package com.renyxin.localalbum.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * SemanticEmbedder 单元测试（Phase 4.1 语义搜索）。
 *
 * 测试文本嵌入、向量归一化、余弦相似度、序列化/反序列化等纯算法逻辑。
 * 图片嵌入因依赖 Android Bitmap 解码，不在单元测试覆盖范围。
 */
class SemanticEmbedderTest {

    private val embedder = SemanticEmbedder()

    private fun l2Norm(vec: FloatArray): Double {
        var sum = 0.0
        for (v in vec) sum += (v * v).toDouble()
        return sqrt(sum)
    }

    // ---- 文本嵌入 ----

    @Test
    fun `text embedding for Chinese sunset query activates sunset concept`() {
        val vec = embedder.embedText("日落")
        // 日落概念索引为 0
        assertTrue("日落概念应被激活", vec[0] > 0f)
        // 向量应已归一化（L2 范数 ≈ 1）
        assertEquals(1.0, l2Norm(vec), 0.01)
    }

    @Test
    fun `text embedding for English sunset query activates sunset concept`() {
        val vec = embedder.embedText("sunset")
        assertTrue("sunset 概念应被激活", vec[0] > 0f)
    }

    @Test
    fun `text embedding for beach synonym activates beach concept`() {
        val vec = embedder.embedText("海边")
        // 海滩概念索引为 2
        assertTrue("海滩概念应被激活", vec[2] > 0f)
    }

    @Test
    fun `text embedding for multi-concept query activates multiple concepts`() {
        val vec = embedder.embedText("海边的日落")
        // 日落 + 海滩都应被激活
        assertTrue("日落概念应被激活", vec[0] > 0f)
        assertTrue("海滩概念应被激活", vec[2] > 0f)
    }

    @Test
    fun `text embedding for blank query returns zero vector`() {
        val vec = embedder.embedText("")
        assertTrue("空查询应返回零向量", vec.all { it == 0f })
    }

    @Test
    fun `text embedding for unmatched query activates unknown concept`() {
        val vec = embedder.embedText("xyzqwerty")
        // unknown 概念索引为 31
        assertTrue("无法匹配的查询应激活 unknown 概念", vec[31] > 0f)
    }

    @Test
    fun `text embedding dimension matches EMBEDDING_DIM`() {
        val vec = embedder.embedText("美食")
        assertEquals(SemanticEmbedder.EMBEDDING_DIM, vec.size)
    }

    @Test
    fun `text embedding for food query activates food concept`() {
        val vec = embedder.embedText("美食")
        // 美食概念索引为 7
        assertTrue("美食概念应被激活", vec[7] > 0f)
    }

    @Test
    fun `text embedding for night query activates night concept`() {
        val vec = embedder.embedText("夜景")
        // 夜景概念索引为 6
        assertTrue("夜景概念应被激活", vec[6] > 0f)
    }

    @Test
    fun `text embedding for selfie query activates selfie concept`() {
        val vec = embedder.embedText("自拍")
        // 自拍概念索引为 8
        assertTrue("自拍概念应被激活", vec[8] > 0f)
    }

    // ---- 归一化 ----

    @Test
    fun `normalize produces unit length vector`() {
        val vec = floatArrayOf(3f, 4f)
        embedder.normalize(vec)
        assertEquals(1.0, l2Norm(vec), 0.001)
    }

    @Test
    fun `normalize zero vector remains zero`() {
        val vec = floatArrayOf(0f, 0f, 0f)
        embedder.normalize(vec)
        assertTrue(vec.all { it == 0f })
    }

    // ---- 余弦相似度 ----

    @Test
    fun `cosine similarity of identical vectors is 1`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(1f, 0f, 0f)
        val sim = embedder.cosineSimilarity(a, b)
        assertEquals(1.0f, sim, 0.001f)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        val sim = embedder.cosineSimilarity(a, b)
        assertEquals(0.0f, sim, 0.001f)
    }

    @Test
    fun `cosine similarity of opposite vectors is -1`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(-1f, 0f)
        val sim = embedder.cosineSimilarity(a, b)
        assertEquals(-1.0f, sim, 0.001f)
    }

    @Test
    fun `cosine similarity of dimension mismatch returns 0`() {
        val a = floatArrayOf(1f, 2f, 3f)
        val b = floatArrayOf(1f, 2f)
        val sim = embedder.cosineSimilarity(a, b)
        assertEquals(0f, sim)
    }

    @Test
    fun `cosine similarity of zero vectors returns 0`() {
        val a = floatArrayOf(0f, 0f)
        val b = floatArrayOf(0f, 0f)
        val sim = embedder.cosineSimilarity(a, b)
        assertEquals(0f, sim)
    }

    // ---- 序列化/反序列化 ----

    @Test
    fun `serialize and deserialize round trip preserves values`() {
        val original = floatArrayOf(0.1f, 0.2f, 0.3f, -0.4f, 0.5f)
        val serialized = embedder.serialize(original)
        val deserialized = embedder.deserialize(serialized)
        assertEquals(original.size, deserialized.size)
        for (i in original.indices) {
            assertEquals(original[i], deserialized[i], 0.001f)
        }
    }

    @Test
    fun `serialize produces comma separated string`() {
        val vec = floatArrayOf(0.1f, 0.2f)
        val str = embedder.serialize(vec)
        assertTrue("序列化结果应包含逗号分隔符", str.contains(","))
    }

    // ---- 跨语言语义匹配 ----

    @Test
    fun `Chinese and English sunset queries produce similar embeddings`() {
        val zhVec = embedder.embedText("日落")
        val enVec = embedder.embedText("sunset")
        val sim = embedder.cosineSimilarity(zhVec, enVec)
        assertTrue(
            "中文「日落」与英文「sunset」的嵌入应高度相似 (sim=$sim)",
            sim > 0.9f,
        )
    }

    @Test
    fun `sunset and food queries produce dissimilar embeddings`() {
        val sunsetVec = embedder.embedText("日落")
        val foodVec = embedder.embedText("美食")
        val sim = embedder.cosineSimilarity(sunsetVec, foodVec)
        assertTrue(
            "「日落」与「美食」的嵌入应不相似 (sim=$sim)",
            sim < 0.5f,
        )
    }

    @Test
    fun `beach and seaside queries produce identical embeddings`() {
        val beachVec = embedder.embedText("beach")
        val seasideVec = embedder.embedText("seaside")
        val sim = embedder.cosineSimilarity(beachVec, seasideVec)
        assertTrue(
            "「beach」与「seaside」是同义词，嵌入应完全相同 (sim=$sim)",
            sim > 0.99f,
        )
    }

    // ---- 概念定义完整性 ----

    @Test
    fun `all concepts have unique indices`() {
        val indices = SemanticEmbedder.CONCEPTS.map { it.index }
        assertEquals("概念索引应唯一", indices.size, indices.toSet().size)
    }

    @Test
    fun `concept count matches embedding dimension`() {
        assertEquals(
            "概念数量应与嵌入维度一致",
            SemanticEmbedder.EMBEDDING_DIM,
            SemanticEmbedder.CONCEPTS.size,
        )
    }

    @Test
    fun `concept indices are contiguous from zero`() {
        val indices = SemanticEmbedder.CONCEPTS.map { it.index }.sorted()
        for (i in indices.indices) {
            assertEquals("概念索引应从 0 开始连续", i, indices[i])
        }
    }
}
