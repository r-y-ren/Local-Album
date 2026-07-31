package com.renyxin.localalbum.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 最后一次完整提交的目录级相册快照。
 *
 * 扫描过程中的批次写入不会修改此表；只有扫描完整结束后才整体替换，
 * 因而进程被杀、扫描取消或来源不可用时仍可恢复上一份完整相册结构。
 */
@Entity(
    tableName = "album_snapshot_directories",
    indices = [Index(value = ["snapshotVersion"])],
)
data class AlbumSnapshotDirectoryEntity(
    @PrimaryKey val directoryPath: String,
    val mediaCount: Int,
    val latestCapturedAtMs: Long,
    val coverThumbnailPath: String?,
    val coverFilePath: String?,
    val snapshotVersion: Long,
)

/** 当前已原子发布的相册快照版本及最近成功同步时间。 */
@Entity(tableName = "album_snapshot_meta")
data class AlbumSnapshotMetaEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val activeVersion: Long,
    val updatedAtMs: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
