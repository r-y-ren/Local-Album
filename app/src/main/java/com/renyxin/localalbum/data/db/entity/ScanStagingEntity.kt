package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * 单次扫描的有界暂存记录。
 *
 * 两个来源分列保存，消费时按既有规则以 MediaStore 为基础、File API 补充 EXIF；
 * 正式表中的收藏、回收站与 AI 字段在提交阶段从旧记录保留。
 */
@Entity(
    tableName = "scan_staging",
    primaryKeys = ["scanId", "filePath"],
    indices = [
        Index(value = ["scanId", "filePath"]),
    ],
)
data class ScanStagingEntity(
    val scanId: String,
    val filePath: String,
    val sourceMask: Int,
    val mediaStoreJson: String? = null,
    val fileSystemJson: String? = null,
)
