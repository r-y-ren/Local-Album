package com.renyxin.localalbum.core.concurrent

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisSchedulingResolverTest {
    @Test
    fun `auto selects stability for low memory devices`() {
        val profile = AnalysisSchedulingResolver.resolve(
            AnalysisSchedulingMode.AUTO,
            capabilities(totalGb = 4, cores = 8),
        )

        assertEquals(AnalysisSchedulingMode.STABILITY, profile.effectiveMode)
        assertEquals(1, profile.workerLeaseGroups)
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
        assertEquals(4, eightGb.workerLeaseGroups)
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
        assertTrue(profile.protectionReason?.contains("内存") == true)
    }

    private fun capabilities(
        totalGb: Long,
        cores: Int,
        lowMemory: Boolean = false,
    ) = AnalysisDeviceCapabilities(
        logicalCpuCores = cores,
        totalMemoryBytes = totalGb * 1024L * 1024L * 1024L,
        availableMemoryBytes = 2L * 1024L * 1024L * 1024L,
        lowMemory = lowMemory,
        thermalStatus = PowerManager.THERMAL_STATUS_NONE,
    )
}
