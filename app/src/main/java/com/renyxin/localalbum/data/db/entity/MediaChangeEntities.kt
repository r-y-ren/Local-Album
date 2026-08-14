package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable MediaStore notification waiting to be resolved by a bounded incremental scan. */
@Entity(
    tableName = "media_change_events",
    indices = [
        Index(value = ["profileId", "status", "observedAtMs"]),
        Index(value = ["leaseToken"]),
        Index(value = ["eventType", "status"]),
    ],
)
data class MediaChangeEventEntity(
    @PrimaryKey val eventKey: String,
    val profileId: String,
    val eventType: String,
    val volumeName: String? = null,
    val mediaType: String? = null,
    val mediaStoreId: Long? = null,
    val contentUri: String? = null,
    val flags: Int = 0,
    val firstObservedAtMs: Long,
    val observedAtMs: Long,
    val status: String = STATUS_PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAt: Long = 0L,
    val leaseUntil: Long = 0L,
    val leaseToken: String? = null,
    val lastError: String? = null,
    val createdAtMs: Long = observedAtMs,
    val updatedAtMs: Long = observedAtMs,
) {
    companion object {
        const val TYPE_MEDIA = "MEDIA"
        const val TYPE_RECONCILIATION = "RECONCILIATION"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_LEASED = "LEASED"
    }
}

/** Stable MediaStore identity to current file-path mapping used for delete and move resolution. */
@Entity(
    tableName = "media_store_references",
    indices = [
        Index(value = ["profileId", "volumeName", "mediaType", "mediaStoreId"], unique = true),
        Index(value = ["profileId", "filePath"], unique = true),
    ],
)
data class MediaStoreReferenceEntity(
    @PrimaryKey val stableKey: String,
    val profileId: String,
    val volumeName: String,
    val mediaType: String,
    val mediaStoreId: Long,
    val contentUri: String,
    val filePath: String,
    val sourceVersion: String,
    val observedAtMs: Long,
    val scanGeneration: Long = 0L,
)
