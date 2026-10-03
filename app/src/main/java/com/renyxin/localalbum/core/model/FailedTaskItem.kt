package com.renyxin.localalbum.core.model

/**
 * 失败任务页的展示项：分析/缩略图/交接三条车道各自的 FAILED 行，
 * 由数据层装配；同一文件可能同时占多条车道（错误信息带车道来源）。
 */
data class FailedTaskItem(
    val kind: FailedTaskKind,
    val filePath: String,
    val fileName: String,
    val parentPath: String,
    val mediaType: String,
    val attemptCount: Int,
    val lastError: String?,
    val updatedAt: Long,
)

enum class FailedTaskKind(val label: String) {
    ANALYSIS("分析"),
    THUMBNAIL("缩略图"),
    HANDOFF("交接"),
}

/** 三条车道失败清单 SQL 投影的公共列；具体 DAO 投影实现本接口后共用一套装配。 */
interface FailedTaskRow {
    val filePath: String
    val fileName: String
    val parentPath: String
    val mediaType: String
    val attemptCount: Int
    val lastError: String?
    val updatedAt: Long
}

fun FailedTaskRow.toItem(kind: FailedTaskKind) = FailedTaskItem(
    kind = kind,
    filePath = filePath,
    fileName = fileName,
    parentPath = parentPath,
    mediaType = mediaType,
    attemptCount = attemptCount,
    lastError = lastError,
    updatedAt = updatedAt,
)
