package com.renyxin.localalbum.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.renyxin.localalbum.data.db.entity.ImportEmbeddingStagingEntity
import com.renyxin.localalbum.data.db.entity.ImportFaceStagingEntity
import com.renyxin.localalbum.data.db.entity.ImportFtsStagingEntity
import com.renyxin.localalbum.data.db.entity.ImportMediaStagingEntity

/**
 * P2-A 导入隔离区 DAO。
 * 每个批次对应一次 Room 短事务；生产表切换由 AppDatabase 在单独最终事务中执行。
 */
@Dao
interface ImportStagingDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMedia(items: List<ImportMediaStagingEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFaces(items: List<ImportFaceStagingEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEmbeddings(items: List<ImportEmbeddingStagingEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertFts(items: List<ImportFtsStagingEntity>)

    @Query("SELECT COUNT(*) FROM import_media_staging WHERE generation = :generation")
    suspend fun mediaCount(generation: String): Int

    @Query("SELECT COUNT(*) FROM import_face_staging WHERE generation = :generation")
    suspend fun faceCount(generation: String): Int

    @Query("SELECT COUNT(*) FROM import_embedding_staging WHERE generation = :generation")
    suspend fun embeddingCount(generation: String): Int

    @Query("SELECT COUNT(*) FROM import_fts_staging WHERE generation = :generation")
    suspend fun ftsCount(generation: String): Int

    @Query("DELETE FROM import_media_staging WHERE generation = :generation")
    suspend fun clearMedia(generation: String)

    @Query("DELETE FROM import_face_staging WHERE generation = :generation")
    suspend fun clearFaces(generation: String)

    @Query("DELETE FROM import_embedding_staging WHERE generation = :generation")
    suspend fun clearEmbeddings(generation: String)

    @Query("DELETE FROM import_fts_staging WHERE generation = :generation")
    suspend fun clearFts(generation: String)

    @Query("DELETE FROM import_media_staging")
    suspend fun clearAllMedia()

    @Query("DELETE FROM import_face_staging")
    suspend fun clearAllFaces()

    @Query("DELETE FROM import_embedding_staging")
    suspend fun clearAllEmbeddings()

    @Query("DELETE FROM import_fts_staging")
    suspend fun clearAllFts()
}
