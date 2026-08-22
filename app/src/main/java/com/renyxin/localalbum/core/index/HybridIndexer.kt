package com.renyxin.localalbum.core.index

import android.content.Context
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.room.withTransaction
import com.renyxin.localalbum.core.concurrent.EnhancementResourceGate
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.PluginAnalysisPipeline
import com.renyxin.localalbum.core.thumbnail.HeadFingerprint
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.MediaChangeDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.ScanRunDao
import com.renyxin.localalbum.data.db.dao.ScanStagingDao
import com.renyxin.localalbum.data.db.dao.DeletionTombstoneDao
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.EnhancementOutboxEntity
import com.renyxin.localalbum.data.db.entity.MediaChangeEventEntity
import com.renyxin.localalbum.data.db.entity.MediaStoreReferenceEntity
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.entity.ScanStagingEntity
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.worker.AnalysisWorker
import com.renyxin.localalbum.data.db.entity.MediaFts
import com.renyxin.localalbum.data.repo.DeletionFailurePolicy
import com.renyxin.localalbum.data.source.MediaSource
import com.renyxin.localalbum.data.source.MediaStoreLookupStatus
import com.renyxin.localalbum.data.source.ResolvedMediaStoreItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.File
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
/**
 * 构造参数契约（主构造 18 个参数，其中 11 个可空 DAO/组件）：
 *
 * - [context] / [profileId] / [mediaDao] / [mediaSource]：必有。profileId 区分 full/lite
 *   扫描 profile（隔离扫描运行记录与删除协调器）。
 * - [database]：可空。仅事务性复合写入（staging 提交等）需要；null 时相关路径退化为
 *   非事务写入，主要为旧测试构造保留。
 * - [faceDao] / [embeddingDao] / [featureStoreDao]：可空。null 时扫描不持久化对应的
 *   AI 特征表（人脸/语义向量/异构特征），增强阶段直接跳过。
 * - [pluginPipeline]：可空。null 时扫描完成后不驱动 AI 分析管道（纯索引模式）。
 * - [analysisStateDao] / [analysisTaskDao]：可空。null 时无断点续跑与持久分析任务
 *   （重分析/续跑相关 API 变为 no-op）。
 * - [enhancementOutboxDao]：可空。null 时扫描核心完成后不入队增强 outbox。
 * - [scanRunDao] / [scanStagingDao]：可空。null 时无扫描运行记录与 staging 流水线
 *   （[latestScanRun] / [publishCoreComplete] 等会经 requireScanRunDao() 抛异常）。
 * - [mediaChangeDao]：可空。null 时 ContentObserver 变更不持久化。
 * - [deletionTombstoneDao] / [mediaDeletionCoordinator]：可空。null 时媒体删除不走
 *   墓碑/协调器链路（删除语义退化为直接操作）。
 * - [scanTelemetry]：默认 [LogScanTelemetry]，测量事件写 Logcat。
 *
 * 生产环境（AppContainer）注入全部非空；「仅 mediaDao + 少量 DAO」的组合是测试专用形态。
 */
