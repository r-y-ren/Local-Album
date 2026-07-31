package com.renyxin.localalbum.core.pipeline

import com.renyxin.localalbum.ui.components.buildProgressSummary
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

    @Test
    fun `progress summary emphasizes remaining work and active file`() {
        val progress = AnalysisProgress(
            totalStages = 5,
            completedStages = 2,
            totalFiles = 120,
            processedFiles = 45,
            percent = 0.375f,
            etaMs = 90_000L,
            perStageFiles = mapOf(
                "ocr" to StageFileProgress(
                    stageId = "ocr",
                    displayName = "文字识别",
                    completedCount = 10,
                    totalCount = 30,
                    files = listOf(FileProgress("/DCIM/current.jpg", FileProcessingStatus.PROCESSING)),
                )
            ),
        )

        val summary = buildProgressSummary(progress)

        assertEquals(37, summary.percent)
        assertEquals(75, summary.remainingFiles)
        assertEquals("约 1m 30s", summary.etaText)
        assertEquals("current.jpg", summary.currentFileName)
        assertEquals("剩余 75 个文件 · 2/5 项完成 · 约 1m 30s", summary.compactText)
    }

    @Test
    fun `progress summary clamps inconsistent remaining count`() {
        val summary = buildProgressSummary(
            AnalysisProgress(totalFiles = 10, processedFiles = 12, percent = 1.2f)
        )

        assertEquals(100, summary.percent)
        assertEquals(0, summary.remainingFiles)
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
    fun `out of order concurrent progress callbacks never decrease processed files`() {
        val mgr = ProgressManager()
        mgr.beginPipeline(totalFiles = 100, stageIds = listOf("a"))
        mgr.onStageStart("a", "Stage A", 100)

        // 并发工作线程可能先递增 AtomicInteger，再较晚执行回调；模拟 8 先于 3 到达。
        mgr.onFileProgress(8, 100)
        mgr.onFileProgress(3, 100)

        val progress = mgr.progress.value
        assertEquals(8, progress.processedFiles)
        assertEquals(8f / 100f, progress.percent)
    }

    @Test
    fun `out of order file status callback never decreases processed files`() {
        val mgr = ProgressManager()
        mgr.beginPipeline(totalFiles = 100, stageIds = listOf("a"))
        mgr.onStageStart("a", "Stage A", 100)
        mgr.onFileProgress(8, 100)

        // 尚未收到其余完成状态时，状态快照不能覆盖更高的进度回调值。
        mgr.onFileStatusChange("a", "file-1", FileProcessingStatus.COMPLETED)

        assertEquals(8, mgr.progress.value.processedFiles)
    }

    @Test
    fun `parallel stages aggregate progress without resetting displayed files`() {
        val mgr = ProgressManager()
        mgr.beginPipeline(totalFiles = 100, stageIds = listOf("face", "scene"))
        mgr.onStageStart("face", "Face", 100)
        mgr.onStageStart("scene", "Scene", 100)

        mgr.onFileProgress("face", 80, 100)
        assertEquals(40, mgr.progress.value.processedFiles)

        // 场景阶段从 0 开始时，已完成的人脸阶段进度必须仍保留在全局显示中。
        mgr.onFileProgress("scene", 1, 100)
        assertEquals(40, mgr.progress.value.processedFiles)

        mgr.onFileProgress("scene", 20, 100)
        assertEquals(50, mgr.progress.value.processedFiles)
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
