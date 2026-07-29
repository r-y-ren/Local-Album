package com.renyxin.localalbum.core.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccelerationPolicyTest {

    @Test
    fun `disabled TFLite NNAPI policy falls back to XNNPACK with CPU pool size`() {
        val policy = ModelAccelerationPolicy(AccelerationBackend.TFLITE_NNAPI)

        assertFalse(policy.enabled)
        assertEquals(AccelerationBackend.CPU_XNNPACK, policy.effectiveBackend)
        assertEquals(InferenceDispatchers.inferenceConcurrency, policy.poolSize)
    }

    @Test
    fun `enabled hardware policy starts with a single-flight pool`() {
        val policy = ModelAccelerationPolicy(
            backend = AccelerationBackend.ONNX_NNAPI,
            enabled = true,
        )

        assertEquals(AccelerationBackend.ONNX_NNAPI, policy.effectiveBackend)
        assertEquals(1, policy.poolSize)
    }

    @Test
    fun `registry keeps TFLite and ONNX on safe CPU defaults`() {
        assertEquals(
            AccelerationBackend.CPU_XNNPACK,
            AccelerationPolicyRegistry.forTflite("model:scene").effectiveBackend,
        )
        assertEquals(
            AccelerationBackend.CPU_DEFAULT,
            AccelerationPolicyRegistry.forOnnx("model:face").effectiveBackend,
        )
    }
}
