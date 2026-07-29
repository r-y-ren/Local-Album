package com.renyxin.localalbum.data.backup

import androidx.room.withTransaction
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 数据库导入器（Phase 4.2 数据库导入导出）。
 *
 * 从 [DatabaseExporter] 生成的 JSON 文件中恢复全部索引数据，
 * 用于换设备后一键恢复，避免重新扫描。
 *
 * ## 导入策略
 *
 * 1. 解析 JSON 文件，校验格式版本
 * 2. 清空现有数据库表（导入为覆盖式恢复）
 * 3. 批量插入媒体记录 → FTS 索引 → 人脸记录 → 语义嵌入
 * 4. 插入顺序保证外键语义完整性（media_items 先于 faces/embeddings）
 *
 * ## 安全考量
 *
 * - 导入前自动清空现有数据，避免主键冲突
 * - 批量插入使用 @Transaction 保证原子性
 * - 格式版本不匹配时拒绝导入
 */
class DatabaseImporter(
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao? = null,
    private val embeddingDao: EmbeddingDao? = null,
    /**
     * 真实 Room 数据库用于将整次覆盖式恢复包进同一个事务。
     * 单元测试可省略该参数，此时仍执行相同逻辑，但不具备跨 DAO 的 SQLite 原子性。
     */
    private val database: AppDatabase? = null,
) {
    companion object {
        private const val TAG = "DatabaseImporter"
    }

    /**
     * 导入结果汇总。
     */
    data class ImportResult(
        val success: Boolean,
        val mediaCount: Int = 0,
        val faceCount: Int = 0,
        val embeddingCount: Int = 0,
        val ftsCount: Int = 0,
        val errorMessage: String? = null,
    )

    /**
     * 从 JSON 文件导入数据库。
     *
     * @param inputFile 导出的 JSON 文件
     * @return 导入结果
     */
    suspend fun importFromFile(inputFile: File): ImportResult = withContext(Dispatchers.IO) {
        try {
            val json = inputFile.readText(Charsets.UTF_8)
            importFromJson(json)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult(success = false, errorMessage = e.message)
        }
    }

    /**
     * 从 JSON 字符串导入数据库（用于测试或流式传输）。
     *
     * @param json JSON 字符串
     * @return 导入结果
     */
    suspend fun importFromJson(json: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(json)

            // 校验格式版本
            val version = root.optInt("version", -1)
            if (version != DatabaseExporter.EXPORT_FORMAT_VERSION) {
                return@withContext ImportResult(
                    success = false,
                    errorMessage = "不支持的导出格式版本: $version（当前支持 ${DatabaseExporter.EXPORT_FORMAT_VERSION}）",
                )
            }

            // 解析各表数据
            val mediaItems = parseMediaItems(root.optJSONArray("mediaItems") ?: return@withContext ImportResult(success = false, errorMessage = "缺少 mediaItems 字段"))
            val faces = parseFaces(root.optJSONArray("faces"))
            val embeddings = parseEmbeddings(root.optJSONArray("embeddings"))
            val ftsEntries = parseFtsEntries(root.optJSONArray("ftsEntries"))

            // 覆盖式恢复必须跨媒体、FTS、人脸和嵌入表原子执行：任何写入失败均回滚到导入前状态。
            // 当 database 为 null（纯 JVM Fake DAO 测试）时仅执行同一写入逻辑，真实 App 路径必须注入 AppDatabase。
            suspend fun restoreAll() {
                mediaDao.clearAll()
                mediaDao.clearAllFts()
                faceDao?.clearAll()
                embeddingDao?.clearAll()

                if (mediaItems.isNotEmpty()) mediaDao.insertAll(mediaItems)
                if (ftsEntries.isNotEmpty()) {
                    ftsEntries.chunked(500).forEach { chunk -> mediaDao.insertFtsAll(chunk) }
                }
                if (faces.isNotEmpty()) faceDao?.insertFaces(faces)
                if (embeddings.isNotEmpty()) embeddingDao?.insertEmbeddings(embeddings)
            }
            if (database != null) {
                database.withTransaction { restoreAll() }
            } else {
                restoreAll()
            }

            ImportResult(
                success = true,
                mediaCount = mediaItems.size,
                faceCount = faces.size,
                embeddingCount = embeddings.size,
                ftsCount = ftsEntries.size,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ImportResult(success = false, errorMessage = e.message)
        }
    }

    /**
     * 仅校验 JSON 文件格式，不执行导入（用于导入前预检）。
     *
     * @param inputFile 导出的 JSON 文件
     * @return 校验通过返回 counts 对象，失败返回 null + 错误信息
     */
    fun validateFile(inputFile: File): Pair<JSONObject?, String?> {
        return try {
            val root = JSONObject(inputFile.readText(Charsets.UTF_8))
            val version = root.optInt("version", -1)
            if (version != DatabaseExporter.EXPORT_FORMAT_VERSION) {
                null to "不支持的导出格式版本: $version"
            } else {
                root.optJSONObject("counts") to null
            }
        } catch (e: Exception) {
            null to e.message
        }
    }

    // ---- JSON 解析 ----

    private fun parseMediaItems(arr: org.json.JSONArray): List<MediaEntity> {
        val list = mutableListOf<MediaEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                MediaEntity(
                    filePath = obj.getString("filePath"),
                    fileName = obj.getString("fileName"),
                    mediaType = MediaType.valueOf(obj.getString("mediaType")),
                    capturedAtMs = obj.getLong("capturedAtMs"),
                    modifiedAtMs = obj.getLong("modifiedAtMs"),
                    indexedAtMs = obj.optLong("indexedAtMs", System.currentTimeMillis()),
                    parentPath = obj.getString("parentPath"),
                    fileSize = obj.optLong("fileSize", 0L),
                    isFavorite = obj.optBoolean("isFavorite", false),
                    isTrashed = obj.optBoolean("isTrashed", false),
                    width = obj.optInt("width", 0),
                    height = obj.optInt("height", 0),
                    mimeType = obj.optString("mimeType", ""),
                    durationMs = obj.optLong("durationMs", 0L),
                    latitude = obj.optDoubleOrNull("latitude"),
                    longitude = obj.optDoubleOrNull("longitude"),
                    make = obj.optStringOrNull("make"),
                    model = obj.optStringOrNull("model"),
                    aperture = obj.optStringOrNull("aperture"),
                    focalLength = obj.optStringOrNull("focalLength"),
                    iso = obj.optStringOrNull("iso"),
                    exposureTime = obj.optStringOrNull("exposureTime"),
                    orientation = obj.optInt("orientation", 0),
                    sceneType = obj.optStringOrNull("sceneType"),
                    thumbnailPath = obj.optStringOrNull("thumbnailPath"),
                    fingerprintHead = obj.optStringOrNull("fingerprintHead"),
                    perceptualHash = obj.optLong("perceptualHash", 0L),
                    ocrText = obj.optStringOrNull("ocrText"),
                    qualityScore = obj.optDouble("qualityScore", 0.0).toFloat(),
                    deletedAtMs = obj.optLong("deletedAtMs", 0L),
                    isCorrupted = obj.optBoolean("isCorrupted", false),
                    faceClusterId = obj.optStringOrNull("faceClusterId"),
                ),
            )
        }
        return list
    }

    private fun parseFaces(arr: org.json.JSONArray?): List<FaceEntity> {
        if (arr == null) return emptyList()
        val list = mutableListOf<FaceEntity>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                FaceEntity(
                    faceId = obj.optLong("faceId", 0L),
                    filePath = obj.getString("filePath"),
                    clusterId = obj.optStringOrNull("clusterId"),
                    personName = obj.optStringOrNull("personName"),
                    embedding = obj.getString("embedding"),
                    boxLeft = obj.optDouble("boxLeft", 0.0).toFloat(),
                    boxTop = obj.optDouble("boxTop", 0.0).toFloat(),
                    boxRight = obj.optDouble("boxRight", 0.0).toFloat(),
                    boxBottom = obj.optDouble("boxBottom", 0.0).toFloat(),
                    thumbnailPath = obj.optStringOrNull("thumbnailPath"),
                    detectedAtMs = obj.optLong("detectedAtMs", System.currentTimeMillis()),
                ),
            )
        }
        return list
    }

    private fun parseEmbeddings(arr: org.json.JSONArray?): List<MediaEmbedding> {
        if (arr == null) return emptyList()
        val list = mutableListOf<MediaEmbedding>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                MediaEmbedding(
                    filePath = obj.getString("filePath"),
                    embedding = obj.getString("embedding"),
                    modelVersion = obj.optInt("modelVersion", 1),
                    generatedAtMs = obj.optLong("generatedAtMs", System.currentTimeMillis()),
                    source = obj.optString("source", "concept"),
                ),
            )
        }
        return list
    }

    private fun parseFtsEntries(arr: org.json.JSONArray?): List<MediaFts> {
        if (arr == null) return emptyList()
        val list = mutableListOf<MediaFts>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                MediaFts(
                    filePath = obj.getString("filePath"),
                    fileName = obj.getString("fileName"),
                    ocrText = obj.optStringOrNull("ocrText"),
                    make = obj.optStringOrNull("make"),
                    model = obj.optStringOrNull("model"),
                ),
            )
        }
        return list
    }
}

// ---- JSONObject 辅助扩展 ----

/**
 * 如果 key 存在且值非 null 则返回字符串，否则返回 null。
 * 区别于 optString(key, "")，此方法正确区分"空字符串"与"不存在"。
 */
private fun JSONObject.optStringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val s = optString(key, "")
    return if (s.isEmpty()) null else s
}

/**
 * 如果 key 存在且值非 null 则返回 Double，否则返回 null。
 */
private fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    return optDouble(key, 0.0)
}
