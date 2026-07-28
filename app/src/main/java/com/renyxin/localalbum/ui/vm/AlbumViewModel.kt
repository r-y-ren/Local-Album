package com.renyxin.localalbum.ui.vm

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.pipeline.AnalysisStage
import com.renyxin.localalbum.core.pipeline.ProgressManager
import com.renyxin.localalbum.core.pipeline.StageFileProgress
import com.renyxin.localalbum.core.plugin.TaskProgress
import com.renyxin.localalbum.core.recommendation.Recommendation
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.repo.AlbumRepository
import com.renyxin.localalbum.data.repo.AlbumStats
import com.renyxin.localalbum.data.repo.FaceCluster
import com.renyxin.localalbum.data.repo.FaceStats
import com.renyxin.localalbum.data.repo.DuplicateGroup
import com.renyxin.localalbum.data.repo.DuplicateLevel
import com.renyxin.localalbum.data.repo.GeoCluster
import com.renyxin.localalbum.data.repo.GeoStats
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.data.repo.SemanticSearchResult
import com.renyxin.localalbum.data.repo.SemanticSearchState
import com.renyxin.localalbum.data.repo.SemanticStats
import com.renyxin.localalbum.data.repo.SimilarGroup
import com.renyxin.localalbum.data.worker.ThumbnailWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(kotlinx.coroutines.FlowPreview::class)
class AlbumViewModel(
    private val repository: AlbumRepository,
    private val application: Application,
    private val progressManager: ProgressManager? = null,
) : ViewModel() {

    val albumTree: StateFlow<List<Album>> = repository.albumTree
    val leafAlbums: StateFlow<List<Album>> = repository.leafAlbums
    val recommendations: StateFlow<List<Recommendation>> = repository.recommendations
    val scanState: StateFlow<ScanState> = repository.scanState
    val searchResults: StateFlow<List<MediaItem>> = repository.searchResults
    val allMedia: StateFlow<List<MediaEntity>> = repository.allMedia
    val favorites = repository.favorites
    val trashedItems: StateFlow<List<MediaItem>> = repository.trashedItems
    val stats: StateFlow<AlbumStats> = repository.stats

    /** Paging 3 分页媒体流，供时间线使用 */
    val pagedMedia: Flow<PagingData<MediaItem>> = repository.pagedMedia.cachedIn(viewModelScope)

    // ---- 任务进度追踪 (Phase 5.4) ----

    /** 全局任务进度状态，供 UI 观察所有活跃任务 */
    private val _taskProgress = MutableStateFlow<Map<String, TaskProgress>>(emptyMap())
    val taskProgress: StateFlow<Map<String, TaskProgress>> = _taskProgress.asStateFlow()

    /** 活跃的扫描任务 Job，用于取消操作 */
    private var scanJob: Job? = null

    // ---- Per-File 进度 (Phase 6.1) ----

    /**
     * 管道是否正在运行（用于判断是否显示 per-file 进度）。
     * 响应式 StateFlow：管道完成/出错后自动变为 false，触发 UI 重组以隐藏进度网格。
     */
    val isPipelineRunning: StateFlow<Boolean> = progressManager?.progress
        ?.map { !it.isCompleted && !it.hasError }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
        ?: MutableStateFlow(false).asStateFlow()

    /** 人脸检测阶段的 per-file 进度（修复：使用与 FaceStage 一致的 stageId 常量） */
    val faceFileProgress: StateFlow<StageFileProgress> = progressManager?.progress
        ?.debounce(80L)
        ?.map { it.perStageFiles[AnalysisStage.STAGE_FACE] ?: StageFileProgress.EMPTY }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StageFileProgress.EMPTY)
        ?: MutableStateFlow(StageFileProgress.EMPTY).asStateFlow()

    /** 地理聚类阶段的 per-file 进度 */
    val geoFileProgress: StateFlow<StageFileProgress> = progressManager?.progress
        ?.debounce(80L)
        ?.map { it.perStageFiles[AnalysisStage.STAGE_GEO] ?: StageFileProgress.EMPTY }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StageFileProgress.EMPTY)
        ?: MutableStateFlow(StageFileProgress.EMPTY).asStateFlow()

    /** 场景识别阶段的 per-file 进度（修复：使用与 SceneStage 一致的 stageId 常量） */
    val sceneFileProgress: StateFlow<StageFileProgress> = progressManager?.progress
        ?.debounce(80L)
        ?.map { it.perStageFiles[AnalysisStage.STAGE_SCENE] ?: StageFileProgress.EMPTY }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StageFileProgress.EMPTY)
        ?: MutableStateFlow(StageFileProgress.EMPTY).asStateFlow()

    /** 相似度分析阶段的 per-file 进度 */
    val similarityFileProgress: StateFlow<StageFileProgress> = progressManager?.progress
        ?.debounce(80L)
        ?.map { it.perStageFiles[AnalysisStage.STAGE_SIMILARITY] ?: StageFileProgress.EMPTY }
        ?.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StageFileProgress.EMPTY)
        ?: MutableStateFlow(StageFileProgress.EMPTY).asStateFlow()

    /**
     * 更新全局任务进度状态。
     * 由管道层通过此方法推送进度更新。
     */
    fun updateTaskProgress(progress: Map<String, TaskProgress>) {
        _taskProgress.value = progress
    }

    /**
     * 取消当前正在执行的扫描任务。
     *
     * 取消后触发 Job 取消 → 协程内 `isActive` 检测 → 管道提前终止。
     * 适用于用户手动中断长时间的 AI 分析管道。
     */
    fun cancelTask() {
        scanJob?.cancel()
        scanJob = null
        _taskProgress.value = emptyMap()
    }

    /**
     * 取消指定的任务（按 taskId）。
     *
     * @param taskId 要取消的任务 ID（当前管道模型下，取消任一任务等同于取消整个扫描 Job）
     */
    fun cancelTaskById(@Suppress("UNUSED_PARAMETER") taskId: String) {
        // 当只有一个扫描 Job 时，取消 Job 等同于取消所有子任务
        cancelTask()
    }

    fun rescan() {
        android.util.Log.i("AlbumViewModel", "rescan: 用户触发扫描")
        // 修复：将 Job 赋值给 scanJob，使 cancelTask() 能真正中断扫描；
        // 启动前取消上一次扫描，避免并发扫描叠加导致状态/数据竞态。
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            repository.rescan()
            // 扫描完成后调度缩略图异步补齐
            ThumbnailWorker.enqueue(application)
        }
    }

    /**
     * 强制对全部已有媒体重新执行 AI 分析管道。
     * 用于从旧版本升级后已有数据但无分析结果的场景。
     */
    fun forceReanalyzeAll() {
        // 修复：同样赋值给 scanJob，支持取消并避免与 rescan 并发
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            repository.forceReanalyzeAll()
            // 分析完成后刷新人脸/地图/语义数据
            loadFaceClusters()
            loadGeoClusters()
            loadSemanticStats()
        }
    }

    fun restoreFromDbIfNeeded() {
        viewModelScope.launch { repository.restoreFromDbIfNeeded() }
    }

    fun findAlbumById(id: String): Album? = repository.findAlbumById(id)

    fun deleteMediaItems(paths: List<String>) {
        viewModelScope.launch { repository.deleteMediaItems(paths) }
    }

    fun permanentlyDelete(paths: List<String>) {
        viewModelScope.launch { repository.permanentlyDelete(paths) }
    }

    fun restoreFromTrash(paths: List<String>) {
        viewModelScope.launch { repository.restoreFromTrash(paths) }
    }

    fun clearTrash() {
        viewModelScope.launch { repository.clearTrash() }
    }

    fun search(query: String) {
        viewModelScope.launch { repository.search(query) }
    }

    fun clearSearch() {
        repository.clearSearch()
    }

    // ---- 语义搜索 (Phase 4.1) ----

    /** 语义搜索结果（含相似度评分） */
    val semanticSearchResults: StateFlow<List<SemanticSearchResult>> = repository.semanticSearchResults

    /** 语义搜索状态（区分无匹配与模型/索引问题） */
    val semanticSearchState: StateFlow<SemanticSearchState> = repository.semanticSearchState

    private val _semanticStats = MutableStateFlow(SemanticStats())
    val semanticStats: StateFlow<SemanticStats> = _semanticStats.asStateFlow()

    /** 是否处于语义搜索模式 */
    private val _isSemanticMode = MutableStateFlow(false)
    val isSemanticMode: StateFlow<Boolean> = _isSemanticMode.asStateFlow()

    /** 切换语义搜索模式 */
    fun setSemanticMode(enabled: Boolean) {
        _isSemanticMode.value = enabled
        if (!enabled) {
            clearSemanticSearch()
        }
    }

    /** 执行语义搜索（自然语言查询） */
    fun semanticSearch(query: String) {
        viewModelScope.launch { repository.semanticSearch(query) }
    }

    fun clearSemanticSearch() {
        repository.clearSemanticSearch()
    }

    /** 加载语义搜索索引统计 */
    fun loadSemanticStats() {
        viewModelScope.launch {
            _semanticStats.value = repository.getSemanticStats()
        }
    }

    /**
     * 智能搜索：根据当前模式自动选择搜索方式。
     * 语义模式 → 混合检索（语义召回 + 关键词精排）
     * 关键词模式 → 纯 FTS4 关键词搜索
     */
    fun smartSearch(query: String) {
        viewModelScope.launch {
            if (_isSemanticMode.value) {
                repository.hybridSearch(query)
            } else {
                repository.search(query)
            }
        }
    }

    fun toggleFavorite(path: String) {
        viewModelScope.launch { repository.toggleFavorite(path) }
    }

    fun batchSetFavorite(paths: List<String>, favorite: Boolean) {
        viewModelScope.launch { repository.batchSetFavorite(paths, favorite) }
    }

    fun updateAlbumCover(albumId: String, coverPath: String) {
        viewModelScope.launch { repository.updateAlbumCover(albumId, coverPath) }
    }

    /** 排序模式变更后重建相册树 */
    fun rebuildAlbumTree() {
        viewModelScope.launch { repository.loadFromDb() }
    }

    // ---- 相似照片管理 (Phase 3.1) ----

    private val _similarGroups = MutableStateFlow<List<SimilarGroup>>(emptyList())
    val similarGroups: StateFlow<List<SimilarGroup>> = _similarGroups.asStateFlow()

    private val _similarGroupPhotos = MutableStateFlow<List<MediaItem>>(emptyList())
    val similarGroupPhotos: StateFlow<List<MediaItem>> = _similarGroupPhotos.asStateFlow()

    /** 加载所有相似组（供管理界面分组展示） */
    fun loadSimilarGroups() {
        viewModelScope.launch {
            _similarGroups.value = repository.getSimilarGroups()
        }
    }

    /** 加载某个相似组内的完整媒体项 */
    fun loadPhotosInGroup(groupId: String) {
        viewModelScope.launch {
            _similarGroupPhotos.value = repository.getPhotosInGroup(groupId)
        }
    }

    /** "保留最佳"：删除组内除最高质量评分项之外的所有项 */
    fun keepBestInGroup(groupId: String) {
        viewModelScope.launch {
            repository.keepBestInGroup(groupId)
            // 刷新相似组列表
            _similarGroups.value = repository.getSimilarGroups()
            _similarGroupPhotos.value = repository.getPhotosInGroup(groupId)
        }
    }

    /** 从相似组中移除指定路径（手动拆分组） */
    fun removeFromSimilarGroup(paths: List<String>) {
        viewModelScope.launch {
            repository.removeFromSimilarGroup(paths)
            _similarGroups.value = repository.getSimilarGroups()
        }
    }

    /** 将多个路径合并到同一相似组 */
    fun mergeIntoSimilarGroup(paths: List<String>, groupId: String) {
        viewModelScope.launch {
            repository.mergeIntoSimilarGroup(paths, groupId)
            _similarGroups.value = repository.getSimilarGroups()
        }
    }

    // ---- 重复照片管理 (DUP-1) ----

    private val _duplicateGroups = MutableStateFlow<List<DuplicateGroup>>(emptyList())
    val duplicateGroups: StateFlow<List<DuplicateGroup>> = _duplicateGroups.asStateFlow()

    private val _duplicateGroupPhotos = MutableStateFlow<List<MediaItem>>(emptyList())
    val duplicateGroupPhotos: StateFlow<List<MediaItem>> = _duplicateGroupPhotos.asStateFlow()

    private val _isDuplicateLoading = MutableStateFlow(false)
    val isDuplicateLoading: StateFlow<Boolean> = _isDuplicateLoading.asStateFlow()

    /** 执行重复检测并加载所有重复组 */
    fun loadDuplicateGroups() {
        viewModelScope.launch {
            _isDuplicateLoading.value = true
            try {
                _duplicateGroups.value = repository.findDuplicateGroups()
            } finally {
                _isDuplicateLoading.value = false
            }
        }
    }

    /** 加载某个重复组内的完整媒体项 */
    fun loadPhotosInDuplicateGroup(groupId: String) {
        viewModelScope.launch {
            val group = _duplicateGroups.value.find { it.groupId == groupId } ?: return@launch
            val dao = repository.getMediaDao()
            val items = group.filePaths.mapNotNull { path ->
                dao.getByFilePathLight(path)?.let {
                    MediaItem(
                        id = java.util.UUID.nameUUIDFromBytes(path.toByteArray()).toString(),
                        filePath = it.filePath,
                        fileName = it.fileName,
                        type = it.mediaType,
                        capturedAt = java.time.Instant.ofEpochMilli(it.capturedAtMs),
                        modifiedAt = java.time.Instant.ofEpochMilli(it.modifiedAtMs),
                        fileSize = it.fileSize,
                        isFavorite = it.isFavorite,
                        isTrashed = it.isTrashed,
                        deletedAtMs = it.deletedAtMs,
                        width = it.width,
                        height = it.height,
                        mimeType = it.mimeType,
                        durationMs = it.durationMs,
                        latitude = it.latitude,
                        longitude = it.longitude,
                        make = it.make,
                        model = it.model,
                        aperture = it.aperture,
                        focalLength = it.focalLength,
                        iso = it.iso,
                        exposureTime = it.exposureTime,
                        orientation = it.orientation,
                        sceneType = it.sceneType,
                        thumbnailPath = it.thumbnailPath,
                        isCorrupted = it.isCorrupted,
                        faceClusterId = it.faceClusterId,
                    )
                }
            }
            _duplicateGroupPhotos.value = items
        }
    }

    /** "保留一项删除其余" */
    fun keepOneDeleteRest(groupId: String, keepPath: String? = null) {
        viewModelScope.launch {
            repository.keepOneDeleteRest(groupId, keepPath)
            _duplicateGroups.value = repository.findDuplicateGroups()
        }
    }

    /** 获取某级别的重复组统计 */
    fun getDuplicateCountByLevel(level: DuplicateLevel): Int {
        return _duplicateGroups.value.count { it.level == level }
    }

    /** 所有重复文件总大小（浪费的存储空间） */
    fun getTotalWastedBytes(): Long {
        return _duplicateGroups.value.sumOf { group ->
            group.filePaths.drop(1).sumOf { group.fileSizes[it] ?: 0L }
        }
    }

    // ---- 人脸聚类管理 (Phase 3.3) ----

    private val _faceClusters = MutableStateFlow<List<FaceCluster>>(emptyList())
    val faceClusters: StateFlow<List<FaceCluster>> = _faceClusters.asStateFlow()

    private val _faceClusterPhotos = MutableStateFlow<List<MediaItem>>(emptyList())
    val faceClusterPhotos: StateFlow<List<MediaItem>> = _faceClusterPhotos.asStateFlow()

    private val _faceStats = MutableStateFlow(FaceStats())
    val faceStats: StateFlow<FaceStats> = _faceStats.asStateFlow()

    /** 加载所有人脸聚类（供人脸相册界面分组展示） */
    fun loadFaceClusters() {
        viewModelScope.launch {
            _faceClusters.value = repository.getFaceClusters()
            _faceStats.value = repository.getFaceStats()
        }
    }

    /** 加载某个聚类内的完整媒体项 */
    fun loadPhotosInFaceCluster(clusterId: String) {
        viewModelScope.launch {
            _faceClusterPhotos.value = repository.getPhotosInFaceCluster(clusterId)
        }
    }

    /** 为聚类命名（人物名称） */
    fun setPersonName(clusterId: String, name: String?) {
        viewModelScope.launch {
            repository.setPersonName(clusterId, name)
            _faceClusters.value = repository.getFaceClusters()
        }
    }

    // ---- 地理聚类管理 (Phase 3.4) ----

    private val _geoClusters = MutableStateFlow<List<GeoCluster>>(emptyList())
    val geoClusters: StateFlow<List<GeoCluster>> = _geoClusters.asStateFlow()

    private val _geoClusterPhotos = MutableStateFlow<List<MediaItem>>(emptyList())
    val geoClusterPhotos: StateFlow<List<MediaItem>> = _geoClusterPhotos.asStateFlow()

    private val _geoStats = MutableStateFlow(GeoStats())
    val geoStats: StateFlow<GeoStats> = _geoStats.asStateFlow()

    /** 加载所有地理聚类（供地图视图界面分组展示） */
    fun loadGeoClusters() {
        viewModelScope.launch {
            _geoClusters.value = repository.getGeoClusters()
            _geoStats.value = repository.getGeoStats()
        }
    }

    /** 加载某个地理聚类内的完整媒体项 */
    fun loadPhotosInGeoCluster(clusterId: String) {
        viewModelScope.launch {
            _geoClusterPhotos.value = repository.getPhotosInGeoCluster(clusterId)
        }
    }

    // ---- 数据库导入导出 (Phase 4.2) ----

    /** 导出/导入操作状态 */
    sealed interface BackupState {
        data object Idle : BackupState
        data class InProgress(val message: String) : BackupState
        data class Success(val message: String) : BackupState
        data class Error(val message: String) : BackupState
    }

    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    val backupState: StateFlow<BackupState> = _backupState.asStateFlow()

    /**
     * 导出数据库为 JSON 文件（Phase 4.2）。
     *
     * @param outputFile 目标文件路径
     */
    fun exportDatabase(outputFile: java.io.File) {
        viewModelScope.launch {
            _backupState.value = BackupState.InProgress("正在导出数据库…")
            val result = repository.exportDatabase(outputFile)
            _backupState.value = if (result.success) {
                BackupState.Success(
                    "导出成功：${result.mediaCount} 条媒体, ${result.faceCount} 张人脸, " +
                        "${result.embeddingCount} 条嵌入 (${result.fileSizeBytes / 1024}KB)",
                )
            } else {
                BackupState.Error(result.errorMessage ?: "导出失败")
            }
        }
    }

    /**
     * 导入数据库 JSON 文件（Phase 4.2）。
     * 覆盖式恢复，导入后自动重建相册树。
     *
     * @param inputFile 导出的 JSON 文件
     */
    fun importDatabase(inputFile: java.io.File) {
        viewModelScope.launch {
            _backupState.value = BackupState.InProgress("正在导入数据库…")
            val result = repository.importDatabase(inputFile)
            _backupState.value = if (result.success) {
                BackupState.Success(
                    "导入成功：${result.mediaCount} 条媒体, ${result.faceCount} 张人脸, " +
                        "${result.embeddingCount} 条嵌入",
                )
            } else {
                BackupState.Error(result.errorMessage ?: "导入失败")
            }
        }
    }

    /** 校验导入文件格式（不执行实际导入） */
    fun validateImportFile(inputFile: java.io.File): Pair<org.json.JSONObject?, String?> {
        return repository.validateImportFile(inputFile)
    }

    /** 重置备份状态为空闲 */
    fun resetBackupState() {
        _backupState.value = BackupState.Idle
    }

    // ---- SAF 自定义路径导入/导出（Phase 改进） ----

    /** 待确认导入的临时文件信息（确认文案 -> 临时文件绝对路径），null 表示无待确认导入。 */
    private val _pendingImport = MutableStateFlow<Pair<String, String?>?>(null)
    val pendingImport: StateFlow<Pair<String, String?>?> = _pendingImport.asStateFlow()

    /**
     * 导出数据库到用户通过 SAF 选择的目标 Uri。
     * 先导出到缓存临时文件，再复制到目标 Uri，最后清理临时文件。
     */
    fun exportDatabaseToUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            _backupState.value = BackupState.InProgress("正在导出数据库…")
            val temp = java.io.File(context.cacheDir, "localalbum_export_tmp.json")
            try {
                val result = repository.exportDatabase(temp)
                if (!result.success) {
                    _backupState.value = BackupState.Error(result.errorMessage ?: "导出失败")
                    return@launch
                }
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    temp.inputStream().use { it.copyTo(out) }
                } ?: run {
                    _backupState.value = BackupState.Error("无法写入目标位置")
                    return@launch
                }
                _backupState.value = BackupState.Success(
                    "导出成功：${result.mediaCount} 条媒体, ${result.faceCount} 张人脸, " +
                        "${result.embeddingCount} 条嵌入 (${result.fileSizeBytes / 1024}KB)",
                )
            } catch (e: Exception) {
                _backupState.value = BackupState.Error("导出失败: ${e.message}")
            } finally {
                temp.delete()
            }
        }
    }

    /**
     * 从用户通过 SAF 选择的 Uri 读取导入文件，校验后置为待确认状态（不立即导入）。
     */
    fun prepareImportFromUri(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            _backupState.value = BackupState.InProgress("正在读取文件…")
            val temp = java.io.File(context.cacheDir, "localalbum_import_tmp.json")
            try {
                val opened = context.contentResolver.openInputStream(uri)?.use { inp ->
                    temp.outputStream().use { inp.copyTo(it) }
                    true
                } ?: false
                if (!opened) {
                    _backupState.value = BackupState.Error("无法读取所选文件")
                    return@launch
                }
                val (counts, error) = repository.validateImportFile(temp)
                if (error != null) {
                    _backupState.value = BackupState.Error("文件格式错误: $error")
                    temp.delete()
                    return@launch
                }
                val mc = counts?.optInt("media", 0) ?: 0
                val fc = counts?.optInt("faces", 0) ?: 0
                val ec = counts?.optInt("embeddings", 0) ?: 0
                _backupState.value = BackupState.Idle
                _pendingImport.value =
                    "即将导入：$mc 条媒体, $fc 张人脸, $ec 条嵌入。当前数据将被覆盖！" to temp.absolutePath
            } catch (e: Exception) {
                _backupState.value = BackupState.Error("读取失败: ${e.message}")
                temp.delete()
            }
        }
    }

    /** 确认执行待确认的导入。 */
    fun confirmImport(tempPath: String) {
        viewModelScope.launch {
            _pendingImport.value = null
            _backupState.value = BackupState.InProgress("正在导入数据库…")
            val temp = java.io.File(tempPath)
            val result = repository.importDatabase(temp)
            temp.delete()
            _backupState.value = if (result.success) {
                BackupState.Success(
                    "导入成功：${result.mediaCount} 条媒体, ${result.faceCount} 张人脸, " +
                        "${result.embeddingCount} 条嵌入",
                )
            } else {
                BackupState.Error(result.errorMessage ?: "导入失败")
            }
        }
    }

    /** 取消待确认的导入，清理临时文件。 */
    fun cancelImport() {
        _pendingImport.value?.second?.let { java.io.File(it).delete() }
        _pendingImport.value = null
    }

    // ---- 推荐刷新 ----

    /**
     * 刷新精选推荐列表。
     * 从全量推荐池中取下一批未展示的推荐，保证每次刷新内容不同。
     * 当所有推荐都展示完毕后自动重新打乱循环。
     */
    fun refreshRecommendations() {
        viewModelScope.launch { repository.refreshRecommendations() }
    }
}
