package com.renyxin.localalbum.core.plugin.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceSwapExecutionPolicyTest {

    @Test
    fun `compatible provider passes the interactive face swap gate`() {
        val failure = FaceSwapExecutionPolicy.compatibilityFailure(
            providerDisplayName = "InsightFace",
            embeddingDim = 512,
            supportsFivePointLandmarks = true,
        )

        assertNull(failure)
    }

    @Test
    fun `provider without five point landmarks reports an actionable incompatibility`() {
        val failure = FaceSwapExecutionPolicy.compatibilityFailure(
            providerDisplayName = "ArcFace combination",
            embeddingDim = 512,
            supportsFivePointLandmarks = false,
        )

        requireNotNull(failure)
        assertEquals(
            OnDemandExecutionFailureKind.PROVIDER_INCOMPATIBLE,
            failure.kind,
        )
        assertTrue(failure.message.orEmpty().contains("五点关键点"))
        assertTrue(failure.message.orEmpty().contains("InsightFace"))
    }

    @Test
    fun `wrong embedding dimension reports the required dimension`() {
        val failure = FaceSwapExecutionPolicy.compatibilityFailure(
            providerDisplayName = "Test provider",
            embeddingDim = 128,
            supportsFivePointLandmarks = true,
        )

        requireNotNull(failure)
        assertEquals(
            OnDemandExecutionFailureKind.PROVIDER_INCOMPATIBLE,
            failure.kind,
        )
        assertTrue(failure.message.orEmpty().contains("128≠512"))
    }

    @Test
    fun `missing bundled model is not collapsed into a detection miss`() {
        val failure = FaceSwapExecutionPolicy.operationalFailure(
            RuntimeException(
                "模型文件不存在: model:inswapper -> files/models/model:inswapper.onnx",
            ),
        )

        assertEquals(
            OnDemandExecutionFailureKind.MODEL_ASSET_MISSING,
            failure.kind,
        )
        assertTrue(failure.message.orEmpty().contains("模型资产缺失"))
        assertTrue(failure.message.orEmpty().contains("InsightFace"))
        assertTrue(failure.message.orEmpty().contains("InSwapper"))
    }

    @Test
    fun `emap absence is classified as a model asset failure`() {
        val failure = FaceSwapExecutionPolicy.operationalFailure(
            IllegalStateException(
                "Required InSwapper emap asset is missing: models/emap_512.bin",
            ),
        )

        assertEquals(
            OnDemandExecutionFailureKind.MODEL_ASSET_MISSING,
            failure.kind,
        )
    }

    @Test
    fun `native link failure is reported separately from inference failure`() {
        val failure = FaceSwapExecutionPolicy.operationalFailure(
            UnsatisfiedLinkError("dlopen failed: libemutls_shim.so not found"),
        )

        assertEquals(
            OnDemandExecutionFailureKind.NATIVE_RUNTIME,
            failure.kind,
        )
        assertTrue(failure.message.orEmpty().contains("运行库初始化失败"))
    }

    @Test
    fun `ordinary provider or inference exception remains an inference failure`() {
        val failure = FaceSwapExecutionPolicy.operationalFailure(
            IllegalArgumentException("unexpected output tensor shape"),
        )

        assertEquals(
            OnDemandExecutionFailureKind.INFERENCE,
            failure.kind,
        )
        assertTrue(failure.message.orEmpty().contains("unexpected output tensor shape"))
    }

    @Test
    fun `an already classified failure is preserved`() {
        val original = FaceSwapExecutionPolicy.descriptorFailure()

        val classified = FaceSwapExecutionPolicy.operationalFailure(original)

        assertTrue(classified === original)
    }

    @Test
    fun `retryable failure restores descriptor availability`() {
        assertEquals(
            OnDemandRuntimeState.AVAILABLE_NEEDS_LOAD,
            FaceSwapExecutionPolicy.idleState(descriptorReady = true),
        )
        assertEquals(
            OnDemandRuntimeState.ERROR,
            FaceSwapExecutionPolicy.idleState(descriptorReady = false),
        )
    }
}
