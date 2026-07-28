package com.renyxin.localalbum.core.analysis

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 感知哈希 (pHash) 相似图像分析器。
 *
 * 基于 DCT 低频分量提取 64 位感知哈希，通过汉明距离分组相似图像。
 * - 相同图像: 汉明距离 = 0
 * - 高度相似: 汉明距离 ≤ 8
 * - 中度相似: 汉明距离 ≤ 16
 * - 不相似:   汉明距离 > 16
 */
class SimilarityAnalyzer {

    /**
     * 相似组，包含组 ID 和成员路径。
     */
    data class SimilarGroup(
        val groupId: String,
        val filePaths: List<String>,
    )

    /**
     * 批量计算文件感知哈希。
     *
     * @return filePath → pHash 的映射，失败时值为 0L
     */
    suspend fun computeHashes(files: List<File>): Map<String, Long> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, Long>()
        for (file in files) {
            val hash = computePHash(file)
            if (hash != 0L) {
                results[file.absolutePath] = hash
            }
        }
        results
    }

    /**
     * 单文件感知哈希计算。
     */
    suspend fun computePHash(file: File): Long = withContext(Dispatchers.IO) {
        val bitmap = decodeToSize(file, TARGET_SIZE) ?: return@withContext 0L
        val hash = computeDCTHash(bitmap)
        bitmap.recycle()
        hash
    }

    /**
     * 基于感知哈希分组相似图像。
     *
     * @param pathHashPairs filePath → pHash 的映射
     * @param maxDistance 判定为相似的最大汉明距离，默认 8
     * @return 相似组列表（仅包含至少有 2 个成员的组）
     */
    fun groupSimilar(
        pathHashPairs: Map<String, Long>,
        maxDistance: Int = 8,
    ): List<SimilarGroup> {
        val entries = pathHashPairs.entries.toList()
        val visited = mutableSetOf<String>()
        val groups = mutableListOf<SimilarGroup>()

        for (i in entries.indices) {
            val (path, hash) = entries[i]
            if (path in visited) continue
            if (hash == 0L) continue

            val group = mutableListOf(path)
            visited.add(path)

            for (j in i + 1 until entries.size) {
                val (otherPath, otherHash) = entries[j]
                if (otherPath in visited) continue
                if (otherHash == 0L) continue

                val distance = hammingDistance(hash, otherHash)
                if (distance <= maxDistance) {
                    group.add(otherPath)
                    visited.add(otherPath)
                }
            }

            if (group.size >= 2) {
                val groupId = java.util.UUID.randomUUID().toString()
                groups.add(SimilarGroup(groupId, group))
            }
        }

        return groups
    }

    /**
     * 查找与给定哈希相似的所有哈希。
     */
    fun findSimilar(
        targetHash: Long,
        candidates: Map<String, Long>,
        maxDistance: Int = 8,
    ): List<String> {
        return candidates.filter { (_, hash) ->
            hash != 0L && hammingDistance(targetHash, hash) <= maxDistance
        }.keys.toList()
    }

    /**
     * 检测重复图像（汉明距离 = 0 且文件不同）。
     */
    fun findDuplicates(pathHashPairs: Map<String, Long>): Map<Long, List<String>> {
        val byHash = mutableMapOf<Long, MutableList<String>>()
        pathHashPairs.forEach { (path, hash) ->
            if (hash != 0L) {
                byHash.getOrPut(hash) { mutableListOf() }.add(path)
            }
        }
        return byHash.filter { it.value.size >= 2 }
    }

    // ---- 内部方法 ----

    /**
     * DCT 感知哈希核心算法。
     * 1. 缩放至 TARGET_SIZE × TARGET_SIZE 灰度图
     * 2. DCT 变换提取低频系数 (8×8)
     * 3. 与均值比较产生 64 位哈希
     */
    private fun computeDCTHash(bitmap: Bitmap): Long {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height
        if (totalPixels < 64) return 0L

        // 1. 提取灰度并缩放至 TARGET_SIZE × TARGET_SIZE
        val pixels = IntArray(totalPixels)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 直接缩放到 32×32 灰度
        val smallSize = 32
        val gray32 = FloatArray(smallSize * smallSize)

        for (y in 0 until smallSize) {
            for (x in 0 until smallSize) {
                val srcX = x * width / smallSize
                val srcY = y * height / smallSize
                val idx = srcY * width + srcX
                val pixel = pixels[idx]
                val r = ((pixel shr 16) and 0xFF)
                val g = ((pixel shr 8) and 0xFF)
                val b = (pixel and 0xFF)
                gray32[y * smallSize + x] = (0.299f * r + 0.587f * g + 0.114f * b)
            }
        }

        // 2. 提取 8×8 DCT 低频
        val dctVals = dct8x8(gray32, 32)

        // 3. 计算 DCT 均值（排除 DC 分量）
        val dcComponent = dctVals[0]
        val acOnly = dctVals.slice(1 until 64)
        val mean = acOnly.average().toFloat()

        // 4. 与均值比较产生哈希
        var hash: Long = 0
        for (i in 0 until 64) {
            if (dctVals[i] > mean) {
                hash = hash or (1L shl i)
            }
        }

        return hash
    }

    /**
     * 对 32×32 灰度图计算 8×8 2D DCT。
     * 使用简化的 DCT-II 变换。
     */
    private fun dct8x8(gray: FloatArray, srcSize: Int): FloatArray {
        val dct = FloatArray(64)
        val block = FloatArray(64)

        // 提取左上角 8×8
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                block[y * 8 + x] = gray[y * srcSize + x]
            }
        }

        // 2D DCT (可分离: 行变换 + 列变换)
        val rowTransformed = FloatArray(64)
        val colTransformed = FloatArray(64)

        for (y in 0 until 8) {
            val row = FloatArray(8) { block[y * 8 + it] }
            val dctRow = dct1D(row)
            for (x in 0 until 8) {
                rowTransformed[y * 8 + x] = dctRow[x]
            }
        }

        for (x in 0 until 8) {
            val col = FloatArray(8) { rowTransformed[it * 8 + x] }
            val dctCol = dct1D(col)
            for (y in 0 until 8) {
                colTransformed[y * 8 + x] = dctCol[y]
            }
        }

        return colTransformed
    }

    /**
     * 一维 DCT-II (8 点)。
     */
    private fun dct1D(input: FloatArray): FloatArray {
        val output = FloatArray(8)
        for (k in 0 until 8) {
            var sum = 0f
            for (n in 0 until 8) {
                sum += input[n] * kotlin.math.cos((kotlin.math.PI.toFloat() * (2 * n + 1) * k) / 16f)
            }
            output[k] = sum * if (k == 0) (1f / kotlin.math.sqrt(2f)) else 1f
        }
        return output
    }

    /**
     * 解码为指定最大边长的 Bitmap。
     */
    private fun decodeToSize(file: File, maxSize: Int): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        var sampleSize = 1
        while (options.outWidth / sampleSize > maxSize || options.outHeight / sampleSize > maxSize) {
            sampleSize *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /**
     * 汉明距离 (两个 64 位整数不同 bit 的数量)。
     */
    fun hammingDistance(a: Long, b: Long): Int {
        return (a xor b).countOneBits()
    }

    companion object {
        private const val TARGET_SIZE = 32

        /** 相似分组级别的汉明距离阈值 */
        const val SIMILARITY_THRESHOLD = 8
        const val LOOSE_SIMILARITY_THRESHOLD = 16
    }
}