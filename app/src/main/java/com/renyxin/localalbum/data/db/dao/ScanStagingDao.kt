package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.ScanStagingEntity

@Dao
abstract class ScanStagingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIgnore(entries: List<ScanStagingEntity>): List<Long>

    @Query(
        """UPDATE scan_staging
           SET sourceMask = sourceMask | :sourceMask,
               mediaStoreJson = COALESCE(:mediaStoreJson, mediaStoreJson),
               fileSystemJson = COALESCE(:fileSystemJson, fileSystemJson)
           WHERE scanId = :scanId AND filePath = :filePath""",
    )
    protected abstract suspend fun mergeExisting(
        scanId: String,
        filePath: String,
        sourceMask: Int,
        mediaStoreJson: String?,
        fileSystemJson: String?,
    ): Int

    /** 批次有界；冲突路径用后到来源 payload 覆盖并合并 sourceMask。 */
    @Transaction
    open suspend fun mergeBatch(entries: List<ScanStagingEntity>) {
        if (entries.isEmpty()) return
        val inserted = insertIgnore(entries)
        entries.forEachIndexed { index, entry ->
            if (inserted[index] == -1L) {
                mergeExisting(
                    entry.scanId,
                    entry.filePath,
                    entry.sourceMask,
                    entry.mediaStoreJson,
                    entry.fileSystemJson,
                )
            }
        }
    }

    @Query(
        """SELECT * FROM scan_staging
           WHERE scanId = :scanId AND filePath > :afterPath
           ORDER BY filePath ASC LIMIT :limit""",
    )
    abstract suspend fun getAfter(scanId: String, afterPath: String, limit: Int): List<ScanStagingEntity>

    @Query("SELECT * FROM scan_staging WHERE scanId = :scanId AND filePath IN (:paths)")
    abstract suspend fun getByPaths(scanId: String, paths: List<String>): List<ScanStagingEntity>

    @Query("SELECT COUNT(*) FROM scan_staging WHERE scanId = :scanId")
    abstract suspend fun count(scanId: String): Int

    @Query("DELETE FROM scan_staging WHERE scanId = :scanId")
    abstract suspend fun deleteByScanId(scanId: String): Int
}
