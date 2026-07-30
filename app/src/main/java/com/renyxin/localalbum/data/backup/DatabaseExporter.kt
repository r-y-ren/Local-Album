package com.renyxin.localalbum.data.backup

import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import androidx.room.withTransaction
import com.renyxin.localalbum.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    private val database: AppDatabase? = null,
) {
    companion object {
        private const val TAG = "DatabaseExporter"

        /** 历史单文件 JSON 格式版本号，保留用于兼容已有备份。 */
        const val EXPORT_FORMAT_VERSION = 1
        /** 默认 ZIP + NDJSON 流式备份格式版本号。 */
        const val STREAMING_EXPORT_FORMAT_VERSION = BackupContract.VERSION
        private const val EXPORT_PAGE_SIZE = 500
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
                if (outputFile.extension.equals("json", ignoreCase = true)) {
                    exportLegacyJson(outputFile, deviceModel)
                } else {
                    exportToZip(outputFile, deviceModel)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportResult(success = false, errorMessage = e.message)
            }
        }

    /**
     * 默认大图库备份格式：ZIP 中每张表一个 NDJSON 条目。
     *
     * 每页只保留最多 [EXPORT_PAGE_SIZE] 个实体，避免导出数万媒体或高维向量时构造
     * 巨大的 JSON 字符串和多个完整表集合。传入 `.json` 仍走旧格式，供历史兼容和测试使用。
     */
    private suspend fun exportToZip(outputFile: File, deviceModel: String): ExportResult {
        outputFile.parentFile?.mkdirs()
        val tempDir = File(outputFile.parentFile ?: outputFile.absoluteFile.parentFile, ".${outputFile.name}.parts")
        tempDir.deleteRecursively()
        check(tempDir.mkdirs()) { "无法创建备份临时目录" }
        try {
            lateinit var metadata: Map<String, EntryMetadata>
            val contractTables = if (database == null) BackupContract.tables.take(4) else BackupContract.tables
            suspend fun snapshot() {
                val result = linkedMapOf(
                    "media.ndjson" to writeNdjsonFile<MediaEntity, String?>(tempDir, "media.ndjson", null,
                        { cursor -> mediaDao.getExportPageAfter(cursor, EXPORT_PAGE_SIZE) },
                        { it.filePath }, { mediaItemsToJsonArray(listOf(it)).getJSONObject(0) }),
                    "faces.ndjson" to writeNdjsonFile(tempDir, "faces.ndjson", 0L,
                        { cursor -> faceDao?.getPagedAfter(cursor, EXPORT_PAGE_SIZE) ?: emptyList() },
                        { it.faceId }, { facesToJsonArray(listOf(it)).getJSONObject(0) }),
                    "embeddings.ndjson" to writeNdjsonFile<MediaEmbedding, String?>(tempDir, "embeddings.ndjson", null,
                        { cursor -> embeddingDao?.getPagedAfter(cursor, EXPORT_PAGE_SIZE) ?: emptyList() },
                        { it.filePath }, { embeddingsToJsonArray(listOf(it)).getJSONObject(0) }),
                    "fts.ndjson" to writeNdjsonFile<MediaFts, String?>(tempDir, "fts.ndjson", null,
                        { cursor -> mediaDao.getFtsEntriesAfter(cursor, EXPORT_PAGE_SIZE) },
                        { it.filePath }, { ftsToJsonArray(listOf(it)).getJSONObject(0) }),
                )
                database?.openHelper?.readableDatabase?.let { db ->
                    BackupContract.tables.drop(4).forEach { table ->
                        result[table.entry] = writeSqlTableNdjson(tempDir, table, db)
                    }
                }
                metadata = result
            }
            if (database != null) database.withTransaction { snapshot() } else snapshot()
            val manifest = JSONObject().apply {
                put("version", STREAMING_EXPORT_FORMAT_VERSION)
                put("format", BackupContract.FORMAT)
                put("roomSchemaVersion", database?.openHelper?.readableDatabase?.version ?: 0)
                put("capabilities", JSONArray(contractTables.map { it.capability }.toSet().sorted()))
                put("profile", if (database == null) "core-test" else "complete")
                put("tables", JSONArray().apply {
                    contractTables.forEach { table -> put(JSONObject().apply {
                        put("entry", table.entry); put("table", table.table)
                        put("required", table.required); put("capability", table.capability)
                    }) }
                })
                put("excludedTables", JSONArray(BackupContract.excludedTables))
                put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))
                put("deviceModel", deviceModel)
                put("counts", JSONObject().apply {
                    put("media", metadata.getValue("media.ndjson").lineCount)
                    put("faces", metadata.getValue("faces.ndjson").lineCount)
                    put("embeddings", metadata.getValue("embeddings.ndjson").lineCount)
                    put("fts", metadata.getValue("fts.ndjson").lineCount)
                })
                put("entries", JSONObject().apply {
                    metadata.forEach { (name, value) -> put(name, value.toJson()) }
                })
            }
            ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
                writeZipText(zip, "manifest.json", manifest.toString())
                contractTables.forEach { table ->
                    val name = table.entry
                    zip.putNextEntry(ZipEntry(name))
                    File(tempDir, name).inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            return ExportResult(
                true, outputFile.absolutePath,
                metadata.getValue("media.ndjson").lineCount,
                metadata.getValue("faces.ndjson").lineCount,
                metadata.getValue("embeddings.ndjson").lineCount,
                metadata.getValue("fts.ndjson").lineCount,
                outputFile.length(),
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private suspend fun exportLegacyJson(outputFile: File, deviceModel: String): ExportResult {
        val json = exportToJson(deviceModel)
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(json, Charsets.UTF_8)
        val counts = JSONObject(json).getJSONObject("counts")
        return ExportResult(
            success = true,
            filePath = outputFile.absolutePath,
            mediaCount = counts.getInt("media"),
            faceCount = counts.getInt("faces"),
            embeddingCount = counts.getInt("embeddings"),
            ftsCount = counts.getInt("fts"),
            fileSizeBytes = outputFile.length(),
        )
    }

    private fun writeZipText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private data class EntryMetadata(val lineCount: Int, val byteSize: Long, val sha256: String) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("lines", lineCount)
            put("bytes", byteSize)
            put("sha256", sha256)
        }
    }

    private suspend fun <T, C> writeNdjsonFile(
        directory: File,
        name: String,
        initialCursor: C,
        loadPage: suspend (C) -> List<T>,
        cursorOf: (T) -> C,
        toJson: (T) -> JSONObject,
    ): EntryMetadata {
        val file = File(directory, name)
        var cursor = initialCursor
        var count = 0
        file.outputStream().buffered().use { output ->
            BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                while (true) {
                    val page = loadPage(cursor)
                    if (page.isEmpty()) break
                    page.forEach { item ->
                        writer.write(toJson(item).toString())
                        writer.newLine()
                    }
                    count += page.size
                    cursor = cursorOf(page.last())
                    if (page.size < EXPORT_PAGE_SIZE) break
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return EntryMetadata(count, file.length(), digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun writeSqlTableNdjson(
        directory: File,
        table: BackupContract.Table,
        db: androidx.sqlite.db.SupportSQLiteDatabase,
    ): EntryMetadata {
        val file = File(directory, table.entry)
        var afterRowId = 0L
        var count = 0
        file.outputStream().buffered().use { output ->
            BufferedWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                while (true) {
                    val where = listOfNotNull(table.whereClause, "rowid > ?").joinToString(" AND ")
                    val query = "SELECT rowid AS __backup_rowid, * FROM ${table.table} WHERE $where ORDER BY rowid LIMIT $EXPORT_PAGE_SIZE"
                    var pageCount = 0
                    db.query(query, arrayOf(afterRowId)).use { cursor ->
                        val rowIdIndex = cursor.getColumnIndexOrThrow("__backup_rowid")
                        while (cursor.moveToNext()) {
                            val obj = JSONObject()
                            for (index in 0 until cursor.columnCount) {
                                val name = cursor.getColumnName(index)
                                if (name == "__backup_rowid") continue
                                when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> obj.put(name, JSONObject.NULL)
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> obj.put(name, cursor.getLong(index))
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> obj.put(name, cursor.getDouble(index))
                                    android.database.Cursor.FIELD_TYPE_BLOB -> obj.put(name, android.util.Base64.encodeToString(cursor.getBlob(index), android.util.Base64.NO_WRAP))
                                    else -> obj.put(name, cursor.getString(index))
                                }
                            }
                            afterRowId = cursor.getLong(rowIdIndex)
                            writer.write(obj.toString()); writer.newLine()
                            pageCount++; count++
                        }
                    }
                    if (pageCount < EXPORT_PAGE_SIZE) break
                }
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return EntryMetadata(count, file.length(), digest.digest().joinToString("") { "%02x".format(it) })
    }

    /**
     * 将数据库导出为历史单 JSON 字符串（仅兼容旧备份与测试）。
     * 此入口有意保留全量内存读取；生产备份必须使用分页的文件导出入口。
     *
     * @param deviceModel 设备型号
     * @return JSON 字符串
     */
    @Deprecated("Legacy single-JSON compatibility only; use paged file export")
    suspend fun exportToJson(deviceModel: String = ""): String = withContext(Dispatchers.IO) {
        val mediaItems = mediaDao.getAllForLegacyExport()
        val faces = faceDao?.getAllForLegacyExport() ?: emptyList()
        val embeddings = embeddingDao?.getAllForLegacyExport() ?: emptyList()
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
            obj.put("qualityScore", item.qualityScore)
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
            // BLOB 作为 Base64 保存到文本 NDJSON；旧 JSON 读取端会忽略该字段。
            emb.embeddingBlob?.let { obj.put("embeddingBlob", android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)) }
            obj.put("modelVersion", emb.modelVersion)
            obj.put("generatedAtMs", emb.generatedAtMs)
            obj.put("source", emb.source)
            obj.put("providerId", emb.providerId)
            obj.put("modelId", emb.modelId)
            obj.put("dimension", emb.dimension)
            obj.put("spaceId", emb.spaceId)
            obj.put("generation", emb.generation)
            obj.put("codecId", emb.codecId)
            obj.put("formatVersion", emb.formatVersion)
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
