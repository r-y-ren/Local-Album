package com.renyxin.localalbum.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.renyxin.localalbum.LocalAlbumApplication

class ScanWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as LocalAlbumApplication
        val repository = app.container.albumRepository
        
        return try {
            repository.rescan()
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }
}
