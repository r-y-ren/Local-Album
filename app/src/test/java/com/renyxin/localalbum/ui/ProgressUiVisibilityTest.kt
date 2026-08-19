package com.renyxin.localalbum.ui

import com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
import com.renyxin.localalbum.data.db.entity.LibraryPipelineState
import com.renyxin.localalbum.ui.components.ProgressOverlayMode
import com.renyxin.localalbum.ui.components.resolveProgressOverlayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressUiVisibilityTest {
    @Test
    fun `global progress is visible when enabled and manager exists`() {
        assertTrue(
            shouldShowGlobalProgressIndicator(
                showAnalysisProgressUi = true,
                hasProgressManager = true,
            ),
        )
    }

    @Test
    fun `disabled preference hides global progress`() {
        assertFalse(
            shouldShowGlobalProgressIndicator(
                showAnalysisProgressUi = false,
                hasProgressManager = true,
            ),
        )
    }

    @Test
    fun `missing progress manager never renders global progress`() {
        assertFalse(
            shouldShowGlobalProgressIndicator(
                showAnalysisProgressUi = true,
                hasProgressManager = false,
            ),
        )
    }

    @Test
    fun `first launch without scan request hides progress`() {
        assertEquals(
            ProgressOverlayMode.HIDDEN,
            resolveProgressOverlayMode(
                pipelineState = pipeline(LibraryPipelineStage.UNINITIALIZED, hasBaseline = false),
                analysisRunning = false,
            ),
        )
    }

    @Test
    fun `configured roots alone do not turn admitted initial stage into progress`() {
        assertEquals(
            ProgressOverlayMode.HIDDEN,
            resolveProgressOverlayMode(
                pipelineState = pipeline(LibraryPipelineStage.INITIAL_SCAN, hasBaseline = false),
                analysisRunning = false,
            ),
        )
    }

    @Test
    fun `explicit first scan request shows progress before run reservation`() {
        assertEquals(
            ProgressOverlayMode.SCAN,
            resolveProgressOverlayMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.UNINITIALIZED,
                    hasBaseline = false,
                    rebuildRequested = true,
                ),
                analysisRunning = false,
            ),
        )
    }

    @Test
    fun `persisted active scan run remains visible during process recovery`() {
        assertEquals(
            ProgressOverlayMode.SCAN,
            resolveProgressOverlayMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.INITIAL_SCAN,
                    hasBaseline = false,
                    activeRunId = "recoverable-run",
                ),
                analysisRunning = false,
            ),
        )
    }

    @Test
    fun `ready baseline is idle and hides progress`() {
        assertEquals(
            ProgressOverlayMode.HIDDEN,
            resolveProgressOverlayMode(
                pipelineState = pipeline(LibraryPipelineStage.READY, hasBaseline = true),
                analysisRunning = false,
            ),
        )
    }

    @Test
    fun `real automatic analysis running shows analysis card`() {
        assertEquals(
            ProgressOverlayMode.ANALYSIS,
            resolveProgressOverlayMode(
                pipelineState = pipeline(LibraryPipelineStage.INITIAL_ANALYSIS, hasBaseline = true),
                analysisRunning = true,
            ),
        )
    }

    @Test
    fun `ready stage keeps explicit user analysis progress visible`() {
        assertEquals(
            ProgressOverlayMode.ANALYSIS,
            resolveProgressOverlayMode(
                pipelineState = pipeline(LibraryPipelineStage.READY, hasBaseline = true),
                analysisRunning = true,
            ),
        )
    }

    @Test
    fun `failed stage keeps explicit retry analysis progress visible`() {
        assertEquals(
            ProgressOverlayMode.ANALYSIS,
            resolveProgressOverlayMode(
                pipelineState = pipeline(LibraryPipelineStage.FAILED, hasBaseline = true),
                analysisRunning = true,
            ),
        )
    }

    @Test
    fun `real analysis batch gap shows waiting card`() {
        assertEquals(
            ProgressOverlayMode.WAITING,
            resolveProgressOverlayMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.INCREMENTAL_ANALYSIS,
                    hasBaseline = true,
                    activeRunId = "analysis-run",
                ),
                analysisRunning = false,
            ),
        )
    }

    @Test
    fun `initial thumbnails never masquerade as AI waiting`() {
        assertEquals(
            ProgressOverlayMode.THUMBNAILS,
            resolveProgressOverlayMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.INITIAL_THUMBNAILS,
                    hasBaseline = false,
                    activeRunId = "thumbnail-run",
                ),
                analysisRunning = false,
            ),
        )
    }

    @Test
    fun `stale analysis running cannot override thumbnail stage`() {
        assertEquals(
            ProgressOverlayMode.THUMBNAILS,
            resolveProgressOverlayMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.INCREMENTAL_THUMBNAILS,
                    hasBaseline = true,
                    activeRunId = "thumbnail-run",
                ),
                analysisRunning = true,
            ),
        )
    }

    @Test
    fun `stale analysis running cannot override publish stage`() {
        assertEquals(
            ProgressOverlayMode.THUMBNAILS,
            resolveProgressOverlayMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.REBUILD_PUBLISH,
                    hasBaseline = true,
                    activeRunId = "publish-run",
                ),
                analysisRunning = true,
            ),
        )
    }

    @Test
    fun `scan stage takes its meaning from durable pipeline`() {
        assertEquals(
            ProgressOverlayMode.SCAN,
            resolveProgressOverlayMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.REBUILD_SCAN,
                    hasBaseline = true,
                    activeRunId = "rebuild-run",
                ),
                analysisRunning = true,
            ),
        )
    }

    @Test
    fun `lite analysis transition with empty automatic plan never shows AI waiting`() {
        listOf(
            LibraryPipelineStage.INITIAL_ANALYSIS,
            LibraryPipelineStage.INCREMENTAL_ANALYSIS,
            LibraryPipelineStage.REBUILD_ANALYSIS,
        ).forEach { stage ->
            assertEquals(
                stage.name,
                ProgressOverlayMode.HIDDEN,
                resolveProgressOverlayMode(
                    pipelineState = pipeline(
                        stage = stage,
                        hasBaseline = true,
                        activeRunId = "lite-analysis-run",
                    ),
                    analysisRunning = false,
                    automaticAnalysisEnabled = false,
                ),
            )
        }
    }

    @Test
    fun `full and lite share scan visibility while retaining edition analysis policy`() {
        val runningScan = pipeline(
            stage = LibraryPipelineStage.INCREMENTAL_SCAN,
            hasBaseline = true,
            activeRunId = "shared-run",
        )
        listOf(true, false).forEach { automaticAnalysisEnabled ->
            assertEquals(
                ProgressOverlayMode.SCAN,
                resolveProgressOverlayMode(
                    pipelineState = runningScan,
                    analysisRunning = false,
                    automaticAnalysisEnabled = automaticAnalysisEnabled,
                ),
            )
        }
    }

    private fun pipeline(
        stage: LibraryPipelineStage,
        hasBaseline: Boolean,
        activeRunId: String? = null,
        rebuildRequested: Boolean = false,
    ) = LibraryPipelineState(
        stage = stage,
        activeRunId = activeRunId,
        hasPublishedBaseline = hasBaseline,
        publishedGeneration = if (hasBaseline) 1L else 0L,
        rebuildRequested = rebuildRequested,
    )
}
