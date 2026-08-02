package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.core.analysis.DuplicateAnalyzer

import kotlinx.coroutines.cancel

import android.util.Log
import com.renyxin.localalbum.core.album.AlbumBuilder
import com.renyxin.localalbum.core.index.HybridIndexer
import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.DirectoryNode
import com.renyxin.localalbum.core.model.DirectoryMediaAnchor
import com.renyxin.localalbum.core.model.DirectoryMediaQuery
import com.renyxin.localalbum.core.model.DirectoryMediaQueryMapper
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaQueryContext
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.recommendation.Recommendation
import com.renyxin.localalbum.core.recommendation.RecommendationDiversifier
import com.renyxin.localalbum.core.recommendation.RecommendationEngine
import com.renyxin.localalbum.core.recommendation.RecommendationFileRotator
import com.renyxin.localalbum.core.search.SemanticSearcher
import com.renyxin.localalbum.data.backup.DatabaseExporter
import com.renyxin.localalbum.data.backup.DatabaseImporter
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.FaceClusterSummary
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.DirectorySummary
import com.renyxin.localalbum.data.db.entity.AlbumSnapshotDirectoryEntity
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.worker.DuplicateMaintenanceWorker
import com.renyxin.localalbum.data.worker.FaceClusterMaintenanceWorker
import com.renyxin.localalbum.data.source.MediaSource
import com.renyxin.localalbum.data.worker.AnalysisResumePrefs
import com.renyxin.localalbum.data.worker.ScanServiceController
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import androidx.paging.map
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emitAll
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

/** 相册快照与后台媒体校准状态；独立于 AI 分析进度。 */
sealed interface AlbumSyncState {
    data object Restoring : AlbumSyncState
    data object FirstBuild : AlbumSyncState
    data class Checking(val hasCachedAlbums: Boolean) : AlbumSyncState
    data class Updating(
        val hasCachedAlbums: Boolean,
        val processed: Int = 0,
        val total: Int = 0,
    ) : AlbumSyncState
    data class UpToDate(val updatedAtMs: Long) : AlbumSyncState
    data class Paused(val hasCachedAlbums: Boolean) : AlbumSyncState
    data class Failed(val message: String, val hasCachedAlbums: Boolean) : AlbumSyncState
}

data class AlbumStats(
    val totalCount: Int = 0,
    val favoriteCount: Int = 0,
    val trashedCount: Int = 0,
    val sceneTypeCounts: Map<String, Int> = emptyMap(),
)

/**
 * 重复照片组，基于 SHA-256 完全重复检测。
 */
typealias DuplicateGroup = DuplicateAnalyzer.DuplicateGroup

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
 * 语义搜索结果（Phase 4.1），用于自然语言搜索界面。
 * @param mediaItem 匹配的媒体项
 * @param similarity 与查询的余弦相似度（0~1）
 */
data class SemanticSearchResult(
    val mediaItem: MediaItem,
    val similarity: Float,
)

