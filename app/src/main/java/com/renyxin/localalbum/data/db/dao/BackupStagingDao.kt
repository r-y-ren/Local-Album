package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.renyxin.localalbum.data.db.entity.BackupImportStagingEntity

@Dao
interface BackupStagingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(rows: List<BackupImportStagingEntity>)

    @Query("SELECT COUNT(*) FROM backup_import_staging WHERE generation = :generation AND entryName = :entry")
    suspend fun count(generation: String, entry: String): Int

    /** stable keyset，禁止 getAll；最终切换也以有限页读取。 */
    @Query("""
        SELECT * FROM backup_import_staging
        WHERE generation = :generation AND entryName = :entry AND lineNumber > :afterLine
        ORDER BY lineNumber LIMIT :limit
    """)
    suspend fun getAfter(generation: String, entry: String, afterLine: Long, limit: Int): List<BackupImportStagingEntity>

    @Query("DELETE FROM backup_import_staging WHERE generation = :generation")
    suspend fun clearGeneration(generation: String)

    @Query("DELETE FROM backup_import_staging")
    suspend fun clearAll()
}
