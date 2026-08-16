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
import com.renyxin.localalbum.core.search.FtsQueryBuilder
import com.renyxin.localalbum.core.search.KeywordSearchProfile
import com.renyxin.localalbum.core.search.SemanticSearcher
import com.renyxin.localalbum.data.backup.DatabaseExporter
import com.renyxin.localalbum.data.backup.DatabaseImporter
import com.renyxin.localalbum.data.backup.PostRestoreTaskPolicy
import com.renyxin.localalbum.data.backup.PostRestoreTaskSeeder
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.db.entity.ScanLifecycle
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.FaceClusterSummary
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.DirectorySummary
import com.renyxin.localalbum.data.db.entity.AlbumSnapshotDirectoryEntity
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.worker.DuplicateMaintenanceWorker
import com.renyxin.localalbum.data.source.MediaSource
import com.renyxin.localalbum.edition.EditionConfiguration
import com.renyxin.localalbum.data.worker.AnalysisResumePrefs
import com.renyxin.localalbum.data.worker.ScanServiceController
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
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
import kotlinx.coroutines.flow.flowOf
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

private enum class ScanRequest {
    INCREMENTAL,
    RECONCILIATION,
}

private enum class ScanExecutionResult {
    COMPLETED,
    SKIPPED,
    FAILED,
}

/**
 * Result of a durable changed-set recovery attempt.
 *
 * [DEFERRED] is deliberately different from [FAILED]: a live lease, retry backoff, or
 * temporarily unavailable scan root is still valid durable work and must remain in Room for a
 * later WorkManager attempt.
 */
internal enum class PersistedScanDrainResult {
    COMPLETED,
    DEFERRED,
    FAILED,
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
    private val keywordSearchProfile: KeywordSearchProfile = KeywordSearchProfile.FULL,
    private val semanticSearchEnabled: Boolean = true,
    private val semanticProviderFactory: (() -> com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider?)? = null,
    private val postRestoreTaskPolicy: PostRestoreTaskPolicy? = null,
    private val allowFaceClusterMaintenance: Boolean = true,
    context: android.content.Context? = null,
) {
    companion object {
        private const val TAG = "AlbumRepository"
        private const val DATABASE_DELETE_BATCH_SIZE = 500
        private const val TRASH_PURGE_BATCH_SIZE = 200

        /** 全量推荐池只保留给显式用户刷新；自动增强使用受影响目录窗口。 */
        private const val RECOMMENDATION_PAGE_SIZE = 1_000
        private const val RECOMMENDATION_DIRECTORY_LIMIT = 1_000
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
        if (!semanticSearchEnabled) return null
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

    /** Observer-only entry: consumes the durable changed-set and never traverses scan roots. */
    suspend fun incrementalRescanIfIdle(): Boolean = runAutomaticScanIfIdle()

    /** Foreground compensation is explicitly a reconciliation because observer coverage was absent. */
    suspend fun reconcileIfIdle(): Boolean = withContext(Dispatchers.IO) {
        hybridIndexer?.requestReconciliation()
        runAutomaticScanIfIdle()
    }

    /** Compatibility entry remains a reconciliation, never a disguised incremental scan. */
    suspend fun rescanIfIdle(): Boolean = reconcileIfIdle()

    private suspend fun runAutomaticScanIfIdle(): Boolean =
        withContext(Dispatchers.IO) {
            if (!scanMutex.tryLock()) {
                // Waiting closes the race between the active scan's final journal check and unlock.
                Log.d(TAG, "自动扫描已持久化，等待当前扫描释放锁后复核队列")
                scanMutex.lock()
            }
            try {
                val indexer = hybridIndexer ?: return@withContext false
                val effectiveRequest = when {
                    indexer.hasPendingReconciliation() -> ScanRequest.RECONCILIATION
                    indexer.hasClaimableMediaChanges() -> ScanRequest.INCREMENTAL
                    else -> {
                        Log.d(TAG, "持锁扫描已消费自动请求，跳过空扫描")
                        return@withContext true
                    }
                }
                rescanAndDrainLocked(effectiveRequest)
            } finally {
                scanMutex.unlock()
            }
        }

    /**
     * WorkManager recovery entry: drains only requests already persisted in the journal.
     *
     * A non-claimable journal is not a failed scan. Keeping that distinction prevents
     * WorkManager's finite business-failure retry budget from abandoning a valid lease or a
     * retry-delayed event.
     */
    internal suspend fun drainPersistedScanRequests(): PersistedScanDrainResult =
        withContext(Dispatchers.IO) {
            scanMutex.withLock {
                val indexer = hybridIndexer
                    ?: return@withLock PersistedScanDrainResult.FAILED
                val request = when {
                    indexer.hasPendingReconciliation() -> ScanRequest.RECONCILIATION
                    indexer.hasClaimableMediaChanges() -> ScanRequest.INCREMENTAL
                    // A live lease or retry delay remains durable; keep the Worker in backoff.
                    indexer.hasOutstandingMediaChanges() ->
                        return@withLock PersistedScanDrainResult.DEFERRED
                    else -> return@withLock PersistedScanDrainResult.COMPLETED
                }
                if (!rescanAndDrainLocked(request)) {
                    return@withLock PersistedScanDrainResult.FAILED
                }
                if (
                    indexer.hasPendingReconciliation() ||
                    indexer.hasOutstandingMediaChanges()
                ) {
                    PersistedScanDrainResult.DEFERRED
                } else {
                    PersistedScanDrainResult.COMPLETED
                }
            }
        }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /** Durable scan lifecycle; UI reconstruction must use this instead of replaying an in-memory Done. */
    val scanLifecycle: StateFlow<ScanLifecycle> =
        (database?.scanRunDao()?.observeLatest()?.map { ScanLifecycle.from(it) }
            ?: flowOf(ScanLifecycle()))
            .stateIn(scope, SharingStarted.Eagerly, ScanLifecycle())

    val indexAvailability: StateFlow<IndexAvailability> = scanLifecycle
        .map { it.indexAvailability }
        .stateIn(scope, SharingStarted.Eagerly, IndexAvailability.EMPTY)

    val coreScanState: StateFlow<CoreScanState> = scanLifecycle
        .map { it.coreScanState }
        .stateIn(scope, SharingStarted.Eagerly, CoreScanState.IDLE)

    val enhancementState: StateFlow<EnhancementState> = scanLifecycle
        .map { it.enhancementState }
        .stateIn(scope, SharingStarted.Eagerly, EnhancementState.NOT_SCHEDULED)

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
    suspend fun rescan(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "rescan: 手动 reconciliation 等待 scanMutex…")
        scanMutex.withLock { rescanAndDrainLocked(ScanRequest.RECONCILIATION) }
    }

    /** Drains notifications that arrived while a full/reconciliation/incremental run held the lock. */
    private suspend fun rescanAndDrainLocked(initialRequest: ScanRequest): Boolean {
        var request = initialRequest
        while (true) {
            when (rescanLocked(request)) {
                ScanExecutionResult.FAILED -> return false
                ScanExecutionResult.SKIPPED -> return true
                ScanExecutionResult.COMPLETED -> Unit
            }
            val indexer = hybridIndexer ?: return true
            request = when {
                indexer.hasPendingReconciliation() -> ScanRequest.RECONCILIATION
                indexer.hasClaimableMediaChanges() -> ScanRequest.INCREMENTAL
                else -> return true
            }
        }
    }

    /** 调用方必须持有 [scanMutex]。 */
    private suspend fun rescanLocked(request: ScanRequest): ScanExecutionResult {
        restoreFromDbIfNeeded()
        val hadCachedAlbums = _albumTree.value.isNotEmpty()
        _albumSyncState.value = AlbumSyncState.Checking(hadCachedAlbums)
        _scanState.value = ScanState.Scanning("准备扫描…")
        Log.i(TAG, "rescan: 获得 scanMutex，设置 Scanning 状态")

        var serviceAcquired = false
        appContext?.let(com.renyxin.localalbum.data.worker.RecommendationRefreshWorker::cancel)
        return try {
            // settingsRepository.state 可能因 combine 源未发射而等待；超时后使用默认设置。
            val settings = withTimeoutOrNull(5000L) {
                settingsRepository.state.first()
            }
            if (settings == null) {
                Log.e(TAG, "rescan: settingsRepository.state.first() 超时(5s)，使用默认设置")
            } else {
                Log.i(TAG, "rescan: 获取设置成功，scanRoots=${settings.scanRoots}")
            }

            val roots = settings?.scanRoots ?: emptyList()
            val allowNomedia = settings?.showNomediaDirectories ?: false
            val ignorePatterns = settings?.ignoreDirNames ?: emptyList()
            if (roots.isEmpty()) {
                Log.w(TAG, "rescan: scanRoots 为空，确认空对账域并保留已提交快照")
                hybridIndexer?.acknowledgeEmptyReconciliationDomain()
                database?.scanRunDao()?.releaseWaitingForCoreEnhancements()
                _scanState.value = ScanState.Idle
                _albumSyncState.value = if (_albumTree.value.isEmpty()) {
                    AlbumSyncState.FirstBuild
                } else {
                    AlbumSyncState.UpToDate(
                        database?.albumSnapshotDao()?.getMeta()?.updatedAtMs ?: 0L,
                    )
                }
                return ScanExecutionResult.SKIPPED
            }

            appContext?.let { ScanServiceController.acquire(it, "正在扫描媒体…") }
            serviceAcquired = true

            val indexer = hybridIndexer
                ?: error("HybridIndexer 未注入，已拒绝执行高风险全量扫描兜底")
            val isFirstScan = mediaDao.getCount() == 0
            val requiresReconciliation = request == ScanRequest.RECONCILIATION ||
                indexer.hasPendingReconciliation()
            _albumSyncState.value = AlbumSyncState.Updating(hadCachedAlbums)
            _scanState.value = ScanState.Scanning(
                when {
                    isFirstScan -> "首次全量扫描…"
                    requiresReconciliation -> "正在校准媒体库…"
                    else -> "正在应用媒体变更…"
                },
            )

            var publishedCoreResult: com.renyxin.localalbum.core.index.ScanCommitResult? = null
            suspend fun publishCore(result: com.renyxin.localalbum.core.index.ScanCommitResult) {
                // CoreScanComplete can only follow the matching full or patched snapshot publication.
                if (result.scanType == ScanRunEntity.TYPE_INCREMENTAL) {
                    publishIncrementalAlbumSnapshot(result.affectedDirectoryPaths)
                } else {
                    publishCommittedAlbumSnapshot()
                    indexer.acknowledgePublishedReconciliation(result.startedAtMs)
                }
                indexer.publishCoreComplete(result)
                if (result.scanType != ScanRunEntity.TYPE_INCREMENTAL) {
                    // Restore-created outbox remains WAITING_FOR_CORE until a real device
                    // reconciliation/full snapshot has been successfully published.
                    database?.scanRunDao()?.releaseWaitingForCoreEnhancements()
                }
                publishedCoreResult = result
            }

            // The process-local gate is acquired before the indexer creates its durable core run.
            // Automatic enhancement can therefore finish its already-running bounded section, but
            // no new model/CPU section can begin after this scan has requested the core lane.
            EnhancementResourceGate.withCoreScan(
                onRequested = {
                    // Persist the reason before cancelling consumers. PREEMPTED is automatically
                    // recoverable; a concurrent user PAUSED state is never overwritten.
                    database?.scanRunDao()?.preemptAllActiveEnhancements()
                    // Cancel long-running automatic consumers before waiting for the gate. Their
                    // durable cancellation handlers release leases; interactive thumbnails use a
                    // separate bounded lane and remain responsive.
                    appContext?.let {
                        com.renyxin.localalbum.data.worker.AnalysisWorker.cancelForCorePreemption(it)
                        com.renyxin.localalbum.data.worker.ThumbnailWorker.cancelBackground(it)
                        com.renyxin.localalbum.data.worker.EnhancementHandoffWorker.cancel(it)
                    }
                },
            ) {
                if (isFirstScan) {
                    indexer.fullScan(
                        roots = roots,
                        allowNomedia = allowNomedia,
                        ignorePatterns = ignorePatterns,
                        progress = { processed, total ->
                            _scanState.value = ScanState.Scanning("首次全量扫描…", processed, total)
                            _albumSyncState.value = AlbumSyncState.Updating(
                                hadCachedAlbums,
                                processed,
                                total,
                            )
                        },
                        onCoreReady = ::publishCore,
                    )
                } else if (requiresReconciliation) {
                    indexer.reconciliationScan(
                        roots = roots,
                        allowNomedia = allowNomedia,
                        ignorePatterns = ignorePatterns,
                        onCoreReady = ::publishCore,
                    )
                } else {
                    indexer.incrementalScan(
                        roots = roots,
                        allowNomedia = allowNomedia,
                        ignorePatterns = ignorePatterns,
                        onCoreReady = ::publishCore,
                    )
                }
            }

            _scanState.value = ScanState.Done
            _albumSyncState.value = AlbumSyncState.UpToDate(
                database?.albumSnapshotDao()?.getMeta()?.updatedAtMs ?: System.currentTimeMillis(),
            )
            val changedCount = publishedCoreResult?.let { it.inserted + it.updated + it.deleted }
            if (changedCount == null || changedCount > 0) {
                scope.launch { refreshStats() }
                appContext?.let { context ->
                    com.renyxin.localalbum.data.worker.RecommendationRefreshWorker.enqueue(
                        context = context,
                        affectedDirectories = publishedCoreResult?.affectedDirectoryPaths.orEmpty(),
                    )
                }
            }
            ScanExecutionResult.COMPLETED
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 不把取消伪装成完成；HybridIndexer 已将当前 ScanRun 标记为 CANCELLED。
            Log.i(TAG, "扫描已取消，继续保留上次完整相册快照")
            _scanState.value = ScanState.Idle
            _albumSyncState.value = AlbumSyncState.Paused(_albumTree.value.isNotEmpty())
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "扫描失败", error)
            val message = error.message ?: "扫描失败"
            _scanState.value = ScanState.Failed(message)
            _albumSyncState.value = AlbumSyncState.Failed(message, _albumTree.value.isNotEmpty())
            ScanExecutionResult.FAILED
        } finally {
            // Core preemption is transient even when the scan fails or is cancelled. User PAUSED
            // runs are excluded by the DAO transition and remain paused across restarts.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                resumeTransientlyPreemptedEnhancements()
            }
            if (serviceAcquired) {
                appContext?.let { ScanServiceController.release(it) }
            }
        }
    }

    private suspend fun resumeTransientlyPreemptedEnhancements() {
        // A following core/maintenance owner will perform its own release. Interactive AI is short
        // lived and must not suppress the only durable queue wake-up after core publication.
        if (EnhancementResourceGate.isCoreRequested || EnhancementResourceGate.isMaintenanceRequested) return
        database?.scanRunDao()?.resumePreemptedEnhancements()
        appContext?.let { context ->
            if (database?.enhancementOutboxDao()?.hasRunnableEntries() == true) {
                com.renyxin.localalbum.data.worker.EnhancementHandoffWorker.enqueue(context)
            }
            val taskDao = database?.analysisTaskDao()
            val hasScanOwnedAnalysis = taskDao?.countActiveScanOwnedEnhancementTasks()?.let { it > 0 } == true
            val activeUserTasks = taskDao?.countActiveUserTasks() ?: 0
            val hasResumableUserAnalysis = com.renyxin.localalbum.data.worker.AnalysisWorker
                .userTaskAdmission(
                    userPaused = AnalysisResumePrefs.isUserPaused(context),
                    resumePending = AnalysisResumePrefs.isPending(context),
                    activeUserTasks = activeUserTasks,
                )
            if (hasResumableUserAnalysis) AnalysisResumePrefs.setPending(context, true)
            if (hasScanOwnedAnalysis || hasResumableUserAnalysis) {
                com.renyxin.localalbum.data.worker.AnalysisWorker.enqueue(context)
            }
            if (database?.thumbnailTaskDao()?.countAutomaticRunnable()?.let { it > 0 } == true) {
                com.renyxin.localalbum.data.worker.ThumbnailWorker.enqueueBackground(context)
            }
        }
    }

    /**
     * Serializes backup table snapshots/replacement with core scans and every admitted enhancement
     * resource section. Lock order is always scanMutex -> maintenance gate, matching core scans.
     */
    private suspend fun <T> withExclusiveBackupMaintenance(block: suspend () -> T): T =
        scanMutex.withLock {
            try {
                EnhancementResourceGate.withExclusiveMaintenance(
                    onRequested = {
                        // Persist a recoverable reason before cancelling durable consumers. User
                        // PAUSED and restore WAITING states are deliberately excluded by the DAO.
                        database?.scanRunDao()?.preemptAllActiveEnhancements()
                        appContext?.let { context ->
                            com.renyxin.localalbum.data.worker.AnalysisWorker
                                .cancelForCorePreemption(context)
                            com.renyxin.localalbum.data.worker.ThumbnailWorker
                                .cancelBackground(context)
                            com.renyxin.localalbum.data.worker.EnhancementHandoffWorker
                                .cancel(context)
                        }
                    },
                    block = block,
                )
            } finally {
                // withExclusiveMaintenance has released both lanes before returning/throwing.
                // Only PREEMPTED runs are re-released; a durable user pause remains authoritative.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    resumeTransientlyPreemptedEnhancements()
                }
            }
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
        val indexer = hybridIndexer ?: run {
            AnalysisResumePrefs.setPending(ctx, false)
            return@withContext
        }
        val scanDao = database?.scanRunDao()
        val taskDao = database?.analysisTaskDao()
        val hasDurableEnhancement = scanDao?.getEnhancementScanIds()?.isNotEmpty() == true
        val hasScanOwnedTasks = taskDao?.countActiveScanOwnedEnhancementTasks()?.let { it > 0 } == true
        val activeUserTasks = taskDao?.countActiveUserTasks() ?: 0
        val hasResumableUserTasks = com.renyxin.localalbum.data.worker.AnalysisWorker
            .userTaskAdmission(
                userPaused = AnalysisResumePrefs.isUserPaused(ctx),
                resumePending = AnalysisResumePrefs.isPending(ctx),
                activeUserTasks = activeUserTasks,
            )
        if (hasResumableUserTasks) AnalysisResumePrefs.setPending(ctx, true)
        if (!hasDurableEnhancement && !hasScanOwnedTasks && !hasResumableUserTasks) {
            if (activeUserTasks == 0) AnalysisResumePrefs.setPending(ctx, false)
            return@withContext
        }
        scanMutex.withLock {
            Log.i(TAG, "resumeAnalysisIfNeeded: 恢复持久化分析任务领取")
            indexer.resumePendingAnalysis()
            // User work keeps its marker until the final bounded Stage window has converged.
            if (activeUserTasks == 0) AnalysisResumePrefs.setPending(ctx, false)
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

    /** Persists a user pause before WorkManager cancellation can terminate individual consumers. */
    suspend fun pauseEnhancementsByUser() = withContext(Dispatchers.IO) {
        database?.scanRunDao()?.pauseAllActiveEnhancements()
        val context = appContext ?: return@withContext
        AnalysisResumePrefs.setUserPaused(context, true)
        AnalysisResumePrefs.setPending(context, false)
    }

    // ---- 删除/回收站 ----
    suspend fun deleteMediaItems(paths: List<String>) = withContext(Dispatchers.IO) {
        if (paths.isEmpty()) return@withContext
        mediaDao.moveToTrash(paths, System.currentTimeMillis())
        removeFromMemoryTree(paths.toSet())
        refreshStats()
    }

    data class TrashOperationResult(
        val requested: Int,
        val completed: Int,
    ) {
        val failed: Int get() = (requested - completed).coerceAtLeast(0)
    }

    /**
     * 彻底删除物理文件，并且只清理删除成功或原本已不存在路径的数据库记录。
     *
     * 删除失败项保留原数据库/回收站状态，避免无权限删除时记录先消失、随后又被扫描复活。
     */
    suspend fun permanentlyDelete(paths: List<String>): TrashOperationResult = withContext(Dispatchers.IO) {
        val distinct = paths.distinct()
        if (distinct.isEmpty()) return@withContext TrashOperationResult(0, 0)
        val service = requireNotNull(deletionService) { "PersistentDeletionService 未注入" }
        val physicallyDeleted = service.delete(
            distinct,
            com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity.OP_USER_PERMANENT,
        )
        if (physicallyDeleted.isNotEmpty()) {
            removeFromMemoryTree(physicallyDeleted.toSet())
            refreshStats()
        }
        appContext?.let { com.renyxin.localalbum.data.worker.DeletionRetryWorker.enqueue(it) }
        TrashOperationResult(distinct.size, physicallyDeleted.size)
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

    /** 为 Android 11+ 系统删除授权按稳定路径分页收集回收站路径。 */
    suspend fun getAllTrashedPaths(): List<String> = withContext(Dispatchers.IO) {
        val result = mutableListOf<String>()
        var afterPath = ""
        while (true) {
            val page = mediaDao.getTrashedPathsAfter(afterPath, TRASH_PURGE_BATCH_SIZE)
            if (page.isEmpty()) break
            result += page
            afterPath = page.last()
            if (page.size < TRASH_PURGE_BATCH_SIZE) break
        }
        result
    }

    /**
     * 清空回收站：按路径 keyset 有界读取，只清理物理删除成功或原文件已不存在的路径。
     *
     * 游标始终推进到本次读取批次的末尾，因此删除失败项不会反复占据首批形成死循环；
     * 它们保留在回收站中，用户下次操作时可重试。
     */
    suspend fun clearTrash(): TrashOperationResult = withContext(Dispatchers.IO) {
        var afterPath = ""
        var requested = 0
        var completed = 0
        while (true) {
            val paths = mediaDao.getTrashedPathsAfter(afterPath, TRASH_PURGE_BATCH_SIZE)
            if (paths.isEmpty()) break
            afterPath = paths.last()
            requested += paths.size
            val physicallyDeleted = requireNotNull(deletionService) { "PersistentDeletionService 未注入" }.delete(
                paths,
                com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity.OP_CLEAR_TRASH,
            )
            completed += physicallyDeleted.size
            if (paths.size < TRASH_PURGE_BATCH_SIZE) break
        }
        if (completed > 0) {
            rebuildTreeFromDirectorySummaries()
            refreshStats()
        }
        appContext?.let { com.renyxin.localalbum.data.worker.DeletionRetryWorker.enqueue(it) }
        TrashOperationResult(requested, completed)
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

    /** 过期回收站批次结果；[lastPath] 用于 Worker 继续扫描，避免失败项阻塞后续记录。 */
    data class ExpiredTrashBatchResult(val scanned: Int, val deleted: Int, val lastPath: String?)

    /**
     * 供后台回收站清理复用的一致删除入口。
     * 仅在物理文件已删除或原文件已不存在时清理数据库，删除失败路径保留供后续重试。
     */
    suspend fun purgeExpiredTrashBatch(
        before: Long,
        afterPath: String = "",
        limit: Int = 200,
    ): ExpiredTrashBatchResult = withContext(Dispatchers.IO) {
        val paths = mediaDao.getExpiredTrashPathsAfter(before, afterPath, limit)
        if (paths.isEmpty()) return@withContext ExpiredTrashBatchResult(0, 0, null)
        val physicallyDeleted = requireNotNull(deletionService) { "PersistentDeletionService 未注入" }.delete(
            paths,
            com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity.OP_EXPIRED_TRASH,
        )
        removeFromMemoryTree(physicallyDeleted.toSet())
        ExpiredTrashBatchResult(paths.size, physicallyDeleted.size, paths.last())
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

    /** 用户词只在 edition 允许的 FTS 列中匹配；Lite 即使导入 OCR 数据也不会命中它。 */
    private fun toFtsQuery(input: String): String = FtsQueryBuilder.build(input, keywordSearchProfile)

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
        if (!semanticSearchEnabled) {
            clearSemanticSearch()
            return@withContext
        }
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

    /** 数据库导入器（Phase 4.2）；可再生任务策略由 edition composition root 提供。 */
    private val databaseImporter: DatabaseImporter? by lazy {
        DatabaseImporter(
            mediaDao = mediaDao,
            faceDao = faceDao,
            embeddingDao = embeddingDao,
            database = database,
            postRestoreTaskSeeder = postRestoreTaskPolicy?.let(::PostRestoreTaskSeeder),
        )
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
        // 同时持有扫描锁与维护 gate，确保核心事务、自动增强和可视缩略图都已排空。
        withExclusiveBackupMaintenance {
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
            // 覆盖切表与快照重建必须同时排斥核心扫描、自动增强和可视缩略图。
            // loadFromDb/refreshStats 也留在同一临界区，禁止发布半恢复的内存快照。
            val result = withExclusiveBackupMaintenance {
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
                // 否则刚导入的数据会被立即清掉。恢复 outbox 必须在这次核心对账完成后
                // 才释放，避免恢复任务与 reconciliation 争用模型/CPU 资源。
                val roots = withTimeoutOrNull(5000L) {
                    settingsRepository.state.first()
                }?.scanRoots ?: emptyList()
                val reconciliationSucceeded = if (roots.isNotEmpty()) {
                    Log.i(TAG, "importDatabase: 导入成功，触发对账增量扫描…")
                    rescan()
                } else {
                    Log.w(TAG, "importDatabase: 未配置扫描根目录，确认空对账域")
                    scanMutex.withLock {
                        hybridIndexer?.acknowledgeEmptyReconciliationDomain()
                        database?.scanRunDao()?.releaseWaitingForCoreEnhancements()
                    }
                    true
                }
                if (reconciliationSucceeded) {
                    if (database?.enhancementOutboxDao()?.hasRunnableEntries() == true) {
                        appContext?.let(com.renyxin.localalbum.data.worker.EnhancementHandoffWorker::enqueue)
                    }
                } else {
                    Log.w(TAG, "importDatabase: 对账失败，恢复 outbox 保留待下次启动恢复")
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

    private fun FaceClusterSummary.toFaceCluster() = FaceCluster(
        clusterId = clusterId,
        personName = personName,
        faceCount = faceCount,
        representativeThumb = representativeThumb,
        // 摘要只保留一个回退封面路径，人物详情通过 PagingSource 查询。
        filePaths = listOfNotNull(representativeFilePath),
    )

    /**
     * Room 是人物页的权威来源；每个人脸阶段批次提交后自动发射，不依赖 UI 轮询。
     */
    val faceClusters: Flow<List<FaceCluster>> = faceDao?.getClusterSummariesFlow()
        ?.map { summaries -> summaries.map { it.toFaceCluster() } }
        ?: flowOf(emptyList())

    /** 人物统计与摘要观察同一张 faces 表，保证卡片和计数同步失效。 */
    val faceStats: Flow<FaceStats> = faceDao?.getStatsFlow()
        ?.map { stats ->
            FaceStats(
                totalFaces = stats.totalFaces,
                clusteredFaces = stats.clusteredFaces,
                clusterCount = stats.clusterCount,
            )
        }
        ?: flowOf(FaceStats())

    /** 兼容非响应式调用方；新 UI 应直接观察 [faceClusters]。 */
    suspend fun getFaceClusters(): List<FaceCluster> = withContext(Dispatchers.IO) {
        faceDao?.getClusterSummaries()?.map { it.toFaceCluster() }.orEmpty()
    }

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
        if (!allowFaceClusterMaintenance) return
        appContext?.let(EditionConfiguration::enqueueFaceClusterMaintenance)
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

    private suspend fun publishIncrementalAlbumSnapshot(affectedDirectoryPaths: Set<String>) {
        val snapshotDao = database?.albumSnapshotDao()
            ?: return publishCommittedAlbumSnapshot()
        val affected = affectedDirectoryPaths.filter(String::isNotBlank).distinct()
        val now = System.currentTimeMillis()
        val summaries = affected.chunked(400).flatMap { chunk ->
            mediaDao.getDirectorySummariesForPaths(chunk)
        }
        snapshotDao.patchSnapshot(
            affectedDirectoryPaths = affected,
            entries = summaries.map { it.toSnapshotEntity(now) },
            version = now,
            updatedAtMs = now,
        )
        if (affected.isNotEmpty()) {
            rebuildTreeFromDirectorySummaries(
                snapshotDao.getDirectories().map { it.toDirectorySummary() },
                rebuildRecommendations = false,
            )
        }
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

    fun isCoreScanActive(): Boolean = when (coreScanState.value) {
        CoreScanState.DISCOVERING,
        CoreScanState.APPLYING_CHANGES,
        CoreScanState.RECONCILING_DELETES,
        CoreScanState.PUBLISHING,
        -> true
        else -> false
    }

    /** User-triggered refresh may explicitly rebuild the full recommendation pool. */
    suspend fun refreshRecommendations() = withContext(Dispatchers.IO) {
        if (isCoreScanActive()) return@withContext
        rebuildRecommendationPool(_leafAlbums.value)
    }

    /** Automatic post-core refresh is bounded to changed directories; an empty set is a no-op. */
    suspend fun refreshRecommendationsForDirectories(directoryPaths: Set<String>) = withContext(Dispatchers.IO) {
        if (isCoreScanActive() || directoryPaths.isEmpty()) return@withContext
        val bounded = directoryPaths.asSequence()
            .filter(String::isNotBlank)
            .distinct()
            .take(400)
            .toList()
        if (bounded.isEmpty()) return@withContext
        val media = bounded.chunked(100).flatMap { directories ->
            mediaDao.getRecommendationCandidatesForDirectories(
                parentPaths = directories,
                limit = RECOMMENDATION_DIRECTORY_LIMIT,
            )
        }.distinctBy { it.filePath }
        if (media.isEmpty()) return@withContext
        val itemsByDirectory = media.map { entity -> entity.toMediaItem() }
            .groupBy { it.filePath.substringBeforeLast('/', "") }
        val leafByPath = _leafAlbums.value.associateBy { it.directoryPath }
        val affectedLeafs = itemsByDirectory.map { (directoryPath, items) ->
            leafByPath[directoryPath]?.copy(mediaItems = items, directMediaCount = items.size)
                ?: Album(
                    id = java.util.UUID.nameUUIDFromBytes(directoryPath.toByteArray()).toString(),
                    name = directoryPath.substringAfterLast('/').ifEmpty { directoryPath },
                    directoryPath = directoryPath,
                    mediaItems = items,
                    directMediaCount = items.size,
                )
        }
        // Rebuild only the bounded changed window; completed optional scene/quality values are read
        // opportunistically from the same rows and never trigger analysis.
        recommendationMutex.withLock {
            val allMedia = affectedLeafs.flatMap { it.mediaItems }
            val allRecs = recommendationEngine.generateAll(affectedLeafs)
            val diversified = recommendationDiversifier.selectAll(allRecs)
            diversifiedRecommendationPool = recommendationFileRotator.build(diversified, allMedia)
            recommendationCursor = 0
            _recommendations.value = diversifiedRecommendationPool.take(recommendationBatchSize)
            recommendationCursor = _recommendations.value.size
        }
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