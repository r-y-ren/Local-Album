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
           WHERE filePath = :filePath AND mediaType = :mediaType
             AND sourceVersion = :sourceVersion AND sizeClass = :sizeClass
             AND formatVersion = :formatVersion AND state = 'READY' LIMIT 1""",
    )
    suspend fun getReadyExact(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
    ): ThumbnailCacheEntryEntity?

    suspend fun getReady(
        filePath: String,
        sizeClass: String,
        sourceVersion: String,
        mediaType: String = com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity.MEDIA_IMAGE,
        formatVersion: Int = com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity.CURRENT_FORMAT_VERSION,
    ) = getReadyExact(filePath,mediaType,sourceVersion,sizeClass,formatVersion)

    @Query(
        """SELECT * FROM thumbnail_cache_entries
           WHERE filePath = :filePath AND mediaType = :mediaType
             AND sourceVersion = :sourceVersion AND sizeClass = :sizeClass
             AND formatVersion = :formatVersion LIMIT 1""",
    )
    suspend fun getExactIdentity(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
    ): ThumbnailCacheEntryEntity?

    suspend fun getExact(
        filePath:String,mediaType:String,sourceVersion:String,sizeClass:String,formatVersion:Int,
    ) = getExactIdentity(filePath,mediaType,sourceVersion,sizeClass,formatVersion)

    /** 仅当上次访问早于阈值时写库，Compose 重组不会持续放大写事务。 */
    @Query(
        """UPDATE thumbnail_cache_entries SET lastAccessAt = :now
           WHERE filePath = :filePath AND mediaType = :mediaType
             AND sourceVersion = :sourceVersion AND sizeClass = :sizeClass
             AND formatVersion = :formatVersion
             AND state = 'READY' AND lastAccessAt <= :touchBefore""",
    )
    suspend fun touchThrottled(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        now: Long,
        touchBefore: Long,
    ): Int

    @Query(
        "SELECT COALESCE(SUM(byteSize), 0) FROM thumbnail_cache_entries " +
            "WHERE state IN ('READY','EVICTING','DELETE_RETRY')",
    )
    suspend fun totalManagedBytes(): Long

    /** 完整身份 keyset LRU；主页 snapshot/canonical 正在引用的 Grid 永不进入候选。 */
    @Query(
        """SELECT * FROM thumbnail_cache_entries
           WHERE state IN ('READY','DELETE_RETRY')
             AND leaseUntil <= :now AND lastAccessAt <= :protectedBefore
             AND NOT (
                 sizeClass = 'grid' AND (
                     EXISTS(SELECT 1 FROM home_media_snapshot home
                            WHERE home.filePath = thumbnail_cache_entries.filePath
                              AND home.mediaType = thumbnail_cache_entries.mediaType
                              AND home.modifiedAtMs =
                                  CAST(substr(thumbnail_cache_entries.sourceVersion,
                                      instr(thumbnail_cache_entries.sourceVersion,'m=') + 2,
                                      instr(thumbnail_cache_entries.sourceVersion,'|s=') -
                                      instr(thumbnail_cache_entries.sourceVersion,'m=') - 2) AS INTEGER)
                              AND home.thumbnailState = 'READY'
                              AND home.thumbnailPath = thumbnail_cache_entries.path)
                     OR EXISTS(SELECT 1 FROM media_items media
                               WHERE media.filePath = thumbnail_cache_entries.filePath
                                 AND media.mediaType = thumbnail_cache_entries.mediaType
                                 AND media.isTrashed = 0
                                 AND media.thumbnailPath = thumbnail_cache_entries.path)
                 )
             )
             AND (
                 lastAccessAt > :afterAccess OR
                 (lastAccessAt = :afterAccess AND filePath > :afterPath) OR
                 (lastAccessAt = :afterAccess AND filePath = :afterPath AND mediaType > :afterMediaType) OR
                 (lastAccessAt = :afterAccess AND filePath = :afterPath AND mediaType = :afterMediaType AND sourceVersion > :afterSourceVersion) OR
                 (lastAccessAt = :afterAccess AND filePath = :afterPath AND mediaType = :afterMediaType AND sourceVersion = :afterSourceVersion AND sizeClass > :afterSizeClass) OR
                 (lastAccessAt = :afterAccess AND filePath = :afterPath AND mediaType = :afterMediaType AND sourceVersion = :afterSourceVersion AND sizeClass = :afterSizeClass AND formatVersion > :afterFormatVersion)
             )
           ORDER BY lastAccessAt ASC,filePath ASC,mediaType ASC,sourceVersion ASC,sizeClass ASC,formatVersion ASC
           LIMIT :limit""",
    )
    suspend fun evictionCandidatesAfter(
        now: Long,
        protectedBefore: Long,
        afterAccess: Long,
        afterPath: String,
        afterMediaType: String,
        afterSourceVersion: String,
        afterSizeClass: String,
        afterFormatVersion: Int,
        limit: Int,
    ): List<ThumbnailCacheEntryEntity>

    @Query(
        """UPDATE thumbnail_cache_entries
           SET state = 'EVICTING', leaseUntil = :leaseUntil
           WHERE filePath = :filePath AND mediaType = :mediaType
             AND sourceVersion = :sourceVersion AND sizeClass = :sizeClass
             AND formatVersion = :formatVersion AND path = :path
             AND state IN ('READY','DELETE_RETRY') AND leaseUntil <= :now
             AND NOT EXISTS(SELECT 1 FROM home_media_snapshot home
                            WHERE :sizeClass = 'grid' AND home.filePath = :filePath
                              AND home.thumbnailState = 'READY' AND home.thumbnailPath = :path)
             AND NOT EXISTS(SELECT 1 FROM media_items media
                            WHERE :sizeClass = 'grid' AND media.filePath = :filePath
                              AND media.isTrashed = 0 AND media.thumbnailPath = :path)""",
    )
    suspend fun beginEviction(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        path: String,
        now: Long,
        leaseUntil: Long,
    ): Int

    /** 保留 EVICTED tombstone，使 DONE 可被基础设施 repair 受控重开。 */
    @Query(
        """UPDATE thumbnail_cache_entries
           SET state = 'EVICTED', byteSize = 0, leaseUntil = 0
           WHERE filePath = :filePath AND mediaType = :mediaType
             AND sourceVersion = :sourceVersion AND sizeClass = :sizeClass
             AND formatVersion = :formatVersion AND path = :path
             AND state = 'EVICTING'""",
    )
    suspend fun finishEviction(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        path: String,
    ): Int

    @Query(
        """UPDATE thumbnail_cache_entries
           SET state = 'DELETE_RETRY', leaseUntil = 0,
               deleteAttemptCount = deleteAttemptCount + 1
           WHERE filePath = :filePath AND mediaType = :mediaType
             AND sourceVersion = :sourceVersion AND sizeClass = :sizeClass
             AND formatVersion = :formatVersion AND path = :path
             AND state IN ('READY','EVICTING','DELETE_RETRY')""",
    )
    suspend fun markDeleteRetry(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        path: String,
    ): Int

    @Query("SELECT * FROM thumbnail_cache_entries WHERE filePath IN (:paths)")
    suspend fun getByPaths(paths: List<String>): List<ThumbnailCacheEntryEntity>

    @Query("DELETE FROM thumbnail_cache_entries WHERE filePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query(
        """DELETE FROM thumbnail_cache_entries
           WHERE filePath=:filePath AND mediaType=:mediaType AND sourceVersion=:sourceVersion
             AND sizeClass=:sizeClass AND formatVersion=:formatVersion AND path=:path""",
    )
    suspend fun deleteExact(
        filePath: String,
        mediaType: String,
        sourceVersion: String,
        sizeClass: String,
        formatVersion: Int,
        path: String,
    ): Int

    @Query(
        """SELECT path FROM thumbnail_cache_entries
           WHERE state = 'READY' AND path IN (:paths)""",
    )
    suspend fun retainManagedReadyPaths(paths: List<String>): List<String>
}
