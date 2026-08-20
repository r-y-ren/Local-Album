package com.renyxin.localalbum.data.worker

import com.renyxin.localalbum.data.repo.ThumbnailWakeDispatcher
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
        assertEquals(4,ThumbnailWorker.BATCH_SIZE)
        assertEquals(8,ThumbnailWorker.MAX_BATCHES_PER_RUN)
        assertEquals(32,ThumbnailWorker.MAX_TASKS_PER_RUN)
    }

    @Test
    fun `batch execution waves follow dynamic thumbnail concurrency`() {
        assertEquals(
            listOf(listOf(1),listOf(2),listOf(3),listOf(4)),
            ThumbnailWorker.executionWaves(listOf(1,2,3,4),1),
        )
        assertEquals(
            listOf(listOf(1,2),listOf(3,4)),
            ThumbnailWorker.executionWaves(listOf(1,2,3,4),2),
        )
        assertEquals(
            listOf(listOf(1,2,3,4)),
            ThumbnailWorker.executionWaves(listOf(1,2,3,4),4),
        )
    }

    @Test
    fun `batch execution clamps illegal concurrency to bitmap safe range`() {
        assertEquals(
            listOf(listOf(1),listOf(2),listOf(3),listOf(4)),
            ThumbnailWorker.executionWaves(listOf(1,2,3,4),0),
        )
        assertEquals(
            listOf(listOf(1,2,3,4)),
            ThumbnailWorker.executionWaves(listOf(1,2,3,4),Int.MAX_VALUE),
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
