package com.renyxin.localalbum.core.pipeline

import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScanFeaturePolicyTest {
    private val projectRoot: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { current ->
            current.parentFile?.takeUnless { it == current }
        }.firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: error("无法从工作目录定位 app/src/main/java")
    }
    @Test
    fun `full enhancement plan preserves five stage order`() {
        assertEquals(
            listOf(
                AnalysisStage.STAGE_FACE,
                AnalysisStage.STAGE_SCENE,
                AnalysisStage.STAGE_SEMANTIC,
                AnalysisStage.STAGE_QUALITY,
                AnalysisStage.STAGE_OCR,
            ),
            StageInclusionPolicy(FullScanFeaturePolicy)
                .includedStageIds(AnalysisPlanType.ENHANCEMENT),
        )
    }

    @Test
    fun `lite automatic enhancement is fail closed while manual scene and quality remain available`() {
        val policy = StageInclusionPolicy(LiteScanFeaturePolicy)
        val automaticStageIds = policy.includedStageIds(AnalysisPlanType.ENHANCEMENT)
        val manualStageIds = policy.includedStageIds(AnalysisPlanType.MANUAL)

        assertTrue(automaticStageIds.isEmpty())
        assertEquals(
            listOf(AnalysisStage.STAGE_SCENE, AnalysisStage.STAGE_QUALITY),
            manualStageIds,
        )
        assertFalse(AnalysisStage.STAGE_FACE in manualStageIds)
        assertFalse(AnalysisStage.STAGE_SEMANTIC in manualStageIds)
        assertFalse(AnalysisStage.STAGE_OCR in manualStageIds)
        assertEquals(2, LiteScanFeaturePolicy.policyVersion)
        assertTrue(policy.scopeParts(AnalysisPlanType.ENHANCEMENT).any { it.startsWith("admission=") })
        assertFalse(policy.scopeParts(AnalysisPlanType.MANUAL).any { it.startsWith("admission=") })
    }

    @Test
    fun `core plan never admits analysis stages`() {
        assertTrue(
            StageInclusionPolicy(FullScanFeaturePolicy)
                .includedStageIds(AnalysisPlanType.CORE)
                .isEmpty(),
        )
        assertTrue(
            StageInclusionPolicy(LiteScanFeaturePolicy)
                .includedStageIds(AnalysisPlanType.CORE)
                .isEmpty(),
        )
    }

    @Test
    fun `policy version and plan type change pipeline scope`() {
        fun plan(policyVersion: Int, planType: AnalysisPlanType) = AnalysisStagePlan(
            planType = planType,
            stages = emptyList(),
            scopeParts = listOf("policy=test@$policyVersion", "plan=${planType.persistedName}"),
            legacyScopeParts = emptyList(),
            claimLegacyFullAnalysisTasks = false,
        )

        val enhancementV1 = PluginAnalysisPipeline.create(plan(1, AnalysisPlanType.ENHANCEMENT))
        val enhancementV2 = PluginAnalysisPipeline.create(plan(2, AnalysisPlanType.ENHANCEMENT))
        val manualV1 = PluginAnalysisPipeline.create(plan(1, AnalysisPlanType.MANUAL))

        assertNotEquals(enhancementV1.pipelineScope, enhancementV2.pipelineScope)
        assertNotEquals(enhancementV1.pipelineScope, manualV1.pipelineScope)
    }

    @Test
    fun `only full policy declares legacy queue compatibility`() {
        val legacyParts = listOf("scene=test:scene", "core:scene@1")
        val full = PluginAnalysisPipeline.create(
            AnalysisStagePlan(
                planType = AnalysisPlanType.ENHANCEMENT,
                stages = emptyList(),
                scopeParts = listOf("policy=full@1", "plan=enhancement") + legacyParts,
                legacyScopeParts = legacyParts,
                claimLegacyFullAnalysisTasks = true,
            ),
        )
        val lite = PluginAnalysisPipeline.create(
            AnalysisStagePlan(
                planType = AnalysisPlanType.ENHANCEMENT,
                stages = emptyList(),
                scopeParts = listOf("policy=lite@1", "plan=enhancement") + legacyParts,
                legacyScopeParts = legacyParts,
                claimLegacyFullAnalysisTasks = false,
            ),
        )

        assertEquals(3, full.claimablePipelineScopes.size)
        assertTrue(AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE in full.claimablePipelineScopes)
        assertEquals(listOf(lite.pipelineScope), lite.claimablePipelineScopes)
    }

    @Test
    fun `retired policy selector is exact and independent of canonical scope part order`() {
        val selector = PluginAnalysisPipeline.retiredPolicyScopeSelector(
            planType = AnalysisPlanType.ENHANCEMENT,
            policyId = "lite",
            policyVersion = 1,
        )
        val canonicalScope = PluginAnalysisPipeline.buildPipelineScope(
            listOf(
                "policy=lite@1",
                "scene=test:scene",
                "plan=enhancement",
                "core:quality@1",
                "quality=test:quality",
                "core:scene@1",
            ),
        )

        assertEquals("pipeline:v1|", selector.pipelinePrefix)
        assertEquals("enhancement", selector.planName)
        assertEquals("lite@1", selector.policyIdentity)
        assertTrue(canonicalScope.startsWith("pipeline:v1|core:quality@1|core:scene@1|"))
        assertTrue(canonicalScope.contains("|plan=enhancement|"))
        assertTrue(canonicalScope.contains("|policy=lite@1|"))
        assertFalse(canonicalScope.startsWith("pipeline:v1|plan=enhancement"))
    }

    @Test
    fun `release policy snapshot matches production policies`() {
        val editions = JSONObject(
            File(projectRoot, "scripts/lite-artifact-purpose-policy.json").readText(),
        ).getJSONObject("editions")

        assertReleaseSnapshot(editions.getJSONObject("full"), FullScanFeaturePolicy)
        assertReleaseSnapshot(editions.getJSONObject("lite"), LiteScanFeaturePolicy)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate stage ids are rejected`() {
        val invalid = object : ScanFeaturePolicy {
            override val policyId = "invalid"
            override val policyVersion = 1
            override val claimLegacyFullAnalysisTasks = false
            override fun stageIds(planType: AnalysisPlanType) = listOf(
                AnalysisStage.STAGE_SCENE,
                AnalysisStage.STAGE_SCENE,
            )
        }

        StageInclusionPolicy(invalid).includedStageIds(AnalysisPlanType.ENHANCEMENT)
    }

    private fun assertReleaseSnapshot(
        snapshot: JSONObject,
        production: ScanFeaturePolicy,
    ) {
        assertEquals(production.policyId, snapshot.getString("productionPolicyId"))
        assertEquals(production.policyVersion, snapshot.getInt("productionPolicyVersion"))
        assertEquals(
            production.claimLegacyFullAnalysisTasks,
            snapshot.getBoolean("claimLegacyFullAnalysisTasks"),
        )
        assertEquals(
            production.stageIds(AnalysisPlanType.CORE),
            snapshot.getJSONArray("corePlan").toStringList(),
        )
        assertEquals(
            production.stageIds(AnalysisPlanType.ENHANCEMENT),
            snapshot.getJSONArray("automaticEnhancementPlan").toStringList(),
        )
        assertEquals(
            production.stageIds(AnalysisPlanType.MANUAL),
            snapshot.getJSONArray("manualEnhancementPlan").toStringList(),
        )

        val admission = production.enhancementAdmissionIdentity
        if (admission == null) {
            assertTrue(snapshot.isNull("enhancementAdmissionIdentity"))
        } else {
            val frozenAdmission = snapshot.getJSONObject("enhancementAdmissionIdentity")
            assertEquals(admission.reportId, frozenAdmission.getString("reportId"))
            assertEquals(admission.reportHash, frozenAdmission.getString("reportHash"))
        }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map(::getString)
}
