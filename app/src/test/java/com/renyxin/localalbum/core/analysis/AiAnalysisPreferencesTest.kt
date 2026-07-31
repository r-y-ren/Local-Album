package com.renyxin.localalbum.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiAnalysisPreferencesTest {
    @Test
    fun `face presets keep strict standard relaxed ordering`() {
        assertTrue(FaceGroupingStrictness.STRICT.clusterEps < FaceGroupingStrictness.STANDARD.clusterEps)
        assertTrue(FaceGroupingStrictness.STANDARD.clusterEps < FaceGroupingStrictness.RELAXED.clusterEps)
        assertTrue(FaceGroupingStrictness.STRICT.matchEps < FaceGroupingStrictness.STANDARD.matchEps)
        assertTrue(FaceGroupingStrictness.STANDARD.matchEps < FaceGroupingStrictness.RELAXED.matchEps)
    }

    @Test
    fun `semantic presets keep more standard precise ordering`() {
        assertTrue(SemanticSearchStrictness.MORE_RESULTS.minSimilarity < SemanticSearchStrictness.STANDARD.minSimilarity)
        assertTrue(SemanticSearchStrictness.STANDARD.minSimilarity < SemanticSearchStrictness.PRECISE.minSimilarity)
    }

    @Test
    fun `preferences normalize user controlled bounds`() {
        val normalized = AiAnalysisPreferences(
            faceMinimumGroupSize = 99,
            semanticSearchResultCount = 1,
        ).normalized()

        assertEquals(5, normalized.faceMinimumGroupSize)
        assertEquals(20, normalized.semanticSearchResultCount)
    }
}
