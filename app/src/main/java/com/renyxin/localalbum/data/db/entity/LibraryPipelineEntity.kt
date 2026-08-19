package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single durable control row for the library pipeline.
 *
 * Stages are strictly serialized. MediaStore notifications may be journaled at any time, but no
 * automatic stage is allowed to preempt the current one. A killed process resumes this row and the
 * durable task queues instead of inventing a new scan.
 */
@Entity(tableName = "library_pipeline")
data class LibraryPipelineEntity(
    @PrimaryKey val pipelineId: String = DEFAULT_PIPELINE_ID,
    val stage: String = LibraryPipelineStage.UNINITIALIZED.name,
    val activeRunId: String? = null,
    val candidateGeneration: Long = 0L,
    val publishedGeneration: Long = 0L,
    val hasPublishedBaseline: Boolean = false,
    val rebuildRequested: Boolean = false,
    val rebuildRequired: Boolean = false,
    val rebuildReason: String? = null,
    val progressCompleted: Int = 0,
    val progressTotal: Int = 0,
    val failureCount: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_PIPELINE_ID = "default"
    }
}

/** Strict persisted order for initial build, incremental batches, and explicit rebuilds. */
enum class LibraryPipelineStage {
    UNINITIALIZED,
    INITIAL_SCAN,
    INITIAL_THUMBNAILS,
    INITIAL_PUBLISH,
    INITIAL_ANALYSIS,
    READY,
    INCREMENTAL_SCAN,
    INCREMENTAL_THUMBNAILS,
    INCREMENTAL_PUBLISH,
    INCREMENTAL_ANALYSIS,
    REBUILD_SCAN,
    REBUILD_THUMBNAILS,
    REBUILD_PUBLISH,
    REBUILD_ANALYSIS,
    NEEDS_REBUILD,
    FAILED,
    ;

    val isScan: Boolean
        get() = this == INITIAL_SCAN || this == INCREMENTAL_SCAN || this == REBUILD_SCAN

    val isThumbnail: Boolean
        get() = this == INITIAL_THUMBNAILS || this == INCREMENTAL_THUMBNAILS ||
            this == REBUILD_THUMBNAILS

    val isPublish: Boolean
        get() = this == INITIAL_PUBLISH || this == INCREMENTAL_PUBLISH ||
            this == REBUILD_PUBLISH

    val isAnalysis: Boolean
        get() = this == INITIAL_ANALYSIS || this == INCREMENTAL_ANALYSIS ||
            this == REBUILD_ANALYSIS

    companion object {
        fun fromPersisted(value: String): LibraryPipelineStage =
            entries.firstOrNull { it.name == value } ?: UNINITIALIZED
    }
}

/** UI-facing immutable projection of the durable pipeline row. */
data class LibraryPipelineState(
    val stage: LibraryPipelineStage = LibraryPipelineStage.UNINITIALIZED,
    val activeRunId: String? = null,
    val candidateGeneration: Long = 0L,
    val publishedGeneration: Long = 0L,
    val hasPublishedBaseline: Boolean = false,
    val rebuildRequested: Boolean = false,
    val rebuildRequired: Boolean = false,
    val rebuildReason: String? = null,
    val progressCompleted: Int = 0,
    val progressTotal: Int = 0,
    val failureCount: Int = 0,
    val lastError: String? = null,
) {
    /** 已发布快照始终可见；只有首次发布门禁尚未打开时才阻塞主页。 */
    val blocksHome: Boolean get() = !hasPublishedBaseline
    val isBusy: Boolean
        get() = stage != LibraryPipelineStage.READY &&
            stage != LibraryPipelineStage.NEEDS_REBUILD &&
            stage != LibraryPipelineStage.FAILED

    companion object {
        fun from(entity: LibraryPipelineEntity?): LibraryPipelineState = entity?.let {
            LibraryPipelineState(
                stage = LibraryPipelineStage.fromPersisted(it.stage),
                activeRunId = it.activeRunId,
                candidateGeneration = it.candidateGeneration,
                publishedGeneration = it.publishedGeneration,
                hasPublishedBaseline = it.hasPublishedBaseline,
                rebuildRequested = it.rebuildRequested,
                rebuildRequired = it.rebuildRequired,
                rebuildReason = it.rebuildReason,
                progressCompleted = it.progressCompleted,
                progressTotal = it.progressTotal,
                failureCount = it.failureCount,
                lastError = it.lastError,
            )
        } ?: LibraryPipelineState()
    }
}
