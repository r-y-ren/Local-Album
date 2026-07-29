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
import com.renyxin.localalbum.data.worker.ScanServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    private val faceDao: FaceDao? = null,
    private val embeddingDao: com.renyxin.localalbum.data.db.dao.EmbeddingDao? = null,
    private val mediaSource: MediaSource = MediaSource(context),
    private val pluginPipeline: PluginAnalysisPipeline? = null,
    private val analysisStateDao: com.renyxin.localalbum.data.db.dao.AnalysisStateDao? = null,
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
    suspend fun fullScan(
        roots: List<String>,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
        progress: ((processed: Int, total: Int) -> Unit)? = null,
    ): Int = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始全量扫描…")
        val startTime = System.currentTimeMillis()

        val mediaStoreItems = queryMediaStore(roots, ignorePatterns)
        val fileSystemItems = queryFileSystem(roots, allowNomedia, ignorePatterns)

        // 合并去重（以 filePath 为主键，File API 的 EXIF 数据更全，优先保留）
        val merged = mergeAndDeduplicate(mediaStoreItems, fileSystemItems)

        // Phase 3: 指纹计算并行化（原为单协程串行 IO，数万文件时为瓶颈）。
        // toEntity() 内部调用 computeFingerprintHead（4KB 读 + SHA-256），属 IO+轻 CPU，
        // 在 ioBound 上并发并用 Semaphore 限流（上限 cpuCores，SD 卡/OTG 场景实测下调）。
        // 同时上报 processed/total 填充 ScanState 进度（D10）。
        val totalForProgress = merged.size
        val processedCounter = AtomicInteger(0)
        val fingerprintSemaphore = Semaphore(InferenceDispatchers.cpuCores)
        val entities = coroutineScope {
            merged.map { item ->
                async(InferenceDispatchers.ioBound) {
                    fingerprintSemaphore.withPermit {
                        val entity = item.toEntity()
                        val done = processedCounter.incrementAndGet()
                        progress?.invoke(done, totalForProgress)
                        entity
                    }
                }
            }.awaitAll()
        }

        // 全量枚举仍会对同一路径执行 REPLACE；若直接写入新实体，会清空既有 AI 结果，
        // 但分析断点可能仍将该文件标为 DONE。对内容未变化的记录保留派生字段，
        // 对内容变化的记录则在下方失效断点并重新分析。
        val existingByPath = mediaDao.getAll().associateBy { it.filePath }
        val entitiesToUpsert = entities.map { fresh ->
            val existing = existingByPath[fresh.filePath]
            if (
                existing != null &&
                existing.modifiedAtMs == fresh.modifiedAtMs &&
                existing.fingerprintHead == fresh.fingerprintHead
            ) {
                // 直接以非空分支中的 existing 作为智能转换条件，供 Kotlin 正确收窄可空类型。
                fresh.copy(
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
                )
            } else {
                fresh
            }
        }
        val changedPaths = entitiesToUpsert.asSequence()
            .filter { entity ->
                val existing = existingByPath[entity.filePath]
                existing != null &&
                    (existing.modifiedAtMs != entity.modifiedAtMs ||
                        existing.fingerprintHead != entity.fingerprintHead)
            }
            .map { it.filePath }
            .toList()
        changedPaths.chunked(500).forEach { chunk -> analysisStateDao?.deleteByPaths(chunk) }

        // P2-4: 先插入新数据（REPLACE 策略），再删除孤儿记录，避免全量扫描期间 UI 空窗
        mediaDao.insertAll(entitiesToUpsert)
        val newPaths = entitiesToUpsert.map { it.filePath }.toSet()
        val orphaned = mediaDao.getAllPaths().toSet() - newPaths
        if (orphaned.isNotEmpty()) {
            // 修复：分块删除，避免 SQLite IN 查询变量数超过 999 限制
            orphaned.toList().chunked(500).forEach { chunk ->
                mediaDao.deleteByPaths(chunk)
                mediaDao.deleteFtsEntries(chunk)
                // Phase 1: 同步清理孤儿文件的分析断点状态
                analysisStateDao?.deleteByPaths(chunk)
                // 同步清理人脸与语义嵌入（人物相册/语义搜索直接查这些表，不 JOIN media_items）
                faceDao?.deleteByFilePaths(chunk)
                embeddingDao?.deleteByFilePaths(chunk)
            }
        }

        // 1.1: 重建 FTS 索引（fileName/make/model），OCR 文本由 OCR 阶段后续补充
        entitiesToUpsert.map { it.filePath }.chunked(500).forEach { chunk ->
            mediaDao.deleteFtsEntries(chunk)
        }
        mediaDao.insertFtsAll(entitiesToUpsert.map {
            MediaFts(it.filePath, it.fileName, it.ocrText, it.make, it.model)
        })

        // === P0-A: 全量扫描完成后异步触发 AI 分析管道 ===
        // 修复：原实现同步调用 runFullScan，对所有文件串行执行 5 个 AI 阶段（每阶段内 for 循环串行），
        // 导致扫描「完成」要等 AI 分析跑完才返回，UI 长时间显示扫描中。
        // 现改为 fire-and-forget：扫描入库后立即返回，AI 分析在后台协程执行，不阻塞扫描状态。
        //
        // 视频过滤：AI 识别管道（人脸/场景/OCR/质量/语义）全部基于 BitmapFactory.decodeFile，
        // 仅支持图片解码。视频文件传入后 decodeFile 返回 null，产生无谓的失败计数与日志噪音。
        // 此处仅将图片路径传入分析管道，视频跳过。
        val allPaths = entitiesToUpsert.filter { it.mediaType == MediaType.IMAGE }.map { it.filePath }
        val skippedVideoCount = entitiesToUpsert.size - allPaths.size
        if (skippedVideoCount > 0) {
            Log.i(TAG, "全量扫描: 跳过 $skippedVideoCount 个视频文件（AI 识别仅支持图片）")
        }
        if (allPaths.isNotEmpty()) {
            // Phase 0: 分析侧前台服务引用，在 launch 前同步 acquire（避免与扫描侧 release 之间的空窗），
            // 在协程结束时 finally release。扫描侧引用在 rescan 返回后释放，二者独立计数。
            ScanServiceController.acquire(context, "正在分析 AI 特征…")
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
                } finally {
                    ScanServiceController.release(context)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val throughput = if (elapsed > 0) entitiesToUpsert.size * 60_000L / elapsed else 0
        Log.i(TAG, "全量扫描完成: ${entitiesToUpsert.size} 个媒体, 耗时 ${elapsed}ms, 吞吐约 $throughput 文件/分钟")

        entitiesToUpsert.size
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
    ): IncrementalResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始增量扫描…")
        val startTime = System.currentTimeMillis()

        val dbMap = mediaDao.getModifiedTimeMap().associateBy { it.filePath }

        val mediaStoreItems = queryMediaStore(roots, ignorePatterns)
        val fileSystemItems = queryFileSystem(roots, allowNomedia, ignorePatterns)

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

        // Phase 1: 文件更新/删除时失效其分析断点状态，确保断点续跑不会跳过需重分析的文件
        val pathsToInvalidate = toUpdate.map { it.filePath } + toDelete
        if (pathsToInvalidate.isNotEmpty() && analysisStateDao != null) {
            pathsToInvalidate.chunked(500).forEach { chunk ->
                analysisStateDao.deleteByPaths(chunk)
            }
        }

        // 执行数据库操作
        if (toDelete.isNotEmpty()) {
            // 修复：分块删除，避免 SQLite IN 查询变量数超过 999 限制
            toDelete.chunked(500).forEach { chunk ->
                mediaDao.deleteByPaths(chunk)
                // 修复：同步清理 FTS/人脸/语义嵌入残留。
                // 人物相册聚类（FaceDao.getClusterSummaries）与语义搜索（SemanticSearcher.getAll）
                // 均直接查这些表而不 JOIN media_items，不清理会导致已删除文件仍出现。
                mediaDao.deleteFtsEntries(chunk)
                faceDao?.deleteByFilePaths(chunk)
                embeddingDao?.deleteByFilePaths(chunk)
            }
        }
        if (toInsert.isNotEmpty()) {
            mediaDao.insertAll(toInsert.map { it.toEntity() })
        }
        if (toUpdate.isNotEmpty()) {
            // P1-B: 增量更新时保留数据库中已有的 faceClusterId，避免 REPLACE 覆盖管道写入的聚类结果
            // Phase 3: N+1 改批量——一次性取回所有待更新文件实体（chunked 规避 999 变量上限）
            val existingByPath: Map<String, MediaEntity> = try {
                toUpdate.map { it.filePath }.chunked(500).flatMap { chunk ->
                    mediaDao.getByFilePathsLight(chunk)
                }.associateBy { it.filePath }
            } catch (_: Exception) { emptyMap() }

            val entitiesToUpdate = toUpdate.map { item ->
                val entity = item.toEntity()
                val existingFaceClusterId = existingByPath[item.filePath]?.faceClusterId
                if (existingFaceClusterId != null) {
                    entity.copy(faceClusterId = existingFaceClusterId)
                } else {
                    entity
                }
            }
            mediaDao.insertAll(entitiesToUpdate)
        }

        // ---- 分析管道：对新文件和更新文件进行智能分析 ----
        // 视频过滤：AI 识别管道仅支持图片（BitmapFactory.decodeFile），视频传入必然失败。
        // 仅将图片类型的新增/更新文件路径传入分析管道，视频跳过。
        val newImagePaths = toInsert.filter { it.type == MediaType.IMAGE }.map { it.filePath }
        val updatedImagePaths = toUpdate.filter { it.type == MediaType.IMAGE }.map { it.filePath }
        val filesToAnalyze = newImagePaths + updatedImagePaths
        val skippedVideoCount = (toInsert + toUpdate).size - filesToAnalyze.size
        if (skippedVideoCount > 0) {
            Log.i(TAG, "增量扫描: 跳过 $skippedVideoCount 个视频文件（AI 识别仅支持图片）")
        }
        // 1.1: 刷新新增/更新文件的 FTS 索引（fileName/make/model）
        // 注意：FTS 索引仍需覆盖全部文件（含视频），搜索功能需检索视频文件名
        val allNewFilePaths = toInsert.map { it.filePath }
        val allUpdatedFilePaths = toUpdate.map { it.filePath }
        val allFilesToIndex = allNewFilePaths + allUpdatedFilePaths
        if (allFilesToIndex.isNotEmpty()) {
            allFilesToIndex.chunked(500).forEach { chunk ->
                mediaDao.deleteFtsEntries(chunk)
            }
            mediaDao.insertFtsAll((toInsert + toUpdate).map {
                MediaFts(it.filePath, it.fileName, null, it.make, it.model)
            })
        }
        if (filesToAnalyze.isNotEmpty()) {
            // 异步执行 AI 分析，不阻塞增量扫描完成状态（与 fullScan 一致）
            val pathsToAnalyze = filesToAnalyze.toList()
            val allPathsSnapshot = mediaDao.getImagePaths()
            // Phase 0: 分析侧前台服务引用（见 fullScan 注释）
            ScanServiceController.acquire(context, "正在分析 AI 特征…")
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
                } catch (e: Throwable) {
                    // 修复：与 fullScan 一致，catch Throwable 并重新抛出 CancellationException，
                    // 避免吞掉取消信号导致协程无法正确终止。
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "分析管道执行失败 (增量扫描): ${e.message}", e)
                } finally {
                    ScanServiceController.release(context)
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

    private fun queryMediaStore(roots: List<String>, ignorePatterns: List<String> = emptyList()): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        // 用户配置的正则忽略规则预编译（对文件名生效，如 ^.trash）。非法正则自动跳过。
        val compiledIgnore = IgnorePatternMatcher.compile(ignorePatterns)

        // 查询图片
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            null, null,
            MediaStore.Images.Media.DATE_TAKEN + " DESC"
        )?.use { cursor ->
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
                // 用户配置的正则忽略规则对文件名生效
                if (IgnorePatternMatcher.matchesAny(file.name, compiledIgnore)) continue

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
                // 用户配置的正则忽略规则对文件名生效
                if (IgnorePatternMatcher.matchesAny(file.name, compiledIgnore)) continue

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
    private fun queryFileSystem(
        roots: List<String>,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
    ): List<MediaItem> {
        if (roots.isEmpty()) return emptyList()
        val result = mutableListOf<MediaItem>()
        val tree = mediaSource.scanRootsSync(roots, allowNomedia = allowNomedia, ignorePatterns = ignorePatterns)
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
data class IncrementalResult(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val elapsedMs: Long,
)