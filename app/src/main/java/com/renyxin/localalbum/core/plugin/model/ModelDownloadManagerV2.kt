package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 模型文件下载管理器 V2 — 实例版本（Phase 0 改进计划）。
 *
 * 与 V1 的区别：从 companion object 静态方法改为实例方法，支持多实例隔离。
 */
class ModelDownloadManagerV2(
    private val context: Context,
) {
    companion object {
        private const val TAG = "ModelDownloadV2"
        private const val BUFFER_SIZE = 8192
    }

    /**
     * 按目标模型文件名的互斥锁：同一模型并发 `ensureModel` 时串行化，
     * 防止两个调用方写同一个 `.tmp` 临时文件互相覆写（Phase 4 并发加固）。
     * 单例收敛后实例内已唯一，此锁同时防御未来出现的新调用路径。
     */
    private val fileLocks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(modelFileName: String): Mutex =
        fileLocks.computeIfAbsent(modelFileName) { Mutex() }

    fun getModelDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun interface ProgressCallback {
        fun onProgress(downloaded: Long, total: Long)
    }

    suspend fun ensureModel(
        modelFileName: String,
        url: String,
        expectedSha256: String? = null,
        progress: ProgressCallback? = null,
    ): File? = lockFor(modelFileName).withLock {
        withContext(Dispatchers.IO) {
            downloadLocked(modelFileName, url, expectedSha256, progress)
        }
    }

    private suspend fun downloadLocked(
        modelFileName: String,
        url: String,
        expectedSha256: String?,
        progress: ProgressCallback?,
    ): File? = withContext(Dispatchers.IO) {
        val modelFile = File(getModelDir(), modelFileName)

        // 已存在且校验通过
        if (modelFile.exists() && modelFile.length() > 0) {
            if (expectedSha256 != null) {
                val actual = sha256(modelFile)
                if (actual.equals(expectedSha256, ignoreCase = true)) return@withContext modelFile
                modelFile.delete()
            } else {
                return@withContext modelFile
            }
        }

        Log.i(TAG, "下载模型: $url → $modelFileName")
        val tempFile = File(getModelDir(), "$modelFileName.tmp")
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 120000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP ${connection.responseCode}")
                return@withContext null
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        progress?.onProgress(downloadedBytes, totalBytes)
                    }
                }
            }

            if (expectedSha256 != null) {
                val actual = sha256(tempFile)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA-256 不匹配")
                    tempFile.delete()
                    return@withContext null
                }
            }

            tempFile.renameTo(modelFile)
            Log.i(TAG, "下载完成: $modelFileName (${downloadedBytes} bytes)")
            modelFile
        } catch (e: Exception) {
            Log.e(TAG, "下载异常", e)
            tempFile.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
