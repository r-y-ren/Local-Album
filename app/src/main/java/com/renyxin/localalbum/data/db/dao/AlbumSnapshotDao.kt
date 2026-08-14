package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.AlbumSnapshotDirectoryEntity
import com.renyxin.localalbum.data.db.entity.AlbumSnapshotMetaEntity

@Dao
abstract class AlbumSnapshotDao {
    @Query("SELECT * FROM album_snapshot_directories ORDER BY latestCapturedAtMs DESC")
    abstract suspend fun getDirectories(): List<AlbumSnapshotDirectoryEntity>

    @Query("SELECT * FROM album_snapshot_meta WHERE id = 1 LIMIT 1")
    abstract suspend fun getMeta(): AlbumSnapshotMetaEntity?

    @Query("DELETE FROM album_snapshot_directories WHERE directoryPath IN (:directoryPaths)")
    protected abstract suspend fun deleteDirectories(directoryPaths: List<String>)

    @Query("DELETE FROM album_snapshot_directories")
    protected abstract suspend fun clearDirectories()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDirectories(entries: List<AlbumSnapshotDirectoryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun putMeta(meta: AlbumSnapshotMetaEntity)

    /**
     * 整体替换并发布快照。Room 事务保证崩溃时只能看到旧版本或完整新版本，
     * 不会暴露只写入一部分目录的中间状态。
     */
    @Transaction
    open suspend fun replaceSnapshot(
        entries: List<AlbumSnapshotDirectoryEntity>,
        version: Long,
        updatedAtMs: Long,
    ) {
        clearDirectories()
        insertDirectories(entries)
        putMeta(
            AlbumSnapshotMetaEntity(
                activeVersion = version,
                updatedAtMs = updatedAtMs,
            )
        )
    }

    /** Atomically replaces only directories affected by one bounded changed-set batch. */
    @Transaction
    open suspend fun patchSnapshot(
        affectedDirectoryPaths: List<String>,
        entries: List<AlbumSnapshotDirectoryEntity>,
        version: Long,
        updatedAtMs: Long,
    ) {
        val affected = affectedDirectoryPaths.distinct()
        if (affected.isNotEmpty()) deleteDirectories(affected)
        if (entries.isNotEmpty()) insertDirectories(entries)
        putMeta(
            AlbumSnapshotMetaEntity(
                activeVersion = version,
                updatedAtMs = updatedAtMs,
            ),
        )
    }
}
