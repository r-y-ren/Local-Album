package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.MediaChangeEventEntity
import com.renyxin.localalbum.data.db.entity.MediaStoreReferenceEntity

@Dao
abstract class MediaChangeDao {
    @Query("SELECT * FROM media_change_events WHERE eventKey = :eventKey LIMIT 1")
    protected abstract suspend fun getEvent(eventKey: String): MediaChangeEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replaceEvent(event: MediaChangeEventEntity)

    /** A new notification always wins over an in-flight lease for the same stable key. */
    @Transaction
    open suspend fun record(event: MediaChangeEventEntity) {
        val existing = getEvent(event.eventKey)
        val newest = if (existing == null || event.observedAtMs >= existing.observedAtMs) event else existing
        replaceEvent(
            newest.copy(
                firstObservedAtMs = minOf(
                    existing?.firstObservedAtMs ?: event.firstObservedAtMs,
                    event.firstObservedAtMs,
                ),
                observedAtMs = maxOf(existing?.observedAtMs ?: event.observedAtMs, event.observedAtMs),
                flags = (existing?.flags ?: 0) or event.flags,
                status = MediaChangeEventEntity.STATUS_PENDING,
                attemptCount = 0,
                nextAttemptAt = 0L,
                leaseUntil = 0L,
                leaseToken = null,
                lastError = null,
                createdAtMs = existing?.createdAtMs ?: event.createdAtMs,
                updatedAtMs = maxOf(existing?.updatedAtMs ?: event.updatedAtMs, event.observedAtMs),
            ),
        )
    }

    @Transaction
    open suspend fun recordAll(events: List<MediaChangeEventEntity>) {
        events.forEach { record(it) }
    }

    @Query(
        """UPDATE media_change_events
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAtMs = :now
           WHERE status = 'LEASED' AND leaseUntil <= :now""",
    )
    abstract suspend fun releaseExpiredLeases(now: Long): Int

    @Query(
        """SELECT eventKey FROM media_change_events
           WHERE profileId = :profileId AND eventType = 'MEDIA'
             AND status = 'PENDING' AND nextAttemptAt <= :now
           ORDER BY observedAtMs ASC, eventKey ASC
           LIMIT :limit""",
    )
    protected abstract suspend fun findClaimableKeys(
        profileId: String,
        now: Long,
        limit: Int,
    ): List<String>

    @Query(
        """UPDATE media_change_events
           SET status = 'LEASED', leaseUntil = :leaseUntil, leaseToken = :leaseToken,
               attemptCount = attemptCount + 1, updatedAtMs = :now
           WHERE eventKey IN (:eventKeys) AND status = 'PENDING' AND nextAttemptAt <= :now""",
    )
    protected abstract suspend fun leaseEvents(
        eventKeys: List<String>,
        now: Long,
        leaseUntil: Long,
        leaseToken: String,
    ): Int

    @Query(
        """SELECT * FROM media_change_events
           WHERE leaseToken = :leaseToken AND status = 'LEASED'
           ORDER BY observedAtMs ASC, eventKey ASC""",
    )
    protected abstract suspend fun getLeased(leaseToken: String): List<MediaChangeEventEntity>

    @Transaction
    open suspend fun claimBatch(
        profileId: String,
        now: Long,
        limit: Int,
        leaseDurationMs: Long,
        leaseToken: String,
    ): List<MediaChangeEventEntity> {
        require(limit in 1..MAX_CLAIM_SIZE)
        releaseExpiredLeases(now)
        val keys = findClaimableKeys(profileId, now, limit)
        if (keys.isEmpty()) return emptyList()
        leaseEvents(keys, now, now + leaseDurationMs, leaseToken)
        return getLeased(leaseToken)
    }