/** 数据库分页搜索上下文；所有筛选条件都参与 PagingSource 身份。 */
data class MediaSearchQuery(
    val text: String,
    val mediaType: MediaType? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val model: String? = null,
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
    private val database: AppDatabase? = null,
    private val faceDao: FaceDao? = null,
    private val embeddingDao: EmbeddingDao? = null,
    private val mediaSource: MediaSource = MediaSource(),
    private val albumBuilder: AlbumBuilder = AlbumBuilder(),
    private val recommendationEngine: RecommendationEngine = RecommendationEngine(
        semanticClusterRecommender = embeddingDao?.let {
            com.renyxin.localalbum.core.recommendation.SemanticClusterRecommender(
                embeddingDao = it,
                mediaDao = mediaDao,
                semanticDao = database?.semanticDao(),
            )
        },
    ),
    private val recommendationDiversifier: RecommendationDiversifier = RecommendationDiversifier(),
    private val recommendationFileRotator: RecommendationFileRotator = RecommendationFileRotator(),
    private val hybridIndexer: HybridIndexer? = null,
    private val thumbnailScheduler: ThumbnailScheduler? = null,
    private val deletionService: PersistentDeletionService? = null,
    private val mediaDeletionCoordinator: MediaDeletionCoordinator? = null,
    private val semanticProviderFactory: (() -> com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider?)? = null,
    context: android.content.Context? = null,
) {
    companion object {
        private const val TAG = "AlbumRepository"
        private const val DATABASE_DELETE_BATCH_SIZE = 500
        private const val TRASH_PURGE_BATCH_SIZE = 200

        /** 全量推荐池按 keyset 分页构建，避免单次 Room 查询返回整个大图库。 */
        private const val RECOMMENDATION_PAGE_SIZE = 1_000
    }
    /** 与 Repository 生命周期绑定的协程作用域，替代 GlobalScope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        return SemanticSearcher(provider, dao, database?.semanticDao())
    }

    private val _albumTree = MutableStateFlow<List<Album>>(emptyList())
    val albumTree: StateFlow<List<Album>> = _albumTree.asStateFlow()

    private val _leafAlbums = MutableStateFlow<List<Album>>(emptyList())
    val leafAlbums: StateFlow<List<Album>> = _leafAlbums.asStateFlow()

    private val _recommendations = MutableStateFlow<List<Recommendation>>(emptyList())
    val recommendations: StateFlow<List<Recommendation>> = _recommendations.asStateFlow()

    // ---- 推荐去重追踪 ----

    /** 每批展示的推荐数量 */
    private val recommendationBatchSize: Int = 10

    /** 全量多样性有序推荐池（Phase 5: 替换原 shuffled 随机打乱） */
    private var diversifiedRecommendationPool: List<Recommendation> = emptyList()

    /** 当前批次指针 */
    private var recommendationCursor: Int = 0

    /** 推荐刷新互斥锁，保护共享的推荐池与游标 */
    private val recommendationMutex = Mutex()

    /** 扫描/重分析互斥锁，串行化 rescan/forceReanalyzeAll/restoreFromDbIfNeeded，
     *  防止 ContentObserver 触发的增量扫描与用户手动扫描并发执行导致数据/FTS/状态竞态。 */
    private val scanMutex = Mutex()

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _albumSyncState = MutableStateFlow<AlbumSyncState>(AlbumSyncState.Restoring)
    val albumSyncState: StateFlow<AlbumSyncState> = _albumSyncState.asStateFlow()

    @Volatile
    private var albumCacheRestored = false

    /** 语义搜索结果（Phase 4.1），含相似度评分 */
    private val _semanticSearchResults = MutableStateFlow<List<SemanticSearchResult>>(emptyList())
    val semanticSearchResults: StateFlow<List<SemanticSearchResult>> = _semanticSearchResults.asStateFlow()

    /** 语义搜索状态（区分无匹配与模型/索引问题） */
    private val _semanticSearchState = MutableStateFlow(SemanticSearchState.IDLE)
    val semanticSearchState: StateFlow<SemanticSearchState> = _semanticSearchState.asStateFlow()

    private val _stats = MutableStateFlow(AlbumStats())
    val stats: StateFlow<AlbumStats> = _stats.asStateFlow()
// ---- 收藏与轻量统计 ----

    /**
     * 仅订阅聚合计数，禁止首页为展示收藏数而常驻完整媒体列表。
     * 大图库下完整实体 Flow 会在每一次索引写入时复制、映射并触发 Compose 重组。
     */
    val favoriteCount: StateFlow<Int> = mediaDao.getFavoriteCountFlow()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val totalCount: StateFlow<Int> = mediaDao.getTotalCountFlow()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    // ---- Paging 3 分页数据流 ----
    private val mediaPagingConfig = PagingConfig(
        pageSize = 50,
        prefetchDistance = 20,
        enablePlaceholders = false,
        initialLoadSize = 100,
    )

    /**
     * 查看器需要占位符来保留数据库中的绝对索引和总数。
     * 这样从图库中部打开文件时，Pager 既能准确显示“当前位置 / 总数”，
     * 也能在当前加载窗口边界继续向前、向后加载。
     */
    private val viewerPagingConfig = PagingConfig(
        pageSize = 50,
        prefetchDistance = 20,
        enablePlaceholders = true,
        initialLoadSize = 100,
    )

    val pagedMedia: Flow<PagingData<MediaItem>> = Pager(
        config = mediaPagingConfig,
        pagingSourceFactory = { mediaDao.pagingSource() },
    ).flow
        .map { pagingData -> pagingData.map { it.toMediaItem() } }
        .cachedIn(scope)

    /** 收藏页独立分页，不能从全量媒体 Flow 过滤。 */
    val pagedFavorites: Flow<PagingData<MediaItem>> = Pager(
        config = mediaPagingConfig,
        pagingSourceFactory = { mediaDao.favoritesPagingSource() },
    ).flow
        .map { pagingData -> pagingData.map { it.toMediaItem() } }
        .cachedIn(scope)

    suspend fun requestThumbnail(item: MediaItem, sizeClass: String, priority: Int): String? =
        thumbnailScheduler?.request(item, sizeClass, priority)

    val trashedCount: StateFlow<Int> = mediaDao.getTrashedCountFlow()
        .stateIn(scope, SharingStarted.Eagerly, 0)

    /** P1-A：回收站页面独立分页。 */
    val pagedTrash: Flow<PagingData<MediaItem>> = Pager(
        config = mediaPagingConfig,
        pagingSourceFactory = { mediaDao.trashPagingSource() },
    ).flow.map { data -> data.map { it.toMediaItem() } }.cachedIn(scope)

    /** P1-A：人物详情独立分页。 */
    fun pagedMediaForFaceCluster(clusterId: String): Flow<PagingData<MediaItem>> = Pager(
        config = mediaPagingConfig,
        pagingSourceFactory = { mediaDao.faceClusterPagingSource(clusterId) },
    ).flow.map { data -> data.map { it.toMediaItem() } }

    /** P1-A：关键词和筛选全部下推 Room，而非过滤当前 Paging 窗口。 */
    fun pagedSearch(query: MediaSearchQuery): Flow<PagingData<MediaItem>> {
        val fts = toFtsQuery(query.text.trim())
        return Pager(
            config = mediaPagingConfig,
            pagingSourceFactory = {
                mediaDao.searchPagingSource(
                    fts,
                    query.mediaType?.name,
                    query.startMs,
                    query.endMs,
                    query.model,
                )
            },
        ).flow.map { data -> data.map { it.toMediaItem() } }
    }

    suspend fun getSearchModels(): List<String> = withContext(Dispatchers.IO) {
        mediaDao.getDistinctModels()
    }

    /** 根据稳定导航上下文重建查看器数据，并以 initialPath 所在数据库偏移作为首屏。 */
    fun viewerMedia(context: MediaQueryContext, initialPath: String): Flow<PagingData<MediaItem>> =
        kotlinx.coroutines.flow.flow {
            val initial = mediaDao.getByFilePathLight(initialPath)
            if (context == MediaQueryContext.Single || initial == null) {
                emit(PagingData.from(listOfNotNull(initial?.toMediaItem())))
                return@flow
            }
            if (context is MediaQueryContext.StablePaths) {
                val entities = if (context.paths.isEmpty()) emptyList()
                else mediaDao.getByFilePathsLight(context.paths)
                val byPath = entities.associateBy { it.filePath }
                emit(PagingData.from(context.paths.mapNotNull(byPath::get).map { it.toMediaItem() }))
                return@flow
            }

            val initialKey = when (context) {
                MediaQueryContext.Timeline -> mediaDao.timelineOffset(initial.capturedAtMs, initialPath)
                is MediaQueryContext.Directory -> mediaDao.directoryOffset(
                    DirectoryMediaQueryMapper.offset(context.query, initial.toDirectoryAnchor()).toRoomQuery()
                )
                is MediaQueryContext.Search -> with(context.query) {
                    mediaDao.searchOffset(
                        toFtsQuery(text.trim()), mediaType?.name, startMs, endMs, model,
                        initial.capturedAtMs, initialPath,
                    )
                }
                is MediaQueryContext.Face -> mediaDao.faceClusterOffset(
                    context.clusterId, initial.capturedAtMs, initialPath
                )
                MediaQueryContext.Favorites -> mediaDao.favoritesOffset(initial.capturedAtMs, initialPath)
                else -> 0
            }
            val source = when (context) {
                MediaQueryContext.Timeline -> { { mediaDao.pagingSource() } }
                is MediaQueryContext.Directory -> { {
                    mediaDao.directoryPagingSource(DirectoryMediaQueryMapper.paging(context.query).toRoomQuery())
                } }
                is MediaQueryContext.Search -> { {
                    with(context.query) {
                        mediaDao.searchPagingSource(
                            toFtsQuery(text.trim()), mediaType?.name, startMs, endMs, model
                        )
                    }
                } }
                is MediaQueryContext.Face -> { { mediaDao.faceClusterPagingSource(context.clusterId) } }
                MediaQueryContext.Favorites -> { { mediaDao.favoritesPagingSource() } }
                else -> error("Unsupported paging context: $context")
            }
            emitAll(
                Pager(viewerPagingConfig, initialKey = initialKey, pagingSourceFactory = source)
                    .flow.map { data -> data.map { it.toMediaItem() } }
            )
        }

    suspend fun getMediaByPath(path: String): MediaItem? = withContext(Dispatchers.IO) {
        mediaDao.getByFilePathLight(path)?.toMediaItem()
    }

    suspend fun loadFromDb() = withContext(Dispatchers.IO) {
        publishCommittedAlbumSnapshot()
        refreshStats()
    }

    /**
     * 冷启动只读取最后完整发布的目录快照，不等待扫描锁，也不执行推荐和统计查询。
     * 迁移后的数据库会预填首份快照；快照意外缺失时才从媒体表聚合一次并立即持久化。
     */
    suspend fun restoreFromDbIfNeeded() = withContext(Dispatchers.IO) {
        if (albumCacheRestored) return@withContext
        _albumSyncState.value = AlbumSyncState.Restoring
        val snapshotDao = database?.albumSnapshotDao()
        var meta = snapshotDao?.getMeta()
        var summaries = snapshotDao?.getDirectories().orEmpty().map { it.toDirectorySummary() }
        if (summaries.isEmpty()) {
            summaries = mediaDao.getDirectorySummaries()
            if (summaries.isNotEmpty() && snapshotDao != null) {
                val version = System.currentTimeMillis()
                snapshotDao.replaceSnapshot(
                    entries = summaries.map { it.toSnapshotEntity(version) },
                    version = version,
                    updatedAtMs = version,
                )
                meta = snapshotDao.getMeta()
            }
        }
        if (summaries.isNotEmpty()) {
            rebuildTreeFromDirectorySummaries(summaries, rebuildRecommendations = false)
            _scanState.value = ScanState.Done
            _albumSyncState.value = AlbumSyncState.UpToDate(meta?.updatedAtMs ?: 0L)
        } else {
            _albumSyncState.value = AlbumSyncState.FirstBuild
        }
        albumCacheRestored = true
        Log.i(TAG, "restoreFromDbIfNeeded: 发布 ${summaries.size} 个缓存目录")
    }

    // ---- 扫描 ----
    suspend fun rescan() = withContext(Dispatchers.IO) {
        restoreFromDbIfNeeded()
        val hadCachedAlbums = _albumTree.value.isNotEmpty()
        _albumSyncState.value = AlbumSyncState.Checking(hadCachedAlbums)
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
                Log.w(TAG, "rescan: scanRoots 为空，保留已提交快照并返回 Idle")
                _scanState.value = ScanState.Idle
                _albumSyncState.value = if (_albumTree.value.isEmpty()) {
                    AlbumSyncState.FirstBuild
                } else {
                    AlbumSyncState.UpToDate(
                        database?.albumSnapshotDao()?.getMeta()?.updatedAtMs ?: 0L
                    )
                }
                return@runCatching
            }

            // Phase 0: 确认有扫描任务后才启动前台服务，防杀后台。AI 分析侧由 HybridIndexer 独立 acquire。
            appContext?.let { ScanServiceController.acquire(it, "正在扫描媒体…") }
            serviceAcquired = true

            if (hybridIndexer != null) {
                // ---- 混合索引路径：优先使用 HybridIndexer ----
                val isFirstScan = mediaDao.getCount() == 0
                _albumSyncState.value = AlbumSyncState.Updating(hadCachedAlbums)
                _scanState.value = ScanState.Scanning(
                    if (isFirstScan) "首次全量扫描…" else "增量扫描…"
                )
                if (isFirstScan) {
                    // Phase 3: 全量扫描进度填充（D10）——指纹阶段上报 processed/total
                    hybridIndexer.fullScan(roots, allowNomedia, ignorePatterns) { processed, total ->
                        _scanState.value = ScanState.Scanning("首次全量扫描…", processed, total)
                        _albumSyncState.value = AlbumSyncState.Updating(hadCachedAlbums, processed, total)
                    }
                } else {
                    hybridIndexer.incrementalScan(roots, allowNomedia, ignorePatterns)
                }
                // 修复：移除无效的"兜底孤儿清理"调用。原调用 removeOrphanedRecords(mediaDao.getAllPaths())
                // 把数据库路径当作"文件系统实际路径"传入，差集恒为空，是 no-op（还白做两次全表路径查询）。
                // 孤儿清理由 fullScan/incrementalScan 内部基于真实文件系统快照完成，此处无需重复。
                publishCommittedAlbumSnapshot()
                _scanState.value = ScanState.Done
                _albumSyncState.value = AlbumSyncState.UpToDate(
                    database?.albumSnapshotDao()?.getMeta()?.updatedAtMs ?: System.currentTimeMillis()
                )
                scope.launch {
                    refreshStats()
                    rebuildRecommendationPool(_leafAlbums.value)
                }
            } else {
                // 依赖注入异常必须显式失败，禁止静默退回构造多份全库映射的旧扫描路径。
                error("HybridIndexer 未注入，已拒绝执行高风险全量扫描兜底")
            }
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "扫描已取消，继续保留上次完整相册快照")
                _scanState.value = ScanState.Done
                _albumSyncState.value = AlbumSyncState.Paused(_albumTree.value.isNotEmpty())
            } else {
                Log.e(TAG, "扫描失败", e)
                val message = e.message ?: "扫描失败"
                _scanState.value = ScanState.Failed(message)
                _albumSyncState.value = AlbumSyncState.Failed(message, _albumTree.value.isNotEmpty())
            }
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
        val indexer = hybridIndexer ?: error("HybridIndexer 未注入，无法创建持久化重分析任务")
        _scanState.value = ScanState.Scanning("正在创建全量重分析任务…")
        indexer.requestFullReanalysis()
        _scanState.value = ScanState.Done
        refreshStats()
        Log.i(TAG, "forceReanalyzeAll: 持久化任务已重置并开始有界领取")
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
        val indexer = hybridIndexer ?: run {
            AnalysisResumePrefs.setPending(ctx, false)
            return@withContext
        }
        scanMutex.withLock {
            Log.i(TAG, "resumeAnalysisIfNeeded: 恢复持久化分析任务领取")
            indexer.resumePendingAnalysis()
            AnalysisResumePrefs.setPending(ctx, false)
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
     * 彻底删除物理文件，并且只清理删除成功或原本已不存在路径的数据库记录。
     *
     * 删除失败项保留原数据库/回收站状态，避免无权限删除时记录先消失、随后又被扫描复活。
     */
    suspend fun permanentlyDelete(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        val service = requireNotNull(deletionService) { "PersistentDeletionService 未注入" }
        val physicallyDeleted = service.delete(
            paths,
            com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity.OP_USER_PERMANENT,
        )
        if (physicallyDeleted.isNotEmpty()) {
            removeFromMemoryTree(physicallyDeleted.toSet())
            refreshStats()
        }
        appContext?.let { com.renyxin.localalbum.data.worker.DeletionRetryWorker.enqueue(it) }
    }

    suspend fun restoreFromTrash(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        // 显式 restore 是唯一清除删除意图的操作；与可见状态在同一 Room 事务提交。
        if (database != null && deletionService != null) {
            database.withTransaction {
                deletionService.restore(paths)
                mediaDao.restoreFromTrash(paths)
            }
        } else {
            deletionService?.restore(paths)
            mediaDao.restoreFromTrash(paths)
        }
        loadFromDb()
    }

    /**
     * 清空回收站：按路径 keyset 有界读取，只清理物理删除成功或原文件已不存在的路径。
     *
     * 游标始终推进到本次读取批次的末尾，因此删除失败项不会反复占据首批形成死循环；
     * 它们保留在回收站中，用户下次操作时可重试。
     */
    suspend fun clearTrash() = withContext(Dispatchers.IO) {
        var afterPath = ""
        var purgedAny = false
        while (true) {
            val paths = mediaDao.getTrashedPathsAfter(afterPath, TRASH_PURGE_BATCH_SIZE)
            if (paths.isEmpty()) break
            afterPath = paths.last()
            val physicallyDeleted = requireNotNull(deletionService) { "PersistentDeletionService 未注入" }.delete(
                paths,
                com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity.OP_CLEAR_TRASH,
            )
            if (physicallyDeleted.isNotEmpty()) purgedAny = true
            if (paths.size < TRASH_PURGE_BATCH_SIZE) break
        }
        if (purgedAny) {
            rebuildTreeFromDirectorySummaries()
            refreshStats()
        }
    }

    /**
     * 删除物理文件（与 TrashCleanupWorker 一致使用 File.delete，失败仅告警不阻塞）。
     */
    private suspend fun deletePhysicalFiles(paths: List<String>): List<String> = withContext(Dispatchers.IO) {
        val result = PhysicalFileDeletion.delete(paths)
        result.failed.forEach { path ->
            // 日志保留行为类别但不打印完整隐私路径。
            Log.w(TAG, "物理文件删除失败（可能无权限）: ${path.substringAfterLast('/')}")
        }
        result.deletedOrMissing
    }

    /**
     * 供用户永久删除和后台回收站清理复用的一致删除入口。
     * 仅在物理文件已删除或原文件已不存在时清理数据库，删除失败的路径保留在回收站以便重试。
     */
    suspend fun purgeExpiredTrashBatch(before: Long, limit: Int = 200): Int = withContext(Dispatchers.IO) {
        val paths = mediaDao.getExpiredTrashPaths(before, limit)
        if (paths.isEmpty()) return@withContext 0
        val physicallyDeleted = requireNotNull(deletionService) { "PersistentDeletionService 未注入" }.delete(
            paths,
            com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity.OP_EXPIRED_TRASH,
        )
        removeFromMemoryTree(physicallyDeleted.toSet())
        physicallyDeleted.size
    }

    /**
     * 彻底清理媒体及全部关联数据。真实应用注入 AppDatabase 时，每一块在同一事务内提交，
     * 防止进程中断留下部分孤儿；database 为 null 仅用于轻量测试替身。
     */
    private suspend fun purgeMediaData(paths: List<String>) {
        if (paths.isEmpty()) return
        requireNotNull(mediaDeletionCoordinator) { "MediaDeletionCoordinator 未注入，禁止手工逐 DAO purge" }
            .purge(paths)
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
        val searchPreferences = com.renyxin.localalbum.core.analysis.AiAnalysisPreferencesRuntime.current
        val diagnostics = searcher.searchDetailed(
            query = query.trim(),
            topK = searchPreferences.semanticSearchResultCount,
            minSimilarity = searchPreferences.semanticSearchStrictness.minSimilarity,
        )

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

    // ---- 数据库导入导出 (Phase 4.2) ----

    /** 数据库导出器（Phase 4.2） */
    private val databaseExporter: DatabaseExporter? by lazy {
        DatabaseExporter(mediaDao, faceDao, embeddingDao, database)
    }

    /** 数据库导入器（Phase 4.2） */
    private val databaseImporter: DatabaseImporter? by lazy {
        DatabaseImporter(mediaDao, faceDao, embeddingDao, database)
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
        // 导出会全量读取多张表；若与扫描的批量 REPLACE、FTS 重建及 AI 写库交错，
        // 峰值内存、SQLite WAL 和游标数量都会放大，1W+ 文件时容易触发闪退。
        // 与扫描/导入共享同一把锁，导出得到完整一致的数据库快照；扫描结束后才开始导出。
        scanMutex.withLock {
            exporter.exportToFile(outputFile, deviceModel)
        }
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

    // ---- 地理位置 ----
    suspend fun getWithLocation() = withContext(Dispatchers.IO) {
        mediaDao.getWithLocation().map { it.toMediaItem() }
    }

    // ---- 重复照片离线维护 ----

    private val duplicateDao get() = database?.duplicateMaintenanceDao()

    val duplicateMaintenanceRun: Flow<MaintenanceRunEntity?> = duplicateDao
        ?.observeLatestRun(MaintenanceRunEntity.TASK_DUPLICATE_EXACT)
        ?: kotlinx.coroutines.flow.flowOf(null)

    /** UI 只观察 active generation 的有限组窗口。 */
    fun observeDuplicateGroups(limit: Int = 50): Flow<List<DuplicateGroup>> =
        duplicateDao?.observeActiveGroups(MaintenanceRunEntity.TASK_DUPLICATE_EXACT, limit, 0)
            ?.map { groups ->
                groups.map { group ->
                    val members = duplicateDao?.getMembersBounded(group.generation, group.groupId, 100).orEmpty()
                    DuplicateGroup(
                        groupId = group.groupId,
                        filePaths = members.map { it.filePath },
                        fileSizes = members.associate { it.filePath to it.fileSize },
                        qualityScores = members.associate { it.filePath to it.qualityScore },
                        modifiedTimes = members.associate { it.filePath to it.modifiedAtMs },
                    )
                }
            } ?: kotlinx.coroutines.flow.flowOf(emptyList())

    /** 仅调度唯一后台任务，不在调用协程检测文件。 */
    fun startDuplicateMaintenance() {
        appContext?.let(DuplicateMaintenanceWorker::enqueue)
    }

    /**
     * 从 active generation 持久成员按 keyset 分批移入回收站；绝不重新检测。
     * 组在操作前先失效，避免 UI 在多批更新中展示不一致快照。
     */
    suspend fun keepOneDeleteRest(groupId: String, keepPath: String? = null): List<String> =
        withContext(Dispatchers.IO) {
            val dao = duplicateDao ?: return@withContext emptyList()
            val run = dao.getActiveRun(MaintenanceRunEntity.TASK_DUPLICATE_EXACT)
                ?: return@withContext emptyList()
            val group = dao.getValidGroup(run.generation, groupId) ?: return@withContext emptyList()
            val keep = keepPath?.takeIf { candidate ->
                dao.getMembersBounded(run.generation, groupId, 100).any { it.filePath == candidate }
            } ?: group.recommendedKeepPath
            if (dao.invalidateGroup(run.generation, groupId) != 1) return@withContext emptyList()

            val removed = mutableListOf<String>()
            var cursor = ""
            while (true) {
                val batch = dao.getDeletionPathsAfter(run.generation, groupId, keep, cursor, 200)
                if (batch.isEmpty()) break
                mediaDao.moveToTrash(batch, System.currentTimeMillis())
                dao.deleteMembers(run.generation, groupId, batch)
                removeFromMemoryTree(batch.toSet())
                removed += batch
                cursor = batch.last()
            }
            if (removed.isNotEmpty()) refreshStats()
            removed
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
            FaceCluster(
                clusterId = summary.clusterId,
                personName = summary.personName,
                faceCount = summary.faceCount,
                representativeThumb = summary.representativeThumb,
                // 摘要只保留一个回退封面路径，人物详情通过 PagingSource 查询。
                filePaths = listOfNotNull(summary.representativeFilePath),
            )
        }
    }

    /** 人脸聚类摘要 Flow（供 UI 实时更新） */
    val faceClusterSummaries: Flow<List<FaceClusterSummary>>?
        get() = faceDao?.getClusterSummariesFlow()

    /** 获取某个聚类内的人脸记录 */
    suspend fun getFacesInCluster(clusterId: String): List<FaceEntity> = withContext(Dispatchers.IO) {
        faceDao?.getByCluster(clusterId) ?: emptyList()
    }

    /** 为聚类命名；metadata 为权威映射，同时保留 faces.personName 兼容双写。 */
    suspend fun setPersonName(clusterId: String, name: String?) = withContext(Dispatchers.IO) {
        database?.faceClusterDao()?.setPersonName(clusterId, name) ?: faceDao?.setPersonName(clusterId, name)
    }

    /** 显式调度人物原型维护；与强制重分析完全分离。 */
    fun startFaceClusterMaintenance() {
        appContext?.let(FaceClusterMaintenanceWorker::enqueue)
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
        val updated = album.copy(summaryCoverPaths = listOf(coverPath))
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

    /** 目录详情独立 Paging 数据源；查询对象变化会重建 Pager。 */
    fun pagedMediaForDirectory(query: DirectoryMediaQuery): Flow<PagingData<MediaItem>> = Pager(
        config = mediaPagingConfig,
        pagingSourceFactory = {
            mediaDao.directoryPagingSource(DirectoryMediaQueryMapper.paging(query).toRoomQuery())
        },
    ).flow.map { pagingData -> pagingData.map { it.toMediaItem() } }

    private suspend fun rebuildTreeFromDirectorySummaries(
        summaries: List<DirectorySummary>? = null,
        rebuildRecommendations: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        val directorySummaries = summaries ?: mediaDao.getDirectorySummaries()
        if (directorySummaries.isEmpty()) {
            clearAll()
            return@withContext
        }
        val settings = withTimeoutOrNull(5000L) { settingsRepository.state.first() }
        val (tree, leaves) = albumBuilder.buildFromDirectorySummaries(
            summaries = directorySummaries,
            roots = settings?.scanRoots ?: emptyList(),
            sortMode = settings?.albumSortMode ?: 0,
        )
        _albumTree.value = tree
        _leafAlbums.value = leaves
        // 目录树保持轻量摘要；精选通过独立的有界候选查询补充真实媒体属性。
        // 每次数据库重建（包括扫描完成）都同步重建推荐，避免精选页永久为空。
        if (rebuildRecommendations) rebuildRecommendationPool(leaves)
    }

    /** 完整扫描成功后构造并原子发布新快照，再更新内存树。 */
    private suspend fun publishCommittedAlbumSnapshot() {
        val summaries = mediaDao.getDirectorySummaries()
        val now = System.currentTimeMillis()
        database?.albumSnapshotDao()?.replaceSnapshot(
            entries = summaries.map { it.toSnapshotEntity(now) },
            version = now,
            updatedAtMs = now,
        )
        rebuildTreeFromDirectorySummaries(summaries, rebuildRecommendations = false)
    }

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
        // 初始加载时生成首批推荐；后续用户刷新会沿用推荐池游标继续取下一批。
        refreshRecommendations(leafs)
    }

    /**
     * 刷新推荐列表。
     * 从全量推荐池中取下一批未展示的推荐，保证相邻批次不重复。
     * 扫描期间仍会重新读取已提交候选，但不会重置现有推荐池的游标。
     */
    suspend fun refreshRecommendations() = withContext(Dispatchers.IO) {
        // 扫描期间媒体分析字段会持续更新；即使目录树尚未发布，也从已提交候选即时构建精选。
        rebuildRecommendationPool(_leafAlbums.value)
    }

    /**
     * 将轻量目录摘要与数据库中的全部媒体合并为推荐输入。
     * 数据库使用稳定 keyset 分页读取；构建出的轮换池覆盖全部未回收媒体。
     */
    private suspend fun rebuildRecommendationPool(leafs: List<Album>) {
        val allMedia = mutableListOf<MediaItem>()
        var afterPath: String? = null
        do {
            val page = mediaDao.getRecommendationPageAfter(afterPath, RECOMMENDATION_PAGE_SIZE)
            allMedia += page.map { it.toMediaItem() }
            afterPath = page.lastOrNull()?.filePath
        } while (page.size == RECOMMENDATION_PAGE_SIZE)

        val candidatesByDirectory = allMedia.groupBy { it.filePath.substringBeforeLast('/', "") }
        val albumsByPath = leafs.associateBy { it.directoryPath }
        val hydrated = candidatesByDirectory.map { (directoryPath, items) ->
            albumsByPath[directoryPath]?.copy(
                mediaItems = items,
                directMediaCount = items.size,
            ) ?: Album(
                id = java.util.UUID.nameUUIDFromBytes(directoryPath.toByteArray()).toString(),
                name = directoryPath.substringAfterLast('/').ifEmpty { directoryPath },
                directoryPath = directoryPath,
                mediaItems = items,
                directMediaCount = items.size,
            )
        }
        // 不要在每次刷新时清空推荐池和游标。旧实现会令下面的内部刷新每次都从
        // 同一个确定性排序的首批开始，导致刷新按钮看起来完全失效。
        refreshRecommendations(hydrated)
    }

    /**
     * 内部刷新逻辑：基于给定的叶子相册列表生成推荐批次。
     */
    private suspend fun refreshRecommendations(leafs: List<Album>) {
        // P3-1: 加锁保护共享的推荐池/游标，避免并发刷新导致状态错乱
        recommendationMutex.withLock {
            // 仅在首次加载或全部文件完成一轮后重建。每个文件在一轮中只属于一个推荐。
            if (diversifiedRecommendationPool.isEmpty() || recommendationCursor >= diversifiedRecommendationPool.size) {
                val allMedia = leafs.flatMap { it.mediaItems }
                val allRecs = recommendationEngine.generateAll(leafs)
                val diversified = recommendationDiversifier.selectAll(allRecs)
                diversifiedRecommendationPool = recommendationFileRotator.build(diversified, allMedia)
                recommendationCursor = 0
            }

            val nextBatch = diversifiedRecommendationPool
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

    private suspend fun removeFromMemoryTree(@Suppress("UNUSED_PARAMETER") paths: Set<String>) =
        rebuildTreeFromDirectorySummaries()

    /** 暴露 MediaDao 供兼容 Worker 使用。 */
    internal fun getMediaDao(): MediaDao = mediaDao

    private fun updateFavoriteInMemory(
        @Suppress("UNUSED_PARAMETER") path: String,
        @Suppress("UNUSED_PARAMETER") favorite: Boolean,
    ) {
        // 轻量目录树不缓存媒体收藏状态；PagingSource 由 Room 写入自动失效刷新。
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

private fun com.renyxin.localalbum.core.model.DirectorySqlSpec.toRoomQuery() =
    androidx.sqlite.db.SimpleSQLiteQuery(sql, args.toTypedArray())

private fun DirectorySummary.toSnapshotEntity(version: Long) = AlbumSnapshotDirectoryEntity(
    directoryPath = parentPath,
    mediaCount = mediaCount,
    latestCapturedAtMs = latestCapturedAtMs,
    coverThumbnailPath = coverThumbnailPath,
    coverFilePath = coverFilePath,
    snapshotVersion = version,
)

private fun AlbumSnapshotDirectoryEntity.toDirectorySummary() = DirectorySummary(
    parentPath = directoryPath,
    mediaCount = mediaCount,
    latestCapturedAtMs = latestCapturedAtMs,
    coverThumbnailPath = coverThumbnailPath,
    coverFilePath = coverFilePath,
)

private fun MediaEntity.toDirectoryAnchor() = DirectoryMediaAnchor(
    filePath = filePath,
    fileName = fileName,
    capturedAtMs = capturedAtMs,
    modifiedAtMs = modifiedAtMs,
    fileSize = fileSize,
)

/**
 * 人脸统计信息（Phase 3.3）。
 */
data class FaceStats(
    val totalFaces: Int = 0,
    val clusteredFaces: Int = 0,
    val clusterCount: Int = 0,
)