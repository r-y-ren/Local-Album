package com.renyxin.localalbum.edition

import android.util.Log
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.AnalysisStageBinding
import com.renyxin.localalbum.core.pipeline.stages.FaceStage
import com.renyxin.localalbum.core.pipeline.stages.OcrStage
import com.renyxin.localalbum.core.pipeline.stages.SemanticStage
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceClusterDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.SemanticDao

/** Full-only bindings for automatic face, semantic, and OCR analysis stages. */
internal object EditionAnalysisStageBindings {
    fun createBinding(
        stageId: String,
        mediaDao: MediaDao,
        faceDao: FaceDao,
        faceClusterDao: FaceClusterDao,
        embeddingDao: EmbeddingDao,
        semanticDao: SemanticDao?,
        capabilityRegistry: CapabilityRegistryV2,
    ): AnalysisStageBinding? = when (stageId) {
        AnalysisStage.STAGE_FACE -> bind<FaceProvider>(
            stageId = stageId,
            slotId = SLOT_FACE,
            capabilityRegistry = capabilityRegistry,
        ) { provider ->
            FaceStage(provider, mediaDao, faceDao, faceClusterDao)
        }
        AnalysisStage.STAGE_SEMANTIC -> bind<SemanticEmbedProvider>(
            stageId = stageId,
            slotId = SLOT_SEMANTIC,
            capabilityRegistry = capabilityRegistry,
        ) { provider ->
            SemanticStage(provider, mediaDao, embeddingDao, semanticDao = semanticDao)
        }
        AnalysisStage.STAGE_OCR -> bind<OcrProvider>(
            stageId = stageId,
            slotId = SLOT_OCR,
            capabilityRegistry = capabilityRegistry,
        ) { provider ->
            OcrStage(provider, mediaDao)
        }
        else -> error("Unsupported Full-only analysis stage: $stageId")
    }

    private inline fun <reified T : Any> bind(
        stageId: String,
        slotId: String,
        capabilityRegistry: CapabilityRegistryV2,
        create: (T) -> AnalysisStage,
    ): AnalysisStageBinding? {
        val provider = capabilityRegistry.getActiveProvider<T>(slotId)
        if (provider == null) {
            Log.w(TAG, "Provider unavailable for admitted Full stage: stage=$stageId, slot=$slotId")
            return null
        }
        val providerId = capabilityRegistry.getActiveProviderId(slotId) ?: return null
        return AnalysisStageBinding(create(provider), slotId, providerId)
    }

    private const val TAG = "EditionStageBindings"
    private const val SLOT_FACE = "face"
    private const val SLOT_SEMANTIC = "semantic"
    private const val SLOT_OCR = "ocr"
}
