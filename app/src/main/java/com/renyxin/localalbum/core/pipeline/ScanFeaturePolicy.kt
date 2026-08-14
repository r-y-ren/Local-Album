package com.renyxin.localalbum.core.pipeline

/** Identifies why a stage plan is being assembled. */
enum class AnalysisPlanType(val persistedName: String) {
    CORE("core"),
    ENHANCEMENT("enhancement"),
    MANUAL("manual"),
}

/**
 * Frozen evidence identity for one automatic-enhancement policy decision.
 *
 * A missing device report must never be interpreted as permission to schedule work. The identity is
 * nevertheless persisted in the pipeline scope, so a later approved report necessarily creates a
 * new durable task identity rather than silently changing the meaning of an existing scope.
 */
data class EnhancementAdmissionIdentity(
    val reportId: String,
    val reportHash: String,
) {
    init {
        require(reportId.isNotBlank()) { "reportId must not be blank" }
        require(reportHash.isNotBlank()) { "reportHash must not be blank" }
    }

    fun scopePart(): String = "admission=$reportId@$reportHash"
}

/** Immutable product policy for automatic and user-triggered analysis plans. */
interface ScanFeaturePolicy {
    val policyId: String
    val policyVersion: Int

    /** Only Full may drain tasks created before policy-aware pipeline scopes existed. */
    val claimLegacyFullAnalysisTasks: Boolean

    /** Optional evidence identity included in durable scopes for report-gated automatic plans. */
    val enhancementAdmissionIdentity: EnhancementAdmissionIdentity?
        get() = null

    fun stageIds(planType: AnalysisPlanType): List<String>
}

/** Full keeps the existing five-stage post-scan analysis behavior. */
object FullScanFeaturePolicy : ScanFeaturePolicy {
    override val policyId: String = "full"
    override val policyVersion: Int = 1
    override val claimLegacyFullAnalysisTasks: Boolean = true

    private val analysisStages = listOf(
        AnalysisStage.STAGE_FACE,
        AnalysisStage.STAGE_SCENE,
        AnalysisStage.STAGE_SEMANTIC,
        AnalysisStage.STAGE_QUALITY,
        AnalysisStage.STAGE_OCR,
    )

    override fun stageIds(planType: AnalysisPlanType): List<String> = when (planType) {
        AnalysisPlanType.CORE -> emptyList()
        AnalysisPlanType.ENHANCEMENT,
        AnalysisPlanType.MANUAL,
        -> analysisStages
    }
}

/**
 * Lite policy is defined independently of build flavors so its hard boundaries can be unit tested.
 *
 * Phase 7 is deliberately fail-closed: neither Scene nor Quality has a device-bound A/B report that
 * satisfies every admission gate. They remain available to an explicit manual enhancement plan, but
 * the automatic ENHANCEMENT plan is empty. Enabling either stage requires a reviewed report, a new
 * report hash below, and another policy version bump.
 */
object LiteScanFeaturePolicy : ScanFeaturePolicy {
    override val policyId: String = "lite"
    override val policyVersion: Int = 2
    override val claimLegacyFullAnalysisTasks: Boolean = false
    override val enhancementAdmissionIdentity = EnhancementAdmissionIdentity(
        reportId = "lite-scene-quality-phase7",
        reportHash = "sha256:c78cb8c3466e3ab342dd813af35fae9d0a3cbe27bb7d6a836a39871f01ef4378",
    )

    private val manualEnhancementStages = listOf(
        AnalysisStage.STAGE_SCENE,
        AnalysisStage.STAGE_QUALITY,
    )

    override fun stageIds(planType: AnalysisPlanType): List<String> = when (planType) {
        AnalysisPlanType.CORE,
        AnalysisPlanType.ENHANCEMENT,
        -> emptyList()
        AnalysisPlanType.MANUAL -> manualEnhancementStages
    }
}
