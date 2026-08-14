package com.renyxin.localalbum.core.pipeline

/** Resolves and validates the ordered stage set for one analysis plan. */
class StageInclusionPolicy(
    val featurePolicy: ScanFeaturePolicy,
) {
    fun includedStageIds(planType: AnalysisPlanType): List<String> {
        val stageIds = featurePolicy.stageIds(planType)
        require(stageIds.size == stageIds.distinct().size) {
            "Policy ${featurePolicy.policyId} contains duplicate stages for ${planType.persistedName}"
        }
        val unknown = stageIds.filterNot { it in SUPPORTED_STAGE_IDS }
        require(unknown.isEmpty()) {
            "Policy ${featurePolicy.policyId} contains unsupported stages: ${unknown.joinToString()}"
        }
        return stageIds.toList()
    }

    fun scopeParts(planType: AnalysisPlanType): List<String> = buildList {
        add("policy=${featurePolicy.policyId}@${featurePolicy.policyVersion}")
        add("plan=${planType.persistedName}")
        if (planType == AnalysisPlanType.ENHANCEMENT) {
            featurePolicy.enhancementAdmissionIdentity?.let { add(it.scopePart()) }
        }
    }

    companion object {
        val SUPPORTED_STAGE_IDS: Set<String> = linkedSetOf(
            AnalysisStage.STAGE_FACE,
            AnalysisStage.STAGE_SCENE,
            AnalysisStage.STAGE_SEMANTIC,
            AnalysisStage.STAGE_QUALITY,
            AnalysisStage.STAGE_OCR,
        )
    }
}
