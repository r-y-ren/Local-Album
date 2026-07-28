package com.renyxin.localalbum.data.backup

import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 数据库导出器（Phase 4.2 数据库导入导出）。
 *
 * 将 Room 数据库中的全部索引数据（媒体记录、人脸记录、语义嵌入、FTS 索引）
 * 序列化为 JSON 格式导出，用于换设备后一键恢复，避免重新扫描。
 *
 * ## 导出格式
 *
 * ```json
 * {
 *   "version": 1,
 *   "exportedAt": "2026-07-24T14:30:00",
 *   "deviceModel": "Pixel 8",
 *   "counts": { "media": 1000, "faces": 500, "embeddings": 800, "fts": 1000 },
 *   "mediaItems": [ ... ],
 *   "faces": [ ... ],
 *   "embeddings": [ ... ],
 *   "ftsEntries": [ ... ]
 * }
 * ```
 *
 * ## 设计考量
 *
 * - 使用 JSON 而非二进制格式，便于调试与跨版本兼容
 * - 向量/嵌入等大字段以字符串形式存储，避免 Base64 编码开销
 * - 导出文件可压缩为 ZIP（含 JSON + 元数据），当前实现直接输出 JSON
 */
class DatabaseExporter(
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao? = null,
    private val embeddingDao: EmbeddingDao? = null,
) {
    companion object {
        private const val TAG = "DatabaseExporter"

        /** 导出格式版本号，格式升级后递增 */
        const val EXPORT_FORMAT_VERSION = 1
    }

    /**
     * 导出结果汇总。
     */
    data class ExportResult(
        val success: Boolean,
        val filePath: String? = null,
        val mediaCount: Int = 0,
        val faceCount: Int = 0,
        val embeddingCount: Int = 0,
        val ftsCount: Int = 0,
        val fileSizeBytes: Long = 0L,
        val errorMessage: String? = null,
    )

    /**
     * 将数据库导出为 JSON 文件。
     *
     * @param outputFile 目标文件路径（建议以 .json 结尾）
     * @param deviceModel 设备型号（写入元数据，便于识别来源）
     * @return 导出结果
     */
    suspend fun exportToFile(outputFile: File, deviceModel: String = ""): ExportResult =
        withContext(Dispatchers.IO) {
            try {
                val json = exportToJson(deviceModel)
                outputFile.parentFile?.mkdirs()
                outputFile.writeText(json, Charsets.UTF_8)
                val counts = JSONObject(json).getJSONObject("counts")
                ExportResult(
                    success = true,
                    filePath = outputFile.absolutePath,
                    mediaCount = counts.getInt("media"),
                    faceCount = counts.getInt("faces"),
                    embeddingCount = counts.getInt("embeddings"),
                    ftsCount = counts.getInt("fts"),
                    fileSizeBytes = outputFile.length(),
                )
            } catch (e: Exception) {
                ExportResult(success = false, errorMessage = e.message)
            }
        }

    /**
     * 将数据库导出为 JSON 字符串（用于测试或流式传输）。
     *
     * @param deviceModel 设备型号
     * @return JSON 字符串
     */
    suspend fun exportToJson(deviceModel: String = ""): String = withContext(Dispatchers.IO) {
        val mediaItems = mediaDao.getAll()
        val faces = faceDao?.getAll() ?: emptyList()
        val embeddings = embeddingDao?.getAll() ?: emptyList()
        val ftsEntries = mediaDao.getAllFtsEntries()

        val root = JSONObject()
        root.put("version", EXPORT_FORMAT_VERSION)
        root.put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))
        root.put("deviceModel", deviceModel)

        val counts = JSONObject()
        counts.put("media", mediaItems.size)
        counts.put("faces", faces.size)
        counts.put("embeddings", embeddings.size)
        counts.put("fts", ftsEntries.size)
        root.put("counts", counts)

        root.put("mediaItems", mediaItemsToJsonArray(mediaItems))
        root.put("faces", facesToJsonArray(faces))
        root.put("embeddings", embeddingsToJsonArray(embeddings))
        root.put("ftsEntries", ftsToJsonArray(ftsEntries))

        root.toString(2) // 缩进 2 空格，便于可读
    }

    /**
     * 将媒体实体列表序列化为 JSON 数组。
     */
    private fun mediaItemsToJsonArray(items: List<MediaEntity>): JSONArray {
        val arr = JSONArray()
        for (item in items) {
            val obj = JSONObject()
            obj.put("filePath", item.filePath)
            obj.put("fileName", item.fileName)
            obj.put("mediaType", item.mediaType.name)
            obj.put("capturedAtMs", item.capturedAtMs)
            obj.put("modifiedAtMs", item.modifiedAtMs)
            obj.put("indexedAtMs", item.indexedAtMs)
            obj.put("parentPath", item.parentPath)
            obj.put("fileSize", item.fileSize)
            obj.put("isFavorite", item.isFavorite)
            obj.put("isTrashed", item.isTrashed)
            obj.put("width", item.width)
            obj.put("height", item.height)
            obj.put("mimeType", item.mimeType)
            obj.put("durationMs", item.durationMs)
            obj.putOpt("latitude", item.latitude)
            obj.putOpt("longitude", item.longitude)
            obj.putOpt("make", item.make)
            obj.putOpt("model", item.model)
            obj.putOpt("aperture", item.aperture)
            obj.putOpt("focalLength", item.focalLength)
            obj.putOpt("iso", item.iso)
            obj.putOpt("exposureTime", item.exposureTime)
            obj.put("orientation", item.orientation)
            obj.putOpt("sceneType", item.sceneType)
            obj.putOpt("thumbnailPath", item.thumbnailPath)
            obj.putOpt("fingerprintHead", item.fingerprintHead)
            obj.put("perceptualHash", item.perceptualHash)
            obj.putOpt("ocrText", item.ocrText)
            obj.putOpt("geoClusterId", item.geoClusterId)
            obj.put("qualityScore", item.qualityScore)
            obj.putOpt("similarGroupId", item.similarGroupId)
            obj.put("deletedAtMs", item.deletedAtMs)
            obj.put("isCorrupted", item.isCorrupted)
            obj.putOpt("faceClusterId", item.faceClusterId)
            arr.put(obj)
        }
        return arr
    }

    /**
     * 将人脸实体列表序列化为 JSON 数组。
     */
    private fun facesToJsonArray(faces: List<FaceEntity>): JSONArray {
        val arr = JSONArray()
        for (face in faces) {
            val obj = JSONObject()
            obj.put("faceId", face.faceId)
            obj.put("filePath", face.filePath)
            obj.putOpt("clusterId", face.clusterId)
            obj.putOpt("personName", face.personName)
            obj.put("embedding", face.embedding)
            obj.put("boxLeft", face.boxLeft)
            obj.put("boxTop", face.boxTop)
            obj.put("boxRight", face.boxRight)
            obj.put("boxBottom", face.boxBottom)
            obj.putOpt("thumbnailPath", face.thumbnailPath)
            obj.put("detectedAtMs", face.detectedAtMs)
            arr.put(obj)
        }
        return arr
    }

    /**
     * 将语义嵌入实体列表序列化为 JSON 数组。
     */
    private fun embeddingsToJsonArray(embeddings: List<MediaEmbedding>): JSONArray {
        val arr = JSONArray()
        for (emb in embeddings) {
            val obj = JSONObject()
            obj.put("filePath", emb.filePath)
            obj.put("embedding", emb.embedding)
            obj.put("modelVersion", emb.modelVersion)
            obj.put("generatedAtMs", emb.generatedAtMs)
            obj.put("source", emb.source)
            arr.put(obj)
        }
        return arr
    }

    /**
     * 将 FTS 索引条目序列化为 JSON 数组。
     */
    private fun ftsToJsonArray(ftsEntries: List<MediaFts>): JSONArray {
        val arr = JSONArray()
        for (fts in ftsEntries) {
            val obj = JSONObject()
            obj.put("filePath", fts.filePath)
            obj.put("fileName", fts.fileName)
            obj.putOpt("ocrText", fts.ocrText)
            obj.putOpt("make", fts.make)
            obj.putOpt("model", fts.model)
            arr.put(obj)
        }
        return arr
    }
}
