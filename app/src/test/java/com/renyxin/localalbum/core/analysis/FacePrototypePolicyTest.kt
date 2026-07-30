package com.renyxin.localalbum.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacePrototypePolicyTest {
    @Test
    fun `nearest prototype matches cosine threshold`() {
        val prototypes = listOf(
            FacePrototypePolicy.Prototype(0, floatArrayOf(1f, 0f), 2),
            FacePrototypePolicy.Prototype(1, floatArrayOf(0f, 1f), 1),
        )
        val nearest = requireNotNull(FacePrototypePolicy.nearest(floatArrayOf(0.99f, 0.01f), prototypes))
        assertEquals(0, nearest.first.index)
        assertTrue(nearest.second <= FacePrototypePolicy.MATCH_EPS)
    }

    @Test
    fun `matching sample updates weighted prototype without adding slot`() {
        val current = listOf(FacePrototypePolicy.Prototype(0, floatArrayOf(1f, 0f), 3))
        val result = FacePrototypePolicy.update(floatArrayOf(0.98f, 0.02f), current)
        assertTrue(result.accepted)
        assertEquals(1, result.prototypes.size)
        assertEquals(4, result.prototypes.single().sampleCount)
    }

    @Test
    fun `diverse samples never exceed three prototypes`() {
        var current = emptyList<FacePrototypePolicy.Prototype>()
        listOf(
            floatArrayOf(1f, 0f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f, 0f),
            floatArrayOf(0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, 0f, 1f),
        ).forEach { current = FacePrototypePolicy.update(it, current).prototypes }
        assertEquals(FacePrototypePolicy.MAX_PROTOTYPES_PER_CLUSTER, current.size)
    }

    @Test
    fun `full distant cluster rejects sample instead of poisoning prototype`() {
        val current = listOf(
            FacePrototypePolicy.Prototype(0, floatArrayOf(1f, 0f, 0f, 0f), 1),
            FacePrototypePolicy.Prototype(1, floatArrayOf(0f, 1f, 0f, 0f), 1),
            FacePrototypePolicy.Prototype(2, floatArrayOf(0f, 0f, 1f, 0f), 1),
        )
        val result = FacePrototypePolicy.update(floatArrayOf(0f, 0f, 0f, 1f), current)
        assertFalse(result.accepted)
        assertEquals(current, result.prototypes)
    }
}
