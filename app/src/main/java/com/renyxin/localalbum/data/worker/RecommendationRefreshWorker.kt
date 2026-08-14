package com.renyxin.localalbum.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.renyxin.localalbum.LocalAlbumApplication
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import java.util.concurrent.TimeUnit

/** Delayed, cancellable recommendation maintenance that never runs inside the core scan window. */
class RecommendationRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalAlbumApplication ?: return Result.failure()
        val container = app.container
        return try {
            if (container.albumRepository.isCoreScanActive() || EnhancementResourceGate.isCoreRequested) return Result.retry()
            val refreshed = EnhancementResourceGate.tryWithAutomaticEnhancement {
                container.albumRepository.refreshRecommendationsForDirectories(
                    inputData.getStringArray(KEY_DIRECTORIES).orEmpty().toSet(),
                )
            }
            if (refreshed == null) Result.retry() else Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "推荐增强刷新失败", error)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "RecommendationRefresh"
        private const val WORK_NAME = "recommendation_refresh_enhancement"
        private const val KEY_DIRECTORIES = "directories"
        private const val MAX_DIRECTORIES = 100
        private const val MAX_ENCODED_PATH_BYTES = 8 * 1024
        private const val INITIAL_DELAY_MS = 2_000L

        fun enqueue(context: Context, affectedDirectories: Set<String>) {
            var encodedBytes = 0
            val bounded = affectedDirectories.asSequence()
                .filter(String::isNotBlank)
                .distinct()
                .take(MAX_DIRECTORIES)
                .takeWhile { path ->
                    val next = path.toByteArray(Charsets.UTF_8).size + 8
                    (encodedBytes + next <= MAX_ENCODED_PATH_BYTES).also { fits ->
                        if (fits) encodedBytes += next
                    }
                }
                .toList()
                .toTypedArray()
            if (bounded.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<RecommendationRefreshWorker>()
                .setInitialDelay(INITIAL_DELAY_MS, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_DIRECTORIES to bounded))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
