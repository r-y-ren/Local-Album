package com.renyxin.localalbum.core.concurrent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class InferenceMetricsTest {
    @Before
    fun setUp() = InferenceMetrics.reset()

    @After
    fun tearDown() = InferenceMetrics.reset()

    @Test
    fun `snapshot exposes nearest rank p95 and p99 for stage only reports`() {
        (1L..100L).forEach { elapsed ->
            InferenceMetrics.record(
                operation = "pipeline:file:core:scene",
                backend = InferenceMetrics.Backend.CPU_DEFAULT,
                elapsedMs = elapsed,
                success = true,
            )
        }

        val snapshot = InferenceMetrics.snapshot().single()
        assertEquals("pipeline:file:core:scene", snapshot.operation)
        assertEquals(50L, snapshot.p50Ms)
        assertEquals(95L, snapshot.p95Ms)
        assertEquals(99L, snapshot.p99Ms)
    }
}
