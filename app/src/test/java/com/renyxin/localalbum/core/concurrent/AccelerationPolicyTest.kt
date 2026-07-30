package com.renyxin.localalbum.core.concurrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelerationPolicyTest {

    @Test
    fun `disabled TFLite NNAPI policy falls back to bounded XNNPACK pool`() {
        val policy = ModelAccelerationPolicy(AccelerationBackend.TFLITE_NNAPI)

        assertFalse(policy.enabled)
        assertEquals(AccelerationBackend.CPU_XNNPACK, policy.effectiveBackend)
        assertTrue(policy.poolSize in 1..2)
    }

    @Test
    fun `CPU pools stay bounded independently of device core count`() {
        assertEquals(2, AccelerationBackend.CPU_XNNPACK.maxPoolSize)
        assertEquals(2, AccelerationBackend.CPU_DEFAULT.maxPoolSize)
        assertTrue(AccelerationBackend.CPU_DEFAULT.maxPoolSize <= InferenceDispatchers.cpuCores)
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
