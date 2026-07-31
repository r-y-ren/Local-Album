package com.renyxin.localalbum.core.index

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.ScanRunDao
import com.renyxin.localalbum.data.db.dao.ScanStagingDao
import com.renyxin.localalbum.data.db.dao.DeletionTombstoneDao
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.entity.ScanStagingEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.worker.AnalysisWorker
import com.renyxin.localalbum.data.db.entity.MediaFts
import com.renyxin.localalbum.data.source.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import com.renyxin.localalbum.core.concurrent.InferenceDispatchers
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * 混合增量索引引擎。
 *
 * 策略 (按 PRODUCT_SPEC_V2 §5.2):
 * - MediaStore 负责高频增量检测（快速感知新增/删除）
 * - File API 负责完整性回补和异常修复
 * - fingerprintHead 作为 modifiedAt 变化时的内容真实性二次校验（mtime 相同视为未变更，跳过指纹计算）
 * - 扫描流水线：枚举 → 元数据提取 → 缩略图 → 入库
 */
class HybridIndexer(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val database: com.renyxin.localalbum.data.db.AppDatabase? = null,
    private val faceDao: FaceDao? = null,
    private val embeddingDao: com.renyxin.localalbum.data.db.dao.EmbeddingDao? = null,
    private val mediaSource: MediaSource = MediaSource(context),
    private val pluginPipeline: PluginAnalysisPipeline? = null,
    private val analysisStateDao: com.renyxin.localalbum.data.db.dao.AnalysisStateDao? = null,
    private val analysisTaskDao: com.renyxin.localalbum.data.db.dao.AnalysisTaskDao? = null,
    private val featureStoreDao: com.renyxin.localalbum.data.db.dao.FeatureStoreDao? = null,
    private val thumbnailTaskDao: com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao? = null,
    private val scanRunDao: ScanRunDao? = null,
    private val scanStagingDao: ScanStagingDao? = null,
    private val deletionTombstoneDao: DeletionTombstoneDao? = null,
    private val mediaDeletionCoordinator: com.renyxin.localalbum.data.repo.MediaDeletionCoordinator? = null,
) {
    /**
     * Phase 1: 清空全部分析断点状态。forceReanalyzeAll 全量重分析前调用，
     * 确保断点续跑不会跳过需重跑的文件。
     */
    suspend fun clearAnalysisState() {
        analysisStateDao?.deleteAll()
    }

    /**
     * Phase 1: 按路径失效分析断点状态（彻底删除媒体时调用，避免残留）。
     */
    suspend fun invalidateAnalysisState(paths: List<String>) {
        if (paths.isEmpty() || analysisStateDao == null) return
        paths.chunked(500).forEach { chunk ->
            analysisStateDao.deleteByPaths(chunk)
        }
    }

    /**
     * Phase 1: 已完成分析（任意阶段）的去重文件数，供 UI 显示分析完成度。
     */
    suspend fun countAnalysisDonePaths(): Int {
        return analysisStateDao?.countDistinctDonePaths() ?: 0
    }
    companion object {
        private const val TAG = "HybridIndexer"
        private const val HEAD_HASH_BYTES = 4096 // 读取前4KB计算头部哈希
        /** 每批入库上限：限制大相册扫描时实体列表和单次 SQLite 事务的内存峰值。 */
        private const val DATABASE_BATCH_SIZE = 500
        /**
         * AI 管道的有界批次大小。阶段内虽已有限并发，但若向 runFullScan 传入整库路径，
         * DAG 层仍会持有全量输入并等待整层完成，造成长时间不可用和内存峰值。
         */
        private const val ENUMERATION_BATCH_SIZE = 250
        private const val ENUMERATION_CHANNEL_CAPACITY = 2
        private const val SOURCE_MEDIA_STORE = 1
        private const val SOURCE_FILE_SYSTEM = 2
    }


    /** 获取插件化分析管道，供兼容调用使用。 */
    internal fun getPluginPipeline() = pluginPipeline

    /** 将全部已有任务重置为用户优先级，并启动有界领取。 */
    suspend fun requestFullReanalysis() {
        analysisStateDao?.deleteAll()
        analysisTaskDao?.resetAll(
            AnalysisTaskEntity.PRIORITY_USER,
            System.currentTimeMillis(),
            currentPipelineScope(),
        )
        AnalysisWorker.enqueue(context)
    }

    /** 启动时由 WorkManager 继续领取持久任务。 */
    fun resumePendingAnalysis() = AnalysisWorker.enqueue(context)

    /** 当前已注册的 ContentObserver，用于注销时引用 */
    @Volatile
    private var registeredObserver: MediaContentObserver? = null

    /**
     * 注册 [MediaContentObserver] 监听 MediaStore 图片/视频变更。
     * 当 MediaStore 发生变化时，防抖后触发 [onChanged] 回调（通常为增量扫描）。
     *
     * 生命周期由调用方（如 [com.renyxin.localalbum.AppContainer] / Application）管理，
     * 应在 [unregisterContentObserver] 时注销以避免泄漏。
     *
     * @param onChanged MediaStore 变更防抖后的回调
     */
    fun registerContentObserver(onChanged: () -> Unit) {
        if (registeredObserver != null) {
            Log.w(TAG, "ContentObserver 已注册，跳过重复注册")
            return
        }
        val observer = MediaContentObserver(onChanged)
        // 监听图片和视频两个 URI
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // 通知子级变更
            observer,
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        observer.markRegistered()
        registeredObserver = observer
        Log.i(TAG, "MediaContentObserver 已注册")
    }

    /**
     * 注销已注册的 [MediaContentObserver]，释放资源。
     */
    fun unregisterContentObserver() {
        val observer = registeredObserver ?: return
        context.contentResolver.unregisterContentObserver(observer)
        observer.markUnregistered()
        registeredObserver = null
        Log.i(TAG, "MediaContentObserver 已注销")
    }

    private val imageProjection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.ORIENTATION,
    )

    private val videoProjection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.DURATION,
    )

    /**
     * 全量扫描：清空数据库，从 MediaStore + File API 重建索引。
     * @param roots 用户配置的扫描根目录列表
     * @param allowNomedia 为 true 时不再跳过含 .nomedia 的目录
     * @return 索引的媒体项总数
     */
    suspend fun fullScan(
        roots: List<String>,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
        progress: ((processed: Int, total: Int) -> Unit)? = null,
    ): Int = executeScanRun(ScanRunEntity.TYPE_FULL, roots) { scanId, generation ->
        scanViaStaging(scanId, generation, roots, allowNomedia, ignorePatterns, progress).indexed
    }

    /**
     * 增量扫描：比较 modifiedAt + fingerprintHead，仅更新有变化的文件。
     * @param roots 用户配置的扫描根目录列表
     * @param allowNomedia 为 true 时不再跳过含 .nomedia 的目录
     * @return 更新的媒体项数量
     */
    suspend fun incrementalScan(
        roots: List<String>,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
    ): IncrementalResult = executeScanRun(ScanRunEntity.TYPE_INCREMENTAL, roots) { scanId, generation ->
        val start = System.currentTimeMillis()
        val result = scanViaStaging(scanId, generation, roots, allowNomedia, ignorePatterns, null)
        IncrementalResult(
            inserted = result.inserted,
            updated = result.updated,
            deleted = result.deleted,
            elapsedMs = System.currentTimeMillis() - start,
        )
    }

    private suspend fun scanViaStaging(
        scanId: String,
        generation: Long,
        roots: List<String>,
        allowNomedia: Boolean,
        ignorePatterns: List<String>,
        progress: ((processed: Int, total: Int) -> Unit)?,
    ): ScanCommitResult = coroutineScope {
        val staging = requireScanStagingDao()
        val channel = Channel<List<ScanStagingEntity>>(ENUMERATION_CHANNEL_CAPACITY)
        val insertedCounter = AtomicInteger(0)
        val updatedCounter = AtomicInteger(0)
        val consumer = launch(Dispatchers.IO) {
            for (batch in channel) {
                staging.mergeBatch(batch)
                val mergedRows = staging.getByPaths(scanId, batch.map { it.filePath })
                val committed = commitStagedBatch(
                    mergedRows.map { ScanMediaCodec.merge(it.mediaStoreJson, it.fileSystemJson) },
                    generation,
                )
                insertedCounter.addAndGet(committed.inserted)
                updatedCounter.addAndGet(committed.updated)
                val visible = staging.count(scanId)
                progress?.invoke(visible, visible)
            }
        }
        try {
            enumerateMediaStoreBatches(roots, ignorePatterns) { items ->
                channel.send(items.map { item ->
                    ScanStagingEntity(scanId, item.filePath, SOURCE_MEDIA_STORE, mediaStoreJson = ScanMediaCodec.encode(item))
                })
            }
            requireScanRunDao().markMediaStoreCompleted(scanId)
            mediaSource.enumerateMediaBatches(
                roots,
                allowNomedia = allowNomedia,
                ignorePatterns = ignorePatterns,
                batchSize = ENUMERATION_BATCH_SIZE,
            ) { items ->
                channel.send(items.map { item ->
                    ScanStagingEntity(scanId, item.filePath, SOURCE_FILE_SYSTEM, fileSystemJson = ScanMediaCodec.encode(item))
                })
            }
            requireScanRunDao().markFileSystemCompleted(scanId)
        } finally {
            channel.close()
        }
        consumer.join()

        check(requireScanRunDao().canDeleteOrphans(scanId) == true) {
            "扫描来源未完整完成，拒绝提交 staging"
        }
        // 每个来源批次在 staging 合并后已立即提交正式表，因此首批无需等待完整枚举即可由 Paging 读取。
        val indexed = staging.count(scanId)
        var deleted = 0
        while (true) {
            val orphaned = mediaDao.getPathsOutsideGeneration(generation, DATABASE_BATCH_SIZE)
            if (orphaned.isEmpty()) break
            val coordinator = mediaDeletionCoordinator
                ?: error("MediaDeletionCoordinator 未注入，拒绝直接删除扫描孤儿")
            coordinator.purge(orphaned)
            deleted += orphaned.size
        }
        check(requireScanRunDao().markCompleted(scanId, System.currentTimeMillis())) {
            "扫描完成状态切换失败"
        }
        staging.deleteByScanId(scanId)
        AnalysisWorker.enqueue(context)
        ScanCommitResult(indexed, insertedCounter.get(), updatedCounter.get(), deleted)
    }

    private suspend fun commitStagedBatch(items: List<MediaItem>, generation: Long): BatchCommitCount {
        val fresh = items.map { it.toEntity() }
        val paths = fresh.map { it.filePath }
        val existingByPath = mediaDao.getByFilePathsLight(paths).associateBy { it.filePath }
        val tombstones = deletionTombstoneDao?.getByPaths(paths).orEmpty().associateBy { it.filePath }
        val upserts = fresh.map { entity ->
            val existing = existingByPath[entity.filePath]
            val tombstone = tombstones[entity.filePath]
            if (existing == null) entity.copy(
                isTrashed = tombstone != null,
                deletedAtMs = tombstone?.deletedAtMs ?: 0,
                scanGeneration = generation,
            ) else entity.copy(
                isFavorite = existing.isFavorite,
                isTrashed = existing.isTrashed,
                thumbnailPath = existing.thumbnailPath,
                sceneType = existing.sceneType,
                perceptualHash = existing.perceptualHash,
                ocrText = existing.ocrText,
                qualityScore = existing.qualityScore,
                deletedAtMs = existing.deletedAtMs,
                isCorrupted = existing.isCorrupted,
                faceClusterId = existing.faceClusterId,
                scanGeneration = generation,
            ).let { preserved ->
                // active/completed tombstone 均代表尚未显式 restore 的用户删除意图。
                if (tombstone == null) preserved else preserved.copy(
                    isTrashed = true,
                    deletedAtMs = maxOf(preserved.deletedAtMs, tombstone.deletedAtMs),
                )
            }
        }
        val insertedCount = upserts.count { existingByPath[it.filePath] == null }
        val changed = upserts.filter { entity ->
            val old = existingByPath[entity.filePath]
            old == null || old.modifiedAtMs != entity.modifiedAtMs || old.fileSize != entity.fileSize || old.fingerprintHead != entity.fingerprintHead
        }
        val now = System.currentTimeMillis()
        suspend fun commit() {
            if (changed.isNotEmpty()) analysisStateDao?.deleteByPaths(changed.map { it.filePath })
            mediaDao.insertAll(upserts)
            mediaDao.deleteFtsEntries(upserts.map { it.filePath })
            mediaDao.insertFtsAll(upserts.map { MediaFts(it.filePath, it.fileName, it.ocrText, it.make, it.model) })
            enqueueAnalysisTasks(changed.filter { it.mediaType == MediaType.IMAGE })
            thumbnailTaskDao?.enqueueAll(changed.map { entity ->
                ThumbnailTaskEntity(
                    filePath = entity.filePath,
                    sourceVersion = "${entity.modifiedAtMs}:${entity.fileSize}",
                    mediaType = entity.mediaType.name,
                    createdAt = now,
                    updatedAt = now,
                )
            })
        }
        if (database != null) database.withTransaction { commit() } else commit()
        return BatchCommitCount(insertedCount, changed.size - insertedCount)
    }

    private fun requireScanStagingDao(): ScanStagingDao =
        scanStagingDao ?: error("ScanStagingDao 未注入，拒绝回退全量内存扫描")

    private fun requireScanRunDao(): ScanRunDao =
        scanRunDao ?: error("ScanRunDao 未注入，拒绝执行无完整性门禁的扫描")

    private suspend fun <T> executeScanRun(
        scanType: String,
        roots: List<String>,
        block: suspend (scanId: String, generation: Long) -> T,
    ): T = withContext(Dispatchers.IO) {
        validateScanRoots(roots)
        val dao = requireScanRunDao()
        dao.abortStaleRunning(System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val generation = maxOf(now, (dao.getMaxGeneration() ?: 0L) + 1L)
        val scanId = java.util.UUID.randomUUID().toString()
        dao.insert(
            ScanRunEntity(
                scanId = scanId,
                generation = generation,
                scanType = scanType,
            ),
        )
        try {
            requireScanStagingDao().deleteByScanId(scanId)
            block(scanId, generation)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            dao.markTerminal(
                scanId,
                ScanRunEntity.STATUS_ABORTED,
                System.currentTimeMillis(),
                "cancelled",
            )
            requireScanStagingDao().deleteByScanId(scanId)
            throw cancelled
        } catch (error: Throwable) {
            dao.markTerminal(
                scanId,
                ScanRunEntity.STATUS_FAILED,
                System.currentTimeMillis(),
                error.javaClass.simpleName.take(80),
            )
            requireScanStagingDao().deleteByScanId(scanId)
            throw error
        }
    }

    private fun validateScanRoots(roots: List<String>) {
        require(roots.isNotEmpty()) { "扫描根目录为空" }
        roots.forEach { path ->
            val root = File(path)
            require(root.exists() && root.isDirectory && root.canRead()) {
                "扫描根目录不可访问: ${root.name}"
            }
        }
    }

    private fun currentPipelineScope(): String =
        pluginPipeline?.pipelineScope ?: AnalysisTaskEntity.DEFAULT_PIPELINE_SCOPE

    private suspend fun enqueueAnalysisTasks(entities: List<MediaEntity>) {
        val dao = analysisTaskDao ?: return
        if (entities.isEmpty()) return
        val now = System.currentTimeMillis()
        val scope = currentPipelineScope()
        dao.enqueueAll(entities.map { entity ->
            AnalysisTaskEntity(
                filePath = entity.filePath,
                sourceVersion = "${entity.modifiedAtMs}:${entity.fileSize}",
                pipelineScope = scope,
                createdAt = now,
                updatedAt = now,
            )
        })
    }

    /** import switch 白名单之外，扫描孤儿必须走统一 coordinator。 */
    private suspend fun purgeOrphanPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        requireNotNull(mediaDeletionCoordinator) { "MediaDeletionCoordinator 未注入" }.purge(paths)
    }


    /**
     * 从 MediaStore 查询所有图片和视频。
     */
    /**
     * 安全获取 cursor 列索引，若列不存在则返回 -1 而非抛异常。
     * 兼容不同 ROM 对 MediaStore 列的差异支持。
     */
    private fun android.database.Cursor.getColumnIndexSafe(columnName: String): Int {
        return try {
            getColumnIndexOrThrow(columnName)
        } catch (_: IllegalArgumentException) {
            -1
        }
    }

    /**
     * 判断文件路径是否位于任一扫描根目录下。
     * 路径末尾不加 "/" 时做前缀匹配，并额外校验匹配后下一字符为 "/" 或已到达末尾，
     * 避免 /DCIM2 误匹配 /DCIM。
     */
    private fun isUnderAnyRoot(filePath: String, roots: List<String>): Boolean {
        if (roots.isEmpty()) return true // 无根目录配置时保持向后兼容
        return roots.any { root ->
            filePath.startsWith(root) &&
                (filePath.length == root.length || filePath[root.length] == '/')
        }
    }

    private suspend fun enumerateMediaStoreBatches(
        roots: List<String>,
        ignorePatterns: List<String>,
        emit: suspend (List<MediaItem>) -> Unit,
    ) {
        val compiledIgnore = IgnorePatternMatcher.compile(ignorePatterns)
        val batch = ArrayList<MediaItem>(ENUMERATION_BATCH_SIZE)
        suspend fun add(item: MediaItem) {
            batch += item
            if (batch.size == ENUMERATION_BATCH_SIZE) {
                emit(batch.toList())
                batch.clear()
            }
        }
        suspend fun enumerate(uri: android.net.Uri, projection: Array<String>, type: MediaType) {
            val sort = if (type == MediaType.IMAGE) MediaStore.Images.Media.DATE_TAKEN else MediaStore.Video.Media.DATE_TAKEN
            val cursor = context.contentResolver.query(uri, projection, null, null, "$sort DESC")
                ?: error("MediaStore ${type.name} 查询返回空 Cursor")
            cursor.use {
                val dataCol = it.getColumnIndexSafe(MediaStore.MediaColumns.DATA)
                val nameCol = it.getColumnIndexSafe(MediaStore.MediaColumns.DISPLAY_NAME)
                val takenCol = it.getColumnIndexSafe(MediaStore.MediaColumns.DATE_TAKEN)
                val modifiedCol = it.getColumnIndexSafe(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeCol = it.getColumnIndexSafe(MediaStore.MediaColumns.SIZE)
                val mimeCol = it.getColumnIndexSafe(MediaStore.MediaColumns.MIME_TYPE)
                val widthCol = it.getColumnIndexSafe(MediaStore.MediaColumns.WIDTH)
                val heightCol = it.getColumnIndexSafe(MediaStore.MediaColumns.HEIGHT)
                val orientationCol = if (type == MediaType.IMAGE) it.getColumnIndexSafe(MediaStore.Images.Media.ORIENTATION) else -1
                val durationCol = if (type == MediaType.VIDEO) it.getColumnIndexSafe(MediaStore.Video.Media.DURATION) else -1
                while (it.moveToNext()) {
                    val path = it.getString(dataCol) ?: continue
                    val file = File(path)
                    if (!file.exists() || !isUnderAnyRoot(path, roots) || IgnorePatternMatcher.matchesAny(file.name, compiledIgnore)) continue
                    add(MediaItem(
                        id = java.util.UUID.nameUUIDFromBytes(path.toByteArray()).toString(),
                        filePath = path,
                        fileName = it.getString(nameCol) ?: file.name,
                        type = type,
                        capturedAt = Instant.ofEpochMilli(it.getLong(takenCol)),
                        modifiedAt = Instant.ofEpochMilli(it.getLong(modifiedCol) * 1000),
                        fileSize = it.getLong(sizeCol),
                        mimeType = it.getString(mimeCol) ?: "",
                        width = it.getInt(widthCol),
                        height = it.getInt(heightCol),
                        durationMs = if (durationCol >= 0) it.getLong(durationCol) else 0L,
                        // MediaStore 经纬度列已弃用且在新系统中不可靠；后续 File/EXIF 扫描负责补齐位置。
                        latitude = null,
                        longitude = null,
                        orientation = if (orientationCol >= 0) it.getInt(orientationCol) else 0,
                    ))
                }
            }
        }
        enumerate(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageProjection, MediaType.IMAGE)
        enumerate(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoProjection, MediaType.VIDEO)
        if (batch.isNotEmpty()) emit(batch.toList())
    }

    /**
     * 计算文件头部哈希，用于增量异常检测。
     * 读取文件前 HEAD_HASH_BYTES 个字节计算 SHA-256。
     */
    fun computeFingerprintHead(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) return null

            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(HEAD_HASH_BYTES)
                val bytesRead = fis.read(buffer)
                if (bytesRead > 0) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "计算头部哈希失败: $filePath", e)
            null
        }
    }

    /**
     * 判断文件内容是否确实发生变化。
     * 若 same modifiedAt 但 fingerprintHead 不同，或者 dbEntry 无 fingerprintHead，则视为变化。
     */
    /**
     * 将 MediaItem 转换为 MediaEntity（含新字段映射）。
     */
    /**
     * 将 MediaItem 转换为 MediaEntity（含 EXIF 字段映射）。
     */
    private fun MediaItem.toEntity(): MediaEntity {
        val capturedAtMs = capturedAt.toEpochMilli()
        val modifiedAtMs = modifiedAt.toEpochMilli()
        val indexedAtMs = System.currentTimeMillis()
        val fingerprint = computeFingerprintHead(filePath)

        return MediaEntity(
            filePath = filePath,
            fileName = fileName,
            mediaType = type,
            capturedAtMs = capturedAtMs,
            modifiedAtMs = modifiedAtMs,
            indexedAtMs = indexedAtMs,
            parentPath = filePath.substringBeforeLast('/', ""),
            fileSize = fileSize,
            isFavorite = isFavorite,
            isTrashed = isTrashed,
            width = width,
            height = height,
            mimeType = mimeType,
            durationMs = durationMs,
            latitude = latitude,
            longitude = longitude,
            make = make,
            model = model,
            aperture = aperture,
            focalLength = focalLength,
            iso = iso,
            exposureTime = exposureTime,
            orientation = orientation,
            sceneType = sceneType,
            thumbnailPath = thumbnailPath,
            fingerprintHead = fingerprint,
            perceptualHash = 0L,
            ocrText = null,
            qualityScore = 0f,
            deletedAtMs = if (isTrashed) modifiedAtMs else 0L,
            isCorrupted = isCorrupted,
            faceClusterId = null,
        )
    }
}

/**
 * 增量扫描结果。
 */
private data class BatchCommitCount(val inserted: Int, val updated: Int)

private data class ScanCommitResult(
    val indexed: Int,
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
)

data class IncrementalResult(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val elapsedMs: Long,
)