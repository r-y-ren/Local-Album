package com.renyxin.localalbum.core.pipeline

import android.graphics.RectF
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.capability.CapabilitySlot
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import com.renyxin.localalbum.core.plugin.capability.QualityProvider
import com.renyxin.localalbum.core.plugin.capability.SceneProvider
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceClusterDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.edition.EditionConfiguration
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class AnalysisStageFactoryTest {
    @Test
    fun `registered providers do not create lite automatic stages without admitted report`() {
        val registry = registryWithAllProviders()
        val plan = factory(registry).createPlan(
            StageInclusionPolicy(LiteScanFeaturePolicy),
            AnalysisPlanType.ENHANCEMENT,
        )

        assertTrue(plan.stages.isEmpty())
        val pipeline = PluginAnalysisPipeline.create(plan)
        assertTrue(pipeline.requiredStageIds.isEmpty())
        assertFalse(plan.scopeParts.any { it.startsWith("face=") })
        assertFalse(plan.scopeParts.any { it.startsWith("scene=") })
        assertFalse(plan.scopeParts.any { it.startsWith("quality=") })
        assertTrue(plan.scopeParts.any { it.startsWith("admission=") })
        assertFalse(plan.claimLegacyFullAnalysisTasks)
    }

    @Test
    fun `lite manual plan retains scene and quality but never face semantic or ocr`() {
        val plan = factory(registryWithAllProviders()).createPlan(
            StageInclusionPolicy(LiteScanFeaturePolicy),
            AnalysisPlanType.MANUAL,
        )

        assertEquals(
            listOf(AnalysisStage.STAGE_SCENE, AnalysisStage.STAGE_QUALITY),
            plan.stages.map { it.stageId },
        )
        assertFalse(plan.scopeParts.any { it.startsWith("face=") })
        assertTrue(plan.scopeParts.contains("scene=test:scene"))
        assertTrue(plan.scopeParts.contains("quality=test:quality"))
        assertFalse(plan.scopeParts.any { it.startsWith("semantic=") || it.startsWith("ocr=") })
    }

    @Test
    fun `full factory preserves five stages while lite rejects full only bindings`() {
        val createFullPlan = {
            factory(registryWithAllProviders()).createPlan(
                StageInclusionPolicy(FullScanFeaturePolicy),
                AnalysisPlanType.ENHANCEMENT,
            )
        }

        if (EditionConfiguration.features.editionId == "full") {
            val plan = createFullPlan()
            assertEquals(
                listOf(
                    AnalysisStage.STAGE_FACE,
                    AnalysisStage.STAGE_SCENE,
                    AnalysisStage.STAGE_SEMANTIC,
                    AnalysisStage.STAGE_QUALITY,
                    AnalysisStage.STAGE_OCR,
                ),
                plan.stages.map { it.stageId },
            )
            assertTrue(plan.claimLegacyFullAnalysisTasks)
            assertTrue(plan.scopeParts.contains("policy=full@1"))
            assertTrue(plan.scopeParts.contains("plan=enhancement"))
        } else {
            val failure = runCatching(createFullPlan).exceptionOrNull()
            assertTrue(
                "Lite 编译图必须拒绝越权构造 Full-only Stage",
                failure is IllegalStateException,
            )
        }
    }

    private fun factory(registry: CapabilityRegistryV2) = AnalysisStageFactory(
        mediaDao = mock(MediaDao::class.java),
        faceDao = mock(FaceDao::class.java),
        faceClusterDao = mock(FaceClusterDao::class.java),
        embeddingDao = mock(EmbeddingDao::class.java),
        capabilityRegistry = registry,
    )

    private fun registryWithAllProviders() = CapabilityRegistryV2().apply {
        registerSlot(CapabilitySlot("face", "Face", "", FaceProvider::class, "test:face"))
        registerSlot(CapabilitySlot("scene", "Scene", "", SceneProvider::class, "test:scene"))
        registerSlot(CapabilitySlot("semantic", "Semantic", "", SemanticEmbedProvider::class, "test:semantic"))
        registerSlot(CapabilitySlot("quality", "Quality", "", QualityProvider::class, "test:quality"))
        registerSlot(CapabilitySlot("ocr", "OCR", "", OcrProvider::class, "test:ocr"))
        registerProvider("face", "test:face", FakeFaceProvider, isDefault = true)
        registerProvider("scene", "test:scene", FakeSceneProvider, isDefault = true)
        registerProvider("semantic", "test:semantic", FakeSemanticProvider, isDefault = true)
        registerProvider("quality", "test:quality", FakeQualityProvider, isDefault = true)
        registerProvider("ocr", "test:ocr", FakeOcrProvider, isDefault = true)
    }

    private object FakeFaceProvider : FaceProvider {
        override val providerId = "test:face"
        override val displayName = "Face"
        override val embeddingDim = 2
        override suspend fun detectFaces(file: File) = listOf(
            FaceProvider.DetectedFace(RectF(), floatArrayOf(0f, 1f)),
        )
        override suspend fun release() = Unit
    }

    private object FakeSceneProvider : SceneProvider {
        override val providerId = "test:scene"
        override val displayName = "Scene"
        override val labels = listOf("test")
        override suspend fun classify(file: File) = SceneProvider.SceneResult("test", 1f)
        override suspend fun release() = Unit
    }

    private object FakeSemanticProvider : SemanticEmbedProvider {
        override val providerId = "test:semantic"
        override val displayName = "Semantic"
        override val embeddingDim = 2
        override suspend fun embedImage(file: File, context: SemanticEmbedProvider.ImageContext?) =
            floatArrayOf(0f, 1f)
        override suspend fun embedText(query: String) = floatArrayOf(0f, 1f)
        override suspend fun release() = Unit
    }

    private object FakeQualityProvider : QualityProvider {
        override val providerId = "test:quality"
        override val displayName = "Quality"
        override suspend fun assess(file: File) = QualityProvider.QualityResult(
            overall = 1f,
            blurScore = 1f,
            exposureScore = 1f,
            contrastScore = 1f,
            colorScore = 1f,
            isTooBlurry = false,
        )
        override suspend fun release() = Unit
    }

    private object FakeOcrProvider : OcrProvider {
        override val providerId = "test:ocr"
        override val displayName = "OCR"
        override suspend fun recognize(file: File) = OcrProvider.OcrResult("")
        override suspend fun release() = Unit
    }
}
