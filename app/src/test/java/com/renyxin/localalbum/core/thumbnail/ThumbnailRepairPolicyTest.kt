package com.renyxin.localalbum.core.thumbnail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailRepairPolicyTest {
    @Test
    fun `repair requires a published non-empty baseline with an eligible gap`() {
        val admitted = ThumbnailRepairAdmissionFacts(
            hasPublishedBaseline = true,
            visibleGridMediaCount = 3,
            eligibleGapCount = 1,
            idleStage = true,
            hasActiveRun = false,
            rebuildRequested = false,
        )

        assertTrue(ThumbnailRepairPolicy.shouldAdmit(admitted))
        assertFalse(
            ThumbnailRepairPolicy.shouldAdmit(
                admitted.copy(hasPublishedBaseline = false),
            ),
        )
        assertFalse(
            ThumbnailRepairPolicy.shouldAdmit(
                admitted.copy(visibleGridMediaCount = 0),
            ),
        )
        assertFalse(
            ThumbnailRepairPolicy.shouldAdmit(
                admitted.copy(eligibleGapCount = 0),
            ),
        )
    }

    @Test
    fun `repair is blocked while pipeline is busy or explicitly rebuilding`() {
        val admitted = ThumbnailRepairAdmissionFacts(
            hasPublishedBaseline = true,
            visibleGridMediaCount = 1,
            eligibleGapCount = 1,
            idleStage = true,
            hasActiveRun = false,
            rebuildRequested = false,
        )

        assertFalse(ThumbnailRepairPolicy.shouldAdmit(admitted.copy(idleStage = false)))
        assertFalse(ThumbnailRepairPolicy.shouldAdmit(admitted.copy(hasActiveRun = true)))
        assertFalse(ThumbnailRepairPolicy.shouldAdmit(admitted.copy(rebuildRequested = true)))
    }

    @Test
    fun `repair identity is deterministic for one baseline and format`() {
        val first = ThumbnailRepairPolicy.repairRunId(17L, ThumbnailIdentity.CURRENT_FORMAT_VERSION)
        val repeated = ThumbnailRepairPolicy.repairRunId(17L, ThumbnailIdentity.CURRENT_FORMAT_VERSION)
        val nextGeneration = ThumbnailRepairPolicy.repairRunId(18L, ThumbnailIdentity.CURRENT_FORMAT_VERSION)
        val nextFormat = ThumbnailRepairPolicy.repairRunId(17L, ThumbnailIdentity.CURRENT_FORMAT_VERSION + 1)

        assertTrue(first == repeated)
        assertNotEquals(first, nextGeneration)
        assertNotEquals(first, nextFormat)
    }
}
