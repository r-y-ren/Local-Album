package com.renyxin.localalbum.data.backup

import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

/**
 * Edition-owned policy for regenerable work after a backup restore.
 *
 * The policy is built by the flavor composition root from the currently admitted automatic plan.
 * Imported face, semantic, OCR, scene, or quality rows never influence these flags.
 */
data class PostRestoreTaskPolicy(
    val profileId: String,
    val pipelineScope: String,
    val enqueueAutomaticAnalysis: Boolean,
    val enqueueThumbnails: Boolean = true,
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(pipelineScope.isNotBlank()) { "pipelineScope must not be blank" }
    }
}

/**
 * Seeds only the durable post-core handoff and reconciliation hint.
 *
 * Stage tasks are expanded later by EnhancementHandoffWorker from the current pipeline. This class
 * deliberately has no Stage IDs and never writes analysis_tasks, so an imported Full backup cannot
 * make Lite run Face, Semantic, or OCR work.
 */
class PostRestoreTaskSeeder(
    private val policy: PostRestoreTaskPolicy,
) {
    data class SeedResult(
        val scanId: String,
        val outboxCount: Int,
    )

    fun seed(
        db: SupportSQLiteDatabase,
        now: Long = System.currentTimeMillis(),
        scanId: String = "restore:${UUID.randomUUID()}",
    ): SeedResult {
        db.execSQL(
            """INSERT INTO scan_runs(
                scanId,generation,scanType,status,mediaStoreCompleted,fileSystemCompleted,
                startedAt,completedAt,errorType,indexAvailability,coreScanState,enhancementState,
                firstBatchCommittedAt,indexAvailableAt,coreCompletedAt,enhancementStartedAt,enhancementCompletedAt
            ) VALUES(?,?,'RECONCILIATION','COMPLETED',1,1,?,?,NULL,'PUBLISHED','COMPLETED','WAITING_FOR_CORE',?,?,?,0,0)""",
            arrayOf(scanId, now, now, now, now, now, now),
        )
        db.execSQL(
            """INSERT OR IGNORE INTO enhancement_outbox(
                scanId,profileId,filePath,sourceVersion,mediaType,pipelineScope,
                enqueueAnalysis,enqueueThumbnail,parentPath,status,attemptCount,nextRetryAt,
                leaseUntil,leaseToken,lastError,createdAt,updatedAt
            ) SELECT ?,?,filePath,modifiedAtMs||':'||fileSize,mediaType,?,
                CASE WHEN ? = 1 AND mediaType='IMAGE' THEN 1 ELSE 0 END,
                ?,parentPath,'PENDING',0,0,0,NULL,NULL,?,?
              FROM media_items WHERE isTrashed=0""",
            arrayOf(
                scanId,
                policy.profileId,
                policy.pipelineScope,
                if (policy.enqueueAutomaticAnalysis) 1 else 0,
                if (policy.enqueueThumbnails) 1 else 0,
                now,
                now,
            ),
        )
        val outboxCount = db.query(
            "SELECT COUNT(*) FROM enhancement_outbox WHERE scanId = ?",
            arrayOf(scanId),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        if (outboxCount == 0) {
            db.execSQL(
                "UPDATE scan_runs SET enhancementState='COMPLETED', enhancementCompletedAt=? WHERE scanId=?",
                arrayOf(now, scanId),
            )
        }
        db.execSQL(
            """INSERT OR REPLACE INTO media_change_events(
                eventKey,profileId,eventType,volumeName,mediaType,mediaStoreId,contentUri,
                flags,firstObservedAtMs,observedAtMs,status,attemptCount,nextAttemptAt,
                leaseUntil,leaseToken,lastError,createdAtMs,updatedAtMs
            ) VALUES(?,?,'RECONCILIATION',NULL,NULL,NULL,NULL,0,?,?,'PENDING',0,0,0,NULL,NULL,?,?)""",
            arrayOf("${policy.profileId}:reconciliation", policy.profileId, now, now, now, now),
        )
        return SeedResult(scanId = scanId, outboxCount = outboxCount)
    }
}
