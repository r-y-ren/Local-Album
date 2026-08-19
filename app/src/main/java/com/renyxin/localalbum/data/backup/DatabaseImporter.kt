package com.renyxin.localalbum.data.backup

import androidx.room.withTransaction
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.toImportStaging
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import com.renyxin.localalbum.data.db.entity.BackupImportStagingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

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
     * 真实 Room 数据库启用 staging 隔离与最终原子切换。
     * 单元测试可省略该参数以保留 Fake DAO 兼容；生产 App 路径必须注入数据库。
     */
    private val database: AppDatabase? = null,
) {
    companion object {
        private const val TAG = "DatabaseImporter"
        /** 同时规避 SQLite 绑定变量上限及导入大备份时的单次事务内存峰值。 */
        private const val INSERT_BATCH_SIZE = 500
        private const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
        private const val MAX_LINE_BYTES = 4 * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 64
        private const val MAX_COMPRESSION_RATIO = 200L
        private val REQUIRED_ENTRIES = BackupContract.tables.filter { it.required }.map { it.entry }
        /** 进程内覆盖式导入互斥；generation 同时隔离异常退出留下的数据。 */
        private val IMPORT_MUTEX = Mutex()
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
        IMPORT_MUTEX.withLock {
            try {
                if (inputFile.inputStream().use { input -> input.read() == 0x50 && input.read() == 0x4b }) {
                    importFromZip(inputFile)
                } else {
                    // 历史 JSON 在真实 App 路径同样进入 staging；Fake DAO 测试保持兼容。
                    importFromJsonInternal(inputFile.readText(Charsets.UTF_8))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: BackupException) {
                ImportResult(success = false, errorMessage = e.backupError.friendlyMessage())
            } catch (e: Exception) {
                ImportResult(success = false, errorMessage = BackupError(type = BackupErrorType.IO, messageCode = "import_io").friendlyMessage())
            }
        }
    }

    /**
     * 从 ZIP + NDJSON 备份恢复。每次只解析和写入有限行，避免大备份在堆中形成完整 JSON 数组。
     */
    private suspend fun importFromZip(inputFile: File): ImportResult {
        // Fake DAO 路径仅用于 JVM 测试；生产构造始终注入 database 并使用 staging。
        if (database == null) return importFromZipWithoutStaging(inputFile)
        val generation = UUID.randomUUID().toString()
        try {
            ZipFile(inputFile).use { zip ->
                val manifest = validateStreamingZip(zip)
                if (manifest.first == null) return ImportResult(false, errorMessage = manifest.second)
                return importV3ToGenericStaging(zip, manifest.first!!, generation)
                /* legacy v2 typed staging retained below for source compatibility
                val mediaCount = importNdjson(zip, "media.ndjson", ::parseMediaItem) {
                    staging.insertMedia(it.map { row -> row.toImportStaging(generation) })
                }
                val faceCount = importNdjson(zip, "faces.ndjson", ::parseFace) {
                    staging.insertFaces(it.map { row -> row.toImportStaging(generation) })
                }
                val embeddingCount = importNdjson(zip, "embeddings.ndjson", ::parseEmbedding) {
                    staging.insertEmbeddings(it.map { row -> row.toImportStaging(generation) })
                }
                val ftsCount = importNdjson(zip, "fts.ndjson", ::parseFtsEntry) {
                    staging.insertFts(it.map { row -> row.toImportStaging(generation) })
                }
                verifyStagingCounts(generation, mediaCount, faceCount, embeddingCount, ftsCount)
                switchFromStaging(generation)
                return ImportResult(true, mediaCount, faceCount, embeddingCount, ftsCount) */
            }
        } finally {
            // 成功时删除已切换 generation；失败或取消时删除半成品。生产表仅可能在最终事务内变化。
            cleanupStaging(generation)
            withContext(NonCancellable) { database.backupStagingDao().clearGeneration(generation) }
        }
    }

    private suspend fun importV3ToGenericStaging(zip: ZipFile, manifest: JSONObject, generation: String): ImportResult {
        val staging = requireNotNull(database).backupStagingDao()
        val declared = manifest.getJSONArray("tables")
        val declaredEntries = (0 until declared.length()).map { declared.getJSONObject(it).getString("entry") }.toSet()
        val counts = mutableMapOf<String, Int>()
        BackupContract.tables.filter { it.entry in declaredEntries }.forEach { table ->
            val entry = zip.getEntry(table.entry)
            if (entry == null) {
                if (table.required) throw BackupException(BackupError(table.entry, type = BackupErrorType.MISSING_ENTRY, messageCode = "required_entry_missing"))
                return@forEach
            }
            var lineNumber = 0L
            val batch = ArrayList<BackupImportStagingEntity>(INSERT_BATCH_SIZE)
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach lineLoop@{ raw ->
                    lineNumber++
                    if (raw.toByteArray(Charsets.UTF_8).size > MAX_LINE_BYTES) throw BackupException(BackupError(table.entry, lineNumber, type = BackupErrorType.LINE_TOO_LONG, messageCode = "line_too_long"))
                    if (raw.isBlank()) return@lineLoop
                    val obj = try { JSONObject(raw) } catch (e: Exception) {
                        throw BackupException(BackupError(table.entry, lineNumber, type = BackupErrorType.JSON_SYNTAX, messageCode = "bad_json"), e)
                    }
                    table.blobColumns.forEach { column ->
                        if (obj.has(column) && !obj.isNull(column)) try {
                            android.util.Base64.decode(obj.getString(column), android.util.Base64.NO_WRAP)
                        } catch (e: Exception) {
                            throw BackupException(BackupError(table.entry, lineNumber, type = BackupErrorType.BASE64, messageCode = "bad_base64"), e)
                        }
                    }
                    val path = obj.optString("filePath", "")
                    val safe = if (path.isEmpty()) obj.optString("pathKey", "line:$lineNumber") else BackupError.pathHash(path)
                    batch += BackupImportStagingEntity(generation, table.entry, lineNumber, safe, obj.toString())
                    if (batch.size == INSERT_BATCH_SIZE) { staging.insertAll(batch); batch.clear() }
                }
            }
            if (batch.isNotEmpty()) staging.insertAll(batch)
            counts[table.entry] = lineNumber.toInt()
        }
        switchGenericStaging(generation, declaredEntries)
        return ImportResult(true, counts["media.ndjson"] ?: 0, counts["faces.ndjson"] ?: 0, counts["embeddings.ndjson"] ?: 0, counts["fts.ndjson"] ?: 0)
    }

    private suspend fun switchGenericStaging(generation: String, declaredEntries: Set<String>) = requireNotNull(database).withTransaction {
        val db = database.openHelper.writableDatabase
        // 子表优先清空。排除表也在同一最终事务清理，失败会完整回滚。
        // maintenance_runs 必须在恢复声明行之前整体清空；否则同库覆盖恢复时，已有
        // FACE_PROTOTYPES 主键会与 complete backup 中的同一快照冲突。
        listOf("media_items_fts", "faces", "media_embeddings", "analysis_state", "feature_store",
            "face_cluster_prototypes", "face_cluster_meta", "semantic_cluster_members", "semantic_cluster_meta",
            "semantic_cluster_generations", "semantic_index_meta", "plugin_manifest", "deletion_tombstones",
            "maintenance_runs", "media_items")
            .forEach { db.execSQL("DELETE FROM $it") }
        BackupContract.tables.filter { it.entry in declaredEntries }.forEach { table ->
            var after = 0L
            while (true) {
                val rows = database.backupStagingDao().getAfter(generation, table.entry, after, INSERT_BATCH_SIZE)
                if (rows.isEmpty()) break
                rows.forEach { row -> insertJsonRow(db, table, JSONObject(row.payload)) }
                after = rows.last().lineNumber
            }
        }
        // 瞬态与可再生表明确不恢复；租约、设备身份映射、半成品和重复结果不可跨设备继承。
        // maintenance_runs 已在导入前清空，只有备份显式声明的 FACE_PROTOTYPES 快照会被恢复。
        listOf("thumbnail_cache_entries", "thumbnail_tasks", "analysis_tasks", "enhancement_outbox", "scan_staging", "scan_runs",
            "media_change_events", "media_store_references", "semantic_maintenance_runs",
            "duplicate_hash_staging", "duplicate_members", "duplicate_groups")
            .forEach { db.execSQL("DELETE FROM $it") }
        resetThumbnailWakeHandshake(db)
        // semantic 只允许激活具有同 space 向量的 metadata/cluster generation。
        db.execSQL("UPDATE semantic_index_meta SET isActive = 0 WHERE NOT EXISTS (SELECT 1 FROM media_embeddings e WHERE e.spaceId = semantic_index_meta.spaceId)")
        db.execSQL("UPDATE semantic_cluster_generations SET isActive = 0 WHERE NOT EXISTS (SELECT 1 FROM media_embeddings e WHERE e.spaceId = semantic_cluster_generations.spaceId)")
        publishImportedBaselineAndRequireValidation(db)
    }

    private fun insertJsonRow(db: androidx.sqlite.db.SupportSQLiteDatabase, table: BackupContract.Table, obj: JSONObject) {
        if (table.table == "media_items_fts") {
            insertFtsJsonRow(db, obj)
            return
        }
        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(${table.table})").use { cursor -> while (cursor.moveToNext()) columns += cursor.getString(cursor.getColumnIndexOrThrow("name")) }
        val present = columns.filter { obj.has(it) }
        val values = present.map { name ->
            if (obj.isNull(name)) null else if (name in table.blobColumns) android.util.Base64.decode(obj.getString(name), android.util.Base64.NO_WRAP)
            else obj.get(name).let { if (it is Boolean) if (it) 1L else 0L else it }
        }.toTypedArray()
        val sql = "INSERT INTO ${table.table}(${present.joinToString()}) VALUES(${present.joinToString { "?" }})"
        db.execSQL(sql, values)
    }

    /** v31 及更早备份没有 FTS parentPath；以已恢复媒体表为可信回填源。 */
    private fun insertFtsJsonRow(db: androidx.sqlite.db.SupportSQLiteDatabase, obj: JSONObject) {
        val filePath = obj.getString("filePath")
        val parentPath = obj.optStringOrNull("parentPath") ?: db.query(
            "SELECT parentPath FROM media_items WHERE filePath = ? LIMIT 1",
            arrayOf(filePath),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else inferParentPath(filePath) }
        db.execSQL(
            "INSERT INTO media_items_fts(filePath,fileName,parentPath,ocrText,make,model) VALUES(?,?,?,?,?,?)",
            arrayOf(
                filePath,
                obj.getString("fileName"),
                parentPath,
                obj.optStringOrNull("ocrText"),
                obj.optStringOrNull("make"),
                obj.optStringOrNull("model"),
            ),
        )
    }

    private suspend fun importFromZipWithoutStaging(inputFile: File): ImportResult = ZipFile(inputFile).use { zip ->
        val validation = validateStreamingZip(zip)
        if (validation.first == null) return@use ImportResult(false, errorMessage = validation.second)
        mediaDao.clearAll(); mediaDao.clearAllFts(); faceDao?.clearAll(); embeddingDao?.clearAll()
        val media = importNdjson(zip, "media.ndjson", ::parseMediaItem) { mediaDao.insertAll(it) }
        val faces = importNdjson(zip, "faces.ndjson", ::parseFace) { faceDao?.insertFaces(it) }
        val embeddings = importNdjson(zip, "embeddings.ndjson", ::parseEmbedding) { embeddingDao?.insertEmbeddings(it) }
        val fts = importNdjson(zip, "fts.ndjson", ::parseFtsEntry) { mediaDao.insertFtsAll(it) }
        ImportResult(true, media, faces, embeddings, fts)
    }

    private suspend fun <T> importNdjson(
        zip: ZipFile,
        entryName: String,
        parse: (JSONObject) -> T,
        insert: suspend (List<T>) -> Unit,
    ): Int {
        val entry = zip.getEntry(entryName) ?: return 0
        var count = 0
        val batch = ArrayList<T>(INSERT_BATCH_SIZE)
        zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                batch += parse(JSONObject(line))
                if (batch.size == INSERT_BATCH_SIZE) {
                    insert(batch)
                    count += batch.size
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) {
            insert(batch)
            count += batch.size
        }
        return count
    }

    private fun parseMediaItem(obj: JSONObject): MediaEntity =
        parseMediaItems(JSONArray().put(obj)).first()

    private fun parseFace(obj: JSONObject): FaceEntity =
        parseFaces(JSONArray().put(obj)).first()

    private fun parseEmbedding(obj: JSONObject): MediaEmbedding =
        parseEmbeddings(JSONArray().put(obj)).first()

    private fun parseFtsEntry(obj: JSONObject): MediaFts =
        parseFtsEntries(JSONArray().put(obj)).first()

    /**
     * 从 JSON 字符串导入数据库（用于测试或流式传输）。
     *
     * @param json JSON 字符串
     * @return 导入结果
     */
    suspend fun importFromJson(json: String): ImportResult = withContext(Dispatchers.IO) {
        IMPORT_MUTEX.withLock { importFromJsonInternal(json) }
    }

    private suspend fun importFromJsonInternal(json: String): ImportResult {
        try {
            val root = JSONObject(json)

            // 校验格式版本
            val version = root.optInt("version", -1)
            if (version != DatabaseExporter.EXPORT_FORMAT_VERSION) {
                return ImportResult(
                    success = false,
                    errorMessage = "不支持的导出格式版本: $version（当前支持 ${DatabaseExporter.EXPORT_FORMAT_VERSION}）",
                )
            }

            // 解析各表数据。旧备份的 FTS 没有 parentPath，以同一备份中的媒体行为可信回填源。
            val mediaItems = parseMediaItems(root.optJSONArray("mediaItems") ?: return ImportResult(success = false, errorMessage = "缺少 mediaItems 字段"))
            val faces = parseFaces(root.optJSONArray("faces"))
            val embeddings = parseEmbeddings(root.optJSONArray("embeddings"))
            val parentPaths = mediaItems.associate { it.filePath to it.parentPath }
            val ftsEntries = parseFtsEntries(root.optJSONArray("ftsEntries"), parentPaths)

            if (database != null) return importParsedViaStaging(mediaItems, faces, embeddings, ftsEntries)

            // Fake DAO 兼容路径；真实 App 的旧 JSON 也走 staging。
            suspend fun restoreAll() {
                mediaDao.clearAll()
                mediaDao.clearAllFts()
                faceDao?.clearAll()
                embeddingDao?.clearAll()

                // Room 的批量 @Insert 会把整个 List 一次交给 SQLite。
                // 大备份中的媒体、人脸与高维嵌入可达数万条，分块可避免 Binder/SQLite
                // 参数数组及事务缓存同时膨胀而造成导入缓慢或 OOM。
                mediaItems.chunked(INSERT_BATCH_SIZE).forEach { chunk -> mediaDao.insertAll(chunk) }
                ftsEntries.chunked(INSERT_BATCH_SIZE).forEach { chunk -> mediaDao.insertFtsAll(chunk) }
                faces.chunked(INSERT_BATCH_SIZE).forEach { chunk -> faceDao?.insertFaces(chunk) }
                embeddings.chunked(INSERT_BATCH_SIZE).forEach { chunk -> embeddingDao?.insertEmbeddings(chunk) }
            }
            restoreAll()

            return ImportResult(
                success = true,
                mediaCount = mediaItems.size,
                faceCount = faces.size,
                embeddingCount = embeddings.size,
                ftsCount = ftsEntries.size,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return ImportResult(success = false, errorMessage = e.message)
        }
    }

    private suspend fun importParsedViaStaging(
        media: List<MediaEntity>, faces: List<FaceEntity>, embeddings: List<MediaEmbedding>, fts: List<MediaFts>,
    ): ImportResult {
        val generation = UUID.randomUUID().toString()
        val staging = requireNotNull(database).importStagingDao()
        try {
            media.chunked(INSERT_BATCH_SIZE).forEach { staging.insertMedia(it.map { row -> row.toImportStaging(generation) }) }
            faces.chunked(INSERT_BATCH_SIZE).forEach { staging.insertFaces(it.map { row -> row.toImportStaging(generation) }) }
            embeddings.chunked(INSERT_BATCH_SIZE).forEach { staging.insertEmbeddings(it.map { row -> row.toImportStaging(generation) }) }
            fts.chunked(INSERT_BATCH_SIZE).forEach { staging.insertFts(it.map { row -> row.toImportStaging(generation) }) }
            verifyStagingCounts(generation, media.size, faces.size, embeddings.size, fts.size)
            switchFromStaging(generation)
            return ImportResult(true, media.size, faces.size, embeddings.size, fts.size)
        } finally {
            cleanupStaging(generation)
        }
    }

    private suspend fun verifyStagingCounts(generation: String, media: Int, faces: Int, embeddings: Int, fts: Int) {
        val dao = requireNotNull(database).importStagingDao()
        check(dao.mediaCount(generation) == media) { "媒体 staging 计数复核失败" }
        check(dao.faceCount(generation) == faces) { "人脸 staging 计数复核失败" }
        check(dao.embeddingCount(generation) == embeddings) { "嵌入 staging 计数复核失败" }
        check(dao.ftsCount(generation) == fts) { "FTS staging 计数复核失败" }
    }

    /**
     * 唯一会修改生产四表的边界。事务内只有 SQLite DELETE 与 INSERT ... SELECT，
     * 不含 ZIP/JSON 解析、文件 IO 或 Kotlin 批次循环；复制耗时仍与备份行数线性相关。
     */
    private suspend fun switchFromStaging(generation: String) = requireNotNull(database).withTransaction {
        val db = database.openHelper.writableDatabase
        db.execSQL("DELETE FROM media_items_fts")
        db.execSQL("DELETE FROM faces")
        db.execSQL("DELETE FROM media_embeddings")
        db.execSQL("DELETE FROM media_items")
        listOf(
            "thumbnail_cache_entries", "thumbnail_tasks", "analysis_tasks", "enhancement_outbox",
            "scan_staging", "scan_runs", "media_change_events", "media_store_references",
        ).forEach { table -> db.execSQL("DELETE FROM $table") }
        resetThumbnailWakeHandshake(db)
        db.execSQL("""INSERT INTO media_items SELECT filePath,fileName,mediaType,capturedAtMs,modifiedAtMs,indexedAtMs,parentPath,fileSize,isFavorite,isTrashed,width,height,mimeType,durationMs,latitude,longitude,make,model,aperture,focalLength,iso,exposureTime,orientation,sceneType,thumbnailPath,fingerprintHead,perceptualHash,ocrText,qualityScore,deletedAtMs,isCorrupted,faceClusterId,scanGeneration FROM import_media_staging WHERE generation = ?""", arrayOf(generation))
        db.execSQL("""INSERT INTO faces(faceId,filePath,clusterId,personName,embedding,boxLeft,boxTop,boxRight,boxBottom,thumbnailPath,detectedAtMs) SELECT faceId,filePath,clusterId,personName,embedding,boxLeft,boxTop,boxRight,boxBottom,thumbnailPath,detectedAtMs FROM import_face_staging WHERE generation = ?""", arrayOf(generation))
        db.execSQL("""INSERT INTO media_embeddings(filePath,embedding,modelVersion,generatedAtMs,source,embeddingBlob,providerId,modelId,dimension,spaceId,generation,codecId,formatVersion) SELECT filePath,embedding,modelVersion,generatedAtMs,source,embeddingBlob,providerId,modelId,dimension,spaceId,vectorGeneration,codecId,formatVersion FROM import_embedding_staging WHERE generation = ?""", arrayOf(generation))
        // 导入结果默认不激活任何空间；运行时 Provider 需重新声明/建立 metadata，避免跨设备静默混用。
        db.execSQL("UPDATE semantic_index_meta SET isActive = 0")
        db.execSQL("UPDATE semantic_cluster_generations SET isActive = 0")
        db.execSQL("""INSERT INTO media_items_fts(filePath,fileName,parentPath,ocrText,make,model) SELECT filePath,fileName,parentPath,ocrText,make,model FROM import_fts_staging WHERE generation = ?""", arrayOf(generation))
        publishImportedBaselineAndRequireValidation(db)
    }

    private fun resetThumbnailWakeHandshake(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM thumbnail_lane_wake")
        listOf("interactive","automatic").forEach { laneId ->
            db.execSQL(
                """INSERT INTO thumbnail_lane_wake(
                    laneId,deliveryState,revision,dispatchToken,dispatchLeaseUntil,runToken,
                    runLeaseUntil,notBefore,enqueueAttemptCount,nextDispatchAt,lastDispatchError,updatedAt
                ) VALUES(?,'QUIESCENT',0,NULL,0,NULL,0,0,0,0,NULL,0)""".trimIndent(),
                arrayOf(laneId),
            )
        }
    }

    /**
     * Backup excludes device-local thumbnails and the library control plane. Rebuild both from the
     * restored canonical rows in the same production-table switch transaction. The imported index
     * remains visible with stable placeholders, while root traversal requires explicit confirmation.
     */
    private fun publishImportedBaselineAndRequireValidation(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        now: Long = System.currentTimeMillis(),
    ) {
        db.execSQL("UPDATE media_items SET thumbnailPath = NULL")
        db.execSQL("DELETE FROM home_media_snapshot")
        db.execSQL(
            """INSERT INTO home_media_snapshot(
                filePath,fileName,mediaType,capturedAtMs,modifiedAtMs,fileSize,isFavorite,
                width,height,mimeType,durationMs,parentPath,latitude,longitude,make,model,
                aperture,focalLength,iso,exposureTime,orientation,thumbnailPath,thumbnailState,
                isCorrupted,publishedGeneration
            ) SELECT filePath,fileName,mediaType,capturedAtMs,modifiedAtMs,fileSize,isFavorite,
                width,height,mimeType,durationMs,parentPath,latitude,longitude,make,model,
                aperture,focalLength,iso,exposureTime,orientation,NULL,'PLACEHOLDER',
                isCorrupted,?
              FROM media_items WHERE isTrashed = 0""",
            arrayOf(now),
        )
        db.execSQL(
            """INSERT OR REPLACE INTO library_pipeline(
                pipelineId,stage,activeRunId,candidateGeneration,publishedGeneration,
                hasPublishedBaseline,rebuildRequested,rebuildRequired,rebuildReason,
                progressCompleted,progressTotal,failureCount,lastError,updatedAt
            ) VALUES('default','NEEDS_REBUILD',NULL,0,?,1,0,1,
                'backup_import_requires_validation',0,0,0,NULL,?)""",
            arrayOf(now, now),
        )
    }

    private suspend fun cleanupStaging(generation: String) = withContext(NonCancellable) {
        val dao = database?.importStagingDao() ?: return@withContext
        // 取消协程已经处于 cancelled 状态时，普通挂起清理可能永远无法执行；
        // NonCancellable 仅包围本地 SQLite DELETE，不包含解析、IO 或后续业务。
        dao.clearMedia(generation)
        dao.clearFaces(generation)
        dao.clearEmbeddings(generation)
        dao.clearFts(generation)
    }

    /** 清理进程异常退出留下的所有 staging generation，不触碰生产数据。 */
    suspend fun cleanupStaleStaging() = withContext(Dispatchers.IO) {
        IMPORT_MUTEX.withLock {
            val currentDatabase = database ?: return@withLock
            val dao = currentDatabase.importStagingDao()
            dao.clearAllMedia(); dao.clearAllFaces(); dao.clearAllEmbeddings(); dao.clearAllFts()
            currentDatabase.backupStagingDao().clearAll()
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
            if (inputFile.inputStream().use { input -> input.read() == 0x50 && input.read() == 0x4b }) {
                ZipFile(inputFile).use { zip ->
                    val validation = validateStreamingZip(zip)
                    validation.first?.optJSONObject("counts") to validation.second
                }
            } else {
                val root = JSONObject(inputFile.readText(Charsets.UTF_8))
                val version = root.optInt("version", -1)
                if (version != DatabaseExporter.EXPORT_FORMAT_VERSION) {
                    null to "不支持的导出格式版本: $version"
                } else {
                    root.optJSONObject("counts") to null
                }
            }
        } catch (e: Exception) {
            null to e.message
        }
    }

    private fun validateStreamingZip(zip: ZipFile): Pair<JSONObject?, String?> {
        val names = zip.entries().toList().map { it.name }
        if (names.size > MAX_ZIP_ENTRIES) return null to BackupError(type = BackupErrorType.ZIP_BOMB, messageCode = "too_many_entries").friendlyMessage()
        if (names.size != names.toSet().size) return null to BackupError(type = BackupErrorType.DUPLICATE_ENTRY, messageCode = "duplicate_entry").friendlyMessage()
        if (names.any { it.startsWith("/") || it.startsWith("\\") || it.split('/', '\\').any { part -> part == ".." } })
            return null to BackupError(type = BackupErrorType.ZIP_SLIP, messageCode = "zip_slip").friendlyMessage()
        val manifestEntry = zip.getEntry("manifest.json") ?: return null to "备份缺少 manifest.json"
        if (manifestEntry.size !in 0..MAX_MANIFEST_BYTES) return null to "manifest.json 超过大小上限"
        val manifestText = zip.getInputStream(manifestEntry).use { input ->
            val bytes = input.readBytes(); if (bytes.size > MAX_MANIFEST_BYTES) return null to "manifest.json 超过大小上限"
            bytes.toString(Charsets.UTF_8)
        }
        val manifest = JSONObject(manifestText)
        if (manifest.optInt("version", -1) != BackupContract.VERSION || manifest.optString("format") != BackupContract.FORMAT)
            return null to BackupError(type = BackupErrorType.VERSION, messageCode = "unsupported_version").friendlyMessage()
        val capabilities = manifest.optJSONArray("capabilities") ?: return null to "备份清单缺少 capabilities"
        val capabilitySet = (0 until capabilities.length()).map { capabilities.getString(it) }.toSet()
        if ((capabilitySet - BackupContract.knownCapabilities).isNotEmpty() || !capabilitySet.containsAll(BackupContract.requiredCapabilities))
            return null to BackupError(type = BackupErrorType.CAPABILITY, messageCode = "unsupported_capability").friendlyMessage()
        val tables = manifest.optJSONArray("tables") ?: return null to "备份清单缺少 tables"
        val declared = (0 until tables.length()).map { tables.getJSONObject(it).getString("entry") }.toSet()
        val profileRequired = if (manifest.optString("profile") == "complete") REQUIRED_ENTRIES else listOf("media.ndjson", "faces.ndjson", "embeddings.ndjson")
        if (!declared.containsAll(profileRequired)) return null to BackupError(type = BackupErrorType.MISSING_ENTRY, messageCode = "required_entry_not_declared").friendlyMessage()
        if ((declared - BackupContract.byEntry.keys).isNotEmpty() || (names.toSet() - declared - "manifest.json").isNotEmpty())
            return null to BackupError(type = BackupErrorType.UNKNOWN_ENTRY, messageCode = "unknown_entry").friendlyMessage()
        val metadata = manifest.optJSONObject("entries") ?: return null to "备份清单缺少 entries 校验信息"
        var totalBytes = 0L
        for (name in declared) {
            val entry = zip.getEntry(name) ?: return null to BackupError(name, type = BackupErrorType.MISSING_ENTRY, messageCode = "missing_entry").friendlyMessage()
            val expected = metadata.optJSONObject(name) ?: return null to "清单缺少 $name"
            if (entry.size > MAX_ENTRY_BYTES || (entry.compressedSize > 0 && entry.size > entry.compressedSize * MAX_COMPRESSION_RATIO))
                return null to BackupError(name, type = BackupErrorType.ZIP_BOMB, messageCode = "entry_limit").friendlyMessage()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L; var lines = 0; var lineBytes = 0
            zip.getInputStream(entry).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer); if (read < 0) break
                    bytes += read; totalBytes += read
                    if (bytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) return null to BackupError(name, type = BackupErrorType.ZIP_BOMB, messageCode = "expanded_limit").friendlyMessage()
                    digest.update(buffer, 0, read)
                    for (index in 0 until read) if (buffer[index] == '\n'.code.toByte()) { lines++; lineBytes = 0 } else if (++lineBytes > MAX_LINE_BYTES)
                        return null to BackupError(name, (lines + 1).toLong(), type = BackupErrorType.LINE_TOO_LONG, messageCode = "line_limit").friendlyMessage()
                }
            }
            if (lineBytes > 0) lines++
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (bytes != expected.optLong("bytes", -1) || lines != expected.optInt("lines", -1) || hash != expected.optString("sha256"))
                return null to BackupError(name, type = BackupErrorType.HASH_MISMATCH, messageCode = "entry_integrity").friendlyMessage()
        }
        return manifest to null
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
                    embeddingBlob = obj.optStringOrNull("embeddingBlob")?.let {
                        android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
                    },
                    providerId = obj.optString("providerId", "legacy"),
                    modelId = obj.optString("modelId", "legacy"),
                    dimension = obj.optInt("dimension", 0),
                    spaceId = obj.optString("spaceId", com.renyxin.localalbum.core.search.SemanticVectorSpace.LEGACY_SPACE_ID),
                    generation = obj.optLong("generation", 0L),
                    codecId = obj.optString("codecId", com.renyxin.localalbum.core.search.EmbeddingCodec.CODEC_ID),
                    formatVersion = obj.optInt("formatVersion", com.renyxin.localalbum.core.search.EmbeddingCodec.FORMAT_VERSION),
                ),
            )
        }
        return list
    }

    private fun parseFtsEntries(
        arr: org.json.JSONArray?,
        parentPaths: Map<String, String> = emptyMap(),
    ): List<MediaFts> {
        if (arr == null) return emptyList()
        val list = mutableListOf<MediaFts>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val filePath = obj.getString("filePath")
            list.add(
                MediaFts(
                    filePath = filePath,
                    fileName = obj.getString("fileName"),
                    parentPath = obj.optStringOrNull("parentPath")
                        ?: parentPaths[filePath]
                        ?: inferParentPath(filePath),
                    ocrText = obj.optStringOrNull("ocrText"),
                    make = obj.optStringOrNull("make"),
                    model = obj.optStringOrNull("model"),
                ),
            )
        }
        return list
    }

    private fun inferParentPath(filePath: String): String {
        val separator = maxOf(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'))
        return if (separator < 0) "" else filePath.substring(0, separator)
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
