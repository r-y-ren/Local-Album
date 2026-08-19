package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.LibraryPipelineEntity
import com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
import kotlinx.coroutines.flow.Flow

/** Conditional transitions for the single durable library pipeline. */
@Dao
abstract class LibraryPipelineDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(entity: LibraryPipelineEntity): Long

    @Query("SELECT * FROM library_pipeline WHERE pipelineId = :pipelineId LIMIT 1")
    abstract suspend fun get(
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): LibraryPipelineEntity?

    @Query("SELECT * FROM library_pipeline WHERE pipelineId = :pipelineId LIMIT 1")
    abstract fun observe(
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Flow<LibraryPipelineEntity?>

    @Query(
        """UPDATE library_pipeline SET
               stage = :toStage, activeRunId = :activeRunId,
               candidateGeneration = :candidateGeneration,
               progressCompleted = 0, progressTotal = 0, failureCount = 0,
               lastError = NULL, updatedAt = :now
           WHERE pipelineId = :pipelineId AND stage IN (:fromStages)""",
    )
    protected abstract suspend fun transitionInternal(
        pipelineId: String,
        fromStages: List<String>,
        toStage: String,
        activeRunId: String?,
        candidateGeneration: Long,
        now: Long,
    ): Int

    @Transaction
    open suspend fun ensureInitialized(
        hasCanonicalMedia: Boolean,
        hasPublishedSnapshot: Boolean,
        publishedGeneration: Long,
        now: Long = System.currentTimeMillis(),
    ): LibraryPipelineEntity {
        insertIgnore(
            LibraryPipelineEntity(
                stage = if (hasCanonicalMedia && hasPublishedSnapshot) {
                    LibraryPipelineStage.READY.name
                } else {
                    LibraryPipelineStage.UNINITIALIZED.name
                },
                publishedGeneration = if (hasPublishedSnapshot) publishedGeneration else 0L,
                hasPublishedBaseline = hasCanonicalMedia && hasPublishedSnapshot,
                updatedAt = now,
            ),
        )
        return requireNotNull(get())
    }

    suspend fun transition(
        from: Set<LibraryPipelineStage>,
        to: LibraryPipelineStage,
        activeRunId: String? = null,
        candidateGeneration: Long = 0L,
        now: Long = System.currentTimeMillis(),
    ): Boolean = transitionInternal(
        pipelineId = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
        fromStages = from.map { it.name },
        toStage = to.name,
        activeRunId = activeRunId,
        candidateGeneration = candidateGeneration,
        now = now,
    ) == 1

    /** Reserves one durable scan run before any source enumeration starts. */
    @Query(
        """UPDATE library_pipeline SET
               activeRunId = :scanId, candidateGeneration = :generation,
               progressCompleted = 0, progressTotal = 0, failureCount = 0,
               lastError = NULL, updatedAt = :now
           WHERE pipelineId = :pipelineId AND stage = :stage
             AND activeRunId IS NULL""",
    )
    abstract suspend fun bindScanRun(
        stage: String,
        scanId: String,
        generation: Long,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    /** Advances only the exact active run; a delayed Worker from an older run is a no-op. */
    @Query(
        """UPDATE library_pipeline SET
               stage = :toStage,
               progressCompleted = 0, progressTotal = 0, failureCount = 0,
               lastError = NULL, updatedAt = :now
           WHERE pipelineId = :pipelineId AND stage = :fromStage
             AND activeRunId = :scanId AND candidateGeneration = :generation""",
    )
    abstract suspend fun transitionActiveRun(
        fromStage: String,
        toStage: String,
        scanId: String,
        generation: Long,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    /** A stale or internally inconsistent home projection must pass through the initial gate again. */
    @Query(
        """UPDATE library_pipeline SET
               stage = 'UNINITIALIZED', activeRunId = NULL, candidateGeneration = 0,
               publishedGeneration = 0, hasPublishedBaseline = 0,
               progressCompleted = 0, progressTotal = 0, failureCount = 0,
               lastError = NULL, updatedAt = :now
           WHERE pipelineId = :pipelineId AND hasPublishedBaseline = 1
             AND stage IN ('UNINITIALIZED', 'READY', 'NEEDS_REBUILD', 'FAILED')""",
    )
    abstract suspend fun invalidatePublishedBaselineIfIdle(
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    @Query(
        """UPDATE library_pipeline SET
               stage = :analysisStage, publishedGeneration = :generation,
               hasPublishedBaseline = 1,
               progressCompleted = 0, progressTotal = 0, failureCount = 0,
               lastError = NULL, updatedAt = :now
           WHERE pipelineId = :pipelineId AND stage = :publishStage
             AND activeRunId = :activeRunId AND candidateGeneration = :generation""",
    )
    abstract suspend fun publishAndEnterAnalysis(
        publishStage: String,
        analysisStage: String,
        activeRunId: String,
        generation: Long,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    @Query(
        """UPDATE library_pipeline SET
               stage = 'REBUILD_SCAN', activeRunId = NULL, candidateGeneration = 0,
               rebuildRequested = 0, rebuildRequired = 0, rebuildReason = NULL,
               progressCompleted = 0, progressTotal = 0, failureCount = 0,
               lastError = NULL, updatedAt = :now
           WHERE pipelineId = :pipelineId
             AND stage IN ('READY', 'NEEDS_REBUILD')
             AND rebuildRequested = 1""",
    )
    abstract suspend fun startRequestedRebuild(
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    @Query(
        """UPDATE library_pipeline SET
               progressCompleted = MAX(progressCompleted, :completed),
               progressTotal = MAX(progressTotal, :total),
               failureCount = MAX(failureCount, :failures),
               updatedAt = :now
           WHERE pipelineId = :pipelineId AND stage = :stage
             AND activeRunId = :scanId AND candidateGeneration = :generation""",
    )
    abstract suspend fun updateProgress(
        stage: String,
        scanId: String,
        generation: Long,
        completed: Int,
        total: Int,
        failures: Int,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    /** Thumbnail target sets can shrink after delete/trash/version changes; persist the exact gate. */
    @Query(
        """UPDATE library_pipeline SET
               progressCompleted = :completed, progressTotal = :total,
               failureCount = :failures, updatedAt = :now
           WHERE pipelineId = :pipelineId AND stage = :stage
             AND stage IN ('INITIAL_THUMBNAILS','INCREMENTAL_THUMBNAILS','REBUILD_THUMBNAILS')
             AND activeRunId = :scanId AND candidateGeneration = :generation""",
    )
    abstract suspend fun updateThumbnailProgressExact(
        stage: String,
        scanId: String,
        generation: Long,
        completed: Int,
        total: Int,
        failures: Int,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    @Query(
        """UPDATE library_pipeline SET rebuildRequested = 1,
               rebuildReason = :reason, updatedAt = :now
           WHERE pipelineId = :pipelineId""",
    )
    abstract suspend fun requestRebuild(
        reason: String,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    @Query(
        """UPDATE library_pipeline SET rebuildRequired = 1,
               rebuildReason = :reason,
               stage = CASE WHEN stage = 'READY' THEN 'NEEDS_REBUILD' ELSE stage END,
               updatedAt = :now
           WHERE pipelineId = :pipelineId""",
    )
    abstract suspend fun requireRebuild(
        reason: String,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    @Query(
        """UPDATE library_pipeline SET stage = 'FAILED', lastError = :error, updatedAt = :now
           WHERE pipelineId = :pipelineId
             AND stage IN ('INITIAL_SCAN','INCREMENTAL_SCAN','REBUILD_SCAN')
             AND activeRunId = :scanId""",
    )
    abstract suspend fun markActiveScanFailed(
        scanId: String,
        error: String,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int

    @Query(
        """UPDATE library_pipeline SET
               stage = CASE
                   WHEN rebuildRequired = 1 AND rebuildRequested = 0 THEN 'NEEDS_REBUILD'
                   ELSE 'READY'
               END,
               activeRunId = NULL,
               publishedGeneration = :generation,
               hasPublishedBaseline = 1,
               progressCompleted = 0, progressTotal = 0, failureCount = :failures,
               lastError = NULL, updatedAt = :now
           WHERE pipelineId = :pipelineId AND stage IN
               ('INITIAL_ANALYSIS','INCREMENTAL_ANALYSIS','REBUILD_ANALYSIS')
             AND activeRunId = :scanId AND candidateGeneration = :generation""",
    )
    abstract suspend fun finishAnalysis(
        scanId: String,
        generation: Long,
        failures: Int,
        now: Long = System.currentTimeMillis(),
        pipelineId: String = LibraryPipelineEntity.DEFAULT_PIPELINE_ID,
    ): Int
}
