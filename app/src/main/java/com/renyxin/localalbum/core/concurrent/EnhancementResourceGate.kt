package com.renyxin.localalbum.core.concurrent

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-local resource barrier between the core indexer, automatic enhancement, and interactive
 * AI work.
 *
 * The gate is deliberately independent from a Room/StateFlow observation. A worker can observe
 * a stale lifecycle value, while this gate still provides a strict happens-before relationship:
 * an automatic enhancement section either finishes before an exclusive section acquires the lock,
 * or it is rejected before it starts. Request counters are raised before waiting, so new automatic
 * work cannot enter while core, maintenance, or interactive AI is waiting for a running batch to
 * drain.
 *
 * Interactive face swap shares the heavy inference lane with automatic enhancement. Core scan has
 * admission priority: a face-swap request queues while core is active, and a queued face swap yields
 * the lane if a core request arrives before its execution section starts. Interactive thumbnails are
 * a documented exception during core scan and retain their independent one-permit lane. Backup
 * import/export is stricter: it closes both interactive admissions and drains every lane before
 * touching a cross-table snapshot or replacing production tables.
 */
object EnhancementResourceGate {
    private val automaticLane = Mutex()
    private val interactiveThumbnailLane = Mutex()
    private val interactiveThumbnailAdmission = Mutex()
    private val interactiveAiAdmission = Mutex()
    private val coreWaiters = AtomicInteger(0)
    private val maintenanceWaiters = AtomicInteger(0)
    private val interactiveAiWaiters = AtomicInteger(0)
    private val exclusiveEpoch = AtomicLong(0)

    /**
     * Monotonic invalidation token for automatic work that intentionally releases the lane between
     * bounded pages. A changed value means a core scan or backup maintenance request occurred, even
     * when that exclusive section completed before the automatic worker was scheduled again.
     */
    val currentExclusiveEpoch: Long
        get() = exclusiveEpoch.get()

    val isCoreRequested: Boolean
        get() = coreWaiters.get() > 0

    val isMaintenanceRequested: Boolean
        get() = maintenanceWaiters.get() > 0

    val isInteractiveAiRequested: Boolean
        get() = interactiveAiWaiters.get() > 0

    val isAutomaticWorkBlocked: Boolean
        get() = isCoreRequested || isMaintenanceRequested || isInteractiveAiRequested

    suspend fun <T> withCoreScan(
        onRequested: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T {
        coreWaiters.incrementAndGet()
        exclusiveEpoch.incrementAndGet()
        var interactiveAiAdmissionLocked = false
        return try {
            // Publish the core request before asking WorkManager to stop long-running automatic
            // consumers. Their cancellation handlers can then distinguish core preemption from a
            // user-requested pause and keep the durable run resumable.
            onRequested()
            // Closing admission gives core priority over a face swap that has not started its native
            // execution section. A face swap already inside the lane is allowed to finish safely.
            interactiveAiAdmission.lock()
            interactiveAiAdmissionLocked = true
            automaticLane.withLock { block() }
        } finally {
            if (interactiveAiAdmissionLocked) interactiveAiAdmission.unlock()
            coreWaiters.decrementAndGet()
        }
    }

    /**
     * Runs one automatic enhancement section only when the core lane is currently available.
     * Returning null asks the caller to release its lease and let WorkManager retry later.
     */
    suspend fun <T> tryWithAutomaticEnhancement(block: suspend () -> T): T? {
        if (isAutomaticWorkBlocked || !automaticLane.tryLock()) return null
        return try {
            // An exclusive request may arrive between the first check and tryLock(). Do not start
            // a new automatic section after that request has become visible.
            if (isAutomaticWorkBlocked) null else block()
        } finally {
            automaticLane.unlock()
        }
    }

    /**
     * Runs one interactive AI operation in the same heavy lane as automatic enhancement.
     *
     * The waiter counter blocks later automatic batches immediately. Admission is acquired before
     * the heavy lane, then released as soon as execution starts so a later core request can close
     * admission and wait for the bounded native operation to finish. If core or maintenance becomes
     * visible before execution begins, this request releases the lane and yields priority.
     */
    suspend fun <T> withInteractiveAi(
        onWaitingForCore: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T {
        interactiveAiWaiters.incrementAndGet()
        var waitingForCoreReported = false
        var admittedResult: InteractiveAiResult<T>? = null
        return try {
            while (admittedResult == null) {
                currentCoroutineContext().ensureActive()
                if (isCoreRequested && !waitingForCoreReported) {
                    waitingForCoreReported = true
                    onWaitingForCore()
                }

                var admissionLocked = false
                var automaticLocked = false
                val attemptResult = try {
                    interactiveAiAdmission.lock()
                    admissionLocked = true
                    automaticLane.lock()
                    automaticLocked = true

                    if (!isCoreRequested && !isMaintenanceRequested) {
                        // Let a subsequent core/maintenance request close admission while this
                        // already-started native section safely owns the heavy lane.
                        interactiveAiAdmission.unlock()
                        admissionLocked = false

                        // Close the race where an exclusive request became visible immediately after
                        // the first check but before admission was released.
                        if (!isCoreRequested && !isMaintenanceRequested) {
                            InteractiveAiResult(block())
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } finally {
                    if (automaticLocked) automaticLane.unlock()
                    if (admissionLocked) interactiveAiAdmission.unlock()
                }
                if (attemptResult == null) {
                    kotlinx.coroutines.yield()
                } else {
                    admittedResult = attemptResult
                }
            }
            checkNotNull(admittedResult).value
        } finally {
            interactiveAiWaiters.decrementAndGet()
        }
    }

    /**
     * Backup maintenance closes thumbnail admission before waiting, so new visible requests queue
     * behind it while an already-running bounded visible batch is allowed to drain.
     */
    suspend fun <T> withInteractiveThumbnail(block: suspend () -> T): T {
        interactiveThumbnailAdmission.lock()
        try {
            interactiveThumbnailLane.lock()
        } finally {
            interactiveThumbnailAdmission.unlock()
        }
        return try {
            block()
        } finally {
            interactiveThumbnailLane.unlock()
        }
    }

    /** Strict cross-table maintenance barrier used by backup import/export. */
    suspend fun <T> withExclusiveMaintenance(
        onRequested: suspend () -> Unit = {},
        block: suspend () -> T,
    ): T {
        maintenanceWaiters.incrementAndGet()
        exclusiveEpoch.incrementAndGet()
        var thumbnailAdmissionLocked = false
        var interactiveAiAdmissionLocked = false
        return try {
            interactiveThumbnailAdmission.lock()
            thumbnailAdmissionLocked = true
            interactiveAiAdmission.lock()
            interactiveAiAdmissionLocked = true
            onRequested()
            automaticLane.withLock {
                interactiveThumbnailLane.withLock { block() }
            }
        } finally {
            if (interactiveAiAdmissionLocked) interactiveAiAdmission.unlock()
            if (thumbnailAdmissionLocked) interactiveThumbnailAdmission.unlock()
            maintenanceWaiters.decrementAndGet()
        }
    }

    private class InteractiveAiResult<T>(val value: T)
}