    @Query(
        """UPDATE media_change_events
           SET leaseUntil = :leaseUntil, updatedAtMs = :now
           WHERE leaseToken = :leaseToken AND status = 'LEASED'""",
    )
    abstract suspend fun renewLease(
        leaseToken: String,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Query(
        """UPDATE media_change_events
           SET leaseUntil = :leaseUntil, updatedAtMs = :now
           WHERE leaseToken IN (:leaseTokens) AND status = 'LEASED'""",
    )
    abstract suspend fun renewLeases(
        leaseTokens: List<String>,
        leaseUntil: Long,
        now: Long,
    ): Int

    @Query("DELETE FROM media_change_events WHERE leaseToken = :leaseToken AND status = 'LEASED'")
    protected abstract suspend fun deleteLeasedEvents(leaseToken: String): Int

    @Query(
        """DELETE FROM media_store_references
           WHERE stableKey IN (:deletedStableKeys)
             AND stableKey IN (SELECT eventKey FROM media_change_events
                 WHERE leaseToken = :leaseToken AND status = 'LEASED')""",
    )
    protected abstract suspend fun deleteLeasedReferences(
        leaseToken: String,
        deletedStableKeys: List<String>,
    ): Int

    @Transaction
    open suspend fun acknowledgeLease(
        leaseToken: String,
        deletedStableKeys: List<String> = emptyList(),
    ): Int {
        if (deletedStableKeys.isNotEmpty()) {
            deleteLeasedReferences(leaseToken, deletedStableKeys)
        }
        return deleteLeasedEvents(leaseToken)
    }

    @Query(
        """UPDATE media_change_events
           SET status = 'PENDING', nextAttemptAt = :nextAttemptAt, leaseUntil = 0,
               leaseToken = NULL, lastError = :error, updatedAtMs = :now
           WHERE leaseToken = :leaseToken AND status = 'LEASED'""",
    )
    abstract suspend fun failLease(
        leaseToken: String,
        nextAttemptAt: Long,
        error: String,
        now: Long,
    ): Int

    @Query(
        """UPDATE media_change_events
           SET status = 'PENDING', leaseUntil = 0, leaseToken = NULL, updatedAtMs = :now
           WHERE leaseToken = :leaseToken AND status = 'LEASED'""",
    )
    abstract suspend fun releaseLease(leaseToken: String, now: Long): Int

    @Query(
        """SELECT EXISTS(SELECT 1 FROM media_change_events
           WHERE profileId = :profileId AND eventType = 'RECONCILIATION'
             AND status IN ('PENDING', 'LEASED'))""",
    )
    abstract suspend fun hasReconciliationHint(profileId: String): Boolean

    /** No configured roots means the reconciliation domain is explicitly empty. */
    @Query(
        """DELETE FROM media_change_events
           WHERE profileId = :profileId AND eventType = 'RECONCILIATION'""",
    )
    abstract suspend fun clearReconciliationHint(profileId: String): Int

    /**
     * Ambiguous provider notifications are a rebuild advisory, not permission to traverse every
     * root. The serialized pipeline persists NEEDS_REBUILD before clearing these hints.
     */
    @Query(
        """SELECT MAX(observedAtMs) FROM media_change_events
           WHERE profileId = :profileId AND eventType = 'RECONCILIATION'
             AND status IN ('PENDING', 'LEASED')""",
    )
    abstract suspend fun latestReconciliationHintAt(profileId: String): Long?

    /** Clears only notifications observed before a successful reconciliation started. */
    @Query(
        """DELETE FROM media_change_events
           WHERE profileId = :profileId AND observedAtMs <= :observedBeforeOrAt""",
    )
    abstract suspend fun acknowledgeEventsThrough(
        profileId: String,
        observedBeforeOrAt: Long,
    ): Int

    @Query("DELETE FROM media_change_events WHERE profileId = :profileId")
    abstract suspend fun clearEvents(profileId: String): Int

    @Query("DELETE FROM media_store_references WHERE profileId = :profileId")
    abstract suspend fun clearReferences(profileId: String): Int

    @Query(
        """SELECT EXISTS(SELECT 1 FROM media_change_events
           WHERE profileId = :profileId AND eventType = 'MEDIA'
             AND status IN ('PENDING', 'LEASED'))""",
    )
    abstract suspend fun hasOutstandingMediaChanges(profileId: String): Boolean

    @Query(
        """SELECT COUNT(*) FROM media_change_events
           WHERE profileId = :profileId AND eventType = 'MEDIA'
             AND status IN ('PENDING', 'LEASED')""",
    )
    abstract suspend fun countOutstandingMediaChanges(profileId: String): Int

    @Query(
        """SELECT EXISTS(SELECT 1 FROM media_change_events
           WHERE profileId = :profileId AND eventType = 'MEDIA'
             AND status = 'PENDING' AND nextAttemptAt <= :now)""",
    )
    protected abstract suspend fun hasClaimableMediaEvent(profileId: String, now: Long): Boolean

    /** Excludes live leases and retry-delayed rows so scan continuation cannot busy-loop. */
    @Transaction
    open suspend fun hasClaimableMediaChanges(profileId: String, now: Long): Boolean {
        releaseExpiredLeases(now)
        return hasClaimableMediaEvent(profileId, now)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertReferences(references: List<MediaStoreReferenceEntity>)

    @Query("SELECT * FROM media_store_references WHERE stableKey IN (:stableKeys)")
    abstract suspend fun getReferences(stableKeys: List<String>): List<MediaStoreReferenceEntity>

    @Query(
        """SELECT * FROM media_store_references
           WHERE profileId = :profileId AND volumeName = :volumeName
             AND mediaType = :mediaType AND mediaStoreId = :mediaStoreId
           LIMIT 1""",
    )
    abstract suspend fun getReference(
        profileId: String,
        volumeName: String,
        mediaType: String,
        mediaStoreId: Long,
    ): MediaStoreReferenceEntity?

    @Query("DELETE FROM media_store_references WHERE profileId = :profileId AND filePath IN (:paths)")
    abstract suspend fun deleteReferencesByPaths(profileId: String, paths: List<String>): Int

    @Query(
        """SELECT filePath FROM media_store_references
           WHERE profileId = :profileId AND scanGeneration != :generation
           ORDER BY filePath ASC LIMIT :limit""",
    )
    abstract suspend fun getReferencePathsOutsideGeneration(
        profileId: String,
        generation: Long,
        limit: Int,
    ): List<String>

    @Query(
        """DELETE FROM media_store_references
           WHERE profileId = :profileId AND scanGeneration != :generation
             AND filePath IN (:paths)""",
    )
    abstract suspend fun deleteReferencesOutsideGeneration(
        profileId: String,
        generation: Long,
        paths: List<String>,
    ): Int

    companion object {
        const val MAX_CLAIM_SIZE = 500
    }
}
