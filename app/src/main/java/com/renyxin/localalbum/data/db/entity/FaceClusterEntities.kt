package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 人物簇的 generation 快照元数据。
 *
 * 人工名称属于稳定 clusterId，而非某个原型；发布新 generation 时由维护服务按 clusterId 复制。
 */
@Entity(
    tableName = "face_cluster_meta",
    primaryKeys = ["generation", "clusterId"],
    indices = [
        Index(value = ["generation", "status", "clusterId"]),
        Index(value = ["clusterId"]),
    ],
)
data class FaceClusterMetaEntity(
    val generation: Long,
    val clusterId: String,
    val personName: String? = null,
    val status: String = STATUS_ACTIVE,
    val providerScope: String,
    val modelScope: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_HIDDEN = "HIDDEN"
    }
}

/**
 * 每簇最多三个持久代表向量。sampleCount 是该代表已吸收的样本数，用于有界在线均值更新。
 */
@Entity(
    tableName = "face_cluster_prototypes",
    primaryKeys = ["generation", "clusterId", "prototypeIndex"],
    indices = [
        Index(value = ["generation", "providerScope", "modelScope", "clusterId"]),
        Index(value = ["generation", "clusterId"]),
    ],
)
data class FaceClusterPrototypeEntity(
    val generation: Long,
    val clusterId: String,
    val prototypeIndex: Int,
    val embedding: String,
    val sampleCount: Long,
    val providerScope: String,
    val modelScope: String,
    val updatedAt: Long = System.currentTimeMillis(),
)
