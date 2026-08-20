
package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import kotlinx.coroutines.flow.Flow

/** Atomic thumbnail publication-gate projection, including live interactive participants. */
data class ThumbnailGateSnapshot(
    val total: Int,
    val terminal: Int,
    val failures: Int,
    val interactiveActive: Int,
    val repairable: Int,
) {
    val remaining: Int get() = (total - terminal).coerceAtLeast(0)
}

/** Producer result retained for UI metrics; dispatch correctness never depends on [shouldWake]. */
data class InteractiveThumbnailEnqueueResult(
    val accepted: Int,
    val shouldWake: Boolean,
    val requestedGeneration: Long = 0L,
)

data class ThumbnailLaneLevel(
    val immediateCount: Int,
    val activeCount: Int,
    val earliestFuture: Long?,
)

enum class ThumbnailPublicationResult { PUBLISHED, SUPERSEDED, LEASE_LOST }

@Dao
abstract class ThumbnailTaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(tasks: List<ThumbnailTaskEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertLaneIgnore(state: ThumbnailLaneWakeEntity): Long

    @Query("SELECT * FROM thumbnail_lane_wake ORDER BY laneId")
    abstract fun observeLaneStates(): Flow<List<ThumbnailLaneWakeEntity>>

    @Query("SELECT * FROM thumbnail_lane_wake WHERE laneId = :laneId LIMIT 1")
    abstract suspend fun laneState(laneId: String): ThumbnailLaneWakeEntity?

    @Transaction
    open suspend fun ensureLaneRows(now: Long = System.currentTimeMillis()) {
        insertLaneIgnore(ThumbnailLaneWakeEntity.initial(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID, now))
        insertLaneIgnore(ThumbnailLaneWakeEntity.initial(ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID, now))
    }

    @Query(
        """SELECT
             COALESCE(SUM(CASE WHEN task.status = 'PENDING' AND task.nextRetryAt <= :now THEN 1 ELSE 0 END),0) AS immediateCount,
             COALESCE(SUM(CASE WHEN task.status IN ('PENDING','RUNNING') THEN 1 ELSE 0 END),0) AS activeCount,
             MIN(CASE WHEN task.status = 'PENDING' AND task.nextRetryAt > :now THEN task.nextRetryAt
                      WHEN task.status = 'RUNNING' AND task.leaseUntil > :now THEN task.leaseUntil END) AS earliestFuture
           FROM thumbnail_tasks task
           WHERE task.status IN ('PENDING','RUNNING')
             AND (
               (:laneId = 'interactive' AND task.scanId IS NULL) OR
               (:laneId = 'automatic' AND task.scanId IS NOT NULL AND EXISTS(
                 SELECT 1 FROM library_pipeline pipeline
                 WHERE pipeline.pipelineId = 'default'
                   AND pipeline.stage IN ('INITIAL_THUMBNAILS','INCREMENTAL_THUMBNAILS','REBUILD_THUMBNAILS')
                   AND pipeline.activeRunId = task.scanId
               ))
             )""",
    )
    protected abstract suspend fun readLaneLevel(laneId: String, now: Long): ThumbnailLaneLevel

    @Query(
        """UPDATE thumbnail_lane_wake
           SET deliveryState = :state, revision = revision + 1, notBefore = :notBefore,
               nextDispatchAt = CASE WHEN :state = 'PENDING' THEN 0 ELSE :notBefore END,
               dispatchToken = CASE WHEN :state IN ('PENDING','DELAYED','QUIESCENT') THEN NULL ELSE dispatchToken END,
               dispatchLeaseUntil = CASE WHEN :state IN ('PENDING','DELAYED','QUIESCENT') THEN 0 ELSE dispatchLeaseUntil END,
               updatedAt = :now
           WHERE laneId = :laneId
             AND (deliveryState != 'RUNNING' OR runLeaseUntil <= :now)""",
    )
    protected abstract suspend fun writeLaneLevel(
        laneId: String,
        state: String,
        notBefore: Long,
        now: Long,
    ): Int

    /** Producer/finalizer/recovery all derive the lane state from durable task facts. */
    @Transaction
    open suspend fun recomputeLaneLevel(laneId: String, now: Long = System.currentTimeMillis()): ThumbnailLaneWakeEntity {
        ensureLaneRows(now)
        recoverExpiredTaskLeasesForLane(laneId, now)
        val current = requireNotNull(laneState(laneId))
        if (current.deliveryState == ThumbnailLaneWakeEntity.STATE_RUNNING && current.runLeaseUntil > now) {
            return current
        }
        val level = readLaneLevel(laneId, now)
        val state = when {
            level.immediateCount > 0 -> ThumbnailLaneWakeEntity.STATE_PENDING
            level.activeCount > 0 -> ThumbnailLaneWakeEntity.STATE_DELAYED
            else -> ThumbnailLaneWakeEntity.STATE_QUIESCENT
        }
        val notBefore = if (level.immediateCount > 0) 0L else level.earliestFuture ?: 0L
        writeLaneLevel(laneId, state, notBefore, now)
        return requireNotNull(laneState(laneId))
    }

    @Query(
        """UPDATE thumbnail_tasks SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL,
               updatedAt = :now
           WHERE status = 'RUNNING' AND leaseUntil <= :now
             AND (
               (:laneId = 'interactive' AND scanId IS NULL) OR
               (:laneId = 'automatic' AND scanId IS NOT NULL AND EXISTS(
                 SELECT 1 FROM library_pipeline pipeline
                 WHERE pipeline.pipelineId = 'default'
                   AND pipeline.stage IN ('INITIAL_THUMBNAILS','INCREMENTAL_THUMBNAILS','REBUILD_THUMBNAILS')
                   AND pipeline.activeRunId = thumbnail_tasks.scanId
               ))
             )""",
    )
    protected abstract suspend fun recoverExpiredTaskLeasesForLane(laneId: String, now: Long): Int

    @Query(
        """UPDATE thumbnail_lane_wake
           SET deliveryState = 'DISPATCHING', revision = revision + 1,
               dispatchToken = :token, dispatchLeaseUntil = :leaseUntil,
               enqueueAttemptCount = enqueueAttemptCount + 1, updatedAt = :now
           WHERE laneId = :laneId AND deliveryState IN ('PENDING','DELAYED','DISPATCHING','ENQUEUED')
             AND notBefore <= :now AND nextDispatchAt <= :now
             AND (deliveryState NOT IN ('DISPATCHING','ENQUEUED') OR dispatchLeaseUntil <= :now)
             AND (runToken IS NULL OR runLeaseUntil <= :now)""",
    )
    protected abstract suspend fun claimDispatchInternal(
        laneId: String,
        token: String,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Transaction
    open suspend fun claimDispatch(
        laneId: String,
        token: String,
        now: Long,
        leaseDurationMs: Long,
    ): ThumbnailLaneWakeEntity? {
        recomputeLaneLevel(laneId, now)
        if (claimDispatchInternal(laneId, token, now + leaseDurationMs, now) != 1) return null
        return laneState(laneId)
    }

    @Query(
        """UPDATE thumbnail_lane_wake SET deliveryState = 'ENQUEUED', updatedAt = :now
           WHERE laneId = :laneId AND deliveryState = 'DISPATCHING'
             AND revision = :revision AND dispatchToken = :token AND dispatchLeaseUntil > :now""",
    )
    abstract suspend fun markDispatchEnqueued(
        laneId: String,
        revision: Long,
        token: String,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_lane_wake
           SET deliveryState = 'PENDING', revision = revision + 1,
               dispatchToken = NULL, dispatchLeaseUntil = 0,
               nextDispatchAt = :nextDispatchAt, lastDispatchError = :error, updatedAt = :now
           WHERE laneId = :laneId AND dispatchToken = :token
             AND deliveryState IN ('DISPATCHING','ENQUEUED')""",
    )
    abstract suspend fun releaseFailedDispatch(
        laneId: String,
        token: String,
        error: String,
        nextDispatchAt: Long,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_lane_wake
           SET deliveryState = 'RUNNING', revision = revision + 1,
               runToken = :runToken, runLeaseUntil = :runLeaseUntil,
               dispatchLeaseUntil = 0, updatedAt = :now
           WHERE laneId = :laneId AND deliveryState IN ('DISPATCHING','ENQUEUED')
             AND revision = :dispatchRevision AND dispatchToken = :dispatchToken""",
    )
    abstract suspend fun startRun(
        laneId: String,
        dispatchRevision: Long,
        dispatchToken: String,
        runToken: String,
        runLeaseUntil: Long,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_lane_wake SET runLeaseUntil = :leaseUntil, updatedAt = :now
           WHERE laneId = :laneId AND deliveryState = 'RUNNING' AND runToken = :runToken""",
    )
    abstract suspend fun renewRunLease(laneId: String, runToken: String, leaseUntil: Long, now: Long): Int

    @Query(
        """UPDATE thumbnail_lane_wake
           SET deliveryState = :state, revision = revision + 1,
               dispatchToken = NULL, dispatchLeaseUntil = 0,
               runToken = NULL, runLeaseUntil = 0, notBefore = :notBefore,
               nextDispatchAt = CASE WHEN :state = 'PENDING' THEN 0 ELSE :notBefore END,
               updatedAt = :now
           WHERE laneId = :laneId AND deliveryState = 'RUNNING' AND runToken = :runToken""",
    )
    protected abstract suspend fun finalizeRunInternal(
        laneId: String,
        runToken: String,
        state: String,
        notBefore: Long,
        now: Long,
    ): Int

    /** 单事务释放 run token 并观察 producer 先后两种顺序，关闭 final-count -> ack 窗口。 */
    @Transaction
    open suspend fun finalizeRun(laneId: String, runToken: String, now: Long): ThumbnailLaneWakeEntity? {
        recoverExpiredTaskLeasesForLane(laneId, now)
        val level = readLaneLevel(laneId, now)
        val state = when {
            level.immediateCount > 0 -> ThumbnailLaneWakeEntity.STATE_PENDING
            level.activeCount > 0 -> ThumbnailLaneWakeEntity.STATE_DELAYED
            else -> ThumbnailLaneWakeEntity.STATE_QUIESCENT
        }
        val notBefore = if (level.immediateCount > 0) 0L else level.earliestFuture ?: 0L
        if (finalizeRunInternal(laneId, runToken, state, notBefore, now) != 1) return null
        return laneState(laneId)
    }

    @Query(
        """UPDATE thumbnail_tasks
           SET status = 'SUPERSEDED', leaseUntil = 0, leaseToken = NULL, updatedAt = :now
           WHERE filePath = :filePath AND sizeClass = :sizeClass AND status = 'PENDING'
             AND (mediaType != :mediaType OR sourceVersion != :sourceVersion OR formatVersion != :formatVersion)""",
    )
    protected abstract suspend fun supersedeOldIdentities(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_tasks SET priority = MAX(priority,:priority), scanId = :scanId,
               status = CASE WHEN status = 'SUPERSEDED' THEN 'PENDING' ELSE status END,
               attemptCount = CASE WHEN status = 'SUPERSEDED' THEN 0 ELSE attemptCount END,
               nextRetryAt = CASE WHEN status = 'SUPERSEDED' THEN 0 ELSE nextRetryAt END,
               lastError = CASE WHEN status = 'SUPERSEDED' THEN NULL ELSE lastError END,
               updatedAt = :now
           WHERE filePath = :filePath AND mediaType = :mediaType AND sourceVersion = :sourceVersion
             AND sizeClass = :sizeClass AND formatVersion = :formatVersion
             AND status IN ('PENDING','SUPERSEDED')""",
    )
    protected abstract suspend fun promoteSeedable(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        priority: Int,
        scanId: String?,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_tasks SET priority = MAX(priority,:priority), updatedAt = :now
           WHERE filePath = :filePath AND mediaType = :mediaType AND sourceVersion = :sourceVersion
             AND sizeClass = :sizeClass AND formatVersion = :formatVersion
             AND status IN ('PENDING','RUNNING')""",
    )
    protected abstract suspend fun promoteActive(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        priority: Int,
        now: Long,
    ): Int

    @Query(
        """SELECT EXISTS(SELECT 1 FROM media_items WHERE filePath = :filePath
             AND mediaType = :mediaType AND isTrashed = 0
             AND ('sv1|m=' || modifiedAtMs || '|s=' || fileSize || '|f=' ||
                  COALESCE(LOWER(fingerprintHead),'-')) = :sourceVersion)""",
    )
    protected abstract suspend fun isCanonicalCurrent(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
    ): Boolean

    /** UI model intentionally omits fingerprint; resolve the exact canonical DB identity before enqueue. */
    @Query(
        """SELECT ('sv1|m=' || modifiedAtMs || '|s=' || fileSize || '|f=' ||
                        COALESCE(LOWER(fingerprintHead),'-'))
           FROM media_items
           WHERE filePath = :filePath AND mediaType = :mediaType AND isTrashed = 0
             AND modifiedAtMs = :modifiedAtMs AND fileSize = :fileSize
           LIMIT 1""",
    )
    abstract suspend fun resolveCanonicalSourceVersion(
        filePath: String,
        mediaType: String,
        modifiedAtMs: Long,
        fileSize: Long,
    ): String?

    @Transaction
    open suspend fun enqueueAll(tasks: List<ThumbnailTaskEntity>): List<Long> {
        if (tasks.isEmpty()) return emptyList()
        require(tasks.all { it.scanId != null })
        tasks.forEach { task ->
            supersedeOldIdentities(
                task.filePath, task.mediaType, task.sourceVersion, task.sizeClass,
                task.formatVersion, task.updatedAt,
            )
            if (promoteSeedable(
                    task.filePath, task.mediaType, task.sourceVersion, task.sizeClass,
                    task.formatVersion, task.priority, task.scanId, task.updatedAt,
                ) == 0
            ) insertIgnore(listOf(task))
        }
        recomputeLaneLevel(ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID, tasks.maxOf { it.updatedAt })
        return emptyList()
    }

    @Transaction
    open suspend fun enqueueInteractive(tasks: List<ThumbnailTaskEntity>): InteractiveThumbnailEnqueueResult {
        if (tasks.isEmpty()) return InteractiveThumbnailEnqueueResult(0, false)
        require(tasks.all { it.scanId == null })
        var accepted = 0
        tasks.distinctBy { listOf(it.filePath, it.mediaType, it.sourceVersion, it.sizeClass, it.formatVersion) }
            .forEach { task ->
                if (!isCanonicalCurrent(task.filePath, task.mediaType, task.sourceVersion)) return@forEach
                supersedeOldIdentities(
                    task.filePath, task.mediaType, task.sourceVersion, task.sizeClass,
                    task.formatVersion, task.updatedAt,
                )
                val promoted = promoteActive(
                    task.filePath, task.mediaType, task.sourceVersion, task.sizeClass,
                    task.formatVersion, task.priority, task.updatedAt,
                )
                if (promoted > 0 || insertIgnore(listOf(task)).singleOrNull() != -1L) accepted++
            }
        val state = recomputeLaneLevel(
            ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,
            tasks.maxOf { it.updatedAt },
        )
        return InteractiveThumbnailEnqueueResult(
            accepted,
            state.deliveryState == ThumbnailLaneWakeEntity.STATE_PENDING,
            state.revision,
        )
    }

    @Query("SELECT COUNT(*) FROM media_items WHERE isTrashed = 0 AND mediaType IN ('IMAGE','VIDEO')")
    abstract suspend fun countVisibleGridMedia(): Int

    @Query(
        """SELECT COUNT(*) FROM media_items media
           WHERE media.isTrashed = 0 AND media.mediaType IN ('IMAGE','VIDEO')
             AND (
               NOT EXISTS(
                 SELECT 1 FROM home_media_snapshot home
                 WHERE home.filePath = media.filePath
                   AND home.thumbnailState = 'READY'
                   AND home.thumbnailPath IS NOT NULL
                   AND home.thumbnailPath = media.thumbnailPath
               )
               OR NOT EXISTS(
                 SELECT 1 FROM thumbnail_cache_entries cache
                 WHERE cache.filePath = media.filePath
                   AND cache.mediaType = media.mediaType
                   AND cache.sizeClass = 'grid'
                   AND cache.formatVersion = :formatVersion
                   AND cache.sourceVersion = 'sv1|m=' || media.modifiedAtMs || '|s=' ||
                       media.fileSize || '|f=' || COALESCE(LOWER(media.fingerprintHead),'-')
                   AND cache.path = media.thumbnailPath
                   AND cache.state = 'READY'
               )
             )""",
    )
    abstract suspend fun countCurrentGridGaps(formatVersion: Int = ThumbnailIdentity.CURRENT_FORMAT_VERSION): Int

    @Query(
        """UPDATE thumbnail_tasks SET status = 'SUPERSEDED', leaseUntil = 0, leaseToken = NULL,
               updatedAt = :now
           WHERE sizeClass = 'grid' AND status = 'PENDING' AND EXISTS(
             SELECT 1 FROM media_items media WHERE media.filePath = thumbnail_tasks.filePath
               AND media.isTrashed = 0 AND (
                 media.mediaType != thumbnail_tasks.mediaType OR
                 ('sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
                  COALESCE(LOWER(media.fingerprintHead),'-')) != thumbnail_tasks.sourceVersion OR
                 thumbnail_tasks.formatVersion != :formatVersion))""",
    )
    protected abstract suspend fun supersedeStaleGridForAll(formatVersion: Int, now: Long): Int

    @Query(
        """INSERT OR IGNORE INTO thumbnail_tasks(
             filePath,sizeClass,sourceVersion,mediaType,formatVersion,priority,status,attemptCount,
             nextRetryAt,leaseUntil,leaseToken,lastError,createdAt,updatedAt,scanId)
           SELECT filePath,'grid','sv1|m=' || modifiedAtMs || '|s=' || fileSize || '|f=' ||
                  COALESCE(LOWER(fingerprintHead),'-'),mediaType,:formatVersion,:priority,
                  'PENDING',0,0,0,NULL,NULL,:now,:now,:scanId
           FROM media_items WHERE isTrashed = 0 AND mediaType IN ('IMAGE','VIDEO')""",
    )
    protected abstract suspend fun insertAllCurrentGrid(scanId: String, priority: Int, formatVersion: Int, now: Long)

    @Query(
        """UPDATE thumbnail_tasks SET scanId = :scanId, priority = MAX(priority,:priority),
               status = CASE WHEN status = 'SUPERSEDED' THEN 'PENDING' ELSE status END,
               attemptCount = CASE WHEN status = 'SUPERSEDED' THEN 0 ELSE attemptCount END,
               nextRetryAt = CASE WHEN status = 'SUPERSEDED' THEN 0 ELSE nextRetryAt END,
               lastError = CASE WHEN status = 'SUPERSEDED' THEN NULL ELSE lastError END,
               updatedAt = :now
           WHERE sizeClass = 'grid' AND formatVersion = :formatVersion
             AND status IN ('PENDING','SUPERSEDED') AND EXISTS(
               SELECT 1 FROM media_items media WHERE media.filePath = thumbnail_tasks.filePath
                 AND media.isTrashed = 0 AND media.mediaType = thumbnail_tasks.mediaType
                 AND ('sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
                      COALESCE(LOWER(media.fingerprintHead),'-')) = thumbnail_tasks.sourceVersion)""",
    )
    protected abstract suspend fun assignAllCurrentGrid(
        scanId: String,
        priority: Int,
        formatVersion: Int,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_tasks SET status = 'PENDING', attemptCount = 0, nextRetryAt = 0,
               leaseUntil = 0, leaseToken = NULL, lastError = NULL,
               priority = MAX(priority,:priority), scanId = :scanId, updatedAt = :now
           WHERE sizeClass = 'grid' AND formatVersion = :formatVersion AND status = 'DONE'
             AND EXISTS(
               SELECT 1 FROM media_items media
               WHERE media.filePath = thumbnail_tasks.filePath
                 AND media.mediaType = thumbnail_tasks.mediaType
                 AND media.isTrashed = 0
                 AND media.mediaType IN ('IMAGE','VIDEO')
                 AND ('sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
                      COALESCE(LOWER(media.fingerprintHead),'-')) = thumbnail_tasks.sourceVersion
             )
             AND NOT EXISTS(
               SELECT 1 FROM thumbnail_cache_entries cache
               JOIN media_items media
                 ON media.filePath = cache.filePath AND media.mediaType = cache.mediaType
               WHERE cache.filePath = thumbnail_tasks.filePath
                 AND cache.mediaType = thumbnail_tasks.mediaType
                 AND cache.sourceVersion = thumbnail_tasks.sourceVersion
                 AND cache.sizeClass = thumbnail_tasks.sizeClass
                 AND cache.formatVersion = thumbnail_tasks.formatVersion
                 AND cache.state = 'READY'
                 AND media.isTrashed = 0
                 AND ('sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
                      COALESCE(LOWER(media.fingerprintHead),'-')) = thumbnail_tasks.sourceVersion
                 AND cache.path = media.thumbnailPath
             )""",
    )
    protected abstract suspend fun reactivateCurrentDoneGridForRepair(
        scanId: String,
        priority: Int,
        formatVersion: Int,
        now: Long,
    ): Int

    @Transaction
    open suspend fun seedAllGrid(
        scanId: String,
        priority: Int,
        now: Long = System.currentTimeMillis(),
        repairMissingDone: Boolean = false,
    ): Int {
        supersedeStaleGridForAll(ThumbnailIdentity.CURRENT_FORMAT_VERSION, now)
        insertAllCurrentGrid(scanId, priority, ThumbnailIdentity.CURRENT_FORMAT_VERSION, now)
        if (repairMissingDone) {
            reactivateCurrentDoneGridForRepair(
                scanId = scanId,
                priority = priority,
                formatVersion = ThumbnailIdentity.CURRENT_FORMAT_VERSION,
                now = now,
            )
        }
        assignAllCurrentGrid(scanId, priority, ThumbnailIdentity.CURRENT_FORMAT_VERSION, now)
        recomputeLaneLevel(ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID, now)
        return thumbnailGateSnapshot(scanId).total
    }

    @Query(
        """INSERT OR IGNORE INTO thumbnail_tasks(
             filePath,sizeClass,sourceVersion,mediaType,formatVersion,priority,status,attemptCount,
             nextRetryAt,leaseUntil,leaseToken,lastError,createdAt,updatedAt,scanId)
           SELECT DISTINCT outbox.filePath,'grid',outbox.sourceVersion,outbox.mediaType,
             :formatVersion,:priority,'PENDING',0,0,0,NULL,NULL,:now,:now,:scanId
           FROM enhancement_outbox outbox JOIN media_items media ON media.filePath = outbox.filePath
            AND media.mediaType = outbox.mediaType AND media.isTrashed = 0
            AND ('sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
                 COALESCE(LOWER(media.fingerprintHead),'-')) = outbox.sourceVersion
           WHERE outbox.scanId = :scanId AND outbox.enqueueThumbnail = 1
             AND outbox.mediaType IN ('IMAGE','VIDEO')""",
    )
    protected abstract suspend fun insertOutboxCurrentGrid(
        scanId: String,
        priority: Int,
        formatVersion: Int,
        now: Long,
    )

    @Query(
        """UPDATE thumbnail_tasks SET scanId = :scanId, priority = MAX(priority,:priority),
               status = CASE WHEN status = 'SUPERSEDED' THEN 'PENDING' ELSE status END,
               attemptCount = CASE WHEN status = 'SUPERSEDED' THEN 0 ELSE attemptCount END,
               nextRetryAt = CASE WHEN status = 'SUPERSEDED' THEN 0 ELSE nextRetryAt END,
               lastError = CASE WHEN status = 'SUPERSEDED' THEN NULL ELSE lastError END,
               updatedAt = :now
           WHERE sizeClass = 'grid' AND formatVersion = :formatVersion
             AND status IN ('PENDING','SUPERSEDED') AND EXISTS(
               SELECT 1 FROM enhancement_outbox outbox JOIN media_items media
                 ON media.filePath = outbox.filePath AND media.mediaType = outbox.mediaType
                AND media.isTrashed = 0
                AND ('sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
                     COALESCE(LOWER(media.fingerprintHead),'-')) = outbox.sourceVersion
               WHERE outbox.scanId = :scanId AND outbox.enqueueThumbnail = 1
                 AND outbox.filePath = thumbnail_tasks.filePath
                 AND outbox.mediaType = thumbnail_tasks.mediaType
                 AND outbox.sourceVersion = thumbnail_tasks.sourceVersion)""",
    )
    protected abstract suspend fun assignOutboxCurrentGrid(
        scanId: String,
        priority: Int,
        formatVersion: Int,
        now: Long,
    ): Int

    @Transaction
    open suspend fun seedGridFromOutbox(scanId: String, priority: Int, now: Long = System.currentTimeMillis()): Int {
        insertOutboxCurrentGrid(scanId, priority, ThumbnailIdentity.CURRENT_FORMAT_VERSION, now)
        assignOutboxCurrentGrid(scanId, priority, ThumbnailIdentity.CURRENT_FORMAT_VERSION, now)
        recomputeLaneLevel(ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID, now)
        return thumbnailGateSnapshot(scanId).total
    }

    @Query(
        """SELECT COUNT(*) AS total,
             COALESCE(SUM(CASE WHEN task.status IN ('DONE','FAILED') THEN 1 ELSE 0 END),0) AS terminal,
             COALESCE(SUM(CASE WHEN task.status = 'FAILED' THEN 1 ELSE 0 END),0) AS failures,
             COALESCE(SUM(CASE WHEN task.scanId IS NULL AND task.status IN ('PENDING','RUNNING') THEN 1 ELSE 0 END),0) AS interactiveActive,
             COALESCE(SUM(CASE WHEN task.taskId IS NULL OR task.status = 'SUPERSEDED' THEN 1 ELSE 0 END),0) AS repairable
           FROM (
             SELECT media.filePath,media.mediaType,
               'sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
               COALESCE(LOWER(media.fingerprintHead),'-') AS sourceVersion
             FROM scan_runs run JOIN media_items media ON media.isTrashed = 0
             WHERE run.scanId = :scanId AND run.scanType IN ('FULL','RECONCILIATION','THUMBNAIL_REPAIR')
               AND media.mediaType IN ('IMAGE','VIDEO')
             UNION
             SELECT outbox.filePath,outbox.mediaType,outbox.sourceVersion
             FROM scan_runs run JOIN enhancement_outbox outbox ON outbox.scanId = run.scanId
             JOIN media_items media ON media.filePath = outbox.filePath
               AND media.mediaType = outbox.mediaType AND media.isTrashed = 0
               AND ('sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
                    COALESCE(LOWER(media.fingerprintHead),'-')) = outbox.sourceVersion
             WHERE run.scanId = :scanId AND run.scanType = 'INCREMENTAL'
               AND outbox.enqueueThumbnail = 1 AND outbox.mediaType IN ('IMAGE','VIDEO')
           ) target LEFT JOIN thumbnail_tasks task ON task.filePath = target.filePath
             AND task.mediaType = target.mediaType AND task.sourceVersion = target.sourceVersion
             AND task.sizeClass = 'grid' AND task.formatVersion = :formatVersion""",
    )
    protected abstract suspend fun thumbnailGateSnapshotVersioned(scanId: String, formatVersion: Int): ThumbnailGateSnapshot

    suspend fun thumbnailGateSnapshot(scanId: String): ThumbnailGateSnapshot =
        thumbnailGateSnapshotVersioned(scanId, ThumbnailIdentity.CURRENT_FORMAT_VERSION)

    @Query(
        """SELECT task.taskId FROM thumbnail_tasks task
           WHERE task.status = 'PENDING' AND task.nextRetryAt <= :now
             AND (
               (:laneId = 'interactive' AND task.scanId IS NULL) OR
               (:laneId = 'automatic' AND task.scanId IS NOT NULL AND task.scanId IN (
                 SELECT pipeline.activeRunId FROM library_pipeline pipeline
                 WHERE pipeline.pipelineId = 'default'
                   AND pipeline.stage IN ('INITIAL_THUMBNAILS','INCREMENTAL_THUMBNAILS','REBUILD_THUMBNAILS')
               ))
             )
           ORDER BY task.priority DESC,task.createdAt LIMIT :limit""",
    )
    protected abstract suspend fun findClaimableIds(laneId: String, now: Long, limit: Int): List<Long>

    @Query(
        """UPDATE thumbnail_tasks SET status = 'RUNNING',leaseUntil = :leaseUntil,
             leaseToken = :leaseToken,attemptCount = attemptCount + 1,updatedAt = :now
           WHERE taskId IN (:ids) AND status = 'PENDING' AND nextRetryAt <= :now""",
    )
    protected abstract suspend fun markClaimed(ids: List<Long>, leaseToken: String, leaseUntil: Long, now: Long): Int

    @Query("SELECT * FROM thumbnail_tasks WHERE leaseToken = :leaseToken AND status = 'RUNNING' ORDER BY priority DESC,createdAt")
    protected abstract suspend fun getByLeaseToken(leaseToken: String): List<ThumbnailTaskEntity>

    suspend fun claimBatch(now:Long,limit:Int,leaseToken:String,leaseDurationMs:Long) =
        claimLaneBatch(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,now,limit,leaseToken,leaseDurationMs)

    @Transaction
    open suspend fun claimLaneBatch(
        laneId: String,
        now: Long,
        limit: Int,
        leaseToken: String,
        leaseDurationMs: Long,
    ): List<ThumbnailTaskEntity> {
        recoverExpiredTaskLeasesForLane(laneId, now)
        val ids = findClaimableIds(laneId, now, limit)
        if (ids.isEmpty()) return emptyList()
        markClaimed(ids, leaseToken, now + leaseDurationMs, now)
        return getByLeaseToken(leaseToken)
    }

    suspend fun claimInteractiveBatch(now: Long, limit: Int, leaseToken: String, leaseDurationMs: Long) =
        claimLaneBatch(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID, now, limit, leaseToken, leaseDurationMs)

    @Query(
        """SELECT task.taskId FROM thumbnail_tasks task
           WHERE task.status = 'PENDING' AND task.nextRetryAt <= :now
             AND task.scanId = :scanId
             AND EXISTS(
               SELECT 1 FROM library_pipeline pipeline
               WHERE pipeline.pipelineId = 'default'
                 AND pipeline.stage IN ('INITIAL_THUMBNAILS','INCREMENTAL_THUMBNAILS','REBUILD_THUMBNAILS')
                 AND pipeline.activeRunId = :scanId
             )
           ORDER BY task.priority DESC,task.createdAt LIMIT :limit""",
    )
    protected abstract suspend fun findClaimableAutomaticIds(
        scanId: String,
        now: Long,
        limit: Int,
    ): List<Long>

    @Transaction
    open suspend fun claimAutomaticBatch(
        now: Long,
        limit: Int,
        leaseToken: String,
        leaseDurationMs: Long,
        scanId: String,
    ): List<ThumbnailTaskEntity> {
        recoverExpiredTaskLeasesForLane(ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID, now)
        val ids = findClaimableAutomaticIds(scanId, now, limit)
        if (ids.isEmpty()) return emptyList()
        markClaimed(ids, leaseToken, now + leaseDurationMs, now)
        return getByLeaseToken(leaseToken)
    }

    @Query("SELECT * FROM thumbnail_tasks WHERE taskId = :taskId LIMIT 1")
    abstract suspend fun getById(taskId: Long): ThumbnailTaskEntity?

    @Query(
        """SELECT * FROM thumbnail_tasks WHERE filePath = :filePath AND mediaType = :mediaType
           AND sourceVersion = :sourceVersion AND sizeClass = :sizeClass
           AND formatVersion = :formatVersion LIMIT 1""",
    )
    abstract suspend fun getByIdentityExact(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
    ): ThumbnailTaskEntity?

    @Query("UPDATE thumbnail_tasks SET status='PENDING',leaseUntil=0,leaseToken=NULL,updatedAt=:now WHERE leaseToken=:leaseToken AND status='RUNNING'")
    abstract suspend fun releaseLease(leaseToken: String, now: Long): Int

    @Query("UPDATE thumbnail_tasks SET status='SUPERSEDED',leaseUntil=0,leaseToken=NULL,updatedAt=:now WHERE taskId=:taskId AND leaseToken=:leaseToken AND status='RUNNING'")
    protected abstract suspend fun markSuperseded(taskId: Long, leaseToken: String, now: Long): Int

    suspend fun supersedeClaimedTask(taskId: Long, leaseToken: String, now: Long = System.currentTimeMillis()) =
        markSuperseded(taskId, leaseToken, now) == 1

    @Query("UPDATE thumbnail_tasks SET status='DONE',leaseUntil=0,leaseToken=NULL,lastError=NULL,updatedAt=:now WHERE taskId=:taskId AND leaseToken=:leaseToken AND status='RUNNING'")
    abstract suspend fun markDone(taskId: Long, leaseToken: String, now: Long): Int

    @Query(
        """UPDATE thumbnail_tasks SET
             status = CASE WHEN attemptCount >= :maxAttempts THEN 'FAILED' ELSE 'PENDING' END,
             nextRetryAt = CASE WHEN attemptCount >= :maxAttempts THEN nextRetryAt ELSE :nextRetryAt END,
             leaseUntil=0,leaseToken=NULL,lastError=:error,updatedAt=:now
           WHERE taskId=:taskId AND leaseToken=:leaseToken AND status='RUNNING'""",
    )
    abstract suspend fun markFailed(
        taskId: Long,
        leaseToken: String,
        error: String,
        nextRetryAt: Long,
        maxAttempts: Int,
        now: Long,
    ): Int

    @Query("DELETE FROM thumbnail_tasks WHERE filePath IN (:paths)")
    abstract suspend fun deleteByPaths(paths: List<String>)

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status IN ('PENDING','RUNNING') AND scanId IS NULL")
    abstract suspend fun countInteractiveActive(): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status='PENDING' AND nextRetryAt<=:now AND scanId IS NULL")
    abstract suspend fun countInteractiveClaimable(now: Long): Int

    @Query(
        """SELECT COUNT(*) FROM thumbnail_tasks task
           WHERE task.status IN ('PENDING','RUNNING') AND task.scanId IS NOT NULL
             AND task.scanId IN (
               SELECT pipeline.activeRunId FROM library_pipeline pipeline
               WHERE pipeline.pipelineId = 'default'
                 AND pipeline.stage IN ('INITIAL_THUMBNAILS','INCREMENTAL_THUMBNAILS','REBUILD_THUMBNAILS')
             )""",
    )
    abstract suspend fun countAutomaticRunnable(): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status IN ('PENDING','RUNNING')")
    abstract suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status IN ('PENDING','RUNNING')")
    abstract suspend fun countRunnable(): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE scanId=:scanId AND status IN ('PENDING','RUNNING')")
    abstract suspend fun countActiveForScan(scanId: String): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE scanId=:scanId AND status='PENDING' AND nextRetryAt<=:now")
    abstract suspend fun countClaimableForScan(scanId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE scanId=:scanId")
    abstract suspend fun countTotalForScan(scanId: String): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE scanId=:scanId AND status IN ('DONE','FAILED','SUPERSEDED')")
    abstract suspend fun countTerminalForScan(scanId: String): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE scanId=:scanId AND status='FAILED'")
    abstract suspend fun countFailedForScan(scanId: String): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status='FAILED'")
    abstract suspend fun countFailed(): Int

    @Query("SELECT COUNT(*) FROM thumbnail_tasks WHERE status='FAILED'")
    abstract fun observeFailedCount(): Flow<Int>

    @Query(
        """UPDATE thumbnail_tasks SET status='PENDING',attemptCount=0,nextRetryAt=0,
             leaseUntil=0,leaseToken=NULL,lastError=NULL,priority=MAX(priority,:priority),
             scanId=NULL,updatedAt=:now
           WHERE filePath=:filePath AND mediaType=:mediaType AND sourceVersion=:sourceVersion
             AND sizeClass=:sizeClass AND formatVersion=:formatVersion AND status='DONE'
             AND EXISTS(SELECT 1 FROM media_items media WHERE media.filePath=:filePath
               AND media.mediaType=:mediaType AND media.isTrashed=0
               AND ('sv1|m=' || media.modifiedAtMs || '|s=' || media.fileSize || '|f=' ||
                    COALESCE(LOWER(media.fingerprintHead),'-'))=:sourceVersion)
             AND (NOT EXISTS(SELECT 1 FROM thumbnail_cache_entries cache
                  WHERE cache.filePath=:filePath AND cache.mediaType=:mediaType
                    AND cache.sourceVersion=:sourceVersion AND cache.sizeClass=:sizeClass
                    AND cache.formatVersion=:formatVersion)
               OR EXISTS(SELECT 1 FROM thumbnail_cache_entries cache
                  WHERE cache.filePath=:filePath AND cache.mediaType=:mediaType
                    AND cache.sourceVersion=:sourceVersion AND cache.sizeClass=:sizeClass
                    AND cache.formatVersion=:formatVersion
                    AND (cache.state IN ('EVICTED','DELETE_RETRY')
                         OR cache.path=:badPath)))""",
    )
    protected abstract suspend fun reactivateInfrastructureDoneInternal(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        badPath: String,
        priority: Int,
        now: Long,
    ): Int

    @Transaction
    open suspend fun reactivateInfrastructureDone(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        badPath: String,
        priority: Int,
        now: Long = System.currentTimeMillis(),
    ): InteractiveThumbnailEnqueueResult {
        val changed = reactivateInfrastructureDoneInternal(
            filePath,mediaType,sourceVersion,sizeClass,formatVersion,badPath,priority,now,
        )
        val state = recomputeLaneLevel(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID, now)
        return InteractiveThumbnailEnqueueResult(changed, state.deliveryState == ThumbnailLaneWakeEntity.STATE_PENDING, state.revision)
    }

    suspend fun reactivateMissingCacheDone(
        filePath: String,
        sizeClass: String,
        sourceVersion: String,
        mediaType: String,
        missingPath: String,
        priority: Int,
        now: Long = System.currentTimeMillis(),
    ) = reactivateInfrastructureDone(
        filePath,mediaType,sourceVersion,sizeClass,ThumbnailIdentity.CURRENT_FORMAT_VERSION,
        missingPath,priority,now,
    )

    @Query(
        """UPDATE thumbnail_tasks SET status='PENDING',attemptCount=0,nextRetryAt=0,
             leaseUntil=0,leaseToken=NULL,lastError=NULL,priority=:priority,scanId=NULL,updatedAt=:now
           WHERE status='FAILED'""",
    )
    protected abstract suspend fun retryAllFailedAsInteractive(priority: Int, now: Long): Int

    @Transaction
    open suspend fun retryAllFailedInteractive(priority: Int, now: Long = System.currentTimeMillis()): InteractiveThumbnailEnqueueResult {
        val changed = retryAllFailedAsInteractive(priority, now)
        val state = recomputeLaneLevel(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID, now)
        return InteractiveThumbnailEnqueueResult(changed, state.deliveryState == ThumbnailLaneWakeEntity.STATE_PENDING, state.revision)
    }

    @Query(
        """UPDATE media_items SET thumbnailPath=:path WHERE filePath=:filePath
           AND mediaType=:mediaType AND isTrashed=0
           AND ('sv1|m=' || modifiedAtMs || '|s=' || fileSize || '|f=' ||
                COALESCE(LOWER(fingerprintHead),'-'))=:sourceVersion""",
    )
    protected abstract suspend fun updateCanonicalGridPointer(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        path: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertPublishedCache(entry: ThumbnailCacheEntryEntity)

    /** Cache metadata, Grid pointer and exact DONE publish as one Room transaction. */
    @Transaction
    open suspend fun publishGeneratedThumbnail(
        task: ThumbnailTaskEntity,
        leaseToken: String,
        cacheEntry: ThumbnailCacheEntryEntity,
        now: Long,
    ): ThumbnailPublicationResult {
        val leased = getById(task.taskId)
        if (leased == null || leased.status != ThumbnailTaskEntity.STATUS_RUNNING ||
            leased.leaseToken != leaseToken
        ) return ThumbnailPublicationResult.LEASE_LOST
        val exact = leased.filePath == task.filePath && leased.mediaType == task.mediaType &&
            leased.sourceVersion == task.sourceVersion && leased.sizeClass == task.sizeClass &&
            leased.formatVersion == task.formatVersion &&
            cacheEntry.filePath == task.filePath && cacheEntry.mediaType == task.mediaType &&
            cacheEntry.sourceVersion == task.sourceVersion && cacheEntry.sizeClass == task.sizeClass &&
            cacheEntry.formatVersion == task.formatVersion && cacheEntry.state == ThumbnailCacheEntryEntity.STATE_READY
        if (!exact || !isCanonicalCurrent(task.filePath, task.mediaType, task.sourceVersion)) {
            markSuperseded(task.taskId,leaseToken,now)
            return ThumbnailPublicationResult.SUPERSEDED
        }
        if (task.sizeClass == ThumbnailIdentity.SIZE_GRID &&
            updateCanonicalGridPointer(task.filePath,task.mediaType,task.sourceVersion,cacheEntry.path) != 1
        ) {
            markSuperseded(task.taskId,leaseToken,now)
            return ThumbnailPublicationResult.SUPERSEDED
        }
        upsertPublishedCache(cacheEntry)
        check(markDone(task.taskId,leaseToken,now) == 1) { "thumbnail publication lease changed" }
        return ThumbnailPublicationResult.PUBLISHED
    }

    @Query("UPDATE thumbnail_tasks SET status='PENDING',leaseUntil=0,leaseToken=NULL,updatedAt=:now WHERE status='RUNNING' AND leaseUntil<=:now")
    abstract suspend fun recoverExpiredLeases(now: Long): Int

    @Query(
        """UPDATE thumbnail_tasks SET status='PENDING',leaseUntil=0,leaseToken=NULL,updatedAt=:now
           WHERE status='RUNNING' AND (leaseToken IS NULL OR leaseToken NOT GLOB :currentOwnerGlob)""",
    )
    protected abstract suspend fun recoverPreviousProcessTasks(
        currentOwnerGlob: String,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_lane_wake
           SET deliveryState='QUIESCENT',revision=revision+1,
               dispatchToken=NULL,dispatchLeaseUntil=0,runToken=NULL,runLeaseUntil=0,
               notBefore=0,nextDispatchAt=0,updatedAt=:now
           WHERE (deliveryState='RUNNING' AND (runToken IS NULL OR runToken NOT GLOB :currentOwnerGlob))
              OR (deliveryState='DISPATCHING' AND
                  (dispatchToken IS NULL OR dispatchToken NOT GLOB :currentOwnerGlob))""",
    )
    protected abstract suspend fun recoverPreviousProcessLanes(
        currentOwnerGlob: String,
        now: Long,
    ): Int

    /** Application 单次入口在任何 dispatcher/Worker 可见前调用；恢复不依赖自然过期。 */
    @Transaction
    open suspend fun recoverThumbnailProcessBoundary(processEpoch: String, now: Long): Int {
        require(processEpoch.isNotBlank() && '*' !in processEpoch && '?' !in processEpoch && '[' !in processEpoch)
        ensureLaneRows(now)
        val ownerGlob = "${processOwnerPrefix(processEpoch)}*"
        val recovered = recoverPreviousProcessTasks(ownerGlob,now) +
            recoverPreviousProcessLanes(ownerGlob,now)
        recomputeLaneLevel(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID,now)
        recomputeLaneLevel(ThumbnailLaneWakeEntity.AUTOMATIC_LANE_ID,now)
        return recovered
    }

    companion object {
        fun processOwnerPrefix(processEpoch: String): String = "process:$processEpoch|"
        fun processOwnedToken(processEpoch: String, token: String): String =
            processOwnerPrefix(processEpoch) + token
    }
}
