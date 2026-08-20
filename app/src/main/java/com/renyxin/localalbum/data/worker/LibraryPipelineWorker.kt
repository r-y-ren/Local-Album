package com.renyxin.localalbum.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
import com.renyxin.localalbum.data.db.entity.isScanProgressVisible

/**
 * Single durable pump for the serialized library pipeline.
 *
 * This worker never performs expensive work itself. It advances only cheap publication/terminal
 * transitions and wakes exactly the worker admitted by the persisted stage. Repeated wake-ups are
 * therefore safe and MediaStore events can accumulate without preempting the active stage.
 */
class LibraryPipelineWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val container = app.container
        val coordinator = container.libraryPipelineCoordinator

        return runCatching {
            // One mutex/Room preparation pass owns recovery, idle admission and exact thumbnail-gate
            // convergence. The current pump consumes its library continuation, while any admitted
            // thumbnail/analysis lane is dispatched only after the coordinator lock has been released.
            val state = coordinator.prepareLibraryWorkerPass()
            when (val stage = LibraryPipelineStage.fromPersisted(state.stage)) {
                LibraryPipelineStage.INITIAL_SCAN,
                LibraryPipelineStage.INCREMENTAL_SCAN,
                LibraryPipelineStage.REBUILD_SCAN,
                -> if (isScanProgressVisible(stage, state.activeRunId, state.rebuildRequested)) {
                    ScanWorker.schedule(applicationContext)
                }

                LibraryPipelineStage.INITIAL_THUMBNAILS,
                LibraryPipelineStage.INCREMENTAL_THUMBNAILS,
                LibraryPipelineStage.REBUILD_THUMBNAILS,
                -> Unit

                LibraryPipelineStage.INITIAL_PUBLISH,
                LibraryPipelineStage.INCREMENTAL_PUBLISH,
                LibraryPipelineStage.REBUILD_PUBLISH,
                -> coordinator.publishCandidateAndAdvance()

                LibraryPipelineStage.INITIAL_ANALYSIS,
                LibraryPipelineStage.INCREMENTAL_ANALYSIS,
                LibraryPipelineStage.REBUILD_ANALYSIS,
                -> coordinator.startOrFinishAnalysis()

                LibraryPipelineStage.UNINITIALIZED,
                LibraryPipelineStage.READY,
                LibraryPipelineStage.NEEDS_REBUILD,
                LibraryPipelineStage.FAILED,
                -> Unit
            }
            Result.success()
        }.getOrElse { error ->
            Log.e(TAG, "图库流水线协调失败", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "LibraryPipeline"
        private const val WORK_NAME = "library_pipeline_pump"

        /** External wakes are level-triggered by durable state, so one pending pump is sufficient. */
        fun enqueue(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

        /** A successful stage transition appends exactly one successor behind a possibly running pump. */
        fun appendSuccessor(context: Context) = enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                policy,
                OneTimeWorkRequestBuilder<LibraryPipelineWorker>().build(),
            )
        }
    }
}
