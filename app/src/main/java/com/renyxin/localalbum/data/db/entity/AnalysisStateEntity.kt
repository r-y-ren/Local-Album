package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * AI 分析阶段断点续跑状态实体（Phase 1）。
 *
 * 以 **(filePath, stageId)** 为主键记录每个文件每个分析阶段的完成状态，
 * 进程被杀重启后可据此跳过已完成阶段、仅续跑未完成部分，避免重复推理。
 *
 * ## 为何独立于 [FeatureStoreEntity]
 *
 * - [FeatureStoreEntity] 唯一键为 `(filePath, pluginId)`，而 `stageId ≠ pluginId`；
 * - 内置阶段（face→`faces`、semantic→`media_embeddings`、quality/ocr→`media_items` 列）
 *   各写专用表，并不统一落 `feature_store`，无法用它判定阶段完成度；
 * - 状态语义（done）与特征数据语义不应混存。
 *
 * ## modelVersion 失效
 *
 * 当某阶段所用模型升级（[com.renyxin.localalbum.core.pipeline.AnalysisStage.modelVersion] 变化），
 * 旧 `done` 记录因 modelVersion 不匹配将被视为失效，该阶段对该文件重跑。
 *
 * @property filePath 媒体文件路径（对应 media_items.filePath）
 * @property stageId 分析阶段 ID（如 "core:face"）
 * @property status 完成状态，目前仅写入 "done"（pending 由"无记录"隐式表达）
 * @property modelVersion 生成该状态所用模型版本
 * @property updatedAtMs 状态更新时间戳
 */
@Entity(
    tableName = "analysis_state",
    primaryKeys = ["filePath", "stageId"],
    indices = [
        Index(value = ["stageId"]),
        Index(value = ["status"]),
        Index(value = ["modelVersion"]),
    ],
)
data class AnalysisStateEntity(
    val filePath: String,
    val stageId: String,
    val status: String,
    val modelVersion: Int = 1,
    val updatedAtMs: Long = System.currentTimeMillis(),
) {
    companion object {
        const val STATUS_DONE = "done"
    }
}
