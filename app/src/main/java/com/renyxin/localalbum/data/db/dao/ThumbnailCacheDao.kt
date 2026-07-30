package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity

@Dao
interface ThumbnailCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ThumbnailCacheEntryEntity)

    @Query(
        """SELECT * FROM thumbnail_cache_entries
           WHERE filePath = :filePath AND sizeClass = :sizeClass AND sourceVersion = :sourceVersion
             AND state = 'READY' LIMIT 1""",
    )
    suspend fun getReady(filePath: String, sizeClass: String, sourceVersion: String): ThumbnailCacheEntryEntity?

    /** 仅当上次访问早于阈值时写库，Compose 重组不会持续放大写事务。 */
    @Query(
        """UPDATE thumbnail_cache_entries SET lastAccessAt = :now
           WHERE filePath = :filePath AND sizeClass = :sizeClass AND sourceVersion = :sourceVersion
             AND state = 'READY' AND lastAccessAt <= :touchBefore""",
    )
    suspend fun touchThrottled(
        filePath: String,
        sizeClass: String,
        sourceVersion: String,
        now: Long,
        touchBefore: Long,
    ): Int

    @Query("SELECT COALESCE(SUM(byteSize), 0) FROM thumbnail_cache_entries WHERE state IN ('READY', 'DELETE_RETRY')")
    suspend fun totalManagedBytes(): Long

    /**
     * 稳定 keyset LRU：只返回 READY/待重试且不在租约、最近触摸保护窗内的记录。
     * (lastAccessAt,filePath,sizeClass,sourceVersion) 组成完整游标，避免 OFFSET 放大。
     */
    @Query(
        """SELECT * FROM thumbnail_cache_entries
           WHERE state IN ('READY', 'DELETE_RETRY')
             AND leaseUntil <= :now AND lastAccessAt <= :protectedBefore
             AND (
                 lastAccessAt > :afterAccess OR
                 (lastAccessAt = :afterAccess AND filePath > :afterPath) OR
                 (lastAccessAt = :afterAccess AND filePath = :afterPath AND sizeClass > :afterSizeClass) OR
                 (lastAccessAt = :afterAccess AND filePath = :afterPath AND sizeClass = :afterSizeClass AND sourceVersion > :afterSourceVersion)
             )
           ORDER BY lastAccessAt ASC, filePath ASC, sizeClass ASC, sourceVersion ASC
           LIMIT :limit""",
    )
    suspend fun evictionCandidatesAfter(
        now: Long,
        protectedBefore: Long,
        afterAccess: Long,
        afterPath: String,
        afterSizeClass: String,
        afterSourceVersion: String,
        limit: Int,
    ): List<ThumbnailCacheEntryEntity>

    @Query(
        """DELETE FROM thumbnail_cache_entries
           WHERE filePath = :filePath AND sizeClass = :sizeClass AND sourceVersion = :sourceVersion
             AND path = :path AND state IN ('READY', 'DELETE_RETRY') AND leaseUntil <= :now""",
    )
    suspend fun deleteAfterFileRemoved(
        filePath: String,
        sizeClass: String,
        sourceVersion: String,
        path: String,
        now: Long,
    ): Int

    @Query(
        """UPDATE thumbnail_cache_entries
           SET state = 'DELETE_RETRY', deleteAttemptCount = deleteAttemptCount + 1
           WHERE filePath = :filePath AND sizeClass = :sizeClass AND sourceVersion = :sourceVersion
             AND path = :path AND state IN ('READY', 'DELETE_RETRY')""",
    )
    suspend fun markDeleteRetry(filePath: String, sizeClass: String, sourceVersion: String, path: String): Int

    @Query("SELECT * FROM thumbnail_cache_entries WHERE filePath IN (:paths)")
    suspend fun getByPaths(paths: List<String>): List<ThumbnailCacheEntryEntity>

    @Query("DELETE FROM thumbnail_cache_entries WHERE filePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)
}
