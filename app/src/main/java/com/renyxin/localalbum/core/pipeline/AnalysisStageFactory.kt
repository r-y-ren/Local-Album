package com.renyxin.localalbum.core.pipeline

import android.util.Log
import com.renyxin.localalbum.core.pipeline.stages.QualityStage
import com.renyxin.localalbum.core.pipeline.stages.SceneStage
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.capability.QualityProvider
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import com.renyxin.localalbum.edition.EditionAnalysisStageBindings
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceClusterDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.SemanticDao

/** A policy-resolved set of executable stages and its durable task identity inputs. */
data class AnalysisStagePlan(
    val planType: AnalysisPlanType,
    val stages: List<AnalysisStage>,
    val scopeParts: List<String>,
    val legacyScopeParts: List<String>,
    val claimLegacyFullAnalysisTasks: Boolean,
)

/** A single Provider-backed binding contributed by either shared code or the compiled edition. */
internal data class AnalysisStageBinding(
    val stage: AnalysisStage,
    val slotId: String,
    val providerId: String,
)

/** Maps explicitly admitted stage IDs to Provider-backed stage implementations. */
class AnalysisStageFactory(
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
    private val faceClusterDao: FaceClusterDao,
    private val embeddingDao: EmbeddingDao,
    private val capabilityRegistry: CapabilityRegistryV2,
    private val semanticDao: SemanticDao? = null,
) {
    fun createPlan(
        inclusionPolicy: StageInclusionPolicy,
        planType: AnalysisPlanType,
    ): AnalysisStagePlan {
        val bindings = inclusionPolicy.includedStageIds(planType).mapNotNull(::createBinding)
        val providerParts = bindings.map { "${it.slotId}=${it.providerId}" }
        val stageParts = bindings.map { "${it.stage.stageId}@${it.stage.modelVersion}" }
        return AnalysisStagePlan(
            planType = planType,
            stages = bindings.map { it.stage },
            scopeParts = inclusionPolicy.scopeParts(planType) + providerParts + stageParts,
            legacyScopeParts = providerParts + stageParts,
            claimLegacyFullAnalysisTasks = inclusionPolicy.featurePolicy.claimLegacyFullAnalysisTasks,
        )
    }

    private fun createBinding(stageId: String): AnalysisStageBinding? = when (stageId) {
        AnalysisStage.STAGE_SCENE -> bind<SceneProvider>(stageId, SLOT_SCENE) { provider ->
            SceneStage(provider, mediaDao)
        }
        AnalysisStage.STAGE_QUALITY -> bind<QualityProvider>(stageId, SLOT_QUALITY) { provider ->
            QualityStage(provider, mediaDao)
        }
        AnalysisStage.STAGE_FACE,
        AnalysisStage.STAGE_SEMANTIC,
        AnalysisStage.STAGE_OCR -> EditionAnalysisStageBindings.createBinding(
            stageId = stageId,
            mediaDao = mediaDao,
            faceDao = faceDao,
            faceClusterDao = faceClusterDao,
            embeddingDao = embeddingDao,
            semanticDao = semanticDao,
            capabilityRegistry = capabilityRegistry,
        )
        else -> error("Unsupported analysis stage: $stageId")
    }

    private inline fun <reified T : Any> bind(
        stageId: String,
        slotId: String,
        create: (T) -> AnalysisStage,
    ): AnalysisStageBinding? {
        val provider = capabilityRegistry.getActiveProvider<T>(slotId)
        if (provider == null) {
            Log.w(TAG, "Provider unavailable for admitted stage: stage=$stageId, slot=$slotId")
            return null
        }
        val providerId = capabilityRegistry.getActiveProviderId(slotId) ?: return null
        return AnalysisStageBinding(create(provider), slotId, providerId)
    }

    companion object {
        private const val TAG = "AnalysisStageFactory"
        private const val SLOT_SCENE = "scene"
        private const val SLOT_QUALITY = "quality"
    }
}
