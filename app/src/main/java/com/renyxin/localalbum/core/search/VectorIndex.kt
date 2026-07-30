package com.renyxin.localalbum.core.search

import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import java.util.PriorityQueue

/**
 * 向量检索的稳定抽象。
 *
 * 当前默认实现保持精确 keyset 分页扫描，内存仅与单页及 Top-K 成正比；后续可在不修改
 * 搜索调用方的前提下接入 HNSW 等近似索引。索引实现必须自行保证增量写入与删除的可见性。
 */
interface VectorIndex {
    data class QueryResult(
        val filePath: String,
        val similarity: Float,
    )

    data class SearchResult(
        val results: List<QueryResult>,
        val scannedCount: Int,
        val spaceId: String,
    )

    /** 所有查询必须显式携带向量空间，禁止无 scope 的精确或 ANN 查询。 */
    suspend fun search(
        spaceId: String,
        queryVector: FloatArray,
        topK: Int,
        minSimilarity: Float,
        excludedPath: String? = null,
    ): SearchResult
}

/**
 * Room 之上的精确分页向量索引基线。
 *
 * 与 ANN 实现不同，它不维护额外文件，也不会出现索引与数据库提交不同步的问题；代价是查询
 * 时间随嵌入数线性增长。损坏向量或维度不匹配的记录被隔离跳过，不中断整次搜索。
 */
class ExactPagedVectorIndex(
    private val embeddingDao: EmbeddingDao,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val decode: (MediaEmbedding) -> FloatArray,
    private val cosineSimilarity: (FloatArray, FloatArray) -> Float,
    private val onInvalidEmbedding: (String, Throwable?) -> Unit = { _, _ -> },
) : VectorIndex {
    companion object {
        const val DEFAULT_PAGE_SIZE = 500
    }

    override suspend fun search(
        spaceId: String,
        queryVector: FloatArray,
        topK: Int,
        minSimilarity: Float,
        excludedPath: String?,
    ): VectorIndex.SearchResult {
        require(spaceId.isNotBlank()) { "向量查询必须指定 spaceId" }
        val heap = PriorityQueue<VectorIndex.QueryResult>(compareBy { it.similarity })
        val safeTopK = topK.coerceAtLeast(1)
        var afterPath: String? = null
        var scannedCount = 0

        while (true) {
            val page = embeddingDao.getPagedAfterInSpace(spaceId, afterPath, pageSize)
            if (page.isEmpty()) break
            scannedCount += page.size
            for (embedding in page) {
                if (embedding.filePath == excludedPath) continue
                val vector = try {
                    decode(embedding)
                } catch (error: Throwable) {
                    onInvalidEmbedding(embedding.filePath, error)
                    continue
                }
                if (vector.size != queryVector.size) {
                    onInvalidEmbedding(embedding.filePath, null)
                    continue
                }
                val candidate = VectorIndex.QueryResult(
                    embedding.filePath,
                    cosineSimilarity(queryVector, vector),
                )
                if (candidate.similarity < minSimilarity) continue
                if (heap.size < safeTopK) {
                    heap += candidate
                } else {
                    val lowest = heap.peek()
                    if (lowest != null && candidate.similarity > lowest.similarity) {
                        heap.poll()
                        heap += candidate
                    }
                }
            }
            if (page.size < pageSize) break
            afterPath = page.last().filePath
        }
        return VectorIndex.SearchResult(heap.toList().sortedByDescending { it.similarity }, scannedCount, spaceId)
    }
}
