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
    fun `dispatcher enqueue failure backoff is bounded`() {
        assertEquals(1_000L,ThumbnailWakeDispatcher.dispatchBackoffMs(1))
        assertEquals(2_000L,ThumbnailWakeDispatcher.dispatchBackoffMs(2))
        assertEquals(256_000L,ThumbnailWakeDispatcher.dispatchBackoffMs(100))
    }
}
