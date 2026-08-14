package com.renyxin.localalbum.data.worker

import com.renyxin.localalbum.data.repo.PersistedScanDrainResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanWorkerTest {
    @Test
    fun `completed persisted drain succeeds regardless of attempt count`() {
        assertEquals(
            ScanWorker.ScanWorkDecision.SUCCESS,
            ScanWorker.scanWorkDecision(PersistedScanDrainResult.COMPLETED, runAttemptCount = 99),
        )
    }

    @Test
    fun `deferred persisted drain always retries`() {
        assertEquals(
            ScanWorker.ScanWorkDecision.RETRY,
            ScanWorker.scanWorkDecision(PersistedScanDrainResult.DEFERRED, runAttemptCount = 99),
        )
    }

    @Test
    fun `failed persisted drain retries below limit`() {
        assertEquals(
            ScanWorker.ScanWorkDecision.RETRY,
            ScanWorker.scanWorkDecision(PersistedScanDrainResult.FAILED, runAttemptCount = 2),
        )
    }

    @Test
    fun `failed persisted drain stops at retry limit`() {
        assertEquals(
            ScanWorker.ScanWorkDecision.FAILURE,
            ScanWorker.scanWorkDecision(PersistedScanDrainResult.FAILED, runAttemptCount = 3),
        )
    }
}
