package com.renyxin.localalbum.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import com.renyxin.localalbum.data.db.entity.HomeMediaSnapshotEntity

/** Publication boundary for the stable home timeline. */
@Dao
abstract class HomeMediaSnapshotDao {
    @Query(
        """SELECT * FROM home_media_snapshot
           ORDER BY capturedAtMs DESC, filePath ASC""",
    )
    abstract fun pagingSource(): PagingSource<Int, HomeMediaSnapshotEntity>

    @Query("SELECT * FROM home_media_snapshot WHERE filePath = :path LIMIT 1")
    abstract suspend fun getByPath(path: String): HomeMediaSnapshotEntity?

    @Query("SELECT COUNT(*) FROM home_media_snapshot")
    abstract suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(publishedGeneration), 0) FROM home_media_snapshot")
    abstract suspend fun publishedGeneration(): Long

    @Query("SELECT COUNT(DISTINCT publishedGeneration) FROM home_media_snapshot")
    abstract suspend fun distinctPublishedGenerationCount(): Int

    @Query("DELETE FROM home_media_snapshot")
    protected abstract suspend fun clear()

    /**
     * Builds a complete candidate projection from canonical media in one SQLite statement.
     * Only a READY Grid entry for the current source version may cross the publication boundary;
     * a stale/missing cache reference becomes a stable placeholder instead of publishing an old image.
     */
    @Query(
        """INSERT INTO home_media_snapshot(
               filePath,fileName,mediaType,capturedAtMs,modifiedAtMs,fileSize,isFavorite,
               width,height,mimeType,durationMs,parentPath,latitude,longitude,make,model,
               aperture,focalLength,iso,exposureTime,orientation,thumbnailPath,thumbnailState,
               isCorrupted,publishedGeneration
           )
           SELECT media.filePath,media.fileName,media.mediaType,media.capturedAtMs,
               media.modifiedAtMs,media.fileSize,media.isFavorite,media.width,media.height,
               media.mimeType,media.durationMs,media.parentPath,media.latitude,media.longitude,
               media.make,media.model,media.aperture,media.focalLength,media.iso,
               media.exposureTime,media.orientation,cache.path,
               CASE WHEN cache.path IS NULL THEN 'PLACEHOLDER' ELSE 'READY' END,
               media.isCorrupted,:generation
           FROM media_items AS media
           LEFT JOIN thumbnail_cache_entries AS cache
             ON cache.filePath = media.filePath
            AND cache.mediaType = media.mediaType
            AND cache.sizeClass = 'grid'
            AND cache.formatVersion = 5
            AND cache.sourceVersion = 'sv1|m=' || media.modifiedAtMs || '|s=' ||
                media.fileSize || '|f=' || COALESCE(LOWER(media.fingerprintHead),'-')
            AND cache.path = media.thumbnailPath
            AND cache.state = 'READY'
           WHERE media.isTrashed = 0""",
    )
    protected abstract suspend fun insertCanonicalProjection(generation: Long)

    /** The only operation that invalidates the home PagingSource for scan/enhancement work. */
    @Transaction
    open suspend fun replaceFromCanonical(generation: Long) {
        clear()
        insertCanonicalProjection(generation)
    }

    /** Explicit user metadata changes bypass scan publication but remain atomic with canonical writes. */
    @Query("UPDATE home_media_snapshot SET isFavorite = :favorite WHERE filePath = :path")
    abstract suspend fun setFavorite(path: String, favorite: Boolean): Int

    @Query("UPDATE home_media_snapshot SET isFavorite = :favorite WHERE filePath IN (:paths)")
    abstract suspend fun setFavoriteByPaths(paths: List<String>, favorite: Boolean): Int

    @Query("DELETE FROM home_media_snapshot WHERE filePath IN (:paths)")
    abstract suspend fun deleteByPaths(paths: List<String>): Int

    /**
     * An explicit user restore is visible immediately, but never opens the initial home gate.
     * As with full publication, only a verified current-version Grid cache is projected.
     */
    @Query(
        """INSERT OR REPLACE INTO home_media_snapshot(
               filePath,fileName,mediaType,capturedAtMs,modifiedAtMs,fileSize,isFavorite,
               width,height,mimeType,durationMs,parentPath,latitude,longitude,make,model,
               aperture,focalLength,iso,exposureTime,orientation,thumbnailPath,thumbnailState,
               isCorrupted,publishedGeneration
           )
           SELECT media.filePath,media.fileName,media.mediaType,media.capturedAtMs,
               media.modifiedAtMs,media.fileSize,media.isFavorite,media.width,media.height,
               media.mimeType,media.durationMs,media.parentPath,media.latitude,media.longitude,
               media.make,media.model,media.aperture,media.focalLength,media.iso,
               media.exposureTime,media.orientation,cache.path,
               CASE WHEN cache.path IS NULL THEN 'PLACEHOLDER' ELSE 'READY' END,
               media.isCorrupted,pipeline.publishedGeneration
           FROM media_items AS media
           JOIN library_pipeline AS pipeline
             ON pipeline.pipelineId = 'default' AND pipeline.hasPublishedBaseline = 1
           LEFT JOIN thumbnail_cache_entries AS cache
             ON cache.filePath = media.filePath
            AND cache.mediaType = media.mediaType
            AND cache.sizeClass = 'grid'
            AND cache.formatVersion = 5
            AND cache.sourceVersion = 'sv1|m=' || media.modifiedAtMs || '|s=' ||
                media.fileSize || '|f=' || COALESCE(LOWER(media.fingerprintHead),'-')
            AND cache.path = media.thumbnailPath
            AND cache.state = 'READY'
           WHERE media.isTrashed = 0 AND media.filePath IN (:paths)""",
    )
    abstract suspend fun restoreFromCanonical(paths: List<String>): Long

    @Query(
        """SELECT thumbnailPath FROM home_media_snapshot
           WHERE filePath IN (:paths) AND thumbnailState = 'READY' AND thumbnailPath IS NOT NULL""",
    )
    abstract suspend fun readyThumbnailPaths(paths: List<String>): List<String>

    @Query(
        """SELECT COUNT(*) FROM home_media_snapshot h
           WHERE h.thumbnailState = 'READY' AND NOT EXISTS(
               SELECT 1 FROM thumbnail_cache_entries c
               WHERE c.filePath = h.filePath AND c.mediaType = h.mediaType
                 AND c.sizeClass = 'grid' AND c.formatVersion = 5
                 AND c.path = h.thumbnailPath AND c.state = 'READY'
           )""",
    )
    abstract suspend fun countBrokenReadyReferences(): Int
}
