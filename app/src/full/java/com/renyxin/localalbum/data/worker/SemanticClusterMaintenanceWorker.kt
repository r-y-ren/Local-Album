package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.search.EmbeddingCodec
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.SemanticClusterGenerationEntity
import com.renyxin.localalbum.data.db.entity.SemanticClusterMemberEntity
import com.renyxin.localalbum.data.db.entity.SemanticClusterMetaEntity
import com.renyxin.localalbum.data.db.entity.SemanticIndexMetaEntity
import com.renyxin.localalbum.edition.EditionConfiguration
import java.util.PriorityQueue

/**
 * 当前 active space 的唯一主题簇维护任务。使用稳定 bottom-k 有界样本，生成 centroid 与每簇
 * Top-N 路径后在单个 Room 事务中发布 generation。前台只读已发布结果。
 */
class SemanticClusterMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        if (!EditionConfiguration.features.enableSemanticSearch) return Result.success()
        val db = app.container.database
        val semanticDao = db.semanticDao()
        val embeddingDao = db.embeddingDao()
        val reservoir = PriorityQueue<Sample>(
            compareByDescending<Sample> { it.priority }
                .thenByDescending { it.row.filePath },
        )
        var snapshot: Snapshot? = null
        var cursor: String? = null

        while (!isStopped) {
            if (
                app.container.albumRepository.isCoreScanActive() ||
                EnhancementResourceGate.isAutomaticWorkBlocked
            ) {
                return Result.retry()
            }
            val pageResult = EnhancementResourceGate.tryWithAutomaticEnhancement {
                val activeIndex = semanticDao.getActiveIndexMeta()
                    ?: return@tryWithAutomaticEnhancement PageResult.NoIndex
                val currentSnapshot = snapshot ?: Snapshot(
                    index = activeIndex,
                    exclusiveEpoch = EnhancementResourceGate.currentExclusiveEpoch,
                ).also { snapshot = it }
                if (
                    currentSnapshot.exclusiveEpoch != EnhancementResourceGate.currentExclusiveEpoch ||
                    currentSnapshot.index != activeIndex
                ) {
                    return@tryWithAutomaticEnhancement PageResult.Stale
                }
                val page = embeddingDao.getPagedAfterInSpace(
                    activeIndex.spaceId,
                    cursor,
                    PAGE_SIZE,
                )
                page.forEach { row -> retainSample(reservoir, row) }
                cursor = page.lastOrNull()?.filePath
                if (page.size < PAGE_SIZE) PageResult.Complete else PageResult.Continue
            } ?: return Result.retry()

            when (pageResult) {
                PageResult.NoIndex -> return Result.success()
                PageResult.Stale -> return Result.retry()
                PageResult.Continue -> continue
                PageResult.Complete -> break
            }
        }
        if (isStopped) return Result.retry()
        val stableSnapshot = snapshot ?: return Result.success()
        val published = EnhancementResourceGate.tryWithAutomaticEnhancement {
            val activeIndex = semanticDao.getActiveIndexMeta()
            if (
                stableSnapshot.exclusiveEpoch != EnhancementResourceGate.currentExclusiveEpoch ||
                stableSnapshot.index != activeIndex
            ) {
                return@tryWithAutomaticEnhancement false
            }
            buildAndPublish(stableSnapshot.index, reservoir.toList(), db)
        } ?: return Result.retry()
        return if (published) Result.success() else Result.retry()
    }

    private fun retainSample(
        reservoir: PriorityQueue<Sample>,
        row: MediaEmbedding,
    ) {
        val sample = Sample(stablePriority(row.filePath), row)
        val largestSample = reservoir.peek()
        if (reservoir.size < SAMPLE_LIMIT) {
            reservoir += sample
        } else if (
            largestSample != null &&
            compareValuesBy(
                sample,
                largestSample,
                Sample::priority,
                { it.row.filePath },
            ) < 0
        ) {
            reservoir.poll()
            reservoir += sample
        }
    }

    private suspend fun buildAndPublish(
        index: SemanticIndexMetaEntity,
        samples: List<Sample>,
        db: com.renyxin.localalbum.data.db.AppDatabase,
    ): Boolean {
        val semanticDao = db.semanticDao()
        val vectors = samples.mapNotNull { sample ->
            runCatching {
                sample.row.filePath to
                    EmbeddingCodec.decode(requireNotNull(sample.row.embeddingBlob))
            }.getOrNull()
        }.filter { it.second.size == index.dimension }.sortedBy { it.first }
        val generation = semanticDao.maxClusterGeneration(index.spaceId) + 1
        val now = System.currentTimeMillis()
        val grouped = vectors
            .groupBy { dominantBucket(it.second) }
            .toSortedMap()
            .entries
            .take(MAX_CLUSTERS)
        val clusters = mutableListOf<SemanticClusterMetaEntity>()
        val members = mutableListOf<SemanticClusterMemberEntity>()
        grouped.forEachIndexed { clusterIndex, entry ->
            if (entry.value.size < MIN_CLUSTER_SIZE) return@forEachIndexed
            val clusterId = "cluster-$clusterIndex-${entry.key}"
            val centroid = FloatArray(index.dimension)
            entry.value.forEach { (_, vector) ->
                vector.indices.forEach { centroid[it] += vector[it] }
            }
            centroid.indices.forEach { centroid[it] /= entry.value.size.toFloat() }
            val topPaths = entry.value
                .asSequence()
                .map { it.first }
                .sorted()
                .take(TOP_N)
                .toList()
            clusters += SemanticClusterMetaEntity(
                spaceId = index.spaceId,
                generation = generation,
                clusterId = clusterId,
                status = "READY",
                centroidBlob = EmbeddingCodec.encode(centroid),
                memberCount = entry.value.size,
                score = entry.value.size.toDouble() / vectors.size.coerceAtLeast(1),
                title = "语义主题 ${clusterIndex + 1}",
                createdAt = now,
            )
            topPaths.forEachIndexed { rank, path ->
                members += SemanticClusterMemberEntity(
                    index.spaceId,
                    generation,
                    clusterId,
                    rank,
                    path,
                )
            }
        }
        val generationRow = SemanticClusterGenerationEntity(
            spaceId = index.spaceId,
            generation = generation,
            status = "BUILDING",
            isActive = false,
            sampleLimit = SAMPLE_LIMIT,
            sampledCount = vectors.size,
            clusterCount = 0,
            createdAt = now,
            completedAt = 0,
        )
        return semanticDao.replaceAndPublishClusters(generationRow, clusters, members, now)
    }

    private data class Snapshot(
        val index: SemanticIndexMetaEntity,
        val exclusiveEpoch: Long,
    )

    private enum class PageResult {
        NoIndex,
        Stale,
        Continue,
        Complete,
    }

    private data class Sample(val priority: Long, val row: MediaEmbedding)

    private fun dominantBucket(vector: FloatArray): Int = vector.indices.maxByOrNull { vector[it] } ?: 0

    private fun stablePriority(path: String): Long {
        var hash = -0x340d631b7bdddcdbL
        path.toByteArray(Charsets.UTF_8).forEach { byte -> hash = (hash xor (byte.toLong() and 0xffL)) * 0x100000001b3L }
        return hash xor Long.MIN_VALUE
    }

    companion object {
        const val SAMPLE_LIMIT = 2_048
        const val PAGE_SIZE = 256
        const val TOP_N = 20
        const val MAX_CLUSTERS = 8
        const val MIN_CLUSTER_SIZE = 5
        private const val WORK_NAME = "semantic_cluster_maintenance"

        fun enqueue(context: Context) = WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SemanticClusterMaintenanceWorker>().build(),
        )
    }
}
