package com.renyxin.localalbum.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.concurrent.InferenceDispatchers
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.source.MediaSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 异步缩略图生成 Worker。
 *
 * 扫描主流程中不再同步生成缩略图（仅记录元数据），
 * 新文件的缩略图由此 Worker 批量异步补齐：
 * - 查询 `thumbnailPath IS NULL` 的记录（每批最多 [BATCH_SIZE] 条）
 * - 调用 [MediaSource.generateThumbnailSync] 生成 WebP 格式缩略图
 * - 更新 `thumbnailPath` 字段
 *
 * 通过 [enqueue] 触发，可在扫描完成后或应用启动时调度。
 */
class ThumbnailWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ThumbnailWorker"
        private const val BATCH_SIZE = 50

        /**
         * 调度一次缩略图补齐任务。
         * 使用 OneTimeWorkRequest，可链式调用（如扫描完成后触发）。
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ThumbnailWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
            Log.i(TAG, "缩略图补齐任务已调度")
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as LocalAlbumApplication
        val mediaDao: MediaDao = app.container.let { container ->
            // 通过 AlbumRepository 暴露的 getMediaDao() 获取 DAO
            container.albumRepository.getMediaDao()
        }
        val mediaSource = MediaSource(applicationContext)

        return try {
            val processed = AtomicInteger(0)
            while (true) {
                val batch = mediaDao.getMissingThumbnails(BATCH_SIZE)
                if (batch.isEmpty()) break
                // 文件级并行生成缩略图（IO+轻计算混合，用 ioBound 调度器）
                coroutineScope {
                    batch.map { entity ->
                        async(InferenceDispatchers.ioBound) {
                            val file = File(entity.filePath)
                            if (!file.exists()) return@async
                            val type = entity.mediaType
                            val thumbPath = runCatching {
                                mediaSource.generateThumbnailSync(file, type)
                            }.getOrNull()
                            if (thumbPath != null) {
                                // P0-4: 使用专用的 updateThumbnail 方法，仅更新缩略图路径
                                // 避免 insertAll(REPLACE) 用旧快照覆盖分析管道写入的 AI 字段
                                mediaDao.updateThumbnail(entity.filePath, thumbPath)
                                processed.incrementAndGet()
                            }
                        }
                    }.awaitAll()
                }
                // 如果本批不足 BATCH_SIZE，说明已无更多待处理记录。
                if (batch.size < BATCH_SIZE) break
            }
            val total = processed.get()
            Log.i(TAG, "缩略图补齐完成: 处理了 $total 个文件")
            Result.success(workDataOf("processed" to total))
        } catch (e: Exception) {
            Log.e(TAG, "缩略图补齐失败", e)
            Result.failure()
        }
    }
}
