package com.renyxin.localalbum.edition

import com.renyxin.localalbum.core.pipeline.AnalysisPlanType
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.StageInclusionPolicy
import com.renyxin.localalbum.core.search.KeywordSearchProfile
import com.renyxin.localalbum.ui.Screen
import com.renyxin.localalbum.ui.resolveEditionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EditionConfigurationContractTest {
    private val features = EditionConfiguration.features
    private val stagePolicy = StageInclusionPolicy(features.scanFeaturePolicy)
    private val enhancementStages = stagePolicy.includedStageIds(AnalysisPlanType.ENHANCEMENT)
    private val manualStages = stagePolicy.includedStageIds(AnalysisPlanType.MANUAL)

    @Test
    fun `compiled edition exposes its exact capability and stage matrix`() {
        when (features.editionId) {
            "full" -> {
                assertEquals(EditionFeatures.ALL_CAPABILITY_SLOTS, features.registeredCapabilitySlots)
                assertEquals(KeywordSearchProfile.FULL, features.keywordSearchProfile)
                assertEquals(
                    listOf(
                        AnalysisStage.STAGE_FACE,
                        AnalysisStage.STAGE_SCENE,
                        AnalysisStage.STAGE_SEMANTIC,
                        AnalysisStage.STAGE_QUALITY,
                        AnalysisStage.STAGE_OCR,
                    ),
                    enhancementStages,
                )
                assertTrue(features.showPeopleAlbums)
                assertTrue(features.enableSemanticSearch)
                assertTrue(features.showAiAnalysisPreferences)
                assertTrue(features.showPluginManager)
                assertTrue(features.showFaceSwap)
                assertTrue(features.allowFaceClusterMaintenance)
            }

            "lite" -> {
                assertEquals(EditionFeatures.LITE_CAPABILITY_SLOTS, features.registeredCapabilitySlots)
                assertEquals(KeywordSearchProfile.LITE, features.keywordSearchProfile)
                assertTrue(enhancementStages.isEmpty())
                assertEquals(
                    listOf(AnalysisStage.STAGE_SCENE, AnalysisStage.STAGE_QUALITY),
                    manualStages,
                )
                assertFalse(AnalysisStage.STAGE_FACE in manualStages)
                assertFalse(AnalysisStage.STAGE_SEMANTIC in manualStages)
                assertFalse(AnalysisStage.STAGE_OCR in manualStages)
                assertEquals(2, features.scanFeaturePolicy.policyVersion)
                assertTrue(stagePolicy.scopeParts(AnalysisPlanType.ENHANCEMENT).any {
                    it.startsWith("admission=")
                })
                assertFalse(features.showPeopleAlbums)
                assertFalse(features.enableSemanticSearch)
                assertFalse(features.showAiAnalysisPreferences)
                assertFalse(features.showPluginManager)
                assertTrue(features.showFaceSwap)
                assertFalse(features.allowFaceClusterMaintenance)
            }

            else -> error("Unknown edition: ${features.editionId}")
        }
    }

    @Test
    fun `compiled edition enforces navigation boundaries while keeping face swap reachable`() {
        assertSame(Screen.FaceSwap, resolveEditionScreen(Screen.FaceSwap, features))

        val pluginDestinations = listOf(
            Screen.PluginManager,
            Screen.ModelImportWizard,
            Screen.ModelJsonEditor("test.plugin"),
        )
        pluginDestinations.forEach { target ->
            val resolved = resolveEditionScreen(target, features)
            if (features.editionId == "lite") {
                assertSame(Screen.Main, resolved)
            } else {
                assertEquals(target, resolved)
            }
        }

        listOf("people-albums", "analysis-preferences").forEach { destinationId ->
            val target = Screen.Edition(destinationId)
            val resolved = resolveEditionScreen(target, features)
            if (features.editionId == "lite") {
                assertSame(Screen.Main, resolved)
            } else {
                assertEquals(target, resolved)
            }
        }
        assertSame(
            Screen.Main,
            resolveEditionScreen(Screen.Edition("unknown-edition-destination"), features),
        )
    }
}
