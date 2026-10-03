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
        val candidates = dir.listFiles().orEmpty()
            .filter { file ->
                // v33 invalidates all pre-v5 metadata, so TTL cleanup must also retire legacy WebP
                // names rather than leaving an unbounded old cache directory behind.
                file.isFile && file.lastModified() <= cutoff &&
                    (file.name.startsWith(".staged_") || file.extension.equals("webp",ignoreCase=true))
            }
        // 窗口推进：全被引用的窗口直接跳过继续找孤儿（listFiles 顺序稳定时，
        // 首窗全引用不代表后面没有孤儿）；单次运行只清理一个有产出的窗口即止，
        // 余下靠续跑推进。零删除且翻完全部候选时不再续跑——大图库的活跃缩略图
        // 在超过 TTL 后会稳定填满整页，无进度续跑等于永久自转。
        var deletedTotal = 0
        var exploredTo = 0
        var cursor = 0
        while (cursor < candidates.size) {
            val page = candidates.subList(cursor, minOf(cursor + PAGE_SIZE, candidates.size))
            val referenced = app.container.database.thumbnailCacheDao()
                .retainManagedReadyPaths(page.map(File::getAbsolutePath))
                .toSet()
            val deleted = page.count { file -> file.absolutePath !in referenced && file.delete() }
            deletedTotal += deleted
            exploredTo = cursor + page.size
            if (deleted > 0) break
            cursor += page.size
        }
        if (deletedTotal > 0 && exploredTo < candidates.size) enqueue(applicationContext)
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
