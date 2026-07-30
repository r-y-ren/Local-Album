package com.renyxin.localalbum.core.analysis

import com.renyxin.localalbum.data.db.dao.FaceClusterDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceClusterPrototypeEntity
import com.renyxin.localalbum.data.db.entity.FaceEntity
import java.util.UUID

/**
 * 常规扫描使用的有界人物归类器。
 *
 * 只读取 active generation 原型，不再从 faces 动态聚合代表。每个当前人脸批次按原型 keyset
 * 流式扫过全部 active 原型页，只保留每张脸的当前最近候选：内存上界为 500 张脸解析向量、
 * 512 个原型及 500 个最近候选，与历史 faces 数量和人物簇数量无关。
 */
class IncrementalFaceClusterAssigner(
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao,
    private val faceClusterDao: FaceClusterDao,
    private val providerScope: String,
    private val modelScope: String,
    private val faceClusterer: FaceClusterer = FaceClusterer(),
    private val matchEps: Float = FacePrototypePolicy.MATCH_EPS,
) {
    data class AssignmentResult(
        val matchedClusterCount: Int,
        val pendingFaceCount: Int,
    )

    private data class ParsedFace(val face: FaceEntity, val embedding: FloatArray)
    private data class Candidate(val clusterId: String, val distance: Float)

    suspend fun assign(filePaths: List<String>): AssignmentResult {
        if (filePaths.isEmpty()) return AssignmentResult(0, 0)
        val activeGeneration = faceClusterDao.activeRun()?.generation
        val matchedClusterIds = linkedSetOf<String>()
        var pendingCount = 0

        filePaths.chunked(DATABASE_BATCH_SIZE).forEach { pathBatch ->
            val parsed = faceDao.getByFilePaths(pathBatch).mapNotNull { face ->
                runCatching { ParsedFace(face, faceClusterer.parseEmbedding(face.embedding)) }.getOrNull()
            }
            // 人物页的数据模型是一张照片对应一个人物簇。多人照片只使用面积最大的人脸作为
            // 该照片的代表，随后把该文件的全部人脸统一到同一簇，避免同一图片出现在多个分类。
            val representatives = parsed.groupBy { it.face.filePath }.values.mapNotNull { faces ->
                faces.maxByOrNull(::faceArea)
            }

            // 首次扫描尚无 active 原型。若把全部结果写成隐藏的 pending，人物页会在扫描中和
            // 扫描后始终为空；先在当前有界批次内建立可见初始簇，后续维护任务再合并原型。
            if (activeGeneration == null) {
                bootstrapVisibleClusters(representatives, matchedClusterIds)
                return@forEach
            }

            val candidates = findCandidates(representatives)
            val unmatched = mutableListOf<ParsedFace>()
            representatives.forEach { item ->
                val candidate = candidates[item.face.faceId]
                if (candidate == null || candidate.distance > matchEps) {
                    unmatched += item
                } else {
                    assignFileToCluster(item, candidate.clusterId)
                    updateActivePrototype(activeGeneration, candidate.clusterId, item.embedding)
                    matchedClusterIds += candidate.clusterId
                }
            }

            // 旧实现为每个未命中 active 原型的人脸直接生成 UUID，导致一次全量重分析后几乎
            // 每张照片都成为独立人物。未命中样本必须先在当前有界批次内聚类，再把新簇原型
            // 写入 active generation，使后续批次也能命中同一人物。
            pendingCount += assignNewClusters(unmatched, activeGeneration, matchedClusterIds)
        }
        return AssignmentResult(matchedClusterIds.size, pendingCount)
    }

    private suspend fun bootstrapVisibleClusters(
        parsed: List<ParsedFace>,
        matchedClusterIds: MutableSet<String>,
    ) {
        if (parsed.isEmpty()) return
        val result = faceClusterer.cluster(parsed.map { FaceClusterer.FaceSample(it.face.faceId, it.embedding) })
        val clusterByFace = buildMap<Long, String> {
            result.clusters.forEach { cluster ->
                cluster.sampleIds.forEach { put(it, cluster.clusterId) }
            }
            // 单张人脸同样应在人物页可见；之后显式维护可将这些初始簇合并。
            result.noise.forEach { put(it, UUID.randomUUID().toString()) }
        }
        parsed.forEach { item ->
            val clusterId = clusterByFace[item.face.faceId] ?: return@forEach
            assignFileToCluster(item, clusterId)
            matchedClusterIds += clusterId
        }
    }

    private suspend fun assignNewClusters(
        unmatched: List<ParsedFace>,
        activeGeneration: Long,
        matchedClusterIds: MutableSet<String>,
    ): Int {
        if (unmatched.isEmpty()) return 0
        val result = faceClusterer.cluster(
            unmatched.map { FaceClusterer.FaceSample(it.face.faceId, it.embedding) },
        )
        val clusterByFace = buildMap<Long, String> {
            result.clusters.forEach { cluster ->
                cluster.sampleIds.forEach { put(it, cluster.clusterId) }
            }
            result.noise.forEach { put(it, UUID.randomUUID().toString()) }
        }
        unmatched.forEach { item ->
            val clusterId = clusterByFace[item.face.faceId] ?: return@forEach
            assignFileToCluster(item, clusterId)
            updateActivePrototype(activeGeneration, clusterId, item.embedding)
            matchedClusterIds += clusterId
        }
        return result.noiseCount
    }

    private suspend fun assignFileToCluster(item: ParsedFace, clusterId: String) {
        faceDao.updateClusterIdsByFilePaths(listOf(item.face.filePath), clusterId)
        mediaDao.setFaceClusterId(item.face.filePath, clusterId)
    }

    private fun faceArea(item: ParsedFace): Float =
        (item.face.boxRight - item.face.boxLeft).coerceAtLeast(0f) *
            (item.face.boxBottom - item.face.boxTop).coerceAtLeast(0f)

    private suspend fun findCandidates(faces: List<ParsedFace>): Map<Long, Candidate> {
        if (faces.isEmpty()) return emptyMap()
        val nearest = mutableMapOf<Long, Candidate>()
        var afterClusterId = ""
        var afterPrototypeIndex = -1
        while (true) {
            val page = faceClusterDao.activePrototypesAfter(
                providerScope = providerScope,
                modelScope = modelScope,
                afterClusterId = afterClusterId,
                afterPrototypeIndex = afterPrototypeIndex,
                limit = PROTOTYPE_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            val parsedPrototypes = page.mapNotNull { entity ->
                runCatching { entity.clusterId to faceClusterer.parseEmbedding(entity.embedding) }.getOrNull()
            }
            faces.forEach { face ->
                parsedPrototypes.forEach { (clusterId, prototype) ->
                    val distance = FacePrototypePolicy.cosineDistance(face.embedding, prototype)
                    val old = nearest[face.face.faceId]
                    if (old == null || distance < old.distance) {
                        nearest[face.face.faceId] = Candidate(clusterId, distance)
                    }
                }
            }
            val last = page.last()
            afterClusterId = last.clusterId
            afterPrototypeIndex = last.prototypeIndex
            if (page.size < PROTOTYPE_PAGE_SIZE) break
        }
        return nearest
    }

    private suspend fun updateActivePrototype(generation: Long, clusterId: String, sample: FloatArray) {
        val entities = faceClusterDao.prototypesForCluster(
            generation,
            clusterId,
            FacePrototypePolicy.MAX_PROTOTYPES_PER_CLUSTER,
        )
        val current = entities.mapNotNull { entity ->
            runCatching {
                FacePrototypePolicy.Prototype(
                    entity.prototypeIndex,
                    faceClusterer.parseEmbedding(entity.embedding),
                    entity.sampleCount,
                )
            }.getOrNull()
        }
        val update = FacePrototypePolicy.update(sample, current)
        val changed = update.updatedIndex ?: return
        val prototype = requireNotNull(update.prototypes.firstOrNull { it.index == changed })
        faceClusterDao.upsertPrototype(
            FaceClusterPrototypeEntity(
                generation = generation,
                clusterId = clusterId,
                prototypeIndex = prototype.index,
                embedding = faceClusterer.serializeEmbedding(prototype.embedding),
                sampleCount = prototype.sampleCount,
                providerScope = providerScope,
                modelScope = modelScope,
            ),
        )
    }

    companion object {
        private const val DATABASE_BATCH_SIZE = 500
        private const val PROTOTYPE_PAGE_SIZE = 512
    }
}
