package com.renyxin.localalbum.core.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeLoaderTest {
    @Test
    fun `construction does not load any native runtime`() {
        val shimLoads = AtomicInteger(0)
        val openCvLoads = AtomicInteger(0)

        val loader = NativeRuntimeLoader(
            loadShim = { shimLoads.incrementAndGet() },
            initializeOpenCv = {
                openCvLoads.incrementAndGet()
                true
            },
        )

        assertEquals(0, shimLoads.get())
        assertEquals(0, openCvLoads.get())
        assertFalse(loader.isShimReady)
        assertFalse(loader.isOpenCvReady)
    }

    @Test
    fun `onnx prerequisite loads only shim and is idempotent`() {
        val shimLoads = AtomicInteger(0)
        val openCvLoads = AtomicInteger(0)
        val loader = NativeRuntimeLoader(
            loadShim = { shimLoads.incrementAndGet() },
            initializeOpenCv = {
                openCvLoads.incrementAndGet()
                true
            },
        )

        repeat(3) { loader.ensureOnnxPrerequisite() }

        assertEquals(1, shimLoads.get())
        assertEquals(0, openCvLoads.get())
        assertTrue(loader.isShimReady)
        assertFalse(loader.isOpenCvReady)
    }

    @Test
    fun `opencv initialization is shim first and idempotent`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val loader = NativeRuntimeLoader(
            loadShim = { events += "shim" },
            initializeOpenCv = {
                events += "opencv"
                true
            },
        )

        repeat(3) { loader.ensureOpenCvRuntime() }

        assertEquals(listOf("shim", "opencv"), events)
        assertTrue(loader.isShimReady)
        assertTrue(loader.isOpenCvReady)
    }

    @Test
    fun `concurrent callers initialize each native prerequisite once`() {
        val shimLoads = AtomicInteger(0)
        val openCvLoads = AtomicInteger(0)
        val loader = NativeRuntimeLoader(
            loadShim = {
                Thread.sleep(5)
                shimLoads.incrementAndGet()
            },
            initializeOpenCv = {
                Thread.sleep(5)
                openCvLoads.incrementAndGet()
                true
            },
        )
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(CONCURRENT_CALLERS)

        try {
            val futures = List(CONCURRENT_CALLERS) {
                executor.submit {
                    check(start.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    loader.ensureOpenCvRuntime()
                }
            }
            start.countDown()
            futures.forEach { it.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

        assertEquals(1, shimLoads.get())
        assertEquals(1, openCvLoads.get())
        assertTrue(loader.isShimReady)
        assertTrue(loader.isOpenCvReady)
    }

    @Test
    fun `failed shim load is not marked ready and can retry`() {
        val failFirst = AtomicBoolean(true)
        val shimLoads = AtomicInteger(0)
        val loader = NativeRuntimeLoader(
            loadShim = {
                shimLoads.incrementAndGet()
                if (failFirst.getAndSet(false)) error("shim failure")
            },
            initializeOpenCv = { true },
        )

        assertThrows(IllegalStateException::class.java) {
            loader.ensureNativePrerequisite()
        }
        assertFalse(loader.isShimReady)

        loader.ensureNativePrerequisite()

        assertEquals(2, shimLoads.get())
        assertTrue(loader.isShimReady)
    }

    @Test
    fun `failed opencv initialization is not marked ready and can retry`() {
        val initializeCalls = AtomicInteger(0)
        val loader = NativeRuntimeLoader(
            loadShim = {},
            initializeOpenCv = { initializeCalls.incrementAndGet() > 1 },
        )

        assertThrows(IllegalStateException::class.java) {
            loader.ensureOpenCvRuntime()
        }
        assertTrue(loader.isShimReady)
        assertFalse(loader.isOpenCvReady)

        loader.ensureOpenCvRuntime()

        assertEquals(2, initializeCalls.get())
        assertTrue(loader.isOpenCvReady)
    }

    private companion object {
        const val CONCURRENT_CALLERS = 24
        const val TEST_TIMEOUT_SECONDS = 5L
    }
}
