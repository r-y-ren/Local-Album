package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.core.analysis.DuplicateAnalyzer

import kotlinx.coroutines.cancel

import android.util.Log
import com.renyxin.localalbum.core.album.AlbumBuilder
import com.renyxin.localalbum.core.analysis.ReverseGeocoder
import com.renyxin.localalbum.core.index.HybridIndexer
import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.DirectoryNode
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.recommendation.Recommendation
import com.renyxin.localalbum.core.recommendation.RecommendationEngine
import com.renyxin.localalbum.core.search.SemanticSearcher
import com.renyxin.localalbum.data.backup.DatabaseExporter
import com.renyxin.localalbum.data.backup.DatabaseImporter
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.FaceClusterSummary
import com.renyxin.localalbum.data.db.dao.GeoClusterSummary
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.source.MediaMetadataCache
import com.renyxin.localalbum.data.source.MediaSource
import com.renyxin.localalbum.data.worker.AnalysisResumePrefs
import com.renyxin.localalbum.data.worker.ScanServiceController
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.Instant

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(
        val message: String,
        val processed: Int = 0,
        val total: Int = 0,
    ) : ScanState
    data object Done : ScanState
    data class Failed(val message: String) : ScanState
}

data class AlbumStats(
    val totalCount: Int = 0,
    val favoriteCount: Int = 0,
    val trashedCount: Int = 0,
    val sceneTypeCounts: Map<String, Int> = emptyMap(),
)

/**
 * 相似照片组（Phase 3.1），用于相似照片管理界面。
 * @param groupId 相似组 ID
 * @param filePaths 组内文件路径（按质量评分降序，首项为推荐保留的最佳项）
 * @param qualityScores 文件路径 → 质量评分映射
 */
data class SimilarGroup(
    val groupId: String,
    val filePaths: List<String>,
    val qualityScores: Map<String, Float>,
) {
    /** 推荐保留的最佳项路径（质量分最高） */
    val bestPath: String? get() = filePaths.firstOrNull()
}

/**
 * 重复照片组（DUP-1），替代相似照片的精确+感知重复检测。
 * 使用 SHA-256 + pHash 三级分层：完全重复 → 感知重复 → 高度相似。
 */
typealias DuplicateGroup = DuplicateAnalyzer.DuplicateGroup
typealias DuplicateLevel = DuplicateAnalyzer.Level

/**
 * 人脸聚类（Phase 3.3），用于人脸相册界面。
 * @param clusterId 聚类 ID
 * @param personName 人物名称（用户命名，未命名时为 null）
 * @param faceCount 该聚类包含的人脸数量
 * @param representativeThumb 代表缩略图路径
 * @param filePaths 该聚类包含的照片路径列表
 */
data class FaceCluster(
    val clusterId: String,
    val personName: String?,
    val faceCount: Int,
    val representativeThumb: String?,
    val filePaths: List<String>,
)

/**
 * 地理聚类（Phase 3.4），用于地图视图界面。
 * @param clusterId 聚类 ID
 * @param photoCount 该聚类包含的照片数量
 * @param centerLatitude 聚类中心纬度
 * @param centerLongitude 聚类中心经度
 * @param representativeThumb 代表缩略图路径
 * @param placeName 地名（反向地理编码结果，未解析时为 null）
 * @param filePaths 该聚类包含的照片路径列表
 */
data class GeoCluster(
    val clusterId: String,
    val photoCount: Int,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val representativeThumb: String?,
    val placeName: String?,
    val filePaths: List<String>,
)

/**
 * 地理统计信息（Phase 3.4）。
 */
data class GeoStats(
    val totalGeoItems: Int = 0,
    val clusterCount: Int = 0,
    val noiseCount: Int = 0,
)

/**
 * 语义搜索结果（Phase 4.1），用于自然语言搜索界面。
 * @param mediaItem 匹配的媒体项
 * @param similarity 与查询的余弦相似度（0~1）
 */
data class SemanticSearchResult(
    val mediaItem: MediaItem,
    val similarity: Float,
)

/**
 * 语义搜索索引统计信息（Phase 4.1）。
 */
data class SemanticStats(
    val totalEmbeddings: Int = 0,
    val modelVersion: Int = 0,
    val embeddingDim: Int = 0,
)

/**
 * 语义搜索状态（用于 UI 区分"无匹配"与"搜索失败"）。
 */
enum class SemanticSearchState {
    /** 空闲 */
    IDLE,
    /** 搜索中 */
    SEARCHING,
    /** 搜索成功，有结果 */
    SUCCESS,
    /** 搜索完成，无匹配 */
    NO_RESULTS,
    /** 语义模型未加载（CLIP 模型加载失败） */
    MODEL_NOT_READY,
    /** 语义索引为空（尚未完成图片分析） */
    NO_INDEX,
}

