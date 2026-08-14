package com.renyxin.localalbum.core.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanBenchmarkConfigTest {
    @Test
    fun `percentile uses nearest rank and keeps empty samples explicit`() {
        assertEquals(10L, ScanBenchmarkConfig.percentile(listOf(10L, 20L, 30L, 40L), 0))
        assertEquals(20L, ScanBenchmarkConfig.percentile(listOf(10L, 20L, 30L, 40L), 50))
        assertEquals(40L, ScanBenchmarkConfig.percentile(listOf(10L, 20L, 30L, 40L), 95))
        assertNull(ScanBenchmarkConfig.percentile(emptyList(), 95))
    }

    @Test
    fun `benchmark scenarios cover core and recovery endpoints`() {
        assertTrue(ScanBenchmarkConfig.scenarios.contains("full_cold"))
        assertTrue(ScanBenchmarkConfig.scenarios.contains("incremental_no_change"))
        assertTrue(ScanBenchmarkConfig.scenarios.contains("process_restart_resume"))
        assertEquals(1_000, ScanBenchmarkConfig.datasets.first { it.id == "full-1000" }.mediaCount)
        assertEquals(10_000, ScanBenchmarkConfig.datasets.first { it.id == "full-10000" }.mediaCount)
    }

    @Test
    fun `enhancement milestones cannot be used as core endpoint`() {
        assertTrue(ScanBenchmarkConfig.isCoreMilestone(ScanMilestone.CORE_SCAN_COMPLETED))
        assertTrue(ScanBenchmarkConfig.isCoreMilestone(ScanMilestone.INDEX_AVAILABLE))
        assertFalse(ScanBenchmarkConfig.isCoreMilestone(ScanMilestone.ENHANCEMENT_COMPLETED))
        assertFalse(ScanBenchmarkConfig.isCoreMilestone(ScanMilestone.ANALYSIS_IDLE))
    }

    @Test
    fun `in memory telemetry preserves scan identity and milestone`() {
        val telemetry = InMemoryScanTelemetry()
        telemetry.record(
            ScanMeasurementEvent(
                scanId = "scan-1",
                scanType = "FULL",
                milestone = ScanMilestone.TRIGGERED,
                elapsedMs = 0L,
            ),
        )
        telemetry.record(
            ScanMeasurementEvent(
                scanId = "scan-1",
                scanType = "FULL",
                milestone = ScanMilestone.CORE_SCAN_COMPLETED,
                elapsedMs = 123L,
                mediaCount = 10,
            ),
        )

        assertEquals(1, telemetry.eventsFor("scan-1", ScanMilestone.CORE_SCAN_COMPLETED).size)
        assertEquals(123L, telemetry.eventsFor("scan-1", ScanMilestone.CORE_SCAN_COMPLETED).single().elapsedMs)
        assertTrue(telemetry.eventsFor("unknown").isEmpty())
    }
}
