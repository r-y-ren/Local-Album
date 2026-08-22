package com.renyxin.localalbum.data.worker

import com.renyxin.localalbum.data.repo.ThumbnailWakeDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThumbnailWorkerTest {
    @Test
    fun `fixed lane pumps never derive unique work from media or generation`() {
        assertEquals(
            "thumbnail_interactive_pump",
            ThumbnailWakeDispatcher.workName("interactive"),
        )
        assertEquals(
            "thumbnail_automatic_pump",
            ThumbnailWakeDispatcher.workName("automatic"),
        )
        assertNotEquals(
            ThumbnailWakeDispatcher.workName("interactive"),
            ThumbnailWakeDispatcher.workName("automatic"),
        )
    }

    @Test
    fun `business retry remains database delay and bounded`() {
        assertEquals(60_000L,ThumbnailWorker.retryDelayMs(1))
        assertEquals(120_000L,ThumbnailWorker.retryDelayMs(2))
        assertEquals(61_440_000L,ThumbnailWorker.retryDelayMs(100))
    }

    @Test
    fun `thumbnail pump keeps fixed batch and run bound`() {
        assertEquals(8,ThumbnailWorker.BATCH_SIZE)
        assertEquals(8,ThumbnailWorker.MAX_BATCHES_PER_RUN)
        assertEquals(64,ThumbnailWorker.MAX_TASKS_PER_RUN)
    }

    @Test
    fun `concurrent pool runs at most the requested concurrency and keeps order`() = runTest {
        var running = 0
        var peak = 0
        val results = ThumbnailWorker.mapWithConcurrency(listOf(1,2,3,4,5),2) { item ->
            running += 1
            peak = maxOf(peak,running)
            delay(100)
            running -= 1
            item * 10
        }
        assertEquals(listOf(10,20,30,40,50),results)
        assertEquals(2,peak)
    }

    @Test
    fun `concurrent pool clamps illegal concurrency to serial`() = runTest {
        var running = 0
        var peak = 0
        val results = ThumbnailWorker.mapWithConcurrency(listOf(1,2,3),0) { item ->
            running += 1
            peak = maxOf(peak,running)
            delay(10)
            running -= 1
            item
        }
        assertEquals(listOf(1,2,3),results)
        assertEquals(1,peak)
    }

    @Test
    fun `concurrent pool tolerates empty batch and surplus concurrency`() = runTest {
        assertEquals(
            emptyList<Int>(),
            ThumbnailWorker.mapWithConcurrency<Int,Int>(emptyList(),4) { it },
        )
        assertEquals(
            listOf(1,2),
            ThumbnailWorker.mapWithConcurrency(listOf(1,2),Int.MAX_VALUE) { it },
        )
    }

    @Test
    fun `parent serially summarizes child task outcomes`() {
        assertEquals(
            ThumbnailWorker.TaskProcessingResult(processed=3,failed=1),
            ThumbnailWorker.summarizeBatch(
                listOf(
                    ThumbnailWorker.TaskProcessingResult(processed=1,failed=0),
                    ThumbnailWorker.TaskProcessingResult(processed=0,failed=1),
                    ThumbnailWorker.TaskProcessingResult(processed=1,failed=0),
                    ThumbnailWorker.TaskProcessingResult(processed=1,failed=0),
                ),
            ),
        )
    }

    @Test
    fun `progress callback occurs once for each non-empty claimed batch`() {
        assertEquals(3,ThumbnailWorker.progressCallbackCount(listOf(4,4,1)))
        assertEquals(2,ThumbnailWorker.progressCallbackCount(listOf(4,0,2,0)))
        assertEquals(0,ThumbnailWorker.progressCallbackCount(emptyList()))
    }

    @Test
    fun `dispatcher enqueue failure backoff is bounded`() {
        assertEquals(1_000L,ThumbnailWakeDispatcher.dispatchBackoffMs(1))
        assertEquals(2_000L,ThumbnailWakeDispatcher.dispatchBackoffMs(2))
        assertEquals(256_000L,ThumbnailWakeDispatcher.dispatchBackoffMs(100))
    }
}
