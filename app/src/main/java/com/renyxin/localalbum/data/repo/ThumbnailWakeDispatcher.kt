package com.renyxin.localalbum.data.repo

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao
import com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity
import com.renyxin.localalbum.data.worker.ThumbnailWorker
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Room-backed level dispatcher. Producers only mutate task/lane facts and call [kick]; the observer and
 * startup [replay] make commit -> kick loss recoverable. WorkManager owns no business retry state.
 */
class ThumbnailWakeDispatcher(
    context: Context,
    private val taskDao: ThumbnailTaskDao,
    private val scope: CoroutineScope,
    private val processEpoch: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val enqueue: (String, Long, String) -> Operation = { laneId, revision, token ->
        val request = OneTimeWorkRequestBuilder<ThumbnailWorker>()
            .setInputData(
                workDataOf(
                    ThumbnailWorker.KEY_LANE_ID to laneId,
                    ThumbnailWorker.KEY_DISPATCH_REVISION to revision,
                    ThumbnailWorker.KEY_DISPATCH_TOKEN to token,
                ),
            )
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(laneId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    },
) {
    private val signals = mapOf(
        ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID to Channel<Unit>(Channel.CONFLATED),
        ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID to Channel<Unit>(Channel.CONFLATED),
    )

    init {
        signals.forEach { (laneId,signal) ->
            scope.launch { dispatchLoop(laneId,signal) }
        }
        scope.launch {
            taskDao.observeLaneStates().collect { states ->
                states.forEach(::scheduleFromState)
            }
        }
    }

    fun replay() {
        kick(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID)
        kick(ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID)
    }

    fun kick(laneId: String) {
        requireNotNull(signals[laneId]) { "Unknown thumbnail lane: $laneId" }.trySend(Unit)
    }

    /** One stable serial actor per lane. A new Room emission is conflated, never cancels an in-flight Operation. */
    private suspend fun dispatchLoop(laneId: String, signal: Channel<Unit>) {
        for (ignored in signal) {
            try {
                dispatchWhenDue(laneId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Durable lane facts remain replayable. Avoid killing the actor on transient DB/runtime errors.
                delay(ACTOR_ERROR_RETRY_MS)
                signal.trySend(Unit)
            }
        }
    }

    private fun scheduleFromState(state: ThumbnailLaneWakeEntity) {
        when (state.deliveryState) {
            ThumbnailLaneWakeEntity.STATE_PENDING,
            ThumbnailLaneWakeEntity.STATE_DELAYED,
            -> kick(state.laneId)
        }
    }

    private suspend fun dispatchWhenDue(laneId: String) {
        // replay() may observe a WorkManager request already persisted before process death. Preserve
        // its exact token until its dispatch lease expires instead of turning live ENQUEUED into PENDING.
        val observed = taskDao.laneState(laneId)
        if (observed != null && observed.deliveryState in setOf(
                ThumbnailLaneWakeEntity.STATE_DISPATCHING,
                ThumbnailLaneWakeEntity.STATE_ENQUEUED,
            ) && observed.dispatchLeaseUntil > now()
        ) delay(observed.dispatchLeaseUntil - now())
        val initial = taskDao.recomputeLaneLevel(laneId, now())
        val due = maxOf(initial.notBefore, initial.nextDispatchAt)
        if (due > now()) delay(due - now())
        val timestamp = now()
        val token = ThumbnailTaskDao.processOwnedToken(processEpoch,UUID.randomUUID().toString())
        val claimed = taskDao.claimDispatch(
            laneId = laneId,
            token = token,
            now = timestamp,
            leaseDurationMs = DISPATCH_LEASE_MS,
        ) ?: return
        try {
            enqueue(laneId, claimed.revision, token).awaitTerminal()
            if (taskDao.markDispatchEnqueued(laneId, claimed.revision, token, now()) != 1) {
                // Worker 可在 Operation terminal 回调前消费 DISPATCHING。认可由 exact token
                // 启动且 revision 单调递增的 RUNNING；其余情况才做通用 level repair。
                val current = taskDao.laneState(laneId)
                val workerAlreadyRunning = current?.deliveryState == ThumbnailLaneWakeEntity.STATE_RUNNING &&
                    current.dispatchToken == token && current.revision == claimed.revision + 1
                if (!workerAlreadyRunning) taskDao.recomputeLaneLevel(laneId, now())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val timestampAfterFailure = now()
            val attempts = claimed.enqueueAttemptCount.coerceAtLeast(1)
            taskDao.releaseFailedDispatch(
                laneId = laneId,
                token = token,
                error = (error.message ?: error::class.java.simpleName).take(160),
                nextDispatchAt = timestampAfterFailure + dispatchBackoffMs(attempts),
                now = timestampAfterFailure,
            )
            kick(laneId)
        }
    }

    companion object {
        const val INTERACTIVE_WORK_NAME = "thumbnail_interactive_pump"
        const val AUTOMATIC_WORK_NAME = "thumbnail_automatic_pump"
        private const val DISPATCH_LEASE_MS = 60_000L
        private const val ACTOR_ERROR_RETRY_MS = 1_000L
        private const val MAX_DISPATCH_BACKOFF_MS = 5 * 60_000L
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }

        fun workName(laneId: String): String = when (laneId) {
            ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID -> INTERACTIVE_WORK_NAME
            ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID -> AUTOMATIC_WORK_NAME
            else -> error("Unknown thumbnail lane: $laneId")
        }

        internal fun dispatchBackoffMs(attempt: Int): Long =
            (1_000L shl (attempt - 1).coerceIn(0, 8)).coerceAtMost(MAX_DISPATCH_BACKOFF_MS)

        /** `enqueueUniqueWork()` 返回不代表持久化成功；必须观察 Operation future 的终态。 */
        internal suspend fun Operation.awaitTerminal() {
            suspendCancellableCoroutine<Unit> { continuation ->
                val future = result
                future.addListener(
                    {
                        runCatching { future.get() }
                            .onSuccess { if (continuation.isActive) continuation.resume(Unit) }
                            .onFailure { if (continuation.isActive) continuation.resumeWithException(it) }
                    },
                    DIRECT_EXECUTOR,
                )
                continuation.invokeOnCancellation { future.cancel(true) }
            }
        }
    }
}
