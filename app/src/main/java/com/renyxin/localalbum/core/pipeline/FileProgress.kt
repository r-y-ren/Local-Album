package com.renyxin.localalbum.core.pipeline

/**
 * 单文件处理状态枚举。
 *
 * 描述分析管道中单个文件在某阶段内的处理状态，供 UI 层按文件粒度渲染进度。
 */
enum class FileProcessingStatus {
    /** 待处理：尚未开始 */
    PENDING,

    /** 处理中：当前正在执行分析 */
    PROCESSING,

    /** 已完成：分析成功 */
    COMPLETED,

    /** 失败：分析出错 */
    FAILED,
}

/**
 * 单文件进度快照。
 *
 * @property filePath 媒体文件绝对路径
 * @property status 当前处理状态
 * @property errorMessage 若 [status] 为 [FileProcessingStatus.FAILED] 时的错误信息
 */
data class FileProgress(
    val filePath: String,
    val status: FileProcessingStatus,
    val errorMessage: String? = null,
)

/**
 * 某阶段内所有文件的整体进度快照。
 *
 * 由 [ProgressManager] 在每次文件状态变更时重新计算并推送。
 *
 * @property stageId 阶段唯一标识（如 "builtin:face"）
 * @property displayName 阶段 UI 显示名称（如 "人脸识别"）
 * @property completedCount 已完成文件数
 * @property totalCount 总文件数
 * @property files 所有文件的进度列表（保持与 pipeline 输入顺序一致）
 */
data class StageFileProgress(
    val stageId: String,
    val displayName: String,
    val completedCount: Int,
    val totalCount: Int,
    val files: List<FileProgress> = emptyList(),
) {
    /** 该阶段的完成百分比 (0f ~ 1f) */
    val percent: Float
        get() = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f

    companion object {
        /** 空进度占位实例，避免 UI 层空判断 */
        val EMPTY = StageFileProgress(
            stageId = "",
            displayName = "",
            completedCount = 0,
            totalCount = 0,
        )
    }
}
