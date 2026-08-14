package com.renyxin.localalbum.edition

import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.AnalysisStageBinding
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceClusterDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.SemanticDao

/** Lite has no face-batch, semantic, or OCR stage implementation bindings. */
internal object EditionAnalysisStageBindings {
    @Suppress("UNUSED_PARAMETER")
    fun createBinding(
        stageId: String,
        mediaDao: MediaDao,
        faceDao: FaceDao,
        faceClusterDao: FaceClusterDao,
        embeddingDao: EmbeddingDao,
        semanticDao: SemanticDao?,
        capabilityRegistry: CapabilityRegistryV2,
    ): AnalysisStageBinding? {
        check(
            stageId != AnalysisStage.STAGE_FACE &&
                stageId != AnalysisStage.STAGE_SEMANTIC &&
                stageId != AnalysisStage.STAGE_OCR,
        ) { "Lite edition must never admit Full-only analysis stage: $stageId" }
        error("Unsupported Lite analysis stage contribution: $stageId")
    }
}
