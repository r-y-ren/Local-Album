package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * v24 缩略图磁盘缓存元数据。
 *
 * 主键绑定源路径、尺寸档与源版本；源文件变化后旧版本不会被命中，并可由 LRU 回收。
 * lastAccessAt 是唯一访问时间依据，禁止用派生文件 mtime 近似。
 */
@Entity(
    tableName = "thumbnail_cache_entries",
    primaryKeys = ["filePath", "sizeClass", "sourceVersion"],
    indices = [
        Index(value = ["state", "lastAccessAt", "filePath", "sizeClass", "sourceVersion"]),
        Index(value = ["filePath", "sizeClass", "sourceVersion", "state"]),
        Index(value = ["leaseUntil"]),
        Index(value = ["path"], unique = true),
    ],
)
data class ThumbnailCacheEntryEntity(
    val filePath: String,
    val sizeClass: String,
    val sourceVersion: String,
    val path: String,
    val byteSize: Long,
    val lastAccessAt: Long,
    val createdAt: Long,
    val state: String = STATE_READY,
    val leaseUntil: Long = 0L,
    val deleteAttemptCount: Int = 0,
) {
    companion object {
        const val STATE_GENERATING = "GENERATING"
        const val STATE_READY = "READY"
        const val STATE_DELETE_RETRY = "DELETE_RETRY"
    }
}
