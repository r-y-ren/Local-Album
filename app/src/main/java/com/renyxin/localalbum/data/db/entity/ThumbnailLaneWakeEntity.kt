package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每 lane 一行的 level-triggered 投递状态。数据库保存工作 level 与租约事实；WorkManager
 * 仅交付固定 pump。revision 在每次 level 提升/投递状态转换时单调递增。
 */
@Entity(tableName = "thumbnail_lane_wake")
data class ThumbnailLaneWakeEntity(
    @PrimaryKey val laneId: String,
    val deliveryState: String = STATE_QUIESCENT,
    val revision: Long = 0L,
    val dispatchToken: String? = null,
    val dispatchLeaseUntil: Long = 0L,
    val runToken: String? = null,
    val runLeaseUntil: Long = 0L,
    val notBefore: Long = 0L,
    val enqueueAttemptCount: Int = 0,
    val nextDispatchAt: Long = 0L,
    val lastDispatchError: String? = null,
    val updatedAt: Long = 0L,
) {
    companion object {
        const val INTERACTIVE_LANE_ID = "interactive"
        const val AUTOMATIC_LANE_ID = "automatic"

        const val STATE_QUIESCENT = "QUIESCENT"
        const val STATE_PENDING = "PENDING"
        const val STATE_DELAYED = "DELAYED"
        const val STATE_DISPATCHING = "DISPATCHING"
        const val STATE_ENQUEUED = "ENQUEUED"
        const val STATE_RUNNING = "RUNNING"

        fun initial(laneId: String, now: Long = 0L) = ThumbnailLaneWakeEntity(
            laneId = laneId,
            updatedAt = now,
        )
    }
}
