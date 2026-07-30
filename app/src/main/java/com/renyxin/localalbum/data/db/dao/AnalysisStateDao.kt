package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.renyxin.localalbum.data.db.entity.AnalysisStateEntity

/**
 * AI 分析阶段断点续跑状态 DAO（Phase 1）。
 *
 * 断点续跑策略：每个缓存型阶段执行前查 [getDonePaths] 取得已完成文件集合，
 * 仅对差集（pending）推理；单文件成功后通过 [upsertAll] 落库 `done` 状态。
 */
@Dao
interface AnalysisStateDao {

    /**
     * 返回指定阶段在指定 [modelVersion] 下已完成的文件路径集合。
     * modelVersion 不匹配的记录视为失效（模型升级后需重跑），不返回。
     */
    @Query(
        "SELECT filePath FROM analysis_state " +
            "WHERE stageId = :stageId AND status = 'done' AND modelVersion = :modelVersion"
    )
    suspend fun getDonePaths(stageId: String, modelVersion: Int): List<String>

    /**
     * 只查询调用方当前批次中已完成的路径，避免每个 250 条分析批次加载阶段历史全集。
     * 调用方需将 filePaths 控制在 SQLite 参数上限以内。
     */
    @Query(
        "SELECT filePath FROM analysis_state " +
            "WHERE stageId = :stageId AND status = 'done' AND modelVersion = :modelVersion " +
            "AND filePath IN (:filePaths)"
    )
    suspend fun getDonePathsInBatch(
        stageId: String,
        modelVersion: Int,
        filePaths: List<String>,
    ): List<String>

    /** 批量写入阶段完成状态（覆盖同主键旧记录）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(states: List<AnalysisStateEntity>)

    /** 删除指定文件路径的所有阶段状态（文件更新或删除时失效）。 */
    @Query("DELETE FROM analysis_state WHERE filePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    /** 删除指定阶段的所有状态（forceReanalyzeAll 重分析该阶段时使用）。 */
    @Query("DELETE FROM analysis_state WHERE stageId = :stageId")
    suspend fun deleteByStage(stageId: String)

    /** 清空全部状态（forceReanalyzeAll 全量重分析时使用）。 */
    @Query("DELETE FROM analysis_state")
    suspend fun deleteAll()

    /** 已完成（任意阶段）的去重文件数，用于分析完成度统计。 */
    @Query("SELECT COUNT(DISTINCT filePath) FROM analysis_state WHERE status = 'done'")
    suspend fun countDistinctDonePaths(): Int

    /** 指定阶段已完成文件数。 */
    @Query(
        "SELECT COUNT(*) FROM analysis_state " +
            "WHERE stageId = :stageId AND status = 'done' AND modelVersion = :modelVersion"
    )
    suspend fun countDone(stageId: String, modelVersion: Int): Int

    /** 全部记录数（诊断用）。 */
    @Query("SELECT COUNT(*) FROM analysis_state")
    suspend fun countAll(): Int
}
