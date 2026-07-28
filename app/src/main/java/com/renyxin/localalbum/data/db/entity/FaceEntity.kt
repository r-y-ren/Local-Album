package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 人脸实体（Phase 3.3 人脸聚类）。
 *
 * 每条记录代表在一张照片中检测到的一个人脸。
 * 一张照片可能包含多个人脸（多条记录共享同一 [filePath]）。
 *
 * @param faceId 自增主键
 * @param filePath 所属媒体文件路径（外键语义，对应 media_items.filePath）
 * @param clusterId 聚类 ID，同一人的所有人脸共享同一 clusterId；未聚类时为 null
 * @param personName 用户命名的人物名称，未命名时为 null
 * @param embedding 人脸特征向量（序列化为逗号分隔的 Float 字符串）
 * @param boxLeft 人脸边界框左边界（归一化 0~1，相对图片宽度）
 * @param boxTop 人脸边界框上边界（归一化 0~1，相对图片高度）
 * @param boxRight 人脸边界框右边界（归一化 0~1）
 * @param boxBottom 人脸边界框下边界（归一化 0~1）
 * @param thumbnailPath 裁剪后的人脸缩略图路径（WebP）
 * @param detectedAtMs 检测时间戳
 */
@Entity(
    tableName = "faces",
    indices = [
        Index(value = ["filePath"]),
        Index(value = ["clusterId"]),
        Index(value = ["personName"]),
    ],
)
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val faceId: Long = 0L,
    val filePath: String,
    val clusterId: String? = null,
    val personName: String? = null,
    val embedding: String,
    val boxLeft: Float,
    val boxTop: Float,
    val boxRight: Float,
    val boxBottom: Float,
    val thumbnailPath: String? = null,
    val detectedAtMs: Long = System.currentTimeMillis(),
)