class AlbumRepository(
    private val settingsRepository: SettingsRepository,
    private val mediaDao: MediaDao,
    private val faceDao: FaceDao? = null,
    private val embeddingDao: EmbeddingDao? = null,
    private val mediaSource: MediaSource = MediaSource(),
    private val albumBuilder: AlbumBuilder = AlbumBuilder(),
    private val recommendationEngine: RecommendationEngine = RecommendationEngine(),
    private val hybridIndexer: HybridIndexer? = null,
    private val semanticProviderFactory: (() -> com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider?)? = null,
    context: android.content.Context? = null,
) {
    companion object {
        private const val TAG = "AlbumRepository"
    }
    /** 与 Repository 生命周期绑定的协程作用域，替代 GlobalScope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 反向地理编码器（Phase 3.5），用于将 GPS 坐标解析为地名 */
    private val reverseGeocoder: ReverseGeocoder? = context?.let { ReverseGeocoder(it) }

    /** 应用上下文（用于构造分析管道） */
    private val appContext: android.content.Context? = context

    /**
     * 构建语义搜索引擎。每次搜索时从 [semanticProviderFactory] 取当前激活的 Provider，
     * 保证搜索侧与索引侧使用同一向量空间（支持运行时切换 Provider）。
     *
     * 重构说明：原实现硬编码 [com.renyxin.localalbum.core.analysis.SemanticEmbedder]（概念向量），
     * 现改为注入 Provider 工厂，使 CLIP / 概念向量等任意 Provider 都能驱动搜索。
     */
    private fun buildSemanticSearcher(): SemanticSearcher? {
        val dao = embeddingDao
        if (dao == null) {
            Log.e(TAG, "buildSemanticSearcher: embeddingDao 为 null")
            return null
        }
        val provider = semanticProviderFactory?.invoke()
        if (provider == null) {
            Log.e(TAG, "buildSemanticSearcher: semanticProviderFactory 返回 null")
            return null
        }
        Log.i(TAG, "buildSemanticSearcher: 成功, provider=${provider.providerId}, dim=${provider.embeddingDim}")
        return SemanticSearcher(provider, dao)
    }

    /** 重复文件分析器（DUP-1），SHA-256 + pHash 三级重复检测 */
    private val duplicateAnalyzer = DuplicateAnalyzer()

    private val _albumTree = MutableStateFlow<List<Album>>(emptyList())
    val albumTree: StateFlow<List<Album>> = _albumTree.asStateFlow()

    private val _leafAlbums = MutableStateFlow<List<Album>>(emptyList())
    val leafAlbums: StateFlow<List<Album>> = _leafAlbums.asStateFlow()

    private val _recommendations = MutableStateFlow<List<Recommendation>>(emptyList())
    val recommendations: StateFlow<List<Recommendation>> = _recommendations.asStateFlow()

    // ---- 推荐去重追踪 ----

    /** 每批展示的推荐数量 */
    private val recommendationBatchSize: Int = 10

    /** 全量打乱后的推荐池 */
    private var shuffledRecommendationPool: List<Recommendation> = emptyList()

    /** 当前批次指针 */
    private var recommendationCursor: Int = 0

    /** 推荐刷新互斥锁，保护共享的推荐池与游标 */
    private val recommendationMutex = Mutex()

    /** 扫描/重分析互斥锁，串行化 rescan/forceReanalyzeAll/restoreFromDbIfNeeded，
     *  防止 ContentObserver 触发的增量扫描与用户手动扫描并发执行导致数据/FTS/状态竞态。 */
    private val scanMutex = Mutex()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults.asStateFlow()

    /** 语义搜索结果（Phase 4.1），含相似度评分 */
    private val _semanticSearchResults = MutableStateFlow<List<SemanticSearchResult>>(emptyList())
    val semanticSearchResults: StateFlow<List<SemanticSearchResult>> = _semanticSearchResults.asStateFlow()

    /** 语义搜索状态（区分无匹配与模型/索引问题） */
    private val _semanticSearchState = MutableStateFlow(SemanticSearchState.IDLE)
    val semanticSearchState: StateFlow<SemanticSearchState> = _semanticSearchState.asStateFlow()

    private val _stats = MutableStateFlow(AlbumStats())
    val stats: StateFlow<AlbumStats> = _stats.asStateFlow()

    // ---- 收藏 ----
    val favorites = mediaDao.getFavorites().map { entities -> entities.map { it.toMediaItem() } }

    // ---- Flow 辅助 (使用 stateIn 替代 GlobalScope.launch，避免内存泄漏) ----
    val allMedia: StateFlow<List<MediaEntity>> = mediaDao.getAllFlow()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val totalCount: StateFlow<Int> = mediaDao.getTotalCountFlow()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // ---- Paging 3 分页数据流 (时间线使用) ----
    val pagedMedia: Flow<PagingData<MediaItem>> = Pager(
        config = PagingConfig(
            pageSize = 50,
            prefetchDistance = 20,
            enablePlaceholders = false,
            initialLoadSize = 100,
        ),
        pagingSourceFactory = { mediaDao.pagingSource() },
    ).flow
        .map { pagingData -> pagingData.map { it.toMediaItem() } }
        .cachedIn(scope)

    suspend fun loadFromDb() = withContext(Dispatchers.IO) {
        val entities = mediaDao.getAll()
        if (entities.isNotEmpty()) {
            val items = entities.map { it.toMediaItem() }
            rebuildTreeFromMediaItems(items)
        } else {
            // 修复：数据库为空时必须清空相册树，避免过滤后旧数据残留导致 UI 状态不一致
            clearAll()
        }
        refreshStats()
    }

    suspend fun restoreFromDbIfNeeded() = withContext(Dispatchers.IO) {
        Log.i(TAG, "restoreFromDbIfNeeded: 开始，等待 scanMutex…")
        // 修复：串行化，避免与并发 rescan 交错重建相册树
        scanMutex.withLock {
            Log.i(TAG, "restoreFromDbIfNeeded: 获得 scanMutex")
            if (_albumTree.value.isNotEmpty()) {
                Log.i(TAG, "restoreFromDbIfNeeded: 相册树已非空，跳过")
                return@withLock
            }
            val entities = mediaDao.getAll()
            Log.i(TAG, "restoreFromDbIfNeeded: DB 中有 ${entities.size} 条媒体记录")
            if (entities.isEmpty()) return@withLock
            val items = entities.map { it.toMediaItem() }
            rebuildTreeFromMediaItems(items)
            _scanState.value = ScanState.Done
            refreshStats()
            Log.i(TAG, "restoreFromDbIfNeeded: 完成")
        }
    }

    // ---- 扫描 ----
    suspend fun rescan() = withContext(Dispatchers.IO) {
        Log.i(TAG, "rescan: 开始，等待 scanMutex…")
        // 修复：串行化扫描，避免并发 rescan 互相覆盖数据与 _scanState
        scanMutex.withLock {
            Log.i(TAG, "rescan: 获得 scanMutex，设置 Scanning 状态")
            _scanState.value = ScanState.Scanning("准备扫描…")
            // 标记是否已启动前台服务。仅在确有扫描任务（roots 非空）时 acquire，
            // 避免 roots 为空时启动又立即停止服务，导致服务未及时 startForeground
            // 触发 ForegroundServiceDidNotStartInTimeException 崩溃（清除数据后复现）。
            var serviceAcquired = false
            try {
            runCatching {
            // 诊断：settingsRepository.state 为 combine(vararg) 冷 Flow，若任一源 Flow 不发射则 .first() 永久阻塞。
            // 加超时保护：5 秒内未获取设置则使用默认值，避免 scanState 永久卡在 Scanning。
            val settings = withTimeoutOrNull(5000L) {
                settingsRepository.state.first()
            }
            if (settings == null) {
                Log.e(TAG, "rescan: settingsRepository.state.first() 超时(5s)，Flow 可能未发射。使用默认设置。")
            } else {
                Log.i(TAG, "rescan: 获取设置成功，scanRoots=${settings.scanRoots}")
            }
            val roots = settings?.scanRoots ?: emptyList()
            val allowNomedia = settings?.showNomediaDirectories ?: false
            // 用户配置的忽略规则（正则），同时匹配目录名与文件名；复用 ignoreDirNames 通路
            val ignorePatterns = settings?.ignoreDirNames ?: emptyList()
            if (roots.isEmpty()) {
                Log.w(TAG, "rescan: scanRoots 为空，清空并返回 Idle")
                clearAll()
                _scanState.value = ScanState.Idle
                return@runCatching
            }

            // Phase 0: 确认有扫描任务后才启动前台服务，防杀后台。AI 分析侧由 HybridIndexer 独立 acquire。
            appContext?.let { ScanServiceController.acquire(it, "正在扫描媒体…") }
            serviceAcquired = true

            if (hybridIndexer != null) {
                // ---- 混合索引路径：优先使用 HybridIndexer ----
                val isFirstScan = mediaDao.getCount() == 0
                _scanState.value = ScanState.Scanning(
                    if (isFirstScan) "首次全量扫描…" else "增量扫描…"
                )
                if (isFirstScan) {
                    // Phase 3: 全量扫描进度填充（D10）——指纹阶段上报 processed/total
                    hybridIndexer.fullScan(roots, allowNomedia, ignorePatterns) { processed, total ->
                        _scanState.value = ScanState.Scanning("首次全量扫描…", processed, total)
                    }
                } else {
                    hybridIndexer.incrementalScan(roots, allowNomedia, ignorePatterns)
                }
                // 修复：移除无效的"兜底孤儿清理"调用。原调用 removeOrphanedRecords(mediaDao.getAllPaths())
                // 把数据库路径当作"文件系统实际路径"传入，差集恒为空，是 no-op（还白做两次全表路径查询）。
                // 孤儿清理由 fullScan/incrementalScan 内部基于真实文件系统快照完成，此处无需重复。
                loadFromDb()
                _scanState.value = ScanState.Done
                refreshStats()
            } else {
                // ---- 兜底路径：未注入 HybridIndexer 时走旧的 MediaSource 全量逻辑 ----
                Log.w(TAG, "HybridIndexer 未注入，回退到 MediaSource 全量扫描")
                val dbModifiedMap = mediaDao.getModifiedTimeMap().associate { it.filePath to it.modifiedAtMs }
                val dbCapturedMap = mediaDao.getAll().associate { it.filePath to it.capturedAtMs }

                val cache = object : MediaMetadataCache {
                    override fun getCapturedAtMs(path: String, modifiedAtMs: Long): Long? {
                        val dbModified = dbModifiedMap[path]
                        return if (dbModified != null && dbModified == modifiedAtMs) {
                            dbCapturedMap[path]
                        } else null
                    }
                }

                _scanState.value = ScanState.Scanning("正在扫描文件系统…")
                val tree = mediaSource.scanRoots(roots, cache, allowNomedia, ignorePatterns)

                val allItems = mutableListOf<MediaItem>()
                tree.forEach { collectMediaItems(it, allItems) }

                _scanState.value = ScanState.Scanning("正在更新本地索引…")
                mediaDao.clearAll()
                mediaDao.insertAll(allItems.map { it.toEntity() })

                _scanState.value = ScanState.Scanning("正在检测无效记录…")
                removeOrphanedRecords(allItems.map { it.filePath }.toSet())

                _scanState.value = ScanState.Scanning("正在构建相册树…")
                val sortMode = withTimeoutOrNull(5000L) {
                    settingsRepository.state.first()
                }?.albumSortMode ?: 0
                rebuildTreeFromDirectoryNodes(tree, sortMode)
                _scanState.value = ScanState.Done
                refreshStats()
            }
        }.onFailure { e ->
            Log.e(TAG, "扫描失败", e)
            _scanState.value = ScanState.Failed(e.message ?: "扫描失败")
        }
            } finally {
                // Phase 0: 仅在已 acquire 时释放，避免误减引用计数影响 AI 分析侧服务
                if (serviceAcquired) {
                    appContext?.let { ScanServiceController.release(it) }
                }
                // 修复：确保 scanState 永远不会卡在 Scanning 状态（如协程被取消时）
                if (_scanState.value is ScanState.Scanning) {
                    _scanState.value = ScanState.Done
                }
            }
        } // scanMutex.withLock
    }

    /**
     * 强制对全部已有媒体文件重新执行 AI 分析管道（不重新扫描文件系统）。
     *
     * 用于以下场景：
     * - 从旧版本升级后，数据库中有媒体记录但无 AI 分析数据
     * - 用户手动触发"重新分析全部照片"
     *
     * 与 [rescan] 的区别：跳过 MediaStore/File API 扫描，直接对已有文件路径执行管道。
     */
    suspend fun forceReanalyzeAll() = withContext(Dispatchers.IO) {
        // 修复：与 rescan 共用 scanMutex 串行化，避免并发分析造成数据竞态
        scanMutex.withLock {
        val allPaths = mediaDao.getAllPaths()
        if (allPaths.isEmpty()) {
            Log.i(TAG, "forceReanalyzeAll: 无媒体文件，跳过")
            return@withLock
        }

        _scanState.value = ScanState.Scanning("正在分析 ${allPaths.size} 个文件…", 0, allPaths.size)

        // Phase 1: 全量重分析前清空断点状态，避免被断点续跑跳过
        hybridIndexer?.clearAnalysisState()

        val pipeline = hybridIndexer?.getPluginPipeline()
        if (pipeline != null) {
            Log.i(TAG, "forceReanalyzeAll: 使用插件化管道分析 ${allPaths.size} 个文件")
            pipeline.runFullScan(allPaths)
        } else {
            Log.i(TAG, "forceReanalyzeAll: 使用内置分析管道分析 ${allPaths.size} 个文件")
            appContext?.let { ctx ->
                val fallback = com.renyxin.localalbum.core.analysis.AnalysisPipeline(
                    ctx, mediaDao, faceDao, embeddingDao
                )
                fallback.analyzeNewFiles(allPaths, enableOcr = true)
            } ?: Log.w(TAG, "forceReanalyzeAll: 无可用分析管道，跳过")
        }

        _scanState.value = ScanState.Done
        refreshStats()
        Log.i(TAG, "forceReanalyzeAll: 完成 ${allPaths.size} 个文件的分析")
        } // scanMutex.withLock
    }

    /**
     * Phase 1: 启动时若存在中断未完成的分析，自动续跑。
     *
     * [PluginAnalysisPipeline.runFullScan] 内部断点续跑会跳过已完成阶段，仅推理 pending 部分，
     * 故可安全地对全量路径调用。仅在 [AnalysisResumePrefs.isPending] 为 true 时执行，
     * 避免每次冷启动都跑重负载。
     *
     * 注意：续跑属重负载，后续可加约束（充电/非低电量）调度；当前仅在中断时触发。
     */
    suspend fun resumeAnalysisIfNeeded() = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext
        if (!AnalysisResumePrefs.isPending(ctx)) return@withContext
        val pipeline = hybridIndexer?.getPluginPipeline() ?: run {
            AnalysisResumePrefs.setPending(ctx, false)
            return@withContext
        }
        val allPaths = mediaDao.getAllPaths()
        if (allPaths.isEmpty()) {
            AnalysisResumePrefs.setPending(ctx, false)
            return@withContext
        }
        scanMutex.withLock {
            Log.i(TAG, "resumeAnalysisIfNeeded: 续跑未完成分析 ${allPaths.size} 个文件")
            _scanState.value = ScanState.Scanning("续跑 AI 分析…", 0, allPaths.size)
            ScanServiceController.acquire(ctx, "正在分析 AI 特征…")
            try {
                pipeline.runFullScan(allPaths)
            } finally {
                ScanServiceController.release(ctx)
                if (_scanState.value is ScanState.Scanning) {
                    _scanState.value = ScanState.Done
                }
            }
        }
    }

    /**
     * Phase 1: 分析完成度统计，供 UI 显示「已分析 x/y」。
     *
     * @return (已完成去重文件数, 媒体总数)
     */
    suspend fun getAnalysisCompletion(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val mediaCount = mediaDao.getCount()
        val done = hybridIndexer?.countAnalysisDonePaths() ?: 0
        done to mediaCount
    }

    // ---- 删除/回收站 ----
    suspend fun deleteMediaItems(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        mediaDao.moveToTrash(paths, System.currentTimeMillis())
        removeFromMemoryTree(paths.toSet())
        refreshStats()
    }

    /**
     * 彻底删除：删除物理文件 + 清理 media_items/FTS/faces/embeddings/analysis_state。
     *
     * 修复：原实现仅删数据库记录，物理文件残留导致下次增量扫描"复活"被删照片；
     * 同时未清 faces/embeddings，人物相册与语义搜索残留。现与 TrashCleanupWorker 对齐。
     */
    suspend fun permanentlyDelete(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        deletePhysicalFiles(paths)
        purgeMediaData(paths)
        removeFromMemoryTree(paths.toSet())
        refreshStats()
    }

    suspend fun restoreFromTrash(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        mediaDao.restoreFromTrash(paths)
        // 从数据库重新加载
        loadFromDb()
    }

    /**
     * 清空回收站：删除所有已回收媒体的物理文件 + 全量清理相关数据。
     */
    suspend fun clearTrash() = withContext(Dispatchers.IO) {
        val trashed = mediaDao.getTrashed()
        if (trashed.isEmpty()) return@withContext
        val paths = trashed.map { it.filePath }
        deletePhysicalFiles(paths)
        purgeMediaData(paths)
        removeFromMemoryTree(paths.toSet())
        refreshStats()
    }

    /**
     * 删除物理文件（与 TrashCleanupWorker 一致使用 File.delete，失败仅告警不阻塞）。
     */
    private suspend fun deletePhysicalFiles(paths: List<String>): List<String> = withContext(Dispatchers.IO) {
        val deleted = mutableListOf<String>()
        for (path in paths) {
            try {
                val file = java.io.File(path)
                if (!file.exists()) {
                    deleted.add(path) // 视为已删除
                    continue
                }
                if (file.delete()) {
                    deleted.add(path)
                } else {
                    Log.w(TAG, "物理文件删除失败（可能无权限）: $path")
                }
            } catch (e: Exception) {
                Log.w(TAG, "删除物理文件异常: $path", e)
            }
        }
        deleted
    }

    /**
     * 彻底清理媒体相关数据：media_items + FTS + faces + embeddings + analysis_state（分块规避 999 上限）。
     */
    private suspend fun purgeMediaData(paths: List<String>) {
        if (paths.isEmpty()) return
        paths.chunked(500).forEach { chunk ->
            mediaDao.deleteByPaths(chunk)
            mediaDao.deleteFtsEntries(chunk)
            faceDao?.deleteByFilePaths(chunk)
            embeddingDao?.deleteByFilePaths(chunk)
            hybridIndexer?.invalidateAnalysisState(chunk)
        }
    }

    // ---- 搜索 (优先 FTS4 全文索引，失败回退 LIKE + OCR) ----
    suspend fun search(query: String) = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return@withContext
        }
        val trimmed = query.trim()
        // 优先尝试 FTS4 查询
        val entities = runCatching {
            val ftsQuery = toFtsQuery(trimmed)
            mediaDao.searchFts(ftsQuery, limit = 200, offset = 0)
        }.getOrElse { e ->
            Log.w(TAG, "FTS 查询失败，回退到 LIKE 搜索: ${e.message}")
            mediaDao.searchWithOcr(trimmed)
        }
        // FTS 返回空时也回退到 LIKE（避免 FTS 索引未填充时无结果）
        val finalEntities = if (entities.isEmpty()) {
            mediaDao.searchWithOcr(trimmed)
        } else {
            entities
        }
        _searchResults.value = finalEntities.map { it.toMediaItem() }
    }

    /**
     * 将用户输入转换为 FTS4 安全查询字符串。
     * 对特殊字符做转义，用双引号包裹以支持短语匹配，多词以 OR 连接。
     */
    private fun toFtsQuery(input: String): String {
        return input.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" OR ") { token ->
                // 移除 FTS 特殊字符，用双引号包裹作为短语
                val cleaned = token.replace(Regex("[\"*()\\-]"), "")
                "\"$cleaned\"*"
            }
            .ifBlank { "\"$input\"*" }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
    }

    // ---- 语义搜索 (Phase 4.1) ----

    /**
     * 语义搜索：将自然语言查询编码为语义向量，通过余弦相似度检索匹配媒体。
     *
     * 支持跨语言搜索（中文查询 ↔ 英文概念 ↔ 图像特征），
     * 例如搜索"日落"可匹配到实际包含日落场景的图片，即使文件名/OCR 中无"日落"二字。
     *
     * @param query 自然语言查询（中文或英文）
     */
    suspend fun semanticSearch(query: String) = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            _semanticSearchResults.value = emptyList()
            _semanticSearchState.value = SemanticSearchState.IDLE
            return@withContext
        }
        val searcher = buildSemanticSearcher()
        if (searcher == null) {
            Log.w(TAG, "SemanticSearcher 未初始化（无 embeddingDao 或 semanticProvider），无法执行语义搜索")
            _semanticSearchResults.value = emptyList()
            _semanticSearchState.value = SemanticSearchState.MODEL_NOT_READY
            return@withContext
        }

        _semanticSearchState.value = SemanticSearchState.SEARCHING
        val diagnostics = searcher.searchDetailed(query.trim())

        if (!diagnostics.queryEncoded) {
            Log.w(TAG, "语义搜索：CLIP 查询文本编码失败（模型可能未加载），索引 ${diagnostics.indexSize} 条")
            _semanticSearchResults.value = emptyList()
            _semanticSearchState.value = SemanticSearchState.MODEL_NOT_READY
            return@withContext
        }
        if (diagnostics.indexSize == 0) {
            Log.i(TAG, "语义搜索：索引为空，尚未完成图片分析")
            _semanticSearchResults.value = emptyList()
            _semanticSearchState.value = SemanticSearchState.NO_INDEX
            return@withContext
        }
        if (diagnostics.results.isEmpty()) {
            _semanticSearchResults.value = emptyList()
            _semanticSearchState.value = SemanticSearchState.NO_RESULTS
            return@withContext
        }

        // 将搜索结果路径批量查询为 MediaEntity，再映射为 MediaItem
        val paths = diagnostics.results.map { it.filePath }
        val entities = paths.mapNotNull { mediaDao.getByFilePathLight(it) }
        val entityMap = entities.associateBy { it.filePath }

        val semanticResults = diagnostics.results.mapNotNull { sr ->
            val entity = entityMap[sr.filePath] ?: return@mapNotNull null
            SemanticSearchResult(
                mediaItem = entity.toMediaItem(),
                similarity = sr.similarity,
            )
        }
        _semanticSearchResults.value = semanticResults
        _semanticSearchState.value = if (semanticResults.isNotEmpty()) SemanticSearchState.SUCCESS else SemanticSearchState.NO_RESULTS
    }

    fun clearSemanticSearch() {
        _semanticSearchResults.value = emptyList()
        _semanticSearchState.value = SemanticSearchState.IDLE
    }

    /** 获取语义搜索索引统计信息 */
    suspend fun getSemanticStats(): SemanticStats = withContext(Dispatchers.IO) {
        val searcher = buildSemanticSearcher() ?: return@withContext SemanticStats()
        val s = searcher.getStats()
        SemanticStats(
            totalEmbeddings = s.totalEmbeddings,
            modelVersion = s.modelVersion,
            embeddingDim = s.embeddingDim,
        )
    }

    /**
     * 混合检索：先语义召回，再关键词精排。
     * 结合语义搜索（召回相关图片）和 FTS4 关键词搜索（精确匹配），
     * 取并集并去重，语义结果在前。
     *
     * @param query 自然语言查询
     */
    suspend fun hybridSearch(query: String) = withContext(Dispatchers.IO) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _semanticSearchResults.value = emptyList()
            return@withContext
        }

        // 并行执行语义搜索和关键词搜索
        val semanticDeferred = async { semanticSearch(query) }
        val keywordDeferred = async { search(query) }
        semanticDeferred.await()
        keywordDeferred.await()
    }

    // ---- 数据库导入导出 (Phase 4.2) ----

    /** 数据库导出器（Phase 4.2） */
    private val databaseExporter: DatabaseExporter? by lazy {
        DatabaseExporter(mediaDao, faceDao, embeddingDao)
    }

    /** 数据库导入器（Phase 4.2） */
    private val databaseImporter: DatabaseImporter? by lazy {
        DatabaseImporter(mediaDao, faceDao, embeddingDao)
    }

    /**
     * 导出数据库为 JSON 文件（Phase 4.2）。
     *
     * 将全部索引数据（媒体记录、人脸记录、语义嵌入、FTS 索引）序列化导出，
     * 用于换设备后一键恢复，避免重新扫描。
     *
     * @param outputFile 目标文件
     * @param deviceModel 设备型号（写入元数据）
     * @return 导出结果
     */
    suspend fun exportDatabase(
        outputFile: java.io.File,
        deviceModel: String = android.os.Build.MODEL,
    ): DatabaseExporter.ExportResult = withContext(Dispatchers.IO) {
        val exporter = databaseExporter
            ?: return@withContext DatabaseExporter.ExportResult(
                success = false,
                errorMessage = "DatabaseExporter 未初始化",
            )
        exporter.exportToFile(outputFile, deviceModel)
    }

    /**
     * 导入数据库 JSON 文件（Phase 4.2）。
     *
     * 覆盖式恢复：清空现有数据后从 JSON 文件导入全部索引数据。
     * 导入完成后自动重建相册树。
     *
     * @param inputFile 导出的 JSON 文件
     * @return 导入结果
     */
    suspend fun importDatabase(inputFile: java.io.File): DatabaseImporter.ImportResult =
        withContext(Dispatchers.IO) {
            val importer = databaseImporter
                ?: return@withContext DatabaseImporter.ImportResult(
                    success = false,
                    errorMessage = "DatabaseImporter 未初始化",
                )
            // 修复：导入（清空 4 表 + 逐表插入）必须在 scanMutex 内执行，防止与
            // ContentObserver 触发的 rescan 并发：rescan 可能读到"清了一半"的库走错分支，
            // 或与导入交叉写入造成数据错乱。loadFromDb/refreshStats 不持锁，可一并放入。
            val result = scanMutex.withLock {
                val r = importer.importFromFile(inputFile)
                if (r.success) {
                    loadFromDb()
                    refreshStats()
                }
                r
            }
            if (result.success) {
                // 对账扫描在锁外执行：scanMutex 不可重入，且 rescan 自身会持锁。
                // 导入后自动触发一次增量扫描，清理"幽灵记录"并补充设备上未纳入备份的新文件。
                // 注意：roots 为空时 rescan 会清空数据库（见 rescan 内分支），必须跳过，
                // 否则刚导入的数据会被立即清掉。
                val roots = withTimeoutOrNull(5000L) {
                    settingsRepository.state.first()
                }?.scanRoots ?: emptyList()
                if (roots.isNotEmpty()) {
                    Log.i(TAG, "importDatabase: 导入成功，触发对账增量扫描…")
                    rescan()
                } else {
                    Log.w(TAG, "importDatabase: 未配置扫描根目录，跳过对账扫描")
                }
            }
            result
        }

    /**
     * 校验导入文件格式（Phase 4.2），不执行实际导入。
     *
     * @param inputFile 待校验的 JSON 文件
     * @return 校验通过返回 counts JSON，失败返回错误信息
     */
    fun validateImportFile(inputFile: java.io.File): Pair<org.json.JSONObject?, String?> {
        val importer = databaseImporter ?: return null to "DatabaseImporter 未初始化"
        return importer.validateFile(inputFile)
    }

    // ---- 收藏 ----
    suspend fun toggleFavorite(path: String) = withContext(Dispatchers.IO) {
        // P1-4: 使用轻量查询替代 getAll() 全量加载
        val current = mediaDao.getFavoriteByPath(path) ?: false
        mediaDao.setFavorite(path, !current)
        updateFavoriteInMemory(path, !current)
    }

    suspend fun batchSetFavorite(paths: List<String>, favorite: Boolean) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        mediaDao.batchSetFavorite(paths, favorite)
        paths.forEach { updateFavoriteInMemory(it, favorite) }
    }

    // ---- 按场景类型查询 ----
    suspend fun getBySceneType(sceneType: String) = withContext(Dispatchers.IO) {
        mediaDao.getBySceneType(sceneType).map { it.toMediaItem() }
    }

    // ---- 按日期范围查询 ----
    suspend fun getByDateRange(startMs: Long, endMs: Long) = withContext(Dispatchers.IO) {
        mediaDao.searchByDateRange(startMs, endMs).map { it.toMediaItem() }
    }

    // ---- 回收站 ----
    val trashedItems: StateFlow<List<MediaItem>> = mediaDao.getTrashedFlow()
        .map { entities -> entities.map { it.toMediaItem() } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun getTrashed() = withContext(Dispatchers.IO) {
        mediaDao.getTrashed().map { it.toMediaItem() }
    }

    // ---- 地理位置 ----
    suspend fun getWithLocation() = withContext(Dispatchers.IO) {
        mediaDao.getWithLocation().map { it.toMediaItem() }
    }

    // ---- 相似照片管理 (Phase 3.1) ----

    /**
     * 获取所有相似组及其成员（一次性加载，供 UI 分组展示）。
     * 返回 groupId → 成员列表（按质量评分降序，首项为推荐保留的最佳项）。
     */
    suspend fun getSimilarGroups(): List<SimilarGroup> = withContext(Dispatchers.IO) {
        val members = mediaDao.getAllSimilarGroupMembers()
        if (members.isEmpty()) return@withContext emptyList()
        members.groupBy { it.groupId }
            .map { (groupId, list) ->
                SimilarGroup(
                    groupId = groupId,
                    filePaths = list.map { it.filePath },
                    qualityScores = list.associate { it.filePath to it.qualityScore },
                )
            }
            .sortedByDescending { it.filePaths.size }
    }

    /** 获取某个相似组内的完整媒体项（按质量评分降序）。 */
    suspend fun getPhotosInGroup(groupId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getPhotosInSimilarGroup(groupId).map { it.toMediaItem() }
    }

    /**
     * "保留最佳"：删除组内除最高质量评分项之外的所有项（移入回收站）。
     * @return 被移入回收站的路径列表
     */
    suspend fun keepBestInGroup(groupId: String): List<String> = withContext(Dispatchers.IO) {
        val photos = mediaDao.getPhotosInSimilarGroup(groupId)
        if (photos.size <= 1) return@withContext emptyList()
        // 首项质量分最高，保留；其余移入回收站
        val toRemove = photos.drop(1).map { it.filePath }
        if (toRemove.isNotEmpty()) {
            mediaDao.moveToTrash(toRemove, System.currentTimeMillis())
            removeFromMemoryTree(toRemove.toSet())
            refreshStats()
        }
        toRemove
    }

    /**
     * 将指定路径从其相似组中移除（清除 similarGroupId），用于手动拆分组。
     */
    suspend fun removeFromSimilarGroup(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        mediaDao.clearSimilarGroupId(paths)
    }

    /**
     * 将多个路径合并到同一相似组（指定 groupId）。
     */
    suspend fun mergeIntoSimilarGroup(paths: List<String>, groupId: String) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        mediaDao.updateSimilarGroupId(paths, groupId)
    }

    // ---- 重复照片管理 (DUP-1) ----

    /**
     * 执行三级重复检测并返回所有重复组。
     * 使用 [DuplicateAnalyzer] 的 SHA-256 + pHash 分层检测。
     *
     * @return 按 [DuplicateLevel] 分组的重复照片组列表
     */
    suspend fun findDuplicateGroups(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val allPaths = mediaDao.getAllPaths()
        if (allPaths.size < 2) return@withContext emptyList()
        val files = allPaths.map { java.io.File(it) }.filter { it.exists() }
        duplicateAnalyzer.findDuplicates(files, mediaDao)
    }

    /**
     * 快速重复检测（仅 Level 1 + Level 2），用于扫描后即时展示。
     */
    suspend fun findExactAndPerceptualDuplicates(): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val allPaths = mediaDao.getAllPaths()
        if (allPaths.size < 2) return@withContext emptyList()
        val files = allPaths.map { java.io.File(it) }.filter { it.exists() }
        duplicateAnalyzer.findExactAndPerceptualDuplicates(files)
    }

    /**
     * "保留一项删除其余"：保留组内推荐项，其余移入回收站。
     * @param groupId 重复组 ID
     * @param keepPath 保留的文件路径（若为 null 则自动选择推荐项）
     * @return 被移入回收站的路径列表
     */
    suspend fun keepOneDeleteRest(groupId: String, keepPath: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            val groups = findDuplicateGroups()
            val group = groups.find { it.groupId == groupId } ?: return@withContext emptyList()
            val keep = keepPath ?: group.recommendedKeepPath
            val toRemove = group.filePaths.filter { it != keep }
            if (toRemove.isNotEmpty()) {
                mediaDao.moveToTrash(toRemove, System.currentTimeMillis())
                removeFromMemoryTree(toRemove.toSet())
                refreshStats()
            }
            toRemove
        }

    // ---- 人脸聚类管理 (Phase 3.3) ----

    /**
     * 获取所有人脸聚类（供人脸相册界面分组展示）。
     * 返回 clusterId → 聚类信息列表，按人脸数量降序。
     */
    suspend fun getFaceClusters(): List<FaceCluster> = withContext(Dispatchers.IO) {
        val dao = faceDao ?: return@withContext emptyList()
        val summaries = dao.getClusterSummaries()
        if (summaries.isEmpty()) return@withContext emptyList()

        summaries.map { summary ->
            val photos = mediaDao.getByFaceCluster(summary.clusterId)
            FaceCluster(
                clusterId = summary.clusterId,
                personName = summary.personName,
                faceCount = summary.faceCount,
                representativeThumb = summary.representativeThumb,
                filePaths = photos.map { it.filePath },
            )
        }
    }

    /** 人脸聚类摘要 Flow（供 UI 实时更新） */
    val faceClusterSummaries: Flow<List<FaceClusterSummary>>?
        get() = faceDao?.getClusterSummariesFlow()

    /** 获取某个聚类内的完整媒体项 */
    suspend fun getPhotosInFaceCluster(clusterId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getByFaceCluster(clusterId).map { it.toMediaItem() }
    }

    /** 获取某个聚类内的人脸记录 */
    suspend fun getFacesInCluster(clusterId: String): List<FaceEntity> = withContext(Dispatchers.IO) {
        faceDao?.getByCluster(clusterId) ?: emptyList()
    }

    /** 为聚类命名（人物名称） */
    suspend fun setPersonName(clusterId: String, name: String?) = withContext(Dispatchers.IO) {
        faceDao?.setPersonName(clusterId, name)
    }

    /** 获取人脸统计信息 */
    suspend fun getFaceStats(): FaceStats = withContext(Dispatchers.IO) {
        val dao = faceDao ?: return@withContext FaceStats()
        FaceStats(
            totalFaces = dao.getCount(),
            clusteredFaces = dao.getClusteredCount(),
            clusterCount = dao.getClusterCount(),
        )
    }

    // ---- 地理聚类管理 (Phase 3.4) ----

    /**
     * 获取所有地理聚类（供地图视图界面分组展示）。
     * 返回 clusterId → 聚类信息列表，按照片数量降序。
     * 同时执行反向地理编码，填充 placeName 字段。
     */
    suspend fun getGeoClusters(): List<GeoCluster> = withContext(Dispatchers.IO) {
        val summaries = mediaDao.getGeoClusterSummaries()
        if (summaries.isEmpty()) return@withContext emptyList()

        // 批量反向地理编码聚类中心坐标
        val geocoder = reverseGeocoder
        val coordinates = summaries.mapNotNull { s ->
            if (s.centerLatitude != null && s.centerLongitude != null) {
                Pair(s.centerLatitude, s.centerLongitude)
            } else null
        }
        val geoNames = if (geocoder != null && coordinates.isNotEmpty()) {
            geocoder.batchReverseGeocode(coordinates)
        } else {
            emptyMap()
        }

        summaries.map { summary ->
            val photos = mediaDao.getByGeoCluster(summary.clusterId)
            val coord = if (summary.centerLatitude != null && summary.centerLongitude != null) {
                Pair(summary.centerLatitude, summary.centerLongitude)
            } else null
            val placeName = coord?.let { geoNames[it]?.shortName }
            GeoCluster(
                clusterId = summary.clusterId,
                photoCount = summary.photoCount,
                centerLatitude = summary.centerLatitude ?: 0.0,
                centerLongitude = summary.centerLongitude ?: 0.0,
                representativeThumb = summary.representativeThumb,
                placeName = placeName,
                filePaths = photos.map { it.filePath },
            )
        }
    }

    /** 获取某个地理聚类内的完整媒体项 */
    suspend fun getPhotosInGeoCluster(clusterId: String): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getByGeoCluster(clusterId).map { it.toMediaItem() }
    }

    /** 获取地理统计信息 */
    suspend fun getGeoStats(): GeoStats = withContext(Dispatchers.IO) {
        val totalGeoItems = mediaDao.getWithLocation().size
        // P1-5: 一次聚合查询各聚类数量，避免 N+1
        val counts = mediaDao.getGeoClusterCounts()
        val clustered = counts.sumOf { it.photoCount }
        GeoStats(
            totalGeoItems = totalGeoItems,
            clusterCount = counts.size,
            noiseCount = (totalGeoItems - clustered).coerceAtLeast(0),
        )
    }

    // ---- 统计 ----
    private suspend fun refreshStats() = withContext(Dispatchers.IO) {
        val sceneStats = mediaDao.getSceneTypeStats().associate { it.sceneType to it.cnt }
        _stats.value = AlbumStats(
            totalCount = mediaDao.getCount(),
            // P2-3: 使用 COUNT 查询替代 getTrashed().size，避免全量加载回收站行
            trashedCount = mediaDao.getTrashedCount(),
            sceneTypeCounts = sceneStats,
        )
    }

    // ---- 树操作 ----
    fun findAlbumById(id: String): Album? {
        val roots = _albumTree.value
        return findAlbumInTree(roots, id)
    }

    private fun findAlbumInTree(albums: List<Album>, id: String): Album? {
        for (album in albums) {
            if (album.id == id) return album
            val found = findAlbumInTree(album.children, id)
            if (found != null) return found
        }
        return null
    }

    suspend fun updateAlbumCover(albumId: String, coverPath: String) {
        val album = findAlbumById(albumId) ?: return
        val coverItem = album.mediaItems.find { it.filePath == coverPath } ?: return
        val updated = album.copy(coverItem = coverItem)
        val newTree = replaceAlbumInTree(_albumTree.value, updated)
        _albumTree.value = newTree
    }

    private fun replaceAlbumInTree(albums: List<Album>, updated: Album): List<Album> {
        return albums.map { album ->
            if (album.id == updated.id) updated
            else album.copy(children = replaceAlbumInTree(album.children, updated))
        }
    }

    /** 关闭 Repository 时释放协程作用域（由 AppContainer 管理生命周期时调用） */
    fun dispose() {
        // P1-1: 取消协程作用域，释放 stateIn 订阅和挂起任务
        scope.cancel()
    }

    // ---- 内部方法 ----

    /**
     * 从内存中的 [MediaItem] 列表重建相册树。
     * 使用 suspend + flow first() 替代 runBlocking，避免阻塞主线程。
     */
    private suspend fun rebuildTreeFromMediaItems(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        // 诊断+防护：settingsRepository.state.first() 可能因 combine(vararg) Flow 不发射而永久阻塞
        val settings = withTimeoutOrNull(5000L) {
            settingsRepository.state.first()
        }
        if (settings == null) {
            Log.e(TAG, "rebuildTreeFromMediaItems: settingsRepository.state.first() 超时(5s)，使用默认设置")
        }
        val rootTree = buildDirectoryNodes(items, settings?.scanRoots ?: emptyList())
        rebuildTreeFromDirectoryNodes(rootTree, settings?.albumSortMode ?: 0)
    }

    private suspend fun rebuildTreeFromDirectoryNodes(
        rootTree: List<DirectoryNode>,
        sortMode: Int = 0,
    ) = withContext(Dispatchers.IO) {
        val albums = albumBuilder.buildAlbumTree(rootTree, sortMode)
        val leafs = albumBuilder.buildFromDirectoryTree(rootTree, sortMode)
        _albumTree.value = albums
        _leafAlbums.value = leafs
        // 初始加载时使用刷新机制打乱推荐
        refreshRecommendations(leafs)
    }

    /**
     * 刷新推荐列表。
     * 从全量推荐池中取下一批未展示的推荐，保证每次刷新内容不同。
     * 当所有推荐都展示完毕后自动重新打乱循环。
     */
    suspend fun refreshRecommendations() = withContext(Dispatchers.IO) {
        val leafs = _leafAlbums.value
        if (leafs.isEmpty()) {
            _recommendations.value = emptyList()
            return@withContext
        }
        refreshRecommendations(leafs)
    }

    /**
     * 内部刷新逻辑：基于给定的叶子相册列表生成推荐批次。
     */
    private suspend fun refreshRecommendations(leafs: List<Album>) {
        // P3-1: 加锁保护共享的推荐池/游标，避免并发刷新导致状态错乱
        recommendationMutex.withLock {
            // 若池为空或已循环完毕，重新生成全量推荐并随机打乱
            if (shuffledRecommendationPool.isEmpty() || recommendationCursor >= shuffledRecommendationPool.size) {
                val allRecs = recommendationEngine.generateAll(leafs)
                shuffledRecommendationPool = allRecs.shuffled()
                recommendationCursor = 0
            }

            val nextBatch = shuffledRecommendationPool
                .drop(recommendationCursor)
                .take(recommendationBatchSize)

            recommendationCursor += nextBatch.size
            _recommendations.value = nextBatch
        }
    }

    private fun buildDirectoryNodes(
        items: List<MediaItem>,
        roots: List<String>,
    ): List<DirectoryNode> {
        val byParent: Map<String, List<MediaItem>> = items.groupBy { it.filePath.substringBeforeLast('/', "") }
        val dirNodes = mutableMapOf<String, DirectoryNode>()

        // 1) 创建直接包含媒体文件的目录节点
        byParent.forEach { (parentPath, mediaList) ->
            val name = parentPath.substringAfterLast('/').ifEmpty { parentPath }
            val parentDir = parentPath.substringBeforeLast('/', "").ifEmpty { null }
            dirNodes[parentPath] = DirectoryNode(
                path = parentPath,
                name = name,
                parentPath = parentDir,
                children = emptyList(),
                mediaItems = mediaList,
            )
        }

        // P1-D: 补全中间目录节点（仅含子文件夹、不含直接媒体文件的目录）
        // 仅创建在任一扫描根目录下的中间节点，保证嵌套文件夹层级结构完整。
        val intermediatePaths = mutableSetOf<String>()
        byParent.keys.forEach { path ->
            var p = path.substringBeforeLast('/', "")
            while (p.isNotEmpty()) {
                if (p !in dirNodes.keys && roots.any { p.startsWith(it) }) {
                    intermediatePaths.add(p)
                }
                p = p.substringBeforeLast('/', "")
            }
        }
        intermediatePaths.forEach { path ->
            val name = path.substringAfterLast('/').ifEmpty { path }
            val parentDir = path.substringBeforeLast('/', "").ifEmpty { null }
            dirNodes[path] = DirectoryNode(
                path = path,
                name = name,
                parentPath = parentDir,
                children = emptyList(),
                mediaItems = emptyList(),
            )
        }

        // 2) 计算父子映射关系 (仅使用路径字符串，不存储节点引用)
        val childPathMap = mutableMapOf<String, MutableList<String>>()
        dirNodes.forEach { (path, _) ->
            val parent = path.substringBeforeLast('/', "")
            if (parent.isNotEmpty() && dirNodes.containsKey(parent)) {
                childPathMap.getOrPut(parent) { mutableListOf() }.add(path)
            }
        }

        // 3) 自底向上重建节点树：按路径深度降序排列，深层节点先更新，
        //    保证父节点的 children 引用到的是已更新的子节点副本。
        val sortedByDepth = dirNodes.keys.sortedByDescending { it.count { c -> c == '/' } }
        for (path in sortedByDepth) {
            val node = dirNodes[path]!!
            val childPaths = childPathMap[path] ?: emptyList()
            val children = childPaths.map { childPath -> dirNodes[childPath]!! }
            dirNodes[path] = node.copy(children = children)
        }

        // 4) 过滤根节点：仅保留扫描根目录下、且父目录不在 dirNodes 中的顶层节点（避免重复）。
        val allChildPaths = childPathMap.values.flatten().toSet()
        return dirNodes.values.filter { node ->
            roots.any { root -> node.path.startsWith(root) } &&
                node.path !in allChildPaths
        }
    }

    private fun clearAll() {
        _albumTree.value = emptyList()
        _leafAlbums.value = emptyList()
        _recommendations.value = emptyList()
    }

    private suspend fun removeFromMemoryTree(paths: Set<String>) = withContext(Dispatchers.IO) {
        val remaining = _albumTree.value.flatMap { collectAllMedia(it) }
            .filter { it.filePath !in paths }
        rebuildTreeFromMediaItems(remaining)
    }

    /** 删除数据库中文件系统已不存在的记录（因文件已通过外部工具删除） */
    private suspend fun removeOrphanedRecords(actualPaths: Set<String>) = withContext(Dispatchers.IO) {
        val dbPaths = mediaDao.getAllPaths().toSet()
        val orphaned = dbPaths - actualPaths
        if (orphaned.isNotEmpty()) {
            // 修复：分块删除，避免 SQLite IN 查询变量数超过 999 限制
            orphaned.toList().chunked(500).forEach { chunk ->
                mediaDao.deleteByPaths(chunk)
                mediaDao.deleteFtsEntries(chunk)
            }
        }
    }

    /** 暴露 MediaDao 供 Worker 使用 */
    internal fun getMediaDao(): MediaDao = mediaDao

    /** 获取已在库中的项目列表 (for TrashCleanupWorker) */
    internal suspend fun getLoadedItems(): List<MediaItem> = withContext(Dispatchers.IO) {
        mediaDao.getAll().map { it.toMediaItem() }
    }

    private fun updateFavoriteInMemory(path: String, favorite: Boolean) {
        fun updateAlbum(album: Album): Album {
            val updatedItems = album.mediaItems.map { item ->
                if (item.filePath == path) item.copy(isFavorite = favorite) else item
            }
            val updatedChildren = album.children.map { updateAlbum(it) }
            return album.copy(mediaItems = updatedItems, children = updatedChildren)
        }
        _albumTree.value = _albumTree.value.map { updateAlbum(it) }
        _leafAlbums.value = _leafAlbums.value.map { updateAlbum(it) }
    }

    /** 内存中收集所有媒体项 */
    private fun collectMediaItems(node: DirectoryNode, out: MutableList<MediaItem>) {
        out.addAll(node.mediaItems)
        node.children.forEach { collectMediaItems(it, out) }
    }

    /** 从 Album 树递归收集所有媒体项 */
    private fun collectAllMedia(album: Album): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        result.addAll(album.mediaItems)
        album.children.forEach { result.addAll(collectAllMedia(it)) }
        return result
    }

    // ---- 实体映射 ----
    private fun MediaItem.toEntity() = MediaEntity(
        filePath = filePath,
        fileName = fileName,
        mediaType = type,
        capturedAtMs = capturedAt.toEpochMilli(),
        modifiedAtMs = modifiedAt.toEpochMilli(),
        parentPath = filePath.substringBeforeLast('/', ""),
        fileSize = fileSize,
        isFavorite = isFavorite,
        isTrashed = isTrashed,
        deletedAtMs = deletedAtMs,
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
        isCorrupted = isCorrupted,
        faceClusterId = null,
    )

    private fun MediaEntity.toMediaItem() = MediaItem(
        id = java.util.UUID.nameUUIDFromBytes(filePath.toByteArray()).toString(),
        filePath = filePath,
        fileName = fileName,
        type = mediaType,
        capturedAt = Instant.ofEpochMilli(capturedAtMs),
        modifiedAt = Instant.ofEpochMilli(modifiedAtMs),
        fileSize = fileSize,
        isFavorite = isFavorite,
        isTrashed = isTrashed,
        deletedAtMs = deletedAtMs,
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
        isCorrupted = isCorrupted,
        faceClusterId = faceClusterId,
        ocrText = ocrText,
    )
}

/**
 * 人脸统计信息（Phase 3.3）。
 */
data class FaceStats(
    val totalFaces: Int = 0,
    val clusteredFaces: Int = 0,
    val clusterCount: Int = 0,
)