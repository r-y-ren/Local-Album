package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * N6 通用备份 staging。payload 已完成逐字段类型/Base64/枚举校验后才可写入。
 * 最终切换事务通过 SQLite JSON 函数从本 generation 复制到正式表；解析阶段不触碰生产表。
 */
@Entity(
    tableName = "backup_import_staging",
    primaryKeys = ["generation", "entryName", "lineNumber"],
    indices = [Index(value = ["generation", "entryName", "lineNumber"])],
)
data class BackupImportStagingEntity(
    val generation: String,
    val entryName: String,
    val lineNumber: Long,
    /** 可安全用于定位的短哈希或非路径业务主键，不存完整隐私路径。 */
    val stableKey: String,
    val payload: String,
)
