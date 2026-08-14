package com.renyxin.localalbum.core.pipeline

/** Automatic scheduling is fail-closed unless a complete, device-bound report passes every gate. */
enum class AutomaticAdmissionDecision {
    AUTO_ENABLED,
    AUTO_DISABLED,
}

enum class EnhancementAdmissionStage(
    val stageId: String,
) {
    SCENE(AnalysisStage.STAGE_SCENE),
    QUALITY(AnalysisStage.STAGE_QUALITY),
}

/**
 * Reproducible evidence for one Scene/Quality automatic-enhancement A/B decision.
 *
 * Nullable measurements represent missing evidence rather than zero cost. This distinction is the
 * core fail-closed invariant: CI or a developer machine cannot accidentally approve a stage merely
 * because no Android device was connected.
 */
data class EnhancementAdmissionReport(
    val reportId: String,
    val reportHash: String,
    val policyId: String,
    val policyVersion: Int,
    val stage: EnhancementAdmissionStage,
    val deviceId: String,
    val datasetId: String,
    val sampleCount: Int,
    val warmStageP95Ms: Long?,
    val warmStageP99Ms: Long?,
    val singleAddCoreP95DeltaMs: Long?,
    val full1kCoreMedianRegressionPercent: Double?,
    val full1kCoreP95RegressionPercent: Double?,
    val full10kCoreMedianRegressionPercent: Double?,
    val full10kCoreP95RegressionPercent: Double?,
    val full10kAbsoluteDeltaMs: Long?,
    val steadyThroughputRegressionPercent: Double?,
    val maxRowsPerDatabaseTransaction: Int?,
    val bitmapDecodeReused: Boolean?,
    val coreStartsBeforeEnhancement: Boolean?,
    val boundedPreemptionVerified: Boolean?,
    val faceSwapArbitrationVerified: Boolean?,
    val crashFree: Boolean?,
)

data class EnhancementAdmissionEvaluation(
    val decision: AutomaticAdmissionDecision,
    val failedGates: Set<String>,
) {
    val isEnabled: Boolean
        get() = decision == AutomaticAdmissionDecision.AUTO_ENABLED
}

/** Pure evaluator shared by JVM tests and device benchmark report ingestion. */
object EnhancementAdmissionEvaluator {
    private const val MIN_SAMPLE_COUNT = 5
    private const val MAX_DATABASE_BATCH_SIZE = 250

    fun evaluate(
        report: EnhancementAdmissionReport?,
        expectedPolicyId: String,
        expectedPolicyVersion: Int,
        expectedReportHash: String,
    ): EnhancementAdmissionEvaluation {
        if (report == null) return disabled("report_missing")

        val failed = linkedSetOf<String>()
        if (report.reportId.isBlank()) failed += "report_id_missing"
        if (report.reportHash != expectedReportHash || report.reportHash.isBlank()) {
            failed += "report_hash_mismatch"
        }
        if (report.policyId != expectedPolicyId || report.policyVersion != expectedPolicyVersion) {
            failed += "policy_identity_mismatch"
        }
        if (report.deviceId.isBlank()) failed += "device_missing"
        if (report.datasetId.isBlank()) failed += "dataset_missing"
        if (report.sampleCount < MIN_SAMPLE_COUNT) failed += "sample_count"
        if (report.coreStartsBeforeEnhancement != true) failed += "post_core_boundary"
        if (report.boundedPreemptionVerified != true) failed += "bounded_preemption"
        if (report.faceSwapArbitrationVerified != true) failed += "face_swap_arbitration"
        if (report.crashFree != true) failed += "stability"
        if (
            report.maxRowsPerDatabaseTransaction == null ||
            report.maxRowsPerDatabaseTransaction !in 1..MAX_DATABASE_BATCH_SIZE
        ) {
            failed += "database_batching"
        }

        when (report.stage) {
            EnhancementAdmissionStage.SCENE -> evaluateScene(report, failed)
            EnhancementAdmissionStage.QUALITY -> evaluateQuality(report, failed)
        }

        return if (failed.isEmpty()) {
            EnhancementAdmissionEvaluation(AutomaticAdmissionDecision.AUTO_ENABLED, emptySet())
        } else {
            EnhancementAdmissionEvaluation(AutomaticAdmissionDecision.AUTO_DISABLED, failed)
        }
    }

    private fun evaluateScene(
        report: EnhancementAdmissionReport,
        failed: MutableSet<String>,
    ) {
        requireAtMost(report.warmStageP95Ms, 40L, "stage_p95", failed)
        requireAtMost(report.warmStageP99Ms, 80L, "stage_p99", failed)
        requireAtMost(report.singleAddCoreP95DeltaMs, 150L, "single_add_core_delta", failed)
        requireAtMost(report.full1kCoreMedianRegressionPercent, 8.0, "full_1k_median", failed)
        requireAtMost(report.full1kCoreP95RegressionPercent, 8.0, "full_1k_p95", failed)
        requireAtMost(report.full10kCoreMedianRegressionPercent, 8.0, "full_10k_median", failed)
        requireAtMost(report.full10kCoreP95RegressionPercent, 8.0, "full_10k_p95", failed)
        requireAtMost(report.full10kAbsoluteDeltaMs, 30_000L, "full_10k_absolute", failed)
        requireAtMost(report.steadyThroughputRegressionPercent, 8.0, "throughput", failed)
    }

    private fun evaluateQuality(
        report: EnhancementAdmissionReport,
        failed: MutableSet<String>,
    ) {
        requireAtMost(report.warmStageP95Ms, 20L, "stage_p95", failed)
        // Quality currently has no independent P99 gate, but the field remains in the report schema
        // so Scene and Quality device runners emit one stable format.
        requireAtMost(report.singleAddCoreP95DeltaMs, 50L, "single_add_core_delta", failed)
        requireAtMost(report.full1kCoreMedianRegressionPercent, 3.0, "full_1k_median", failed)
        requireAtMost(report.full1kCoreP95RegressionPercent, 3.0, "full_1k_p95", failed)
        requireAtMost(report.full10kCoreMedianRegressionPercent, 3.0, "full_10k_median", failed)
        requireAtMost(report.full10kCoreP95RegressionPercent, 3.0, "full_10k_p95", failed)
        requireAtMost(report.full10kAbsoluteDeltaMs, 10_000L, "full_10k_absolute", failed)
        if (report.bitmapDecodeReused != true) failed += "bitmap_decode_reuse"
    }

    private fun requireAtMost(
        value: Long?,
        maximum: Long,
        gate: String,
        failed: MutableSet<String>,
    ) {
        if (value == null || value > maximum) failed += gate
    }

    private fun requireAtMost(
        value: Double?,
        maximum: Double,
        gate: String,
        failed: MutableSet<String>,
    ) {
        if (value == null || !value.isFinite() || value > maximum) failed += gate
    }

    private fun disabled(reason: String) = EnhancementAdmissionEvaluation(
        decision = AutomaticAdmissionDecision.AUTO_DISABLED,
        failedGates = setOf(reason),
    )
}
