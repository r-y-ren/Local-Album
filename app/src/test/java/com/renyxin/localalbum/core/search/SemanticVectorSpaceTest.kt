package com.renyxin.localalbum.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SemanticVectorSpaceTest {
    private val baseline = SemanticVectorSpace("provider:a", "model:a", 1, 32)

    @Test
    fun `space id is stable for identical metadata`() {
        assertEquals(baseline.spaceId, SemanticVectorSpace("provider:a", "model:a", 1, 32).spaceId)
    }

    @Test
    fun `space id changes for every compatibility dimension`() {
        assertNotEquals(baseline.spaceId, baseline.copy(providerId = "provider:b").spaceId)
        assertNotEquals(baseline.spaceId, baseline.copy(modelId = "model:b").spaceId)
        assertNotEquals(baseline.spaceId, baseline.copy(modelVersion = 2).spaceId)
        assertNotEquals(baseline.spaceId, baseline.copy(dimension = 64).spaceId)
        assertNotEquals(baseline.spaceId, baseline.copy(codecId = "float16-le").spaceId)
        assertNotEquals(baseline.spaceId, baseline.copy(formatVersion = 2).spaceId)
    }

    @Test
    fun `legacy space is never a computed current space`() {
        assertNotEquals(SemanticVectorSpace.LEGACY_SPACE_ID, baseline.spaceId)
    }
}