class HybridIndexer(
    private val context: Context,
    private val profileId: String,
    private val mediaDao: MediaDao,
    private val database: com.renyxin.localalbum.data.db.AppDatabase? = null,
    private val faceDao: FaceDao? = null,
    private val embeddingDao: com.renyxin.localalbum.data.db.dao.EmbeddingDao? = null,
    private val mediaSource: MediaSource = MediaSource(context),
    private val pluginPipeline: PluginAnalysisPipeline? = null,
    private val analysisStateDao: com.renyxin.localalbum.data.db.dao.AnalysisStateDao? = null,
    private val analysisTaskDao: com.renyxin.localalbum.data.db.dao.AnalysisTaskDao? = null,
    private val featureStoreDao: com.renyxin.localalbum.data.db.dao.FeatureStoreDao? = null,
    private val enhancementOutboxDao: com.renyxin.localalbum.data.db.dao.EnhancementOutboxDao? = null,
    private val scanRunDao: ScanRunDao? = null,
    private val scanStagingDao: ScanStagingDao? = null,
    private val mediaChangeDao: MediaChangeDao? = null,
    private val deletionTombstoneDao: DeletionTombstoneDao? = null,
    private val mediaDeletionCoordinator: com.renyxin.localalbum.data.repo.MediaDeletionCoordinator? = null,
    private val scanTelemetry: ScanTelemetry = LogScanTelemetry(),
) {
    /**
     * 最近一次扫描运行记录。
     *
     * @return 最近一条 [ScanRunEntity]；无任何记录（未扫描过）时返回 null
     * @throws IllegalStateException 未注入 [scanRunDao]（测试专用构造形态）
     */
    suspend fun latestScanRun(): ScanRunEntity? = requireScanRunDao().getLatest()

    /**
     * 发布扫描核心终态（SNAPSHOT_PUBLISHED → CORE_SCAN_COMPLETED 双里程碑）。
     *
     * 契约：
     * 1. 先经 [requireScanRunDao].markSnapshotPublished 将该 scanId 标记为快照已发布；
     *    `check` 失败（影响行数 != 1）意味着 scanId 不存在或状态不满足前置条件，直接抛
     *    [IllegalStateException]——核心终态提交绝不容忍静默丢失；
     * 2. 依次记录 [ScanMilestone.SNAPSHOT_PUBLISHED] 与 [ScanMilestone.CORE_SCAN_COMPLETED]
     *    两段 telemetry 里程碑（elapsedMs 均相对 result.startedAtMs）；
     * 3. 若本次扫描有增强 outbox 条目，只标记持久增强已排队。这里绝不唤醒 handoff、
     *    缩略图或分析 Worker；严格串行图库协调器会在对应持久阶段消费这些条目。
     *
     * @param result 本次扫描核心阶段的提交结果（scanId、起止计数、outbox 计数等）
     */
    suspend fun publishCoreComplete(result: ScanCommitResult) {
        val completedAt = System.currentTimeMillis()
        check(requireScanRunDao().markSnapshotPublished(result.scanId, completedAt) == 1) {
            "扫描核心终态提交失败"
        }
        scanTelemetry.record(
            ScanMeasurementEvent(
                scanId = result.scanId,
                scanType = result.scanType,
                milestone = ScanMilestone.SNAPSHOT_PUBLISHED,
                elapsedMs = completedAt - result.startedAtMs,
                mediaCount = result.indexed,
                changedCount = result.inserted + result.updated + result.deleted,
            ),
        )
        scanTelemetry.record(
            ScanMeasurementEvent(
                scanId = result.scanId,
                scanType = result.scanType,
                milestone = ScanMilestone.CORE_SCAN_COMPLETED,
                elapsedMs = completedAt - result.startedAtMs,
                mediaCount = result.indexed,
                changedCount = result.inserted + result.updated + result.deleted,
            ),
        )
        if (result.enhancementOutboxCount > 0) {
            requireScanRunDao().markEnhancementQueued(result.scanId)
            Log.i(
                TAG,
                "enhancement handoff persisted for serialized pipeline: " +
                    "scanIdHash=${result.scanId.hashCode()} outbox=${result.enhancementOutboxCount}",
            )
        }
    }

    /**
     * 标记一次扫描在「索引已入库之后、核心终态发布之前」失败。
     *
     * 与直接废弃不同：媒体数据已落库，仅扫描运行记录被标记为失败态，
     * 下次扫描可在此基础上做增量修复。errorType 会被截断到 80 字符以防溢出列宽。
     *
     * @param scanId 目标扫描运行 ID
     * @param errorType 稳定的失败分类标识（非用户可读文案）
     */
    suspend fun markCoreFailedAfterIndex(scanId: String, errorType: String) {
        requireScanRunDao().markCoreFailedAfterIndex(
            errorType = errorType.take(80),
            scanId = scanId,
            now = System.currentTimeMillis(),
        )
    }

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
        paths.chunked(CHANGE_BATCH_SIZE).forEach { chunk ->
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
        private const val CHANGE_BATCH_SIZE = 500
        private const val CHANGE_LEASE_MS = 60_000L
        private const val CHANGE_RETRY_DELAY_MS = 5_000L
        internal const val CHANGE_DISCOVERY_PAGE_SIZE = 250
        /** Full/reconciliation recommendation refresh also uses a bounded changed-directory window. */
        private const val RECOMMENDATION_DIRECTORY_WINDOW_LIMIT = 100
        /** 每批入库上限：限制大相册扫描时实体列表和单次 SQLite 事务的内存峰值。 */
        private const val DATABASE_BATCH_SIZE = 500
        /**
         * AI 管道的有界批次大小。阶段内虽已有限并发，但若向 runFullScan 传入整库路径，
         * DAG 层仍会持有全量输入并等待整层完成，造成长时间不可用和内存峰值。
         */
        private const val ENUMERATION_BATCH_SIZE = 250
        private const val CHANGE_RESOLVE_BATCH_SIZE = 250
        private const val ENUMERATION_CHANNEL_CAPACITY = 2
        private const val SOURCE_MEDIA_STORE = 1
        private const val SOURCE_FILE_SYSTEM = 2
    }


    /** 获取插件化分析管道，供兼容调用使用。 */
    internal fun getPluginPipeline() = pluginPipeline

    /**
     * Atomically creates explicit user tasks for every current image without materializing paths or
     * placing them in WorkManager Data. Per-Stage identities avoid replaying the aggregate DAG once
     * per scope; legacy aggregate scopes are retired before the user run starts.
     */
    suspend fun requestFullReanalysis() {
        val pipeline = pluginPipeline ?: return
        val dao = analysisTaskDao ?: return
        val scopes = pipeline.stageTaskScopes.values.toList()
        if (scopes.isEmpty()) {
            Log.w(TAG, "full-reanalysis ignored: current edition has no executable analysis stages")
            return
        }

        // Replace the old unique chain before mutating checkpoints. Cancellation releases any live
        // lease; the shared lane then provides a strict boundary against an in-flight Stage writing
        // analysis_state after this request cleared it.
        AnalysisWorker.cancelForQueueReplacement(context)
        EnhancementResourceGate.withInteractiveAi {
            val now = System.currentTimeMillis()
            dao.recoverInterruptedLeases(now)
            database?.withTransaction {
                analysisStateDao?.deleteAll()
                pipeline.claimablePipelineScopes.forEach { legacyScope ->
                    dao.supersedeScope(legacyScope, "user_reanalysis_uses_stage_tasks", now)
                }
                scopes.forEach { scope ->
                    val prepared = dao.prepareFullReanalysisScope(
                        scope = scope,
                        scanId = null,
                        mediaType = MediaType.IMAGE.name,
                        priority = AnalysisTaskEntity.PRIORITY_USER,
                        now = now,
                    )
                    Log.i(
                        TAG,
                        "full-reanalysis prepared: scopeHash=${scope.hashCode()} " +
                            "reset=${prepared.reset} inserted=${prepared.inserted} " +
                            "superseded=${prepared.superseded}",
                    )
                }
            } ?: run {
                analysisStateDao?.deleteAll()
                scopes.forEach { scope ->
                    dao.resetAll(AnalysisTaskEntity.PRIORITY_USER, now, scope)
                }
            }
        }
        val runnable = scopes.sumOf { scope -> dao.countRunnable(scope) }
        Log.i(TAG, "full-reanalysis diagnostic: scopes=${scopes.size} runnable=$runnable")
        AnalysisWorker.publishQueueReplacement(context, hasRunnableTasks = runnable > 0)
    }

    /** 当前已注册的 ContentObserver，用于注销时引用 */
    @Volatile
    private var registeredObserver: MediaContentObserver? = null

    /** Registers a bounded observer; the callback must persist changes before requesting a scan. */
    fun registerContentObserver(onChanges: (List<MediaStoreChangeNotification>) -> Unit) {
        if (registeredObserver != null) {
            Log.w(TAG, "ContentObserver 已注册，跳过重复注册")
            return
        }
        val observer = MediaContentObserver(onChanges)
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

    suspend fun recordMediaChanges(changes: List<MediaStoreChangeNotification>) {
        if (changes.isEmpty()) return
        requireMediaChangeDao().recordAll(
            changes.map { MediaStoreChangeResolver.toEvent(profileId, it) },
        )
    }

    /**
     * Discovers precise MediaStore identities that may have changed while no observer was active.
     *
     * MediaStore is streamed in fixed batches and never materialized as a full-library list. Existing
     * references are then validated by bounded keyset pages so deletions are also journaled. The method
     * writes only durable MEDIA events; the serialized pipeline remains the sole scan admission owner.
     */
    suspend fun discoverMediaStoreChanges(
        roots: List<String>,
        ignorePatterns: List<String> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): MediaChangeDiscoveryResult = withContext(Dispatchers.IO) {
        val changeDao = requireMediaChangeDao()
        var observed = 0
        var journaled = 0
        enumerateMediaStoreBatches(ScanRootPolicy.normalize(roots), ignorePatterns) { items ->
            observed += items.size
            val references = changeDao.getReferences(
                items.map { indexed -> indexed.identity.stableKey(profileId) },
            ).associateBy { it.stableKey }
            val events = items.mapNotNull { indexed ->
                val item = indexed.item
                val key = indexed.identity.stableKey(profileId)
                val reference = references[key]
                val canonicalPath = com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
                    .canonicalizePath(item.filePath)
                val persistedFingerprint = reference?.sourceVersion?.let(
                    com.renyxin.localalbum.core.thumbnail.SourceVersionCodec::decode,
                )?.fingerprintHead
                val metadataMatches = reference != null &&
                    reference.filePath == canonicalPath &&
                    com.renyxin.localalbum.core.thumbnail.SourceVersionCodec.matchesPhysical(
                        encoded = reference.sourceVersion,
                        modifiedAtMs = item.modifiedAt.toEpochMilli(),
                        fileSize = item.fileSize,
                        // Discovery intentionally avoids file I/O. Supplying the persisted fingerprint
                        // makes this comparison cover only MediaStore-visible mtime/size metadata.
                        fingerprintHead = persistedFingerprint,
                    )
                if (metadataMatches) null else discoveryEvent(indexed.identity, now)
            }
            if (events.isNotEmpty()) {
                changeDao.recordAll(events)
                journaled += events.size
            }
        }

        var afterKey: String? = null
        while (true) {
            val page = changeDao.getReferencePageAfter(
                profileId = profileId,
                afterKey = afterKey,
                limit = CHANGE_DISCOVERY_PAGE_SIZE,
            )
            if (page.isEmpty()) break
            val identities = page.map { reference ->
                MediaStoreIdentity(
                    volumeName = reference.volumeName,
                    mediaType = MediaType.valueOf(reference.mediaType),
                    mediaStoreId = reference.mediaStoreId,
                )
            }
            val validation = mediaSource.resolveMediaStoreItems(
                identities = identities,
                rootPaths = roots,
                ignorePatterns = ignorePatterns,
            ).associateBy { resolved -> resolved.identity.stableKey(profileId) }
            val missingEvents = page.asSequence()
                .filter { reference ->
                    when (validation[reference.stableKey]?.status) {
                        MediaStoreLookupStatus.INCLUDED,
                        MediaStoreLookupStatus.UNRESOLVED,
                        -> false
                        MediaStoreLookupStatus.EXCLUDED,
                        null,
                        -> true
                    }
                }
                .map { reference ->
                    discoveryEvent(
                        MediaStoreIdentity(
                            volumeName = reference.volumeName,
                            mediaType = MediaType.valueOf(reference.mediaType),
                            mediaStoreId = reference.mediaStoreId,
                        ),
                        now,
                    )
                }
                .toList()
            if (missingEvents.isNotEmpty()) {
                changeDao.recordAll(missingEvents)
                journaled += missingEvents.size
            }
            afterKey = page.last().stableKey
            if (page.size < CHANGE_DISCOVERY_PAGE_SIZE) break
        }
        MediaChangeDiscoveryResult(observed = observed, journaled = journaled)
    }

    private fun discoveryEvent(identity: MediaStoreIdentity, now: Long) = MediaChangeEventEntity(
        eventKey = identity.stableKey(profileId),
        profileId = profileId,
        eventType = MediaChangeEventEntity.TYPE_MEDIA,
        volumeName = identity.volumeName,
        mediaType = identity.mediaType.name,
        mediaStoreId = identity.mediaStoreId,
        contentUri = identity.canonicalContentUri(),
        firstObservedAtMs = now,
        observedAtMs = now,
    )

    suspend fun requestReconciliation(now: Long = System.currentTimeMillis()) {
        requireMediaChangeDao().record(
            MediaChangeEventEntity(
                eventKey = MediaStoreChangeResolver.reconciliationKey(profileId),
                profileId = profileId,
                eventType = MediaChangeEventEntity.TYPE_RECONCILIATION,
                firstObservedAtMs = now,
                observedAtMs = now,
            ),
        )
    }

    suspend fun hasPendingReconciliation(): Boolean =
        requireMediaChangeDao().hasReconciliationHint(profileId)

    suspend fun consumeReconciliationHintAsAdvisory(): Boolean {
        val dao = requireMediaChangeDao()
        val pending = dao.hasReconciliationHint(profileId)
        if (pending) dao.clearReconciliationHint(profileId)
        return pending
    }

    suspend fun countOutstandingMediaChanges(): Int =
        requireMediaChangeDao().countOutstandingMediaChanges(profileId)

    /**
     * Returns the durable changed-set state when this indexer is fully wired. A null result means
     * the journal DAO was intentionally omitted by a lightweight test-only construction; callers
     * that make an admission decision must treat that state as unknown, not as an empty journal.
     * Production [com.renyxin.localalbum.AppContainer] construction always injects the DAO.
     */
    suspend fun hasOutstandingMediaChangesOrNull(): Boolean? =
        mediaChangeDao?.hasOutstandingMediaChanges(profileId)

    suspend fun hasOutstandingMediaChanges(): Boolean =
        requireMediaChangeDao().hasOutstandingMediaChanges(profileId)

    suspend fun hasClaimableMediaChanges(now: Long = System.currentTimeMillis()): Boolean =
        requireMediaChangeDao().hasClaimableMediaChanges(profileId, now)

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
        reservedRun: ScanRunEntity? = null,
        onCoreReady: suspend (ScanCommitResult) -> Unit = { result -> finalizeCoreRun(result) },
    ): Int {
        val normalizedRoots = ScanRootPolicy.normalize(roots)
        return executeScanRun(ScanRunEntity.TYPE_FULL, normalizedRoots, reservedRun) { scanId, generation, startedAtMs ->
            val result = scanViaStaging(
                scanType = ScanRunEntity.TYPE_FULL,
                scanId = scanId,
                generation = generation,
                startedAtMs = startedAtMs,
                roots = normalizedRoots,
                allowNomedia = allowNomedia,
                ignorePatterns = ignorePatterns,
                progress = progress,
            )
            onCoreReady(result)
            result.indexed
        }
    }

    /** Full source reconciliation. This is deliberately distinct from changed-set incremental. */
    suspend fun reconciliationScan(
        roots: List<String>,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
        reservedRun: ScanRunEntity? = null,
        onCoreReady: suspend (ScanCommitResult) -> Unit = { result -> finalizeCoreRun(result) },
    ): IncrementalResult {
        val normalizedRoots = ScanRootPolicy.normalize(roots)
        return executeScanRun(ScanRunEntity.TYPE_RECONCILIATION, normalizedRoots, reservedRun) {
                scanId, generation, startedAtMs ->
            val result = scanViaStaging(
                scanType = ScanRunEntity.TYPE_RECONCILIATION,
                scanId = scanId,
                generation = generation,
                startedAtMs = startedAtMs,
                roots = normalizedRoots,
                allowNomedia = allowNomedia,
                ignorePatterns = ignorePatterns,
                progress = null,
            )
            onCoreReady(result)
            IncrementalResult(
                inserted = result.inserted,
                updated = result.updated,
                deleted = result.deleted,
                elapsedMs = System.currentTimeMillis() - startedAtMs,
            )
        }
    }

    /** Consumes only durable MediaStore identities claimed from the changed-set journal. */
    suspend fun incrementalScan(
        roots: List<String>,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
        reservedRun: ScanRunEntity? = null,
        onCoreReady: suspend (ScanCommitResult) -> Unit = { result -> finalizeCoreRun(result) },
    ): IncrementalResult {
        val normalizedRoots = ScanRootPolicy.normalize(roots)
        return executeScanRun(ScanRunEntity.TYPE_INCREMENTAL, normalizedRoots, reservedRun) {
                scanId, generation, startedAtMs ->
            val drained = drainChangedSet(
                scanId = scanId,
                generation = generation,
                startedAtMs = startedAtMs,
                roots = normalizedRoots,
                allowNomedia = allowNomedia,
                ignorePatterns = ignorePatterns,
            )
            onCoreReady(drained.result)
            drained.acknowledgements.forEach { acknowledgement ->
                try {
                    requireMediaChangeDao().acknowledgeLease(
                        leaseToken = acknowledgement.leaseToken,
                        deletedStableKeys = acknowledgement.deletedStableKeys,
                    )
                } catch (error: Throwable) {
                    // Core and snapshot are already published. Leaving the lease for idempotent
                    // replay is safer than reporting a completed scan as failed.
                    Log.e(TAG, "changed-set 确认失败，将在租约恢复后重放", error)
                }
            }
            IncrementalResult(
                inserted = drained.result.inserted,
                updated = drained.result.updated,
                deleted = drained.result.deleted,
                elapsedMs = System.currentTimeMillis() - startedAtMs,
                affectedDirectoryPaths = drained.result.affectedDirectoryPaths,
                reconciliationRequired = requireMediaChangeDao().hasReconciliationHint(profileId),
            )
        }
    }

    private suspend fun finalizeCoreRun(result: ScanCommitResult) = publishCoreComplete(result)

    suspend fun acknowledgePublishedReconciliation(observedThroughMs: Long) {
        requireMediaChangeDao().acknowledgeEventsThrough(profileId, observedThroughMs)
    }

    /** Empty configured roots explicitly define an empty reconciliation domain. */
    suspend fun acknowledgeEmptyReconciliationDomain() {
        requireMediaChangeDao().clearReconciliationHint(profileId)
    }

    private suspend fun drainChangedSet(
        scanId: String,
        generation: Long,
        startedAtMs: Long,
        roots: List<String>,
        allowNomedia: Boolean,
        ignorePatterns: List<String>,
    ): DeltaDrainResult {
        val changeDao = requireMediaChangeDao()
        // 与全量扫描同语义的运行级缓存：指纹去重 + 视频超时黑名单，随本次增量运行丢弃。
        val runCache = ScanRunCache()
        val acknowledgements = mutableListOf<ChangeAcknowledgement>()
        val affectedDirectories = linkedSetOf<String>()
        var inserted = 0
        var updated = 0
        var deleted = 0
        var processed = 0
        var firstChangePublished = false

        while (true) {
            val claimedAt = System.currentTimeMillis()
            if (acknowledgements.isNotEmpty()) {
                changeDao.renewLeases(
                    leaseTokens = acknowledgements.map(ChangeAcknowledgement::leaseToken),
                    leaseUntil = claimedAt + CHANGE_LEASE_MS,
                    now = claimedAt,
                )
            }
            val leaseToken = java.util.UUID.randomUUID().toString()
            val events = changeDao.claimBatch(
                profileId = profileId,
                now = claimedAt,
                limit = CHANGE_BATCH_SIZE,
                leaseDurationMs = CHANGE_LEASE_MS,
                leaseToken = leaseToken,
            )
            if (events.isEmpty()) break
            try {
                val identities = events.mapNotNull(MediaStoreChangeResolver::identityOf)
                val lookups = identities.chunked(CHANGE_RESOLVE_BATCH_SIZE).flatMap { chunk ->
                    val resolved = mediaSource.resolveMediaStoreItems(
                        identities = chunk,
                        rootPaths = roots,
                        allowNomedia = allowNomedia,
                        ignorePatterns = ignorePatterns,
                        scanCache = runCache,
                    )
                    changeDao.renewLease(
                        leaseToken = leaseToken,
                        leaseUntil = System.currentTimeMillis() + CHANGE_LEASE_MS,
                        now = System.currentTimeMillis(),
                    )
                    resolved
                }
                val committed = commitDeltaBatch(
                    events = events,
                    lookups = lookups,
                    generation = generation,
                    scanId = scanId,
                    runCache = runCache,
                )
                // Ambiguous identities are persisted as a rebuild advisory. The pipeline never
                // turns them into an automatic root traversal; only explicit rebuild may do that.
                if (committed.reconciliationRequired) requestReconciliation()
                inserted += committed.inserted
                updated += committed.updated
                deleted += committed.deleted
                processed += events.size
                affectedDirectories += committed.affectedDirectoryPaths
                acknowledgements += ChangeAcknowledgement(
                    leaseToken = leaseToken,
                    deletedStableKeys = committed.deletedStableKeys,
                )

                if (!firstChangePublished && committed.changedCount > 0) {
                    firstChangePublished = true
                    val availableAt = System.currentTimeMillis()
                    requireScanRunDao().markIndexAvailable(scanId, now = availableAt)
                    scanTelemetry.record(
                        ScanMeasurementEvent(
                            scanId = scanId,
                            scanType = ScanRunEntity.TYPE_INCREMENTAL,
                            milestone = ScanMilestone.FIRST_BATCH_COMMITTED,
                            elapsedMs = availableAt - startedAtMs,
                            mediaCount = events.size,
                            changedCount = committed.changedCount,
                        ),
                    )
                    scanTelemetry.record(
                        ScanMeasurementEvent(
                            scanId = scanId,
                            scanType = ScanRunEntity.TYPE_INCREMENTAL,
                            milestone = ScanMilestone.INDEX_AVAILABLE,
                            elapsedMs = availableAt - startedAtMs,
                            mediaCount = events.size,
                            changedCount = committed.changedCount,
                        ),
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                changeDao.releaseLease(leaseToken, System.currentTimeMillis())
                throw cancelled
            } catch (error: Throwable) {
                val now = System.currentTimeMillis()
                changeDao.failLease(
                    leaseToken = leaseToken,
                    nextAttemptAt = now + CHANGE_RETRY_DELAY_MS,
                    error = error.javaClass.simpleName.take(80),
                    now = now,
                )
                throw error
            }
        }

        requireScanRunDao().markMediaStoreCompleted(scanId)
        requireScanRunDao().markFileSystemCompleted(scanId)
        check(requireScanRunDao().markCoreReadyForSnapshot(scanId)) {
            "增量 changed-set 完成后无法进入快照发布状态"
        }
        return DeltaDrainResult(
            result = ScanCommitResult(
                scanId = scanId,
                scanType = ScanRunEntity.TYPE_INCREMENTAL,
                startedAtMs = startedAtMs,
                indexed = processed,
                inserted = inserted,
                updated = updated,
                deleted = deleted,
                enhancementOutboxCount = enhancementOutboxDao?.countActiveForScan(scanId) ?: 0,
                affectedDirectoryPaths = affectedDirectories,
            ),
            acknowledgements = acknowledgements,
        )
    }

    private suspend fun commitDeltaBatch(
        events: List<MediaChangeEventEntity>,
        lookups: List<ResolvedMediaStoreItem>,
        generation: Long,
        scanId: String,
        runCache: ScanRunCache? = null,
    ): DeltaBatchCommit {
        val changeDao = requireMediaChangeDao()
        val coordinator = requireNotNull(mediaDeletionCoordinator) {
            "MediaDeletionCoordinator 未注入，拒绝提交增量删除或路径迁移"
        }
        val lookupsByKey = lookups.associateBy { it.identity.stableKey(profileId) }
        val referencesByKey = changeDao.getReferences(events.map { it.eventKey })
            .associateBy { it.stableKey }
        val candidatePaths = buildList {
            addAll(referencesByKey.values.map { it.filePath })
            addAll(lookups.mapNotNull { it.filePath })
        }.distinct()
        val existingByPath = candidatePaths.chunked(DATABASE_BATCH_SIZE)
            .flatMap { paths -> mediaDao.getByFilePathsLight(paths) }
            .associateBy { it.filePath }
        val tombstones = if (candidatePaths.isEmpty()) {
            emptyMap()
        } else {
            deletionTombstoneDao?.getByPaths(candidatePaths).orEmpty().associateBy { it.filePath }
        }
        val affectedDirectories = linkedSetOf<String>()
        val upserts = mutableListOf<DeltaUpsert>()
        val deletedPaths = linkedSetOf<String>()
        val deletedStableKeys = linkedSetOf<String>()
        var reconciliationRequired = false

        events.forEach { event ->
            val identity = MediaStoreChangeResolver.identityOf(event)
            if (identity == null) {
                reconciliationRequired = true
                return@forEach
            }
            val stableKey = identity.stableKey(profileId)
            val reference = referencesByKey[stableKey]
            val lookup = lookupsByKey[stableKey]
            when (lookup?.status) {
                MediaStoreLookupStatus.INCLUDED -> {
                    val mediaItem = lookup.mediaItem
                    if (mediaItem == null) {
                        reconciliationRequired = true
                        return@forEach
                    }
                    val oldPath = reference?.filePath
                    val sourceExisting = oldPath?.let(existingByPath::get)
                        ?: existingByPath[mediaItem.filePath]
                    val fresh = mediaItem.toEntity(runCache)
                    val pathChanged = sourceExisting != null && sourceExisting.filePath != fresh.filePath
                    val coreChanged = sourceExisting == null || coreIndexChanged(sourceExisting, fresh)
                    val changed = pathChanged || coreChanged
                    val tombstone = tombstones[fresh.filePath] ?: oldPath?.let(tombstones::get)
                    val preserved = preserveIndexedState(
                        fresh = fresh,
                        existing = sourceExisting,
                        tombstone = tombstone,
                        generation = generation,
                        clearRegenerable = changed,
                    )
                    upserts += DeltaUpsert(
                        stableKey = stableKey,
                        lookup = lookup,
                        entity = preserved,
                        previous = sourceExisting,
                        changed = changed,
                        pathChanged = pathChanged,
                    )
                    if (changed) {
                        affectedDirectories += preserved.parentPath
                        sourceExisting?.parentPath?.let(affectedDirectories::add)
                    }
                }

                MediaStoreLookupStatus.EXCLUDED -> {
                    val path = reference?.filePath ?: lookup.filePath
                    if (path != null && existingByPath[path] != null) {
                        deletedPaths += path
                        affectedDirectories += path.substringBeforeLast('/', "")
                    }
                    if (reference != null) deletedStableKeys += stableKey
                }

                MediaStoreLookupStatus.UNRESOLVED -> reconciliationRequired = true
                null -> {
                    if (reference == null) {
                        // A deleted ID without a prior stable mapping cannot identify the old path safely.
                        reconciliationRequired = true
                    } else {
                        deletedPaths += reference.filePath
                        deletedStableKeys += stableKey
                        affectedDirectories += reference.filePath.substringBeforeLast('/', "")
                    }
                }
            }
        }

        val changedUpserts = upserts.filter { it.changed }
        val replacementOldPaths = changedUpserts.mapNotNull { it.previous?.filePath }.toSet()
        val pathsToPurge = (deletedPaths + replacementOldPaths).toList()
        val now = System.currentTimeMillis()
        coordinator.replacePaths(
            oldPaths = pathsToPurge,
            deleteSourceReferences = false,
        ) {
            changedUpserts.filter { it.pathChanged }.forEach { upsert ->
                val previous = requireNotNull(upsert.previous)
                deletionTombstoneDao?.moveToPath(
                    oldPath = previous.filePath,
                    newPath = upsert.entity.filePath,
                    newPathKey = DeletionFailurePolicy.pathKey(upsert.entity.filePath),
                    sourceVersion = sourceVersion(upsert.entity),
                    now = now,
                )
            }
            val entities = changedUpserts.map { it.entity }
            if (entities.isNotEmpty()) {
                mediaDao.insertAll(entities)
                mediaDao.deleteFtsEntries(entities.map { it.filePath })
                mediaDao.insertFtsAll(entities.map {
                    MediaFts(
                        filePath = it.filePath,
                        fileName = it.fileName,
                        parentPath = it.parentPath,
                        ocrText = it.ocrText,
                        make = it.make,
                        model = it.model,
                    )
                })
                enqueueEnhancementOutbox(entities, scanId, now)
            }
            val references = upserts.map { upsert ->
                MediaStoreReferenceEntity(
                    stableKey = upsert.stableKey,
                    profileId = profileId,
                    volumeName = upsert.lookup.identity.volumeName,
                    mediaType = upsert.lookup.identity.mediaType.name,
                    mediaStoreId = upsert.lookup.identity.mediaStoreId,
                    contentUri = upsert.lookup.contentUri,
                    filePath = upsert.entity.filePath,
                    sourceVersion = sourceVersion(upsert.entity),
                    observedAtMs = now,
                    scanGeneration = generation,
                )
            }
            if (references.isNotEmpty()) changeDao.upsertReferences(references)
        }

        return DeltaBatchCommit(
            inserted = changedUpserts.count { it.previous == null },
            updated = changedUpserts.count { it.previous != null },
            deleted = deletedPaths.size,
            deletedStableKeys = deletedStableKeys.toList(),
            affectedDirectoryPaths = affectedDirectories,
            reconciliationRequired = reconciliationRequired,
        )
    }

    private fun preserveIndexedState(
        fresh: MediaEntity,
        existing: MediaEntity?,
        tombstone: com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity?,
        generation: Long,
        clearRegenerable: Boolean,
    ): MediaEntity {
        val preserved = if (existing == null) {
            fresh.copy(
                isTrashed = tombstone != null,
                deletedAtMs = tombstone?.deletedAtMs ?: 0L,
                scanGeneration = generation,
            )
        } else {
            fresh.copy(
                isFavorite = existing.isFavorite,
                isTrashed = existing.isTrashed,
                thumbnailPath = if (clearRegenerable) null else existing.thumbnailPath,
                sceneType = if (clearRegenerable) null else existing.sceneType,
                perceptualHash = if (clearRegenerable) 0L else existing.perceptualHash,
                ocrText = if (clearRegenerable) null else existing.ocrText,
                qualityScore = if (clearRegenerable) 0f else existing.qualityScore,
                deletedAtMs = existing.deletedAtMs,
                faceClusterId = if (clearRegenerable) null else existing.faceClusterId,
                scanGeneration = generation,
            )
        }
        return if (tombstone == null) preserved else preserved.copy(
            isTrashed = true,
            deletedAtMs = maxOf(preserved.deletedAtMs, tombstone.deletedAtMs),
        )
    }

    private fun coreIndexChanged(existing: MediaEntity, fresh: MediaEntity): Boolean =
        existing.fileName != fresh.fileName ||
            existing.mediaType != fresh.mediaType ||
            existing.capturedAtMs != fresh.capturedAtMs ||
            existing.modifiedAtMs != fresh.modifiedAtMs ||
            existing.parentPath != fresh.parentPath ||
            existing.fileSize != fresh.fileSize ||
            existing.width != fresh.width ||
            existing.height != fresh.height ||
            existing.mimeType != fresh.mimeType ||
            existing.durationMs != fresh.durationMs ||
            existing.latitude != fresh.latitude ||
            existing.longitude != fresh.longitude ||
            existing.make != fresh.make ||
            existing.model != fresh.model ||
            existing.aperture != fresh.aperture ||
            existing.focalLength != fresh.focalLength ||
            existing.iso != fresh.iso ||
            existing.exposureTime != fresh.exposureTime ||
            existing.orientation != fresh.orientation ||
            existing.fingerprintHead != fresh.fingerprintHead ||
            existing.isCorrupted != fresh.isCorrupted

    private fun sourceVersion(entity: MediaEntity): String =
        com.renyxin.localalbum.core.thumbnail.SourceVersionCodec.encode(
            entity.modifiedAtMs,
            entity.fileSize,
            entity.fingerprintHead,
        )

    internal fun thumbnailIdentityChanged(existing: MediaEntity, fresh: MediaEntity): Boolean =
        com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity.canonicalizePath(existing.filePath) !=
            com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity.canonicalizePath(fresh.filePath) ||
            existing.mediaType != fresh.mediaType ||
            existing.modifiedAtMs != fresh.modifiedAtMs ||
            existing.fileSize != fresh.fileSize ||
            existing.fingerprintHead != fresh.fingerprintHead

    private suspend fun scanViaStaging(
        scanType: String,
        scanId: String,
        generation: Long,
        startedAtMs: Long,
        roots: List<String>,
        allowNomedia: Boolean,
        ignorePatterns: List<String>,
        progress: ((processed: Int, total: Int) -> Unit)?,
    ): ScanCommitResult = coroutineScope {
        // 扫描运行级缓存：头指纹去重 + 视频元数据超时黑名单，随本次扫描结束丢弃。
        val runCache = ScanRunCache()
        val staging = requireScanStagingDao()
        val channel = Channel<List<ScanStagingEntity>>(ENUMERATION_CHANNEL_CAPACITY)
        val insertedCounter = AtomicInteger(0)
        val updatedCounter = AtomicInteger(0)
        val affectedDirectories = linkedSetOf<String>()
        val firstBatchPublished = java.util.concurrent.atomic.AtomicBoolean(false)
        val consumer = launch(Dispatchers.IO) {
            for (batch in channel) {
                staging.mergeBatch(batch)
                val mergedRows = staging.getByPaths(scanId, batch.map { it.filePath })
                val committed = commitStagedBatch(
                    mergedRows.map { ScanMediaCodec.merge(it.mediaStoreJson, it.fileSystemJson) },
                    generation,
                    scanId,
                    runCache,
                )
                insertedCounter.addAndGet(committed.inserted)
                updatedCounter.addAndGet(committed.updated)
                committed.affectedDirectoryPaths.forEach { directory ->
                    if (affectedDirectories.size < RECOMMENDATION_DIRECTORY_WINDOW_LIMIT) {
                        affectedDirectories += directory
                    }
                }
                if (firstBatchPublished.compareAndSet(false, true)) {
                    val availableAt = System.currentTimeMillis()
                    requireScanRunDao().markIndexAvailable(scanId, now = availableAt)
                    scanTelemetry.record(
                        ScanMeasurementEvent(
                            scanId = scanId,
                            scanType = scanType,
                            milestone = ScanMilestone.FIRST_BATCH_COMMITTED,
                            elapsedMs = availableAt - startedAtMs,
                            mediaCount = mergedRows.size,
                            changedCount = committed.inserted + committed.updated,
                        ),
                    )
                    scanTelemetry.record(
                        ScanMeasurementEvent(
                            scanId = scanId,
                            scanType = scanType,
                            milestone = ScanMilestone.INDEX_AVAILABLE,
                            elapsedMs = availableAt - startedAtMs,
                            mediaCount = mergedRows.size,
                            changedCount = committed.inserted + committed.updated,
                        ),
                    )
                }
                val visible = staging.count(scanId)
                progress?.invoke(visible, visible)
            }
        }
        val mediaStoreStartedAt = SystemClock.elapsedRealtime()
        try {
            enumerateMediaStoreBatches(roots, ignorePatterns) { indexedItems ->
                val now = System.currentTimeMillis()
                channel.send(indexedItems.map { indexed ->
                    val item = indexed.item
                    ScanStagingEntity(scanId, item.filePath, SOURCE_MEDIA_STORE, mediaStoreJson = ScanMediaCodec.encode(item))
                })
                mediaChangeDao?.upsertReferences(indexedItems.map { indexed ->
                    val item = indexed.item
                    MediaStoreReferenceEntity(
                        stableKey = indexed.identity.stableKey(profileId),
                        profileId = profileId,
                        volumeName = indexed.identity.volumeName,
                        mediaType = indexed.identity.mediaType.name,
                        mediaStoreId = indexed.identity.mediaStoreId,
                        contentUri = indexed.identity.canonicalContentUri(),
                        filePath = com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
                            .canonicalizePath(item.filePath),
                        sourceVersion = com.renyxin.localalbum.core.thumbnail.SourceVersionCodec.encode(
                            item.modifiedAt.toEpochMilli(),
                            item.fileSize,
                            runCache.fingerprintHead(item.filePath),
                        ),
                        observedAtMs = now,
                        scanGeneration = generation,
                    )
                })
            }
            requireScanRunDao().markMediaStoreCompleted(scanId)
            Log.i(TAG, "scan phase=mediastore-enumerate elapsedMs=${SystemClock.elapsedRealtime() - mediaStoreStartedAt}")
            val fileSystemStartedAt = SystemClock.elapsedRealtime()
            mediaSource.enumerateMediaBatches(
                roots,
                allowNomedia = allowNomedia,
                ignorePatterns = ignorePatterns,
                batchSize = ENUMERATION_BATCH_SIZE,
                scanCache = runCache,
            ) { items ->
                channel.send(items.map { item ->
                    ScanStagingEntity(scanId, item.filePath, SOURCE_FILE_SYSTEM, fileSystemJson = ScanMediaCodec.encode(item))
                })
            }
            requireScanRunDao().markFileSystemCompleted(scanId)
            Log.i(TAG, "scan phase=filesystem-enumerate elapsedMs=${SystemClock.elapsedRealtime() - fileSystemStartedAt}")
        } finally {
            channel.close()
        }
        consumer.join()
        Log.i(TAG, "scan phase=staging-commit-total elapsedMs=${SystemClock.elapsedRealtime() - mediaStoreStartedAt}")

        check(requireScanRunDao().canDeleteOrphans(scanId) == true) {
            "扫描来源未完整完成，拒绝提交 staging"
        }
        // 每个来源批次在 staging 合并后已立即提交正式表，因此首批无需等待完整枚举即可由 Paging 读取。
        val indexed = staging.count(scanId)
        requireScanRunDao().markCoreState(scanId, com.renyxin.localalbum.data.db.entity.CoreScanState.RECONCILING_DELETES.name)
        var deleted = 0
        while (true) {
            val orphaned = mediaDao.getPathsOutsideGeneration(generation, DATABASE_BATCH_SIZE)
            if (orphaned.isEmpty()) break
            orphaned.forEach { path ->
                if (affectedDirectories.size < RECOMMENDATION_DIRECTORY_WINDOW_LIMIT) {
                    affectedDirectories += path.substringBeforeLast('/', "")
                }
            }
            val coordinator = mediaDeletionCoordinator
                ?: error("MediaDeletionCoordinator 未注入，拒绝直接删除扫描孤儿")
            coordinator.purge(orphaned)
            deleted += orphaned.size
        }
        if (mediaChangeDao != null) {
            while (true) {
                val staleReferencePaths = mediaChangeDao.getReferencePathsOutsideGeneration(
                    profileId = profileId,
                    generation = generation,
                    limit = DATABASE_BATCH_SIZE,
                )
                if (staleReferencePaths.isEmpty()) break
                mediaChangeDao.deleteReferencesOutsideGeneration(
                    profileId = profileId,
                    generation = generation,
                    paths = staleReferencePaths,
                )
            }
        }
        check(
            requireScanRunDao().markCoreReadyForSnapshot(scanId),
        ) {
            "扫描核心阶段切换到 Publishing 失败"
        }
        staging.deleteByScanId(scanId)
        ScanCommitResult(
            scanId = scanId,
            scanType = scanType,
            startedAtMs = startedAtMs,
            indexed = indexed,
            inserted = insertedCounter.get(),
            updated = updatedCounter.get(),
            deleted = deleted,
            // Query once after all batches. Per-batch values are cumulative for this scan
            // and must not be added together.
            enhancementOutboxCount = enhancementOutboxDao?.countActiveForScan(scanId) ?: 0,
            affectedDirectoryPaths = affectedDirectories,
        )
    }

    private suspend fun commitStagedBatch(
        items: List<MediaItem>,
        generation: Long,
        scanId: String,
        runCache: ScanRunCache? = null,
    ): BatchCommitCount {
        val fresh = items.map { item ->
            item.toEntity(runCache).copy(
                filePath = com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
                    .canonicalizePath(item.filePath),
            )
        }
        val paths = fresh.map { it.filePath }
        val existingByPath = mediaDao.getByFilePathsLight(paths).associateBy { it.filePath }
        val tombstones = deletionTombstoneDao?.getByPaths(paths).orEmpty().associateBy { it.filePath }
        val identityChangedPaths = fresh.mapNotNull { entity ->
            existingByPath[entity.filePath]
                ?.takeIf { thumbnailIdentityChanged(it, entity) }
                ?.filePath
        }.toSet()
        val upserts = fresh.map { entity ->
            val existing = existingByPath[entity.filePath]
            val tombstone = tombstones[entity.filePath]
            preserveIndexedState(
                fresh = entity,
                existing = existing,
                tombstone = tombstone,
                generation = generation,
                clearRegenerable = entity.filePath in identityChangedPaths,
            )
        }
        val insertedCount = upserts.count { existingByPath[it.filePath] == null }
        val changed = upserts.filter { entity ->
            val old = existingByPath[entity.filePath]
            old == null || thumbnailIdentityChanged(old, entity)
        }
        val affectedDirectoryPaths = changed.asSequence()
            .flatMap { entity ->
                sequenceOf(
                    entity.parentPath,
                    existingByPath[entity.filePath]?.parentPath,
                )
            }
            .filterNotNull()
            .filter(String::isNotBlank)
            .take(RECOMMENDATION_DIRECTORY_WINDOW_LIMIT)
            .toCollection(linkedSetOf())
        val now = System.currentTimeMillis()
        suspend fun commit() {
            if (identityChangedPaths.isNotEmpty()) {
                val invalidated = identityChangedPaths.toList()
                analysisStateDao?.deleteByPaths(invalidated)
                faceDao?.deleteByFilePaths(invalidated)
                embeddingDao?.deleteByFilePaths(invalidated)
                featureStoreDao?.deleteByFilePaths(invalidated)
            }
            mediaDao.insertAll(upserts)
            mediaDao.deleteFtsEntries(upserts.map { it.filePath })
            mediaDao.insertFtsAll(upserts.map {
                MediaFts(
                    filePath = it.filePath,
                    fileName = it.fileName,
                    parentPath = it.parentPath,
                    ocrText = it.ocrText,
                    make = it.make,
                    model = it.model,
                )
            })
            enqueueEnhancementOutbox(changed, scanId, now)
        }
        if (database != null) database.withTransaction { commit() } else commit()
        return BatchCommitCount(
            inserted = insertedCount,
            updated = changed.size - insertedCount,
            affectedDirectoryPaths = affectedDirectoryPaths,
        )
    }

    private fun requireScanStagingDao(): ScanStagingDao =
        scanStagingDao ?: error("ScanStagingDao 未注入，拒绝回退全量内存扫描")

    private fun requireMediaChangeDao(): MediaChangeDao =
        mediaChangeDao ?: error("MediaChangeDao 未注入，拒绝执行无持久 changed-set 的增量扫描")

    private fun requireScanRunDao(): ScanRunDao =
        scanRunDao ?: error("ScanRunDao 未注入，拒绝执行无完整性门禁的扫描")

    private suspend fun <T> executeScanRun(
        scanType: String,
        roots: List<String>,
        reservedRun: ScanRunEntity? = null,
        block: suspend (scanId: String, generation: Long, startedAtMs: Long) -> T,
    ): T = withContext(Dispatchers.IO) {
        validateScanRoots(roots)
        val dao = requireScanRunDao()
        val run = if (reservedRun == null) {
            dao.abortStaleRunning(System.currentTimeMillis())
            val now = System.currentTimeMillis()
            ScanRunEntity(
                scanId = java.util.UUID.randomUUID().toString(),
                generation = maxOf(now, (dao.getMaxGeneration() ?: 0L) + 1L),
                scanType = scanType,
                startedAt = now,
            ).also { created -> dao.insert(created) }
        } else {
            require(reservedRun.scanType == scanType) {
                "Reserved scan type ${reservedRun.scanType} does not match $scanType"
            }
            check(
                dao.resumeReservedCoreRun(
                    reservedRun.scanId,
                    reservedRun.generation,
                    scanType,
                ) == 1,
            ) { "Reserved core run is not resumable: ${reservedRun.scanId}" }
            reservedRun
        }
        val scanId = run.scanId
        val generation = run.generation
        val startedAtMs = run.startedAt
        scanTelemetry.record(
            ScanMeasurementEvent(
                scanId = scanId,
                scanType = scanType,
                milestone = ScanMilestone.TRIGGERED,
                elapsedMs = 0L,
            ),
        )
        dao.markCoreState(scanId, com.renyxin.localalbum.data.db.entity.CoreScanState.DISCOVERING.name)
        try {
            // Full/reconciliation enumeration is replayed idempotently under the same run identity.
            // Incremental journal acknowledgements remain leased until publication and are likewise replay-safe.
            requireScanStagingDao().deleteByScanId(scanId)
            block(scanId, generation, startedAtMs)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            dao.markTerminal(
                scanId,
                ScanRunEntity.STATUS_ABORTED,
                System.currentTimeMillis(),
                "cancelled",
            )
            // Keep partial staging under the same reserved identity. Resume may replay enumeration
            // idempotently and merge those rows instead of allocating another run/generation.
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

    /** Core transactions write only this compact handoff; no Stage/thumbnail task is created here. */
    private suspend fun enqueueEnhancementOutbox(
        entities: List<MediaEntity>,
        scanId: String,
        now: Long,
    ) {
        val dao = enhancementOutboxDao ?: return
        if (entities.isEmpty()) return
        val scope = currentPipelineScope()
        val analysisEnabled = pluginPipeline?.requiredStageIds?.isNotEmpty() == true
        dao.enqueueAll(
            entities.map { entity ->
                EnhancementOutboxEntity(
                    scanId = scanId,
                    profileId = profileId,
                    filePath = entity.filePath,
                    sourceVersion = sourceVersion(entity),
                    mediaType = entity.mediaType.name,
                    pipelineScope = scope,
                    enqueueAnalysis = analysisEnabled && entity.mediaType == MediaType.IMAGE,
                    enqueueThumbnail = true,
                    parentPath = entity.parentPath,
                    createdAt = now,
                    updatedAt = now,
                )
            },
        )
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
        emit: suspend (List<MediaStoreIndexedItem>) -> Unit,
    ) {
        val compiledIgnore = IgnorePatternMatcher.compile(ignorePatterns)
        val batch = ArrayList<MediaStoreIndexedItem>(ENUMERATION_BATCH_SIZE)
        suspend fun add(item: MediaStoreIndexedItem) {
            batch += item
            if (batch.size == ENUMERATION_BATCH_SIZE) {
                emit(batch.toList())
                batch.clear()
            }
        }
        suspend fun enumerate(
            uri: android.net.Uri,
            projection: Array<String>,
            type: MediaType,
            volumeName: String,
        ) {
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
                val idCol = it.getColumnIndexSafe(MediaStore.MediaColumns._ID)
                while (it.moveToNext()) {
                    if (idCol < 0 || dataCol < 0) continue
                    val mediaStoreId = it.getLong(idCol)
                    val path = it.getString(dataCol) ?: continue
                    val file = File(path)
                    if (!file.exists() || !isUnderAnyRoot(path, roots) || IgnorePatternMatcher.matchesAny(file.name, compiledIgnore)) continue
                    add(
                        MediaStoreIndexedItem(
                            identity = MediaStoreIdentity(volumeName, type, mediaStoreId),
                            item = MediaItem(
                                id = java.util.UUID.nameUUIDFromBytes(path.toByteArray()).toString(),
                                filePath = path,
                                fileName = if (nameCol >= 0) it.getString(nameCol) ?: file.name else file.name,
                                type = type,
                                capturedAt = Instant.ofEpochMilli(if (takenCol >= 0) it.getLong(takenCol) else 0L),
                                modifiedAt = Instant.ofEpochMilli(if (modifiedCol >= 0) it.getLong(modifiedCol) * 1000 else file.lastModified()),
                                fileSize = if (sizeCol >= 0) it.getLong(sizeCol) else file.length(),
                                mimeType = if (mimeCol >= 0) it.getString(mimeCol) ?: "" else "",
                                width = if (widthCol >= 0) it.getInt(widthCol) else 0,
                                height = if (heightCol >= 0) it.getInt(heightCol) else 0,
                                durationMs = if (durationCol >= 0) it.getLong(durationCol) else 0L,
                                // MediaStore 经纬度列已弃用且在新系统中不可靠；后续 File/EXIF 扫描负责补齐位置。
                                latitude = null,
                                longitude = null,
                                orientation = if (orientationCol >= 0) it.getInt(orientationCol) else 0,
                            ),
                        ),
                    )
                }
            }
        }
        val volumeName = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.pathSegments.firstOrNull()
            ?: "external"
        enumerate(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageProjection, MediaType.IMAGE, volumeName)
        enumerate(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoProjection, MediaType.VIDEO, volumeName)
        if (batch.isNotEmpty()) emit(batch.toList())
    }

    /** 计算持久化文件头指纹；协议由 [HeadFingerprint] 统一定义。 */
    fun computeFingerprintHead(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.isFile) return null
            HeadFingerprint.compute(file)
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
    private fun MediaItem.toEntity(runCache: ScanRunCache? = null): MediaEntity {
        val capturedAtMs = capturedAt.toEpochMilli()
        val modifiedAtMs = modifiedAt.toEpochMilli()
        val indexedAtMs = System.currentTimeMillis()
        val fingerprint = runCache?.fingerprintHead(filePath) ?: computeFingerprintHead(filePath)

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
private data class MediaStoreIndexedItem(
    val identity: MediaStoreIdentity,
    val item: MediaItem,
)

private data class BatchCommitCount(
    val inserted: Int,
    val updated: Int,
    val affectedDirectoryPaths: Set<String>,
)

data class ScanCommitResult(
    val scanId: String,
    val scanType: String,
    val startedAtMs: Long,
    val indexed: Int,
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val enhancementOutboxCount: Int = 0,
    val affectedDirectoryPaths: Set<String> = emptySet(),
)

data class MediaChangeDiscoveryResult(
    val observed: Int,
    val journaled: Int,
) {
    val hasChanges: Boolean get() = journaled > 0
}

data class IncrementalResult(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val elapsedMs: Long,
    val affectedDirectoryPaths: Set<String> = emptySet(),
    val reconciliationRequired: Boolean = false,
)

private data class DeltaUpsert(
    val stableKey: String,
    val lookup: ResolvedMediaStoreItem,
    val entity: MediaEntity,
    val previous: MediaEntity?,
    val changed: Boolean,
    val pathChanged: Boolean,
)

private data class DeltaBatchCommit(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val deletedStableKeys: List<String>,
    val affectedDirectoryPaths: Set<String>,
    val reconciliationRequired: Boolean,
) {
    val changedCount: Int get() = inserted + updated + deleted
}

private data class ChangeAcknowledgement(
    val leaseToken: String,
    val deletedStableKeys: List<String>,
)

private data class DeltaDrainResult(
    val result: ScanCommitResult,
    val acknowledgements: List<ChangeAcknowledgement>,
)