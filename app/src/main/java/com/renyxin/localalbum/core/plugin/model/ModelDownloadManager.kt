package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * AI 模型文件下载管理器。
 *
 * 在首次使用时从远程 URL 下载 TFLite/ONNX 模型文件到本地存储，
 * 避免将大体积模型文件打包进 APK。
 *
 * ## 设计原则
 *
 * - 下载前检查文件是否已存在 + SHA-256 校验
 * - 使用 [Dispatchers.IO] 执行网络 I/O
 * - 提供进度回调接口
 */
object ModelDownloadManager {
    private const val TAG = "ModelDownload"
    private const val BUFFER_SIZE = 8192

    /** 模型文件存储目录（context.filesDir/models/） */
    fun getModelDir(context: Context): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 下载结果。
     */
    data class DownloadResult(
        val modelFile: File,
        val downloadedBytes: Long,
        val alreadyExisted: Boolean,
    )

    /**
     * 下载进度回调。
     */
    fun interface ProgressCallback {
        fun onProgress(downloaded: Long, total: Long)
    }

    /**
     * 确保模型文件存在于本地。如果不存在则从 [url] 下载，下载后校验 SHA-256。
     *
     * @param context 上下文
     * @param modelFileName 本地文件名（如 "mobilenet_v2_quant.tflite"）
     * @param url 下载 URL
     * @param expectedSha256 期望的 SHA-256 哈希（可选），用于完整性校验
     * @param progress 下载进度回调（可选）
     * @return 模型文件，下载/校验失败返回 null
     */
    suspend fun ensureModel(
        context: Context,
        modelFileName: String,
        url: String,
        expectedSha256: String? = null,
        progress: ProgressCallback? = null,
    ): File? = withContext(Dispatchers.IO) {
        val modelFile = File(getModelDir(context), modelFileName)

        // 已存在且校验通过
        if (modelFile.exists() && modelFile.length() > 0) {
            if (expectedSha256 != null) {
                val actual = sha256(modelFile)
                if (actual.equals(expectedSha256, ignoreCase = true)) {
                    Log.i(TAG, "模型文件已存在且校验通过: $modelFileName")
                    return@withContext modelFile
                } else {
                    Log.w(TAG, "SHA-256 不匹配，重新下载: $modelFileName")
                    modelFile.delete()
                }
            } else {
                Log.i(TAG, "模型文件已存在: $modelFileName")
                return@withContext modelFile
            }
        }

        // 下载
        Log.i(TAG, "开始下载模型: $url → $modelFileName")
        val tempFile = File(modelFile.parentFile, "$modelFileName.tmp")
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "下载失败 HTTP ${connection.responseCode}: $url")
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

            // 校验
            if (expectedSha256 != null) {
                val actual = sha256(tempFile)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    Log.e(TAG, "SHA-256 校验失败: 期望=$expectedSha256, 实际=$actual")
                    tempFile.delete()
                    return@withContext null
                }
            }

            // 重命名为正式文件
            tempFile.renameTo(modelFile)
            Log.i(TAG, "模型下载完成: $modelFileName (${downloadedBytes} bytes)")
            return@withContext modelFile
        } catch (e: Exception) {
            Log.e(TAG, "模型下载异常: $url", e)
            tempFile.delete()
            return@withContext null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 计算文件的 SHA-256 哈希。
     */
    fun sha256(file: File): String {
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
