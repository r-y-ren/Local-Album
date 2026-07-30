package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.search.EmbeddingCodec
import com.renyxin.localalbum.core.search.SemanticSearcher
import com.renyxin.localalbum.core.search.SemanticVectorSpace
import com.renyxin.localalbum.data.db.entity.SemanticMaintenanceRunEntity

/**
 * 唯一、可恢复的 v25 legacy payload 回填。
 *
 * 回填只补齐可从 payload 自证的 dimension/codec metadata；旧 Provider/模型身份不可可靠推断，
 * 因此记录始终保留在 legacy space，绝不伪装成当前 Provider 空间。每批解析在事务外进行，
 * BLOB 更新与 cursor/计数在同一短事务提交；坏 CSV 计失败后游标继续前进，最终可收敛。
 */
class SemanticMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val db = app.container.database
        val semanticDao = db.semanticDao()
        val embeddingDao = db.embeddingDao()
        val taskType = SemanticMaintenanceRunEntity.TASK_CSV_BACKFILL
        val run = semanticDao.resumableMaintenanceRun(taskType) ?: db.withTransaction {
            semanticDao.resumableMaintenanceRun(taskType) ?: SemanticMaintenanceRunEntity(
                taskType = taskType,
                generation = semanticDao.maxMaintenanceGeneration(taskType) + 1,
                targetSpaceId = SemanticVectorSpace.LEGACY_SPACE_ID,
                totalCount = embeddingDao.getCountInSpace(SemanticVectorSpace.LEGACY_SPACE_ID).toLong(),
            ).also { semanticDao.insertMaintenanceRun(it) }
        }
        var cursor = run.cursorPath
        var batches = 0
        while (batches < MAX_BATCHES_PER_WORK && !isStopped) {
            val page = embeddingDao.getLegacyBackfillPage(SemanticVectorSpace.LEGACY_SPACE_ID, cursor, BATCH_SIZE)
            if (page.isEmpty()) {
                semanticDao.completeMaintenance(taskType, run.generation, System.currentTimeMillis())
                return Result.success()
            }
            val decoded = page.map { row ->
                runCatching {
                    val vector = row.embeddingBlob?.let(EmbeddingCodec::decode)
                        ?: SemanticSearcher.deserializeLegacyCsv(row.embedding)
                    row to vector
                }
            }
            val nextCursor = page.last().filePath
            var converted = 0
            var failed = 0
            var covered = 0
            var lastError: String? = null
            db.withTransaction {
                decoded.forEach { result ->
                    result.fold(
                        onSuccess = { (row, vector) ->
                            covered++
                            if (row.embeddingBlob == null || row.dimension != vector.size) {
                                embeddingDao.updateLegacyPayloadMetadata(
                                    filePath = row.filePath,
                                    legacySpaceId = SemanticVectorSpace.LEGACY_SPACE_ID,
                                    blob = EmbeddingCodec.encode(vector),
                                    providerId = "legacy:${row.source}",
                                    modelId = "legacy-unknown",
                                    dimension = vector.size,
                                    generation = run.generation,
                                    codecId = EmbeddingCodec.CODEC_ID,
                                    formatVersion = EmbeddingCodec.FORMAT_VERSION,
                                )
                                converted++
                            }
                        },
                        onFailure = { error ->
                            failed++
                            lastError = error.message?.take(160) ?: error.javaClass.simpleName
                        },
                    )
                }
                check(semanticDao.advanceMaintenance(taskType, run.generation, nextCursor, page.size, converted, failed, covered, lastError, System.currentTimeMillis()) == 1)
            }
            cursor = nextCursor
            batches++
        }
        return Result.retry()
    }

    companion object {
        internal const val BATCH_SIZE = 64
        internal const val MAX_BATCHES_PER_WORK = 8
        private const val WORK_NAME = "semantic_payload_maintenance"

        fun enqueue(context: Context) = WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SemanticMaintenanceWorker>().build(),
        )
    }
}
