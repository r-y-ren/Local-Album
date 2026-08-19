package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication
import java.io.File

/** 有界清理无 READY metadata 引用的 lease-private staging/final orphan。 */
class ThumbnailCacheMaintenanceWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.success()
        val dir = File(applicationContext.cacheDir,"thumbnails")
        if (!dir.isDirectory) return Result.success()
        val cutoff = System.currentTimeMillis() - ORPHAN_TTL_MS
        val page = dir.listFiles().orEmpty()
            .asSequence()
            .filter { file ->
                // v33 invalidates all pre-v5 metadata, so TTL cleanup must also retire legacy WebP
                // names rather than leaving an unbounded old cache directory behind.
                file.isFile && file.lastModified() <= cutoff &&
                    (file.name.startsWith(".staged_") || file.extension.equals("webp",ignoreCase=true))
            }
            .take(PAGE_SIZE)
            .toList()
        val referenced = app.container.database.thumbnailCacheDao()
            .retainManagedReadyPaths(page.map(File::getAbsolutePath))
            .toSet()
        page.filterNot { it.absolutePath in referenced }.forEach(File::delete)
        if (page.size == PAGE_SIZE) enqueue(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "thumbnail_cache_maintenance"
        private const val PAGE_SIZE = 128
        private const val ORPHAN_TTL_MS = 24 * 60 * 60_000L
        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ThumbnailCacheMaintenanceWorker>().build(),
            )
        }
    }
}
