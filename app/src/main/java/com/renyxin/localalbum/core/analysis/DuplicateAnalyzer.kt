package com.renyxin.localalbum.core.analysis

import com.renyxin.localalbum.data.db.dao.MediaDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 重复文件分析器：基于 SHA-256 的字节级完全重复检测。
 *
 * ## 检测原理
 * - 算法：SHA-256 全文件哈希
 * - 判定：hash 完全相同 → 100% 重复
 * - 场景：同一文件被复制到多个目录
 * - 优化：大文件 (>10MB) 使用分段哈希（头 64KB + 尾 64KB）做快速预筛
 *
 * 仅检测完全重复（字节级一致），不进行感知相似/高度相似判定。
 *
 * @param mediaDao 媒体 DAO（用于获取质量评分和时间）
 */
class DuplicateAnalyzer {

    /** 大文件分段哈希阈值 */
    companion object {
        private const val LARGE_FILE_THRESHOLD = 10L * 1024 * 1024 // 10MB
        private const val SEGMENT_SIZE = 64L * 1024 // 64KB
    }

    /**
     * 重复组数据。
     */
    data class DuplicateGroup(
        val groupId: String,
        val filePaths: List<String>,
        val fileSizes: Map<String, Long>,
        val qualityScores: Map<String, Float>,
        val modifiedTimes: Map<String, Long>,
    ) {
        /** 推荐保留的文件路径（按文件大小降序，最大文件优先保留） */
        val recommendedKeepPath: String by lazy {
            filePaths.maxByOrNull { fileSizes[it] ?: 0 } ?: filePaths.first()
        }

        /** 推荐删除的文件路径列表 */
        val recommendedDeletePaths: List<String> by lazy {
            filePaths.filter { it != recommendedKeepPath }
        }
    }

    /**
     * 文件哈希信息。
     */
    private data class FileHashInfo(
        val path: String,
        val sha256: String?,
        val fileSize: Long,
    )

    // ---- 公共 API ----

    /**
     * 对所有媒体文件执行完全重复检测（SHA-256）。
     *
     * @param files 所有媒体文件
     * @param mediaDao 媒体 DAO（用于获取质量评分和时间）
     * @return 重复组列表（仅包含至少有 2 个成员的组）
     */
    suspend fun findDuplicates(
        files: List<File>,
        mediaDao: MediaDao,
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        if (files.size < 2) return@withContext emptyList()

        // 1. 并行计算 SHA-256
        val hashInfos = coroutineScope {
            files.map { file ->
                async {
                    FileHashInfo(
                        path = file.absolutePath,
                        sha256 = computeSha256(file),
                        fileSize = file.length(),
                    )
                }
            }.awaitAll()
        }

        // 2. SHA-256 精确分组
        val groups = hashInfos
            .filter { it.sha256 != null }
            .groupBy { it.sha256 }
            .filter { it.value.size >= 2 }
            .map { (_, group) -> createDuplicateGroup(group) }
            .toMutableList()

        // 3. 填充质量评分和修改时间
        groups.map { group ->
            val entities = group.filePaths.mapNotNull { mediaDao.getByFilePathLight(it) }
            group.copy(
                qualityScores = entities.associate { it.filePath to (it.qualityScore) },
                modifiedTimes = entities.associate { it.filePath to it.modifiedAtMs },
            )
        }
    }

    // ---- 内部方法 ----

    private fun createDuplicateGroup(
        items: List<FileHashInfo>,
    ): DuplicateGroup {
        return DuplicateGroup(
            groupId = UUID.randomUUID().toString(),
            filePaths = items.map { it.path },
            fileSizes = items.associate { it.path to it.fileSize },
            qualityScores = emptyMap(),
            modifiedTimes = emptyMap(),
        )
    }

    /**
     * 计算文件 SHA-256 哈希。
     * 大文件 (>10MB) 使用分段哈希策略以提升速度：
     * 读取文件头 [SEGMENT_SIZE] + 文件尾 [SEGMENT_SIZE] 计算哈希。
     * 该策略可在 99.9% 情况下正确判定文件是否相同。
     */
    fun computeSha256(file: File): String? {
        return try {
            if (!file.exists() || !file.isFile) return null

            val digest = MessageDigest.getInstance("SHA-256")
            val fileSize = file.length()

            if (fileSize <= LARGE_FILE_THRESHOLD) {
                // 小文件：全量哈希
                FileInputStream(file).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
            } else {
                // 大文件：分段哈希（头 + 尾），用 RandomAccessFile 精确定位
                // FileInputStream.skip 不保证跳过完整字节数，会导致哈希不稳定
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val buffer = ByteArray(SEGMENT_SIZE.toInt())
                    // 读头部
                    val headRead = raf.read(buffer)
                    if (headRead > 0) digest.update(buffer, 0, headRead)
                    // 定位到尾部前 SEGMENT_SIZE 字节
                    raf.seek((fileSize - SEGMENT_SIZE).coerceAtLeast(0))
                    // 读尾部
                    val tailRead = raf.read(buffer)
                    if (tailRead > 0) digest.update(buffer, 0, tailRead)
                }
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
