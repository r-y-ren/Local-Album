package com.renyxin.localalbum.core.pipeline

import com.renyxin.localalbum.ui.components.formatEta
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ProgressManager] 及 [formatEta] 单元测试（Phase 5.5 R3）。
 *
 * 测试 ETA 格式化逻辑的边界条件与典型场景。
 */
class ProgressManagerTest {

    // ---- formatEta ----

    @Test
    fun `formatEta returns empty for zero`() {
        assertEquals("", formatEta(0L))
    }

    @Test
    fun `formatEta returns empty for negative`() {
        assertEquals("", formatEta(-1L))
    }

    @Test
    fun `formatEta returns empty for negative large`() {
        assertEquals("", formatEta(-10000L))
    }

    @Test
    fun `formatEta returns seconds only for less than a minute`() {
        assertEquals("约 45s", formatEta(45_000L))
    }

    @Test
    fun `formatEta returns seconds for exactly one second`() {
        assertEquals("约 1s", formatEta(1_000L))
    }

    @Test
    fun `formatEta returns minutes and seconds`() {
        assertEquals("约 2m 30s", formatEta(150_000L))
    }

    @Test
    fun `formatEta returns minutes without seconds`() {
        assertEquals("约 5m 0s", formatEta(300_000L))
    }

    @Test
    fun `formatEta returns minutes for exactly one minute`() {
        assertEquals("约 1m 0s", formatEta(60_000L))
    }

    @Test
    fun `formatEta returns long duration`() {
        assertEquals("约 120m 0s", formatEta(7_200_000L))
    }

    // ---- ProgressManager State ----

    @Test
    fun `beginPipeline resets all state`() {
        val mgr = ProgressManager()
        mgr.beginPipeline(totalFiles = 100, stageIds = listOf("a", "b", "c"))

        val progress = mgr.progress.value
        assertEquals(100, progress.totalFiles)
        assertEquals(3, progress.totalStages)
        assertEquals(0, progress.completedStages)
        assertEquals(0, progress.processedFiles)
        assertEquals(0f, progress.percent)
        assertEquals(false, progress.isCompleted)
        assertEquals(false, progress.hasError)
    }

    @Test
    fun `onPipelineComplete sets isCompleted and percent to 1`() {
        val mgr = ProgressManager()
        mgr.beginPipeline(totalFiles = 50, stageIds = listOf("a"))
        mgr.onPipelineComplete()

        val progress = mgr.progress.value
        assertEquals(true, progress.isCompleted)
        assertEquals(1f, progress.percent)
        assertEquals(50, progress.processedFiles)
    }

    @Test
    fun `onStageError sets hasError and preserves extra info`() {
        val mgr = ProgressManager()
        mgr.beginPipeline(totalFiles = 30, stageIds = listOf("a", "b"))
        mgr.onStageStart("a", "Stage A", 15)
        mgr.onFileProgress(5, 15)
        mgr.onStageError("a", "Something went wrong")

        val progress = mgr.progress.value
        assertEquals(true, progress.hasError)
        assertEquals("Something went wrong", progress.extra["error"])
        assertEquals("a", progress.extra["failedStage"])
    }

    @Test
    fun `reset returns to idle state`() {
        val mgr = ProgressManager()
        mgr.beginPipeline(totalFiles = 10, stageIds = listOf("a"))
        mgr.onPipelineComplete()
        mgr.reset()

        val progress = mgr.progress.value
        assertEquals(0, progress.totalFiles)
        assertEquals(0, progress.totalStages)
        assertEquals(false, progress.isCompleted)
    }
}
