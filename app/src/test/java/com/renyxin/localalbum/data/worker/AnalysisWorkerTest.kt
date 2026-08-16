package com.renyxin.localalbum.data.worker

import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.core.pipeline.StageResult
import com.renyxin.localalbum.data.db.entity.EnhancementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisWorkerTest {
    @Test
    fun `all stages successful can complete task`() {
        assertNull(AnalysisWorker.failureSummary(mapOf("face" to StageResult(3, 0))))
    }

    @Test
    fun `partial stage failure keeps task retryable`() {
        assertEquals(
            "semantic:2",
            AnalysisWorker.failureSummary(mapOf(
                "face" to StageResult(3, 0),
                "semantic" to StageResult(1, 2),
            )),
        )
    }

    @Test
    fun `empty pipeline is not marked done`() {
        assertEquals("pipeline_no_stages", AnalysisWorker.failureSummary(emptyMap()))
    }

    @Test
    fun `missing required stage is not marked done`() {
        assertEquals(
            "missing_stages:semantic",
            AnalysisWorker.failureSummary(
                mapOf("face" to StageResult(3, 0)),
                setOf("face", "semantic"),
            ),
        )
    }

    @Test
    fun `pipeline scope is stable and version sensitive`() {
        val first = PluginAnalysisPipeline.buildPipelineScope(listOf("scene=provider-b", "core:scene@1"))
        val reordered = PluginAnalysisPipeline.buildPipelineScope(listOf("core:scene@1", "scene=provider-b"))
        val upgraded = PluginAnalysisPipeline.buildPipelineScope(listOf("core:scene@2", "scene=provider-b"))
        assertEquals(first, reordered)
        assertTrue(first != upgraded)
    }

    @Test
    fun `retry delay is exponential and bounded`() {
        assertEquals(60_000L, AnalysisWorker.retryDelay(1))
        assertEquals(120_000L, AnalysisWorker.retryDelay(2))
        assertTrue(AnalysisWorker.retryDelay(100) > 0)
    }

    @Test
    fun `only paths failing a required stage are retried`() {
        val results = mapOf(
            "face" to StageResult(2, 0),
            "semantic" to StageResult(1, 1, failedPaths = setOf("/bad.jpg")),
        )

        assertEquals(
            setOf("/bad.jpg"),
            AnalysisWorker.failedPaths(
                results,
                setOf("face", "semantic"),
                listOf("/good.jpg", "/bad.jpg"),
            ),
        )
    }

    @Test
    fun `missing required stage conservatively retries whole attempted window`() {
        assertEquals(
            setOf("/a.jpg", "/b.jpg"),
            AnalysisWorker.failedPaths(
                mapOf("face" to StageResult(2, 0)),
                setOf("face", "semantic"),
                listOf("/a.jpg", "/b.jpg"),
            ),
        )
    }

    @Test
    fun `active tasks keep enhancement non terminal`() {
        assertNull(
            AnalysisWorker.terminalEnhancementState(
                activeTasks = 1,
                failedTasks = 0,
            ),
        )
    }

    @Test
    fun `failed terminal tasks do not fail core scan`() {
        assertEquals(
            EnhancementState.COMPLETED_WITH_FAILURES,
            AnalysisWorker.terminalEnhancementState(
                activeTasks = 0,
                failedTasks = 2,
            ),
        )
    }

    @Test
    fun `empty failed set completes enhancement independently`() {
        assertEquals(
            EnhancementState.COMPLETED,
            AnalysisWorker.terminalEnhancementState(
                activeTasks = 0,
                failedTasks = 0,
            ),
        )
    }

    @Test
    fun `large healthy queue appends bounded successors without consuming retry backoff`() {
        var remaining = 50_000 // 10,000 Full images multiplied by five Stage task scopes.
        var successors = 0
        while (remaining > 0) {
            remaining = (remaining - 1_000).coerceAtLeast(0)
            val decision = AnalysisWorker.continuationDecision(
                activeTasks = remaining,
                claimableTasks = remaining,
            )
            if (remaining > 0) {
                assertEquals(AnalysisWorker.ContinuationDecision.ENQUEUE_SUCCESSOR, decision)
                successors++
            } else {
                assertEquals(AnalysisWorker.ContinuationDecision.COMPLETE, decision)
            }
        }
        assertEquals(49, successors)
    }

    @Test
    fun `only active tasks delayed by task retry budget use work backoff`() {
        assertEquals(
            AnalysisWorker.ContinuationDecision.RETRY_BACKOFF,
            AnalysisWorker.continuationDecision(activeTasks = 7, claimableTasks = 0),
        )
    }

    @Test
    fun `user task admission distinguishes legacy work from explicit pause`() {
        assertTrue(
            AnalysisWorker.userTaskAdmission(
                userPaused = false,
                resumePending = false,
                activeUserTasks = 1,
            ),
        )
        assertEquals(
            false,
            AnalysisWorker.userTaskAdmission(
                userPaused = true,
                resumePending = true,
                activeUserTasks = 10,
            ),
        )
    }

    @Test
    fun `handoff appends a successor for an ordinary remaining batch`() {
        assertEquals(
            EnhancementHandoffWorker.ContinuationDecision.ENQUEUE_SUCCESSOR,
            EnhancementHandoffWorker.continuationDecision(
                hasActiveEntries = true,
                hasClaimableEntries = true,
            ),
        )
    }

    @Test
    fun `handoff reserves work retry for entries waiting on failure budget`() {
        assertEquals(
            EnhancementHandoffWorker.ContinuationDecision.RETRY_BACKOFF,
            EnhancementHandoffWorker.continuationDecision(
                hasActiveEntries = true,
                hasClaimableEntries = false,
            ),
        )
        assertEquals(
            EnhancementHandoffWorker.ContinuationDecision.COMPLETE,
            EnhancementHandoffWorker.continuationDecision(
                hasActiveEntries = false,
                hasClaimableEntries = false,
            ),
        )
    }
}
