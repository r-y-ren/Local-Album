package com.renyxin.localalbum.core.recommendation

import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecommendationDiversifierTest {

    private fun makeRecommendation(
        albumId: String,
        category: RecommendationCategory,
        score: Double,
    ): Recommendation {
        return Recommendation(
            albumId = albumId,
            albumName = "Test",
            directoryPath = "/test",
            windowStart = Instant.EPOCH,
            windowEnd = Instant.EPOCH,
            mediaItems = emptyList(),
            reason = "",
            score = score,
            category = category,
        )
    }

    @Test
    fun `empty input returns empty`() {
        val diversifier = RecommendationDiversifier()
        val result = diversifier.select(emptyList(), 10)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `zero batch size returns empty`() {
        val diversifier = RecommendationDiversifier()
        val recs = listOf(makeRecommendation("a1", RecommendationCategory.SCENE_THEME, 1.0))
        val result = diversifier.select(recs, 0)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `selects highest score when no diversity constraint`() {
        val diversifier = RecommendationDiversifier()
        val recs = listOf(
            makeRecommendation("a1", RecommendationCategory.SCENE_THEME, 0.3),
            makeRecommendation("a2", RecommendationCategory.SEMANTIC_CLUSTER, 0.9),
            makeRecommendation("a3", RecommendationCategory.WHOLE_ALBUM, 0.5),
        )
        val result = diversifier.select(recs, 1)
        assertEquals(1, result.size)
        assertEquals("a2", result[0].albumId) // 最高分
    }

    @Test
    fun `same category not consecutive beyond max`() {
        val diversifier = RecommendationDiversifier(maxConsecutivePerCategory = 2)
        val recs = (0 until 6).map {
            makeRecommendation("a$it", RecommendationCategory.SCENE_THEME, 1.0 - it * 0.01)
        }
        val result = diversifier.select(recs, 6)

        // 前 2 条同类别，第 3 条应被跳过（但无其他类别可选，会 fallback）
        assertEquals(6, result.size) // 全部返回（fallback 机制）
        // 验证前 2 条是最高分的
        assertEquals("a0", result[0].albumId)
        assertEquals("a1", result[1].albumId)
    }

    @Test
    fun `alternates categories when possible`() {
        val diversifier = RecommendationDiversifier(maxConsecutivePerCategory = 1)
        val recs = listOf(
            makeRecommendation("scene1", RecommendationCategory.SCENE_THEME, 0.9),
            makeRecommendation("scene2", RecommendationCategory.SCENE_THEME, 0.8),
            makeRecommendation("semantic1", RecommendationCategory.SEMANTIC_CLUSTER, 0.7),
            makeRecommendation("semantic2", RecommendationCategory.SEMANTIC_CLUSTER, 0.6),
        )
        val result = diversifier.select(recs, 4)

        assertEquals(4, result.size)
        // 第 1 条和第 2 条不应同类别（maxConsecutivePerCategory=1）
        assertNotEquals(result[0].category, result[1].category)
        // 第 2 条和第 3 条不应同类别
        assertNotEquals(result[1].category, result[2].category)
    }

    @Test
    fun `same album not consecutive beyond max`() {
        val diversifier = RecommendationDiversifier(maxConsecutivePerAlbum = 1)
        val recs = listOf(
            makeRecommendation("album1", RecommendationCategory.SCENE_THEME, 0.9),
            makeRecommendation("album1", RecommendationCategory.SEMANTIC_CLUSTER, 0.8),
            makeRecommendation("album2", RecommendationCategory.WHOLE_ALBUM, 0.7),
            makeRecommendation("album2", RecommendationCategory.EVENT, 0.6),
        )
        val result = diversifier.select(recs, 4)

        assertEquals(4, result.size)
        // 相邻不应同 albumId
        assertNotEquals(result[0].albumId, result[1].albumId)
        assertNotEquals(result[1].albumId, result[2].albumId)
    }

    @Test
    fun `selectAll returns all candidates ordered`() {
        val diversifier = RecommendationDiversifier(maxConsecutivePerCategory = 2)
        val recs = listOf(
            makeRecommendation("a1", RecommendationCategory.SCENE_THEME, 0.5),
            makeRecommendation("a2", RecommendationCategory.SEMANTIC_CLUSTER, 0.9),
            makeRecommendation("a3", RecommendationCategory.WHOLE_ALBUM, 0.3),
        )
        val result = diversifier.selectAll(recs)

        assertEquals(3, result.size)
        // 应按评分降序
        assertTrue(result[0].score >= result[1].score)
        assertTrue(result[1].score >= result[2].score)
    }

    @Test
    fun `batch size smaller than candidates returns subset`() {
        val diversifier = RecommendationDiversifier()
        val recs = (0 until 10).map {
            makeRecommendation("a$it", RecommendationCategory.entries[it % 5], 1.0 - it * 0.01)
        }
        val result = diversifier.select(recs, 3)
        assertEquals(3, result.size)
    }

    @Test
    fun `mixed categories produce diverse output`() {
        val diversifier = RecommendationDiversifier(maxConsecutivePerCategory = 2)
        val recs = listOf(
            makeRecommendation("s1", RecommendationCategory.SCENE_THEME, 0.9),
            makeRecommendation("s2", RecommendationCategory.SCENE_THEME, 0.85),
            makeRecommendation("s3", RecommendationCategory.SCENE_THEME, 0.8),
            makeRecommendation("sem1", RecommendationCategory.SEMANTIC_CLUSTER, 0.75),
            makeRecommendation("sem2", RecommendationCategory.SEMANTIC_CLUSTER, 0.7),
            makeRecommendation("w1", RecommendationCategory.WHOLE_ALBUM, 0.65),
        )
        val result = diversifier.select(recs, 6)

        assertEquals(6, result.size)
        // 不应出现 3 条连续同类别
        for (i in 2 until result.size) {
            val sameCat = result[i].category == result[i - 1].category &&
                result[i].category == result[i - 2].category
            assertTrue("位置 $i 处不应有 3 条连续同类别", !sameCat)
        }
    }
}
