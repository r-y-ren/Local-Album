package com.renyxin.localalbum.core.index

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.renyxin.localalbum.core.analysis.AnalysisPipeline
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.PathModifiedTime
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import com.renyxin.localalbum.data.source.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant

/**
 * 混合增量索引引擎。
 *
 * 策略 (按 PRODUCT_SPEC_V2 §5.2):
 * - MediaStore 负责高频增量检测（快速感知新增/删除）
 * - File API 负责完整性回补和异常修复
 * - fingerprintHead 作为 modifiedAt 相同时的二次校验
 * - 扫描流水线：枚举 → 元数据提取 → 缩略图 → 入库
 */
class HybridIndexer(
    private val context: Context,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao? = null,
    private val embeddingDao: com.renyxin.localalbum.data.db.dao.EmbeddingDao? = null,
    private val mediaSource: MediaSource = MediaSource(context),
    private val pluginPipeline: PluginAnalysisPipeline? = null,
) {
    companion object {
        private const val TAG = "HybridIndexer"
        private const val HEAD_HASH_BYTES = 4096 // 读取前4KB计算头部哈希
    }

    /** 后台 AI 分析协程作用域（fire-and-forget，不阻塞扫描状态） */
    private val analysisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val analysisPipeline: AnalysisPipeline
        get() = AnalysisPipeline(context, mediaDao, faceDao, embeddingDao)

    /** 获取插件化分析管道，供外部触发全量重分析 */
    internal fun getPluginPipeline() = pluginPipeline

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
        // P0-B: EXIF/位置数据列，修复 GPS 坐标和相机信息丢失
        MediaStore.Images.Media.LATITUDE,
        MediaStore.Images.Media.LONGITUDE,
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
        // P0-B: 视频同样需要位置数据
        MediaStore.Video.Media.LATITUDE,
        MediaStore.Video.Media.LONGITUDE,
    )

    /**
     * 全量扫描：清空数据库，从 MediaStore + File API 重建索引。
     * @param roots 用户配置的扫描根目录列表
     * @param allowNomedia 为 true 时不再跳过含 .nomedia 的目录
     * @return 索引的媒体项总数
     */
    suspend fun fullScan(roots: List<String>, allowNomedia: Boolean = false): Int = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始全量扫描…")
        val startTime = System.currentTimeMillis()

        val mediaStoreItems = queryMediaStore(roots)
        val fileSystemItems = queryFileSystem(roots, allowNomedia)

        // 合并去重（以 filePath 为主键，File API 的 EXIF 数据更全，优先保留）
        val merged = mergeAndDeduplicate(mediaStoreItems, fileSystemItems)
        val entities = merged.map { it.toEntity() }

        // P2-4: 先插入新数据（REPLACE 策略），再删除孤儿记录，避免全量扫描期间 UI 空窗
        mediaDao.insertAll(entities)
        val newPaths = entities.map { it.filePath }.toSet()
        val orphaned = mediaDao.getAllPaths().toSet() - newPaths
        if (orphaned.isNotEmpty()) {
            // 修复：分块删除，避免 SQLite IN 查询变量数超过 999 限制
            orphaned.toList().chunked(500).forEach { chunk ->
                mediaDao.deleteByPaths(chunk)
                mediaDao.deleteFtsEntries(chunk)
            }
        }

        // 1.1: 重建 FTS 索引（fileName/make/model），OCR 文本由 OCR 阶段后续补充
        entities.map { it.filePath }.chunked(500).forEach { chunk ->
            mediaDao.deleteFtsEntries(chunk)
        }
        mediaDao.insertFtsAll(entities.map {
            MediaFts(it.filePath, it.fileName, null, it.make, it.model)
        })

        // === P0-A: 全量扫描完成后异步触发 AI 分析管道 ===
        // 修复：原实现同步调用 runFullScan，对所有文件串行执行 5 个 AI 阶段（每阶段内 for 循环串行），
        // 导致扫描「完成」要等 AI 分析跑完才返回，UI 长时间显示扫描中。
        // 现改为 fire-and-forget：扫描入库后立即返回，AI 分析在后台协程执行，不阻塞扫描状态。
        val allPaths = entities.map { it.filePath }
        if (allPaths.isNotEmpty()) {
            analysisScope.launch {
                try {
                    if (pluginPipeline != null) {
                        Log.i("CrashDebug", "### 全量扫描: 后台启动插件化分析管道 ${allPaths.size} 个文件 ###")
                        pluginPipeline.runFullScan(allPaths)
                        Log.i("CrashDebug", "### 全量扫描: 分析管道完成 ###")
                    } else {
                        Log.i(TAG, "全量扫描完成，后台启动内置分析管道: ${allPaths.size} 个文件")
                        analysisPipeline.analyzeNewFiles(allPaths, enableOcr = true)
                    }
                } catch (e: Throwable) {
                    // 修复：catch Throwable 而非 Exception，捕获 Error（OOM/native 包装错误等），
                    // 避免冒泡到协程 UncaughtExceptionHandler 导致进程闪退。
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e("CrashDebug", "AI 分析管道执行失败 (全量扫描): ${e.javaClass.name}: ${e.message}", e)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val throughput = if (elapsed > 0) entities.size * 60_000L / elapsed else 0
        Log.i(TAG, "全量扫描完成: ${entities.size} 个媒体, 耗时 ${elapsed}ms, 吞吐约 $throughput 文件/分钟")

        entities.size
    }

    /**
     * 增量扫描：比较 modifiedAt + fingerprintHead，仅更新有变化的文件。
     * @param roots 用户配置的扫描根目录列表
     * @param allowNomedia 为 true 时不再跳过含 .nomedia 的目录
     * @return 更新的媒体项数量
     */
    suspend fun incrementalScan(roots: List<String>, allowNomedia: Boolean = false): IncrementalResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始增量扫描…")
        val startTime = System.currentTimeMillis()

        val dbMap = mediaDao.getModifiedTimeMap().associateBy { it.filePath }

        val mediaStoreItems = queryMediaStore(roots)
        val fileSystemItems = queryFileSystem(roots, allowNomedia)

        val currentMap = mergeAndDeduplicate(mediaStoreItems, fileSystemItems)
            .associateBy { it.filePath }

        val toInsert = mutableListOf<MediaItem>()
        val toUpdate = mutableListOf<MediaItem>()
        val toDelete = mutableListOf<String>()

        // 检测新增 & 修改
        for ((path, item) in currentMap) {
            val dbEntry = dbMap[path]
            when {
                dbEntry == null -> {
                    // 数据库中不存在 → 新增
                    toInsert.add(item)
                }
                dbEntry.modifiedAtMs != item.modifiedAt.toEpochMilli() -> {
                    // modifiedAt 已变化 → 检查指纹以确认是否真实变化
                    if (hasContentChanged(path, dbEntry)) {
                        toUpdate.add(item)
                    }
                }
                else -> {
                    // modifiedAt 相同 → 不做处理
                }
            }
        }

        // 检测删除（路径在 DB 中但不在当前磁盘/MediaStore 中）
        for ((path, _) in dbMap) {
            if (path !in currentMap) {
                toDelete.add(path)
            }
        }

        // 执行数据库操作
        if (toDelete.isNotEmpty()) {
            // 修复：分块删除，避免 SQLite IN 查询变量数超过 999 限制
            toDelete.chunked(500).forEach { chunk ->
                mediaDao.deleteByPaths(chunk)
            }
        }
        if (toInsert.isNotEmpty()) {
            mediaDao.insertAll(toInsert.map { it.toEntity() })
        }
        if (toUpdate.isNotEmpty()) {
            // P1-B: 增量更新时保留数据库中已有的 faceClusterId，避免 REPLACE 覆盖管道写入的聚类结果
            val entitiesToUpdate = toUpdate.map { item ->
                val entity = item.toEntity()
                val existingFaceClusterId = dbMap[item.filePath]?.let {
                    // 从数据库读取当前实体以获取 faceClusterId
                    try {
                        mediaDao.getByFilePathLight(item.filePath)?.faceClusterId
                    } catch (_: Exception) { null }
                }
                if (existingFaceClusterId != null) {
                    entity.copy(faceClusterId = existingFaceClusterId)
                } else {
                    entity
                }
            }
            mediaDao.insertAll(entitiesToUpdate)
        }

        // ---- 分析管道：对新文件和更新文件进行智能分析 ----
        val newFilePaths = toInsert.map { it.filePath }
        val updatedFilePaths = toUpdate.map { it.filePath }
        val filesToAnalyze = newFilePaths + updatedFilePaths
        // 1.1: 刷新新增/更新文件的 FTS 索引（fileName/make/model）
        if (filesToAnalyze.isNotEmpty()) {
            filesToAnalyze.chunked(500).forEach { chunk ->
                mediaDao.deleteFtsEntries(chunk)
            }
            mediaDao.insertFtsAll((toInsert + toUpdate).map {
                MediaFts(it.filePath, it.fileName, null, it.make, it.model)
            })
        }
        if (filesToAnalyze.isNotEmpty()) {
            // 异步执行 AI 分析，不阻塞增量扫描完成状态（与 fullScan 一致）
            val pathsToAnalyze = filesToAnalyze.toList()
            val allPathsSnapshot = mediaDao.getAllPaths()
            analysisScope.launch {
                try {
                    if (pluginPipeline != null) {
                        pluginPipeline.runIncremental(
                            incrementalPaths = pathsToAnalyze,
                            allPaths = allPathsSnapshot,
                        )
                    } else {
                        analysisPipeline.analyzeNewFiles(pathsToAnalyze, enableOcr = false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "分析管道执行失败 (增量扫描): ${e.message}", e)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "增量扫描完成: 新增 ${toInsert.size}, 更新 ${toUpdate.size}, 删除 ${toDelete.size}, " +
                "耗时 ${elapsed}ms")

        IncrementalResult(
            inserted = toInsert.size,
            updated = toUpdate.size,
            deleted = toDelete.size,
            elapsedMs = elapsed,
        )
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

    private fun queryMediaStore(roots: List<String>): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        // 查询图片
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            null, null,
            MediaStore.Images.Media.DATE_TAKEN + " DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexSafe(MediaStore.Images.Media._ID)
            val dataCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.DATA)
            val nameCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.DATE_TAKEN)
            val modCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.SIZE)
            val mimeCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.MIME_TYPE)
            val wCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.WIDTH)
            val hCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.HEIGHT)
            // P0-B: 新增 EXIF/位置列
            val latCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.LATITUDE)
            val lonCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.LONGITUDE)
            val orientCol = cursor.getColumnIndexSafe(MediaStore.Images.Media.ORIENTATION)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol)
                val file = File(path)
                if (!file.exists()) continue
                // 按扫描根目录过滤：仅索引用户选定目录下的媒体
                if (!isUnderAnyRoot(path, roots)) continue

                items.add(MediaItem(
                    id = java.util.UUID.nameUUIDFromBytes(path.toByteArray()).toString(),
                    filePath = path,
                    fileName = cursor.getString(nameCol) ?: file.name,
                    type = MediaType.IMAGE,
                    capturedAt = Instant.ofEpochMilli(cursor.getLong(takenCol)),
                    modifiedAt = Instant.ofEpochMilli(cursor.getLong(modCol) * 1000),
                    fileSize = cursor.getLong(sizeCol),
                    mimeType = cursor.getString(mimeCol) ?: "",
                    width = cursor.getInt(wCol),
                    height = cursor.getInt(hCol),
                    // P0-B: 填充 GPS 和方向数据
                    latitude = if (latCol >= 0) cursor.getDouble(latCol).takeIf { it != 0.0 } else null,
                    longitude = if (lonCol >= 0) cursor.getDouble(lonCol).takeIf { it != 0.0 } else null,
                    orientation = if (orientCol >= 0) cursor.getInt(orientCol) else 0,
                ))
            }
        }

        // 查询视频
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            null, null,
            MediaStore.Video.Media.DATE_TAKEN + " DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexSafe(MediaStore.Video.Media._ID)
            val dataCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.DATA)
            val nameCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.DATE_TAKEN)
            val modCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.SIZE)
            val mimeCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.MIME_TYPE)
            val wCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.WIDTH)
            val hCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.HEIGHT)
            val durCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.DURATION)
            // P0-B: 视频位置列
            val latCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.LATITUDE)
            val lonCol = cursor.getColumnIndexSafe(MediaStore.Video.Media.LONGITUDE)

            while (cursor.moveToNext()) {
                val path = cursor.getString(dataCol)
                val file = File(path)
                if (!file.exists()) continue
                // 按扫描根目录过滤：仅索引用户选定目录下的媒体
                if (!isUnderAnyRoot(path, roots)) continue

                items.add(MediaItem(
                    id = java.util.UUID.nameUUIDFromBytes(path.toByteArray()).toString(),
                    filePath = path,
                    fileName = cursor.getString(nameCol) ?: file.name,
                    type = MediaType.VIDEO,
                    capturedAt = Instant.ofEpochMilli(cursor.getLong(takenCol)),
                    modifiedAt = Instant.ofEpochMilli(cursor.getLong(modCol) * 1000),
                    fileSize = cursor.getLong(sizeCol),
                    mimeType = cursor.getString(mimeCol) ?: "",
                    width = cursor.getInt(wCol),
                    height = cursor.getInt(hCol),
                    durationMs = cursor.getLong(durCol),
                    // P0-B: 填充位置数据
                    latitude = if (latCol >= 0) cursor.getDouble(latCol).takeIf { it != 0.0 } else null,
                    longitude = if (lonCol >= 0) cursor.getDouble(lonCol).takeIf { it != 0.0 } else null,
                ))
            }
        }

        return items
    }

    /**
     * 从 File API 索引用户指定的扫描根目录。
     * 调用 [MediaSource.scanRootsSync] 获取目录树后展平为 [MediaItem] 列表，
     * 用于补充 MediaStore 未覆盖的文件（如用户自选目录下的媒体）。
     * @param allowNomedia 为 true 时不再跳过含 .nomedia 的目录
     */
    private fun queryFileSystem(roots: List<String>, allowNomedia: Boolean = false): List<MediaItem> {
        if (roots.isEmpty()) return emptyList()
        val result = mutableListOf<MediaItem>()
        val tree = mediaSource.scanRootsSync(roots, allowNomedia = allowNomedia)
        tree.forEach { collectMediaItems(it, result) }
        return result
    }

    /** 递归收集 [DirectoryNode] 树中的所有 [MediaItem] */
    private fun collectMediaItems(node: com.renyxin.localalbum.core.model.DirectoryNode, out: MutableList<MediaItem>) {
        out.addAll(node.mediaItems)
        node.children.forEach { collectMediaItems(it, out) }
    }

    /**
     * 合并去重：以 filePath 为键，MediaStore 数据优先。
     */
    /**
     * 合并去重：以 filePath 为键，MediaStore 提供基础元数据，
     * File API 的 EXIF 数据（GPS/相机型号/光圈等）更全，作为补充。
     * P0-B：修复原逻辑中 MediaStore 覆盖导致 EXIF 数据丢失的问题。
     */
    private fun mergeAndDeduplicate(
        mediaStoreItems: List<MediaItem>,
        fileSystemItems: List<MediaItem>,
    ): List<MediaItem> {
        val map = linkedMapOf<String, MediaItem>()

        // MediaStore 先放入（基础元数据较可靠：DATE_TAKEN、SIZE、WIDTH/HEIGHT）
        for (item in mediaStoreItems) {
            map[item.filePath] = item
        }

        // File API 补充 EXIF 字段（不覆盖已有非空/非零值）
        for (item in fileSystemItems) {
            val existing = map[item.filePath]
            if (existing != null) {
                map[item.filePath] = existing.copy(
                    latitude = existing.latitude ?: item.latitude,
                    longitude = existing.longitude ?: item.longitude,
                    make = existing.make?.ifBlank { item.make } ?: item.make,
                    model = existing.model?.ifBlank { item.model } ?: item.model,
                    aperture = existing.aperture ?: item.aperture,
                    focalLength = existing.focalLength ?: item.focalLength,
                    iso = existing.iso ?: item.iso,
                    exposureTime = existing.exposureTime ?: item.exposureTime,
                    orientation = if (existing.orientation == 0) item.orientation else existing.orientation,
                )
            } else {
                map[item.filePath] = item
            }
        }

        return map.values.toList()
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
    private fun hasContentChanged(filePath: String, dbEntry: PathModifiedTime): Boolean {
        val dbFingerprint = dbEntry.fingerprintHead
        if (dbFingerprint == null) return true // 无旧指纹，视为变化

        val currentFingerprint = computeFingerprintHead(filePath) ?: return true // 无法计算，视为变化
        return currentFingerprint != dbFingerprint
    }

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
            geoClusterId = null,
            qualityScore = 0f,
            similarGroupId = null,
            deletedAtMs = if (isTrashed) modifiedAtMs else 0L,
            isCorrupted = isCorrupted,
            faceClusterId = null,
        )
    }
}

/**
 * 增量扫描结果。
 */
data class IncrementalResult(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val elapsedMs: Long,
)