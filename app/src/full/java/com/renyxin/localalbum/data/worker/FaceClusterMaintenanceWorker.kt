package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.analysis.FaceClusterer
import com.renyxin.localalbum.core.analysis.FacePrototypePolicy
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.pipeline.stages.BuiltinFaceStage
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.entity.FaceClusterMetaEntity
import com.renyxin.localalbum.data.db.entity.FaceClusterPrototypeEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity
import com.renyxin.localalbum.edition.EditionConfiguration

/**
 * 唯一、可恢复的人物原型维护任务。
 *
 * 本任务不是精确全局 DBSCAN：它按 faceId keyset 分批读取已有非 pending clusterId，并在各稳定
 * clusterId 内用最多三个在线代表压缩样本。内存上界为一个 128-face 批次和单簇三个原型；优点是
 * 可恢复且不会常驻全库向量，差别是它不会利用跨批密度可达性合并/拆分人物，也不会改写现有
 * faces.clusterId 或 media_items.faceClusterId。后续如需重聚类，应另行实现有界候选归并，不得复用
 * 常规“强制重分析”入口。
 */
class FaceClusterMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        if (!EditionConfiguration.features.allowFaceClusterMaintenance) return Result.success()
        val db = app.container.database
        var batches = 0

        while (batches < MAX_BATCHES_PER_WORK && !isStopped) {
            if (
                app.container.albumRepository.isCoreScanActive() ||
                EnhancementResourceGate.isAutomaticWorkBlocked
            ) {
                return Result.retry()
            }
            val outcome = EnhancementResourceGate.tryWithAutomaticEnhancement {
                runBatch(app, db)
            } ?: return Result.retry()
            when (outcome) {
                BatchResult.DONE -> return Result.success()
                BatchResult.CONTINUE -> batches++
                BatchResult.RETRY -> return Result.retry()
            }
        }
        return Result.retry()
    }

    private suspend fun runBatch(
        app: LocalAlbumApplication,
        db: AppDatabase,
    ): BatchResult {
        val dao = db.faceClusterDao()
        val faceDao = db.faceDao()
        val run = runCatching { dao.resumableRun() ?: createRun(db) }
            .getOrElse { return BatchResult.RETRY }
        val generation = run.generation
        return try {
            if (run.status == MaintenanceRunEntity.STATUS_FINALIZING) {
                return if (dao.publish(generation)) BatchResult.DONE else BatchResult.RETRY
            }
            val cursor = run.cursorPath?.toLongOrNull() ?: 0L
            val faces = faceDao.getClusteredAfter(cursor, BATCH_SIZE)
            if (faces.isEmpty()) {
                dao.markFinalizing(generation, System.currentTimeMillis())
                return if (dao.publish(generation)) BatchResult.DONE else BatchResult.RETRY
            }
            val scope = resolveScope(app)
            var eligible = 0
            var failed = 0
            db.withTransaction {
                faces.forEach { face ->
                    val clusterId = face.clusterId
                    val sample = runCatching { codec.parseEmbedding(face.embedding) }.getOrNull()
                    if (clusterId == null || sample == null) {
                        failed++
                        return@forEach
                    }
                    val entities = dao.prototypesForCluster(
                        generation,
                        clusterId,
                        FacePrototypePolicy.MAX_PROTOTYPES_PER_CLUSTER,
                    )
                    val current = entities.mapNotNull { entity ->
                        runCatching {
                            FacePrototypePolicy.Prototype(
                                entity.prototypeIndex,
                                codec.parseEmbedding(entity.embedding),
                                entity.sampleCount,
                            )
                        }.getOrNull()
                    }
                    val update = FacePrototypePolicy.update(sample, current)
                    update.updatedIndex?.let { changed ->
                        val prototype = requireNotNull(
                            update.prototypes.firstOrNull { it.index == changed },
                        )
                        dao.upsertPrototype(
                            FaceClusterPrototypeEntity(
                                generation = generation,
                                clusterId = clusterId,
                                prototypeIndex = prototype.index,
                                embedding = codec.serializeEmbedding(prototype.embedding),
                                sampleCount = prototype.sampleCount,
                                providerScope = scope.first,
                                modelScope = scope.second,
                            ),
                        )
                    }
                    val oldMeta = dao.getMeta(generation, clusterId)
                    if (oldMeta == null) {
                        dao.upsertMeta(
                            FaceClusterMetaEntity(
                                generation = generation,
                                clusterId = clusterId,
                                personName = dao.latestPersonName(clusterId) ?: face.personName,
                                providerScope = scope.first,
                                modelScope = scope.second,
                            ),
                        )
                    }
                    eligible++
                }
                val next = faces.last().faceId
                check(
                    dao.advanceCursor(
                        generation = generation,
                        cursorFaceId = next.toString(),
                        scanned = faces.size,
                        eligible = eligible,
                        failed = failed,
                        now = System.currentTimeMillis(),
                    ) == 1,
                ) { "人物原型维护状态已改变" }
            }
            BatchResult.CONTINUE
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            dao.markFailed(
                generation,
                error.message?.take(240) ?: error.javaClass.simpleName,
                System.currentTimeMillis(),
            )
            BatchResult.RETRY
        }
    }

    private enum class BatchResult {
        DONE,
        CONTINUE,
        RETRY,
    }

    private suspend fun createRun(db: AppDatabase): MaintenanceRunEntity = db.withTransaction {
        val dao = db.faceClusterDao()
        dao.resumableRun() ?: MaintenanceRunEntity(
            taskType = MaintenanceRunEntity.TASK_FACE_PROTOTYPES,
            generation = dao.maxGeneration(MaintenanceRunEntity.TASK_FACE_PROTOTYPES) + 1,
        ).also { dao.insertRun(it) }
    }

    private fun resolveScope(app: LocalAlbumApplication): Pair<String, String> {
        val provider = app.container.capabilityRegistry.getActiveProvider<FaceProvider>("face")
        return if (provider == null) {
            BuiltinFaceStage.BUILTIN_PROVIDER_SCOPE to BuiltinFaceStage.BUILTIN_MODEL_SCOPE
        } else {
            provider.providerId to "dim:${provider.embeddingDim}"
        }
    }

    companion object {
        internal const val BATCH_SIZE = 128
        internal const val MAX_BATCHES_PER_WORK = 8
        private const val WORK_NAME = "face_cluster_prototype_maintenance"
        private val codec = FaceClusterer()

        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FaceClusterMaintenanceWorker>().build(),
            )
        }
    }
}
