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

    /**
     * 模型文件根目录（`{filesDir}/models`），不存在时自动创建（含父目录）。
     *
     * @return 已确保存在的模型目录
     */
    fun getModelDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun interface ProgressCallback {
        fun onProgress(downloaded: Long, total: Long)
    }

    /**
     * 确保模型文件就绪：已存在且校验通过则直接复用，否则下载到 `.tmp` 后原子改名。
     *
     * 契约与失败条件：
     * - 目标文件已存在且（提供了 [expectedSha256] 时）SHA-256 匹配 → 返回该文件；
     *   校验不匹配会先删除损坏文件再重新下载；
     * - 下载写入固定命名的临时文件 `"$modelFileName.tmp"`（完成后 renameTo 正式文件）；
     * - 返回 null 的全部条件：HTTP 响应码非 200、下载后 SHA-256 不匹配（临时文件已清理）、
     *   下载过程中发生 IO/其他异常（临时文件已清理）。
     *
     * 并发保护：同一 [modelFileName] 经 [lockFor] 的 per-file [Mutex] 串行化（Phase 4 加固），
     * 实例内并发调用不会互写 `.tmp`；配合 AppContainer 的单例收敛，全进程亦唯一。
     *
     * @param modelFileName 目标文件名（含扩展名，最终落在 [getModelDir] 下）
     * @param url 下载地址
     * @param expectedSha256 期望的 SHA-256（十六进制）；null 表示跳过校验
     * @param progress 可选进度回调（已下载字节数 / 总字节数，总长未知时为 -1）
     * @return 就绪的模型文件；上述任一失败条件命中时返回 null
     */
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
