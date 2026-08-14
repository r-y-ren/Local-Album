package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.analysis.BoundedDuplicateBatchProcessor
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.analysis.ExactFileHasher
import com.renyxin.localalbum.data.db.entity.DuplicateHashStagingEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity
import java.io.File

/**
 * 唯一、可恢复的完全重复维护 Worker。
 *
 * 语义严格限定为字节级完整 SHA-256 相同；不使用 perceptualHash，也不声称感知相似聚类完备。
 * fileSize 仅作 SQL 预筛，最终分组始终以完整文件 SHA-256 为准。
 */
class DuplicateMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val db = app.container.database
        val mediaDao = db.mediaDao()
        val dao = db.duplicateMaintenanceDao()
        val taskType = MaintenanceRunEntity.TASK_DUPLICATE_EXACT

        return try {
            var batches = 0
            while (batches < MAX_BATCHES_PER_WORK && !isStopped) {
                if (
                    app.container.albumRepository.isCoreScanActive() ||
                    EnhancementResourceGate.isAutomaticWorkBlocked
                ) {
                    return Result.retry()
                }
                val completed = EnhancementResourceGate.tryWithAutomaticEnhancement {
                    // Run recovery/creation is inside the same lane as hashing and state commits;
                    // backup maintenance can never replace these tables between lifecycle steps.
                    val run = dao.resumableRun(taskType) ?: createRun(db, taskType)
                    val generation = run.generation
                    try {
                        // 若进程在结果物化前后中断，FINALIZING 可幂等重建本 generation 并原子发布。
                        if (run.status == MaintenanceRunEntity.STATUS_FINALIZING) {
                            val published = dao.finalizeAndPublish(
                                taskType,
                                generation,
                                System.currentTimeMillis(),
                            )
                            return@tryWithAutomaticEnhancement if (published) {
                                BatchResult.DONE
                            } else {
                                BatchResult.RETRY
                            }
                        }

                        val candidates = mediaDao.getDuplicateCandidatesAfter(
                            run.cursorPath,
                            BATCH_SIZE,
                        )
                        if (candidates.isEmpty()) {
                            dao.markFinalizing(taskType, generation, System.currentTimeMillis())
                            val published = dao.finalizeAndPublish(
                                taskType,
                                generation,
                                System.currentTimeMillis(),
                            )
                            return@tryWithAutomaticEnhancement if (published) {
                                BatchResult.DONE
                            } else {
                                BatchResult.RETRY
                            }
                        }
                        val output = BoundedDuplicateBatchProcessor.process(
                            input = candidates.map {
                                BoundedDuplicateBatchProcessor.Input(it.filePath, it.fileSize)
                            },
                            maxBatchSize = BATCH_SIZE,
                            hash = { ExactFileHasher.sha256(File(it)) },
                        )
                        val byPath = candidates.associateBy { it.filePath }
                        val hashes = output.map { hashed ->
                            val source = requireNotNull(byPath[hashed.path])
                            DuplicateHashStagingEntity(
                                generation = generation,
                                filePath = hashed.path,
                                fileSize = hashed.fileSize,
                                exactHash = hashed.exactHash,
                                qualityScore = source.qualityScore,
                                modifiedAtMs = source.modifiedAtMs,
                            )
                        }
                        val nextCursor = candidates.last().filePath
                        db.withTransaction {
                            if (hashes.isNotEmpty()) dao.insertHashes(hashes)
                            check(
                                dao.advanceCursor(
                                    taskType = taskType,
                                    generation = generation,
                                    cursorPath = nextCursor,
                                    scanned = candidates.size,
                                    eligible = hashes.size,
                                    failed = candidates.size - hashes.size,
                                    now = System.currentTimeMillis(),
                                ) == 1,
                            ) { "维护任务状态已改变" }
                        }
                        BatchResult.CONTINUE
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        dao.markFailed(
                            taskType,
                            generation,
                            error.message?.take(240) ?: error.javaClass.simpleName,
                            System.currentTimeMillis(),
                        )
                        throw error
                    }
                }
                when (completed) {
                    BatchResult.DONE -> return Result.success()
                    BatchResult.CONTINUE -> batches++
                    BatchResult.RETRY, null -> return Result.retry()
                }
            }
            Result.retry()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private enum class BatchResult { CONTINUE, DONE, RETRY }

    private suspend fun createRun(
        db: com.renyxin.localalbum.data.db.AppDatabase,
        taskType: String,
    ): MaintenanceRunEntity = db.withTransaction {
        val dao = db.duplicateMaintenanceDao()
        dao.resumableRun(taskType) ?: MaintenanceRunEntity(
            taskType = taskType,
            generation = dao.maxGeneration(taskType) + 1,
        ).also { dao.insertRun(it) }
    }

    companion object {
        internal const val BATCH_SIZE = 64
        internal const val MAX_BATCHES_PER_WORK = 8
        private const val WORK_NAME = "duplicate_exact_maintenance"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DuplicateMaintenanceWorker>().build(),
            )
        }
    }
}
