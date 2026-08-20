package com.renyxin.localalbum.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.concurrent.AnalysisDeviceCapabilityDetector
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingMode
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingResolver
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.dao.ThumbnailPublicationResult
import com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import com.renyxin.localalbum.data.prefs.SettingsStore
import com.renyxin.localalbum.data.repo.ThumbnailCacheTrimmer
import com.renyxin.localalbum.data.repo.ThumbnailWakeDispatcher
import com.renyxin.localalbum.data.source.MediaSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 固定双 lane pump；任务 level、延迟和运行租约全部由 Room 保存。 */
class ThumbnailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val container = app.container
        val database = container.database
        val dao = database.thumbnailTaskDao()
        val laneId = inputData.getString(KEY_LANE_ID) ?: return Result.success()
        val revision = inputData.getLong(KEY_DISPATCH_REVISION,-1L)
        val dispatchToken = inputData.getString(KEY_DISPATCH_TOKEN) ?: return Result.success()
        val runToken = ThumbnailTaskDao.processOwnedToken(
            container.thumbnailProcessEpoch,
            UUID.randomUUID().toString(),
        )
        val now = System.currentTimeMillis()

        // 必须先消费 exact ENQUEUED token；通用 level repair 会回收过期投递并清 token，
        // 若放在 CAS 前会把当前合法 Work 自己变成 stale。
        if (dao.startRun(
                laneId,revision,dispatchToken,runToken,now + RUN_LEASE_MS,now,
            ) != 1
        ) return Result.success()
        var processed = 0
        var failed = 0
        return try {
            // exact token 已被当前 run 接管；此后的设置读取、能力检测与 lane repair 都受
            // 下方统一取消/异常清理保护，不会把持久队列遗留在 ENQUEUED/RUNNING。
            dao.recomputeLaneLevel(laneId,now)
            // WorkManager 可在冷启动或进程重建后运行，因此每次 pump 直接从持久化设置解析
            // 一份不可变 profile；本次最多 32 项都使用同一快照，设置变更从下一次 pump 生效。
            val requestedMode = AnalysisSchedulingMode.fromPersistedValue(
                SettingsStore(applicationContext).analysisSchedulingMode.first(),
            )
            val schedulingProfile = AnalysisSchedulingResolver.resolve(
                requestedMode,
                AnalysisDeviceCapabilityDetector.detect(applicationContext),
            )
            val thumbnailConcurrency = schedulingProfile.thumbnailConcurrency
            val mediaSource = MediaSource(applicationContext)
            val admittedScanId = if (laneId == ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID) {
                database.libraryPipelineDao().get()?.takeIf { pipeline ->
                    pipeline.stage in setOf(
                        "INITIAL_THUMBNAILS","INCREMENTAL_THUMBNAILS","REBUILD_THUMBNAILS",
                    )
                }?.activeRunId
            } else null
            if (laneId == ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID && admittedScanId == null) {
                finalize(database,laneId,runToken,container)
                return Result.success()
            }
            if (admittedScanId != null) {
                database.scanRunDao().markEnhancementRunning(
                    admittedScanId,
                    now = System.currentTimeMillis(),
                )
            }
            var batches = 0
            while (batches < MAX_BATCHES_PER_RUN && !isStopped) {
                val batchWork: suspend () -> Boolean = {
                    val batchNow = System.currentTimeMillis()
                    dao.renewRunLease(laneId,runToken,batchNow + RUN_LEASE_MS,batchNow)
                    val leaseToken = ThumbnailTaskDao.processOwnedToken(
                        container.thumbnailProcessEpoch,
                        UUID.randomUUID().toString(),
                    )
                    val tasks = if (laneId == ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID) {
                        dao.claimAutomaticBatch(
                            now = batchNow,
                            limit = BATCH_SIZE,
                            leaseToken = leaseToken,
                            leaseDurationMs = TASK_LEASE_MS,
                            scanId = requireNotNull(admittedScanId),
                        )
                    } else {
                        dao.claimInteractiveBatch(
                            now = batchNow,
                            limit = BATCH_SIZE,
                            leaseToken = leaseToken,
                            leaseDurationMs = TASK_LEASE_MS,
                        )
                    }
                    if (tasks.isEmpty()) false else {
                        try {
                            val taskResults = coroutineScope {
                                val results = ArrayList<TaskProcessingResult>(tasks.size)
                                executionWaves(tasks,thumbnailConcurrency).forEach { wave ->
                                    results += wave.map { task ->
                                        async(Dispatchers.IO) {
                                            val error = processTask(task,leaseToken,database,mediaSource)
                                            if (error == null) {
                                                TaskProcessingResult(processed=1,failed=0)
                                            } else {
                                                val completed = System.currentTimeMillis()
                                                dao.markFailed(
                                                    task.taskId,leaseToken,error.take(MAX_ERROR_LENGTH),
                                                    completed + retryDelayMs(task.attemptCount),MAX_ATTEMPTS,completed,
                                                )
                                                TaskProcessingResult(processed=0,failed=1)
                                            }
                                        }
                                    }.awaitAll()
                                }
                                results
                            }
                            val summary = summarizeBatch(taskResults)
                            processed += summary.processed
                            failed += summary.failed
                            // Exact gate truth cannot count PENDING retry as completed. All claimed
                            // task state writes settle before this single bounded batch-level commit.
                            if (admittedScanId != null && shouldReportBatchProgress(tasks.size)) {
                                container.libraryPipelineCoordinator
                                    .onThumbnailProgressChanged(admittedScanId)
                            }
                        } catch (cancelled: CancellationException) {
                            // coroutineScope does not return until every child has cancelled or
                            // completed, so no decoder can still publish after this lease release.
                            withContext(NonCancellable) { dao.releaseLease(leaseToken,System.currentTimeMillis()) }
                            throw cancelled
                        } catch (error: Throwable) {
                            // Infrastructure failures are not business retries. Once structured
                            // concurrency has settled every child, release unfinished claims now
                            // instead of waiting for the ten-minute task lease to expire.
                            withContext(NonCancellable) { dao.releaseLease(leaseToken,System.currentTimeMillis()) }
                            throw error
                        }
                        true
                    }
                }
                val completed = if (laneId == ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID) {
                    if (EnhancementResourceGate.isCoreRequested) false
                    else EnhancementResourceGate.tryWithAutomaticEnhancement { batchWork() } ?: false
                } else {
                    EnhancementResourceGate.withInteractiveThumbnail { batchWork() }
                }
                if (!completed) break
                batches++
            }
            val final = finalize(database,laneId,runToken,container)
            Log.i(TAG,"lane=$laneId processed=$processed failed=$failed next=${final?.deliveryState}")
            // finalize 写回 PENDING/DELAYED 后仅 kick 一次；业务 retry 不使用 Result.retry。
            if (final?.deliveryState in setOf(
                    ThumbnailLaneWakeEntity.STATE_PENDING,
                    ThumbnailLaneWakeEntity.STATE_DELAYED,
                )
            ) container.thumbnailWakeDispatcher.kick(laneId)
            Result.success()
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                // batch 内层已释放当前 task token；这里 exact finalize lane，覆盖闸门等待或
                // batch 间取消，确保同进程取消不会留下 RUNNING zombie。
                val final = dao.finalizeRun(laneId,runToken,System.currentTimeMillis())
                if (final?.deliveryState != ThumbnailLaneWakeEntity.STATE_QUIESCENT) {
                    container.thumbnailWakeDispatcher.kick(laneId)
                }
            }
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG,"thumbnail pump failed",error)
            val final = withContext(NonCancellable) {
                dao.finalizeRun(laneId,runToken,System.currentTimeMillis())
            }
            if (final?.deliveryState != ThumbnailLaneWakeEntity.STATE_QUIESCENT) {
                container.thumbnailWakeDispatcher.kick(laneId)
            }
            Result.success()
        }
    }

    private suspend fun finalize(
        database: AppDatabase,
        laneId: String,
        runToken: String,
        container: com.renyxin.localalbum.AppContainer,
    ): ThumbnailLaneWakeEntity? {
        ThumbnailCacheTrimmer(database.thumbnailCacheDao()).trim()
        if (laneId == ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID) {
            database.libraryPipelineDao().get()?.activeRunId?.let {
                // Recovery/final-batch progress is committed before the independent gate transition.
                container.libraryPipelineCoordinator.onThumbnailProgressChanged(it)
                container.libraryPipelineCoordinator.onThumbnailQueueChanged(it)
            }
        } else {
            container.libraryPipelineCoordinator.onInteractiveThumbnailQueueChanged()
        }
        return database.thumbnailTaskDao().finalizeRun(laneId,runToken,System.currentTimeMillis())
    }

    /** null 成功；错误摘要不含隐私路径。 */
    private suspend fun processTask(
        task: ThumbnailTaskEntity,
        leaseToken: String,
        database: AppDatabase,
        mediaSource: MediaSource,
    ): String? {
        val identity = runCatching {
            ThumbnailIdentity(
                task.filePath,task.mediaType,task.sourceVersion,task.sizeClass,task.formatVersion,
            )
        }.getOrElse { return "invalid_identity" }
        val media = database.mediaDao().getByFilePathLight(task.filePath)
        if (media == null || media.isTrashed || media.mediaType.name != task.mediaType) {
            database.thumbnailTaskDao().supersedeClaimedTask(task.taskId,leaseToken)
            return null
        }
        val cacheDao = database.thumbnailCacheDao()
        val existing = cacheDao.getReadyExact(
            identity.canonicalPath,identity.mediaType,identity.sourceVersion,
            identity.sizeClass,identity.formatVersion,
        )
        if (existing != null && File(existing.path).isFile && File(existing.path).length() > 0L) {
            val publication = database.thumbnailTaskDao().publishGeneratedThumbnail(
                task,leaseToken,existing.copy(lastAccessAt=System.currentTimeMillis()),System.currentTimeMillis(),
            )
            return if (publication == ThumbnailPublicationResult.LEASE_LOST) null else null
        }
        val publicationNonce = UUID.randomUUID().toString()
        var staged: String? = null
        var installed: String? = null
        val failure = runCatching {
            val generated = mediaSource.generateThumbnailSync(
                File(identity.canonicalPath),MediaType.valueOf(identity.mediaType),identity,
                task.taskId,leaseToken,publicationNonce,
            ) ?: error("decode_or_source_validation_failed")
            staged = generated.stagedPath
            installed = mediaSource.installGeneratedThumbnail(
                generated.stagedPath,identity,task.taskId,leaseToken,publicationNonce,
            ) ?: error("atomic_install_failed")
            val final = File(requireNotNull(installed))
            val timestamp = System.currentTimeMillis()
            val result = database.thumbnailTaskDao().publishGeneratedThumbnail(
                task,leaseToken,
                ThumbnailCacheEntryEntity(
                    filePath=identity.canonicalPath,mediaType=identity.mediaType,
                    sourceVersion=identity.sourceVersion,sizeClass=identity.sizeClass,
                    formatVersion=identity.formatVersion,path=final.absolutePath,
                    byteSize=final.length(),lastAccessAt=timestamp,createdAt=timestamp,
                ),
                timestamp,
            )
            if (result != ThumbnailPublicationResult.PUBLISHED) {
                // final 是本 lease/private nonce 唯一拥有；失败删除不会触碰任何其他发布者。
                final.delete()
            }
        }.onFailure {
            staged?.let { path -> File(path).delete() }
            installed?.let { path ->
                val ready = cacheDao.getReadyExact(
                    identity.canonicalPath,identity.mediaType,identity.sourceVersion,
                    identity.sizeClass,identity.formatVersion,
                )
                if (ready?.path != path) File(path).delete()
            }
        }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        return failure?.let { it.message ?: it::class.java.simpleName }
    }

    internal data class TaskProcessingResult(val processed: Int, val failed: Int)

    companion object {
        private const val TAG = "ThumbnailWorker"
        const val KEY_LANE_ID = "thumbnail_lane_id"
        const val KEY_DISPATCH_REVISION = "thumbnail_dispatch_revision"
        const val KEY_DISPATCH_TOKEN = "thumbnail_dispatch_token"
        internal const val BATCH_SIZE = 4
        internal const val MAX_BATCHES_PER_RUN = 8
        internal const val MAX_TASKS_PER_RUN = BATCH_SIZE * MAX_BATCHES_PER_RUN
        private const val MAX_ATTEMPTS = 4
        private const val TASK_LEASE_MS = 10 * 60_000L
        private const val RUN_LEASE_MS = 12 * 60_000L
        private const val MAX_ERROR_LENGTH = 240

        internal fun retryDelayMs(attemptCount: Int): Long =
            60_000L * (1L shl (attemptCount - 1).coerceIn(0,10))

        internal fun <T> executionWaves(tasks: List<T>, concurrency: Int): List<List<T>> =
            tasks.chunked(concurrency.coerceIn(1,BATCH_SIZE))

        internal fun summarizeBatch(results: List<TaskProcessingResult>): TaskProcessingResult =
            TaskProcessingResult(
                processed=results.sumOf { it.processed },
                failed=results.sumOf { it.failed },
            )

        internal fun shouldReportBatchProgress(taskCount: Int): Boolean = taskCount > 0

        internal fun progressCallbackCount(batchSizes: List<Int>): Int =
            batchSizes.count(::shouldReportBatchProgress)

        /** Compatibility entry points now only kick fixed level-triggered lanes. */
        fun enqueueBackground(context: Context) = dispatcher(context)?.kick(ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID)
        fun appendBackgroundSuccessor(context: Context) = enqueueBackground(context)
        suspend fun replayInteractiveWake(context: Context, @Suppress("UNUSED_PARAMETER") taskDao: com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao): Boolean {
            dispatcher(context)?.kick(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID)
            return true
        }
        fun cancelBackground(context: Context) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(ThumbnailWakeDispatcher.AUTOMATIC_WORK_NAME)
        }
        private fun dispatcher(context: Context): com.renyxin.localalbum.data.repo.ThumbnailWakeDispatcher? =
            (context.applicationContext as? LocalAlbumApplication)?.container?.thumbnailWakeDispatcher
    }
}
