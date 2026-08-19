package com.renyxin.localalbum.data.worker

import com.renyxin.localalbum.data.repo.PersistedScanDrainResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanWorkerTest {
    @Test
    fun `completed persisted drain succeeds regardless of attempt count`() {
        assertEquals(
            ScanWorker.ScanWorkDecision.SUCCESS,
            ScanWorker.scanWorkDecision(PersistedScanDrainResult.Completed, runAttemptCount = 99),
        )
    }

    @Test
    fun `deferred persisted drain always retries`() {
        assertEquals(
            ScanWorker.ScanWorkDecision.RETRY,
            ScanWorker.scanWorkDecision(PersistedScanDrainResult.Deferred, runAttemptCount = 99),
        )
    }

    @Test
    fun `failed persisted drain retries below limit without losing run identity`() {
        val failure = PersistedScanDrainResult.Failed(
            scanId = "same-reserved-run",
            error = "SecurityException",
        )

        assertEquals(
            ScanWorker.ScanWorkDecision.RETRY,
            ScanWorker.scanWorkDecision(failure, runAttemptCount = 2),
        )
        assertEquals("same-reserved-run", failure.scanId)
        assertEquals("SecurityException", failure.error)
    }

    @Test
    fun `failed persisted drain becomes terminal only at retry limit`() {
        val failure = PersistedScanDrainResult.Failed(
            scanId = "same-reserved-run",
            error = "SecurityException",
        )

        assertEquals(
            ScanWorker.ScanWorkDecision.FAILURE,
            ScanWorker.scanWorkDecision(failure, runAttemptCount = 3),
        )
        assertEquals("same-reserved-run", failure.scanId)
    }
}
