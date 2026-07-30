package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/** 一个可查询向量空间及其 Exact/未来 ANN 索引生命周期。 */
@Entity(
    tableName = "semantic_index_meta",
    primaryKeys = ["spaceId"],
    indices = [
        Index(value = ["isActive"]),
        Index(value = ["providerId", "modelId", "modelVersion", "dimension"]),
    ],
)
data class SemanticIndexMetaEntity(
    val spaceId: String,
    val providerId: String,
    val modelId: String,
    val modelVersion: Int,
    val dimension: Int,
    val codecId: String,
    val formatVersion: Int,
    val activeGeneration: Long,
    val isActive: Boolean,
    val status: String,
    val vectorCount: Long,
    val indexedCount: Long,
    val deletedCount: Long,
    val indexKind: String,
    val builtAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val STATUS_NOT_BUILT = "NOT_BUILT"
        const val STATUS_READY = "READY"
        const val STATUS_SPACE_MISMATCH = "SPACE_MISMATCH"
        const val INDEX_EXACT = "EXACT"
        const val INDEX_ANN = "ANN"
    }
}

/** CSV→BLOB/metadata 回填游标；坏记录计入失败后游标仍前进，因此任务可收敛。 */
@Entity(
    tableName = "semantic_maintenance_runs",
    primaryKeys = ["taskType", "generation"],
    indices = [Index(value = ["taskType", "status"]), Index(value = ["taskType", "isActive"])],
)
data class SemanticMaintenanceRunEntity(
    val taskType: String,
    val generation: Long,
    val targetSpaceId: String,
    val status: String = STATUS_RUNNING,
    val cursorPath: String? = null,
    val scannedCount: Long = 0,
    val convertedCount: Long = 0,
    val failedCount: Long = 0,
    val totalCount: Long = 0,
    val coverageCount: Long = 0,
    val lastError: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0,
    val isActive: Boolean = false,
) {
    companion object {
        const val TASK_CSV_BACKFILL = "SEMANTIC_CSV_BACKFILL"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_COMPLETED = "COMPLETED"
    }
}

/** 主题簇 generation 元数据；只有 COMPLETED + isActive 才允许前台读取。 */
@Entity(
    tableName = "semantic_cluster_meta",
    primaryKeys = ["spaceId", "generation", "clusterId"],
    indices = [Index(value = ["spaceId", "generation", "status"])],
)
data class SemanticClusterMetaEntity(
    val spaceId: String,
    val generation: Long,
    val clusterId: String,
    val status: String,
    val centroidBlob: ByteArray,
    val memberCount: Int,
    val score: Double,
    val title: String,
    val createdAt: Long,
)

@Entity(
    tableName = "semantic_cluster_members",
    primaryKeys = ["spaceId", "generation", "clusterId", "rank"],
    indices = [Index(value = ["spaceId", "generation", "clusterId"]), Index(value = ["filePath"])],
)
data class SemanticClusterMemberEntity(
    val spaceId: String,
    val generation: Long,
    val clusterId: String,
    val rank: Int,
    val filePath: String,
)

/** generation 发布指针，与簇明细分离以支持单事务原子切换。 */
@Entity(tableName = "semantic_cluster_generations", primaryKeys = ["spaceId", "generation"], indices = [Index(value = ["spaceId", "isActive"])])
data class SemanticClusterGenerationEntity(
    val spaceId: String,
    val generation: Long,
    val status: String,
    val isActive: Boolean,
    val sampleLimit: Int,
    val sampledCount: Int,
    val clusterCount: Int,
    val createdAt: Long,
    val completedAt: Long,
)
