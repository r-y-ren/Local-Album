package com.renyxin.localalbum.core.plugin.model

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelConsumerLifecycleTest {
    @Test
    fun `registered consumer prevents exact eviction until it unregisters`() {
        val lifecycle = ModelConsumerLifecycle()
        val evictions = AtomicInteger(0)

        lifecycle.register(MODEL_A, CONSUMER_A)

        assertFalse(
            lifecycle.evictIfUnused(
                modelId = MODEL_A,
                isLoaded = { true },
                evict = { evictions.incrementAndGet() },
            ),
        )
        assertEquals(setOf(CONSUMER_A), lifecycle.consumerIds(MODEL_A))
        assertEquals(0, evictions.get())

        lifecycle.unregister(MODEL_A, CONSUMER_A)

        assertTrue(
            lifecycle.evictIfUnused(
                modelId = MODEL_A,
                isLoaded = { true },
                evict = { evictions.incrementAndGet() },
            ),
        )
        assertEquals(1, evictions.get())
    }

    @Test
    fun `unloaded model is not reported as evicted`() {
        val lifecycle = ModelConsumerLifecycle()
        val callbackCalled = AtomicBoolean(false)

        assertFalse(
            lifecycle.evictIfUnused(
                modelId = MODEL_A,
                isLoaded = { false },
                evict = { callbackCalled.set(true) },
            ),
        )
        assertFalse(callbackCalled.get())
    }

    @Test
    fun `consumers are isolated by exact model id`() {
        val lifecycle = ModelConsumerLifecycle()
        lifecycle.register(MODEL_A, CONSUMER_A)
        val evictedB = AtomicBoolean(false)

        assertTrue(
            lifecycle.evictIfUnused(
                modelId = MODEL_B,
                isLoaded = { true },
                evict = { evictedB.set(true) },
            ),
        )

        assertTrue(evictedB.get())
        assertEquals(setOf(CONSUMER_A), lifecycle.consumerIds(MODEL_A))
        assertTrue(lifecycle.consumerIds(MODEL_B).isEmpty())
    }

    @Test
    fun `consumer registration waits until an in progress runtime close completes`() {
        val lifecycle = ModelConsumerLifecycle()
        val closeEntered = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val registrationStarted = CountDownLatch(1)
        val registrationFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val eviction = executor.submit<Boolean> {
                lifecycle.evictIfUnused(
                    modelId = MODEL_A,
                    isLoaded = { true },
                    evict = {
                        closeEntered.countDown()
                        check(releaseClose.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    },
                )
            }
            assertTrue(closeEntered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val registration = executor.submit {
                registrationStarted.countDown()
                lifecycle.register(MODEL_A, CONSUMER_A)
                registrationFinished.countDown()
            }
            assertTrue(registrationStarted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertFalse(
                "consumer admission must not overlap native close",
                registrationFinished.await(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS),
            )

            releaseClose.countDown()
            assertTrue(eviction.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            registration.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(registrationFinished.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(setOf(CONSUMER_A), lifecycle.consumerIds(MODEL_A))
        } finally {
            releaseClose.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `state callback observes serialized consumer snapshots`() {
        val lifecycle = ModelConsumerLifecycle()
        val snapshots = mutableListOf<Set<String>>()

        lifecycle.register(MODEL_A, CONSUMER_A) { snapshots += it }
        lifecycle.register(MODEL_A, CONSUMER_B) { snapshots += it }
        lifecycle.unregister(MODEL_A, CONSUMER_A) { snapshots += it }
        lifecycle.unregister(MODEL_A, CONSUMER_B) { snapshots += it }

        assertEquals(
            listOf(
                setOf(CONSUMER_A),
                setOf(CONSUMER_A, CONSUMER_B),
                setOf(CONSUMER_B),
                emptySet(),
            ),
            snapshots,
        )
    }

    private companion object {
        const val MODEL_A = "model:a"
        const val MODEL_B = "model:b"
        const val CONSUMER_A = "consumer:a"
        const val CONSUMER_B = "consumer:b"
        const val TEST_TIMEOUT_SECONDS = 5L
        const val SHORT_WAIT_MILLIS = 100L
    }
}
