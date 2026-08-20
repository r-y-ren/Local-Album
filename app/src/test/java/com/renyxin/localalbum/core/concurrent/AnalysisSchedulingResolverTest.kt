package com.renyxin.localalbum.core.concurrent

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSchedulingResolverTest {
    @Test
    fun `concrete modes map thumbnail concurrency to one two and four`() {
        val capabilities = capabilities(totalGb = 12, cores = 8)

        assertEquals(
            1,
            AnalysisSchedulingResolver.resolve(AnalysisSchedulingMode.STABILITY,capabilities)
                .thumbnailConcurrency,
        )
        assertEquals(
            2,
            AnalysisSchedulingResolver.resolve(AnalysisSchedulingMode.BALANCED,capabilities)
                .thumbnailConcurrency,
        )
        assertEquals(
            4,
            AnalysisSchedulingResolver.resolve(AnalysisSchedulingMode.PERFORMANCE,capabilities)
                .thumbnailConcurrency,
        )
    }

    @Test
    fun `auto selects stability for low memory devices`() {
        val profile = AnalysisSchedulingResolver.resolve(
            AnalysisSchedulingMode.AUTO,
            capabilities(totalGb = 4, cores = 8),
        )

        assertEquals(AnalysisSchedulingMode.STABILITY, profile.effectiveMode)
        assertEquals(1, profile.workerLeaseGroups)
        assertEquals(1, profile.thumbnailConcurrency)
        assertFalse(profile.allowFaceQualityParallel)
    }

    @Test
    fun `auto selects balanced and scales window by memory`() {
        val sixGb = AnalysisSchedulingResolver.resolve(
            AnalysisSchedulingMode.AUTO,
            capabilities(totalGb = 6, cores = 6),
        )
        val eightGb = AnalysisSchedulingResolver.resolve(
            AnalysisSchedulingMode.AUTO,
            capabilities(totalGb = 8, cores = 8),
        )

        assertEquals(AnalysisSchedulingMode.BALANCED, sixGb.effectiveMode)
        assertEquals(2, sixGb.workerLeaseGroups)
        assertEquals(2, sixGb.thumbnailConcurrency)
        assertEquals(4, eightGb.workerLeaseGroups)
        assertEquals(2, eightGb.thumbnailConcurrency)
        assertTrue(eightGb.allowFaceQualityParallel)
    }

    @Test
    fun `auto recommends performance only for high end devices`() {
        val profile = AnalysisSchedulingResolver.resolve(
            AnalysisSchedulingMode.AUTO,
            capabilities(totalGb = 12, cores = 8),
        )

        assertEquals(AnalysisSchedulingMode.PERFORMANCE, profile.effectiveMode)
        assertEquals(4, profile.workerLeaseGroups)
        assertEquals(4, profile.thumbnailConcurrency)
        assertEquals(1, profile.semanticConcurrency)
        assertEquals(1, profile.ocrConcurrency)
    }

    @Test
    fun `memory pressure overrides user performance request`() {
        val profile = AnalysisSchedulingResolver.resolve(
            AnalysisSchedulingMode.PERFORMANCE,
            capabilities(totalGb = 12, cores = 8, lowMemory = true),
        )

        assertEquals(AnalysisSchedulingMode.PERFORMANCE, profile.requestedMode)
        assertEquals(AnalysisSchedulingMode.STABILITY, profile.effectiveMode)
        assertEquals(1, profile.thumbnailConcurrency)
        assertTrue(profile.protectionReason?.contains("内存") == true)
    }

    @Test
    fun `serious thermal pressure overrides user performance thumbnail concurrency`() {
        val profile = AnalysisSchedulingResolver.resolve(
            AnalysisSchedulingMode.PERFORMANCE,
            capabilities(
                totalGb = 12,
                cores = 8,
                thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
            ),
        )

        assertEquals(AnalysisSchedulingMode.STABILITY, profile.effectiveMode)
        assertEquals(1, profile.thumbnailConcurrency)
        assertTrue(profile.protectionReason?.contains("发热") == true)
    }

    @Test
    fun `thumbnail concurrency safety clamp cannot exceed bitmap boundary`() {
        assertEquals(1, AnalysisSchedulingResolver.clampThumbnailConcurrency(Int.MIN_VALUE))
        assertEquals(1, AnalysisSchedulingResolver.clampThumbnailConcurrency(0))
        assertEquals(4, AnalysisSchedulingResolver.clampThumbnailConcurrency(5))
        assertEquals(4, AnalysisSchedulingResolver.clampThumbnailConcurrency(Int.MAX_VALUE))
    }

    private fun capabilities(
        totalGb: Long,
        cores: Int,
        lowMemory: Boolean = false,
        thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
    ) = AnalysisDeviceCapabilities(
        logicalCpuCores = cores,
        totalMemoryBytes = totalGb * 1024L * 1024L * 1024L,
        availableMemoryBytes = 2L * 1024L * 1024L * 1024L,
        lowMemory = lowMemory,
        thermalStatus = thermalStatus,
    )
}
