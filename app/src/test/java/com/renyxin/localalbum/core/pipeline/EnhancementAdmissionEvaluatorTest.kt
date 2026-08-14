package com.renyxin.localalbum.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementAdmissionEvaluatorTest {
    @Test
    fun `missing report is fail closed`() {
        val evaluation = EnhancementAdmissionEvaluator.evaluate(
            report = null,
            expectedPolicyId = "lite",
            expectedPolicyVersion = 2,
            expectedReportHash = "approved-hash",
        )

        assertEquals(AutomaticAdmissionDecision.AUTO_DISABLED, evaluation.decision)
        assertEquals(setOf("report_missing"), evaluation.failedGates)
    }

    @Test
    fun `scene enables only when every identity and performance gate passes`() {
        val evaluation = EnhancementAdmissionEvaluator.evaluate(
            report = passingReport(EnhancementAdmissionStage.SCENE),
            expectedPolicyId = "lite",
            expectedPolicyVersion = 2,
            expectedReportHash = "approved-hash",
        )

        assertTrue(evaluation.isEnabled)
        assertTrue(evaluation.failedGates.isEmpty())
    }

    @Test
    fun `scene p99 and transaction regression disable automatic scheduling`() {
        val report = passingReport(EnhancementAdmissionStage.SCENE).copy(
            warmStageP99Ms = 81L,
            maxRowsPerDatabaseTransaction = 251,
        )

        val evaluation = evaluate(report)

        assertFalse(evaluation.isEnabled)
        assertTrue("stage_p99" in evaluation.failedGates)
        assertTrue("database_batching" in evaluation.failedGates)
    }

    @Test
    fun `quality cannot enable without bitmap decode reuse`() {
        val report = passingReport(EnhancementAdmissionStage.QUALITY).copy(
            bitmapDecodeReused = false,
        )

        val evaluation = evaluate(report)

        assertEquals(AutomaticAdmissionDecision.AUTO_DISABLED, evaluation.decision)
        assertTrue("bitmap_decode_reuse" in evaluation.failedGates)
    }

    @Test
    fun `report identity mismatch cannot be bypassed by passing numbers`() {
        val report = passingReport(EnhancementAdmissionStage.SCENE).copy(
            policyVersion = 1,
            reportHash = "different",
            deviceId = "",
        )

        val evaluation = evaluate(report)

        assertTrue("policy_identity_mismatch" in evaluation.failedGates)
        assertTrue("report_hash_mismatch" in evaluation.failedGates)
        assertTrue("device_missing" in evaluation.failedGates)
    }

    private fun evaluate(report: EnhancementAdmissionReport) = EnhancementAdmissionEvaluator.evaluate(
        report = report,
        expectedPolicyId = "lite",
        expectedPolicyVersion = 2,
        expectedReportHash = "approved-hash",
    )

    private fun passingReport(stage: EnhancementAdmissionStage) = EnhancementAdmissionReport(
        reportId = "device-ab-2026-08",
        reportHash = "approved-hash",
        policyId = "lite",
        policyVersion = 2,
        stage = stage,
        deviceId = "reference-arm64-device",
        datasetId = "lite-mixed-10k-v1",
        sampleCount = 5,
        warmStageP95Ms = if (stage == EnhancementAdmissionStage.SCENE) 40L else 20L,
        warmStageP99Ms = if (stage == EnhancementAdmissionStage.SCENE) 80L else null,
        singleAddCoreP95DeltaMs = if (stage == EnhancementAdmissionStage.SCENE) 150L else 50L,
        full1kCoreMedianRegressionPercent = if (stage == EnhancementAdmissionStage.SCENE) 8.0 else 3.0,
        full1kCoreP95RegressionPercent = if (stage == EnhancementAdmissionStage.SCENE) 8.0 else 3.0,
        full10kCoreMedianRegressionPercent = if (stage == EnhancementAdmissionStage.SCENE) 8.0 else 3.0,
        full10kCoreP95RegressionPercent = if (stage == EnhancementAdmissionStage.SCENE) 8.0 else 3.0,
        full10kAbsoluteDeltaMs = if (stage == EnhancementAdmissionStage.SCENE) 30_000L else 10_000L,
        steadyThroughputRegressionPercent = if (stage == EnhancementAdmissionStage.SCENE) 8.0 else null,
        maxRowsPerDatabaseTransaction = 250,
        bitmapDecodeReused = stage == EnhancementAdmissionStage.QUALITY,
        coreStartsBeforeEnhancement = true,
        boundedPreemptionVerified = true,
        faceSwapArbitrationVerified = true,
        crashFree = true,
    )
}
