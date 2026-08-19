package com.renyxin.localalbum.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N1 源码架构守卫：阻止运行时层重新引入整库 DAO 读取，并锁定扫描 staging 路径。
 * 仅检查项目相对目录；不依赖开发机绝对路径或当前工作目录恰好位于仓库根目录。
 */
class BoundedDataAccessArchitectureTest {
    private val projectRoot: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { current ->
            current.parentFile?.takeUnless { it == current }
        }.firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: error("无法从工作目录定位 app/src/main/java")
    }

    @Test
    fun `runtime layers do not call unbounded media face or embedding DAO APIs`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val guardedRoots = listOf(
            "com/renyxin/localalbum/ui",
            "com/renyxin/localalbum/data/repo",
            "com/renyxin/localalbum/data/worker",
            "com/renyxin/localalbum/core/index",
        ).map { File(sourceRoot, it) }
        val forbidden = Regex(
            "\\b(?:mediaDao|faceDao|embeddingDao)\\s*(?:\\?\\.|\\.)\\s*" +
                "(?:getAll|getAllFlow|getAllPaths|getImagePaths|getAllTimelineItems)\\s*\\(",
        )

        val violations = guardedRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line.substringBefore("//"))) {
                        "${file.relativeTo(projectRoot).path}:${index + 1}"
                    } else null
                }
            }

        assertTrue("发现运行时整库 DAO 调用: $violations", violations.isEmpty())
    }

    @Test
    fun `regular face assignment reads only active persisted prototypes`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val fullSourceRoot = File(projectRoot, "app/src/full/java")
        val assigner = File(
            sourceRoot,
            "com/renyxin/localalbum/core/analysis/IncrementalFaceClusterAssigner.kt",
        ).readText()
        assertTrue("常规路径必须读取 active generation 原型", assigner.contains("activePrototypesAfter("))
        listOf(
            "getAllForLegacyExport(",
            "getClusteredAfter(",
            "getMaintenanceBatchAfter(",
            "getClusterRepresentatives(",
            "getClustered(",
        ).forEach { forbidden ->
            assertFalse("常规人物分配器调用 FaceDao 全量/维护接口: $forbidden", assigner.contains(forbidden))
        }
        val stages = listOf("FaceStage.kt").map {
            File(fullSourceRoot, "com/renyxin/localalbum/core/pipeline/stages/$it").readText()
        }
        assertTrue("常规 FaceStage 不得触发人物维护", stages.none { it.contains("FaceClusterMaintenanceWorker") })
    }

    @Test
    fun `legacy full reads are named deprecated and confined to exporter`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val legacyCall = Regex("\\.getAllForLegacyExport\\s*\\(")
        val callSites = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { legacyCall.containsMatchIn(it.readText()) }
            .map { it.relativeTo(projectRoot).path.replace('\\', '/') }
            .toList()

        assertTrue(
            "legacy 全量读取越出 DatabaseExporter: $callSites",
            callSites.all { it == "app/src/main/java/com/renyxin/localalbum/data/backup/DatabaseExporter.kt" },
        )
        assertTrue("DatabaseExporter 应明确使用 legacy 接口", callSites.isNotEmpty())

        listOf("MediaDao.kt", "FaceDao.kt", "EmbeddingDao.kt").forEach { name ->
            val dao = File(sourceRoot, "com/renyxin/localalbum/data/db/dao/$name").readText()
            assertTrue("$name 缺少 legacy-only 命名", dao.contains("getAllForLegacyExport"))
            assertTrue("$name legacy 接口缺少 Deprecated", dao.contains("@Deprecated"))
        }
    }

    @Test
    fun `scan types keep full traversal out of changed set incremental`() {
        val indexerFile = File(
            projectRoot,
            "app/src/main/java/com/renyxin/localalbum/core/index/HybridIndexer.kt",
        )
        assertNotNull(indexerFile)
        val source = indexerFile.readText()

        val fullScan = source.substringAfter("suspend fun fullScan(")
            .substringBefore("suspend fun reconciliationScan(")
        val reconciliationScan = source.substringAfter("suspend fun reconciliationScan(")
            .substringBefore("suspend fun incrementalScan(")
        val incrementalScan = source.substringAfter("suspend fun incrementalScan(")
            .substringBefore("private suspend fun finalizeCoreRun(")
        assertTrue("fullScan 未进入 staging", fullScan.contains("scanViaStaging("))
        assertTrue("reconciliationScan 未进入 staging", reconciliationScan.contains("scanViaStaging("))
        assertTrue("incrementalScan 未消费 changed-set journal", incrementalScan.contains("drainChangedSet("))
        listOf(
            "scanViaStaging(",
            "enumerateMediaStoreBatches(",
            "enumerateMediaBatches(",
            "getPathsOutsideGeneration(",
            "getReferencePathsOutsideGeneration(",
        ).forEach { forbidden ->
            assertFalse("Incremental 恢复了全根或 generation 对账路径: $forbidden", incrementalScan.contains(forbidden))
        }

        listOf(
            "fullScanGuarded(",
            "incrementalScanGuarded(",
            "queryMediaStore(",
            "queryFileSystem(",
            "collectMediaItems(",
            "mergeAndDeduplicate(",
            "getModifiedTimeMap(",
            "scanRootsSync(",
        ).forEach { forbidden ->
            assertFalse("HybridIndexer 残留旧全量物化路径: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `incremental publication is bounded and never rebuilds recommendations`() {
        val repository = File(
            projectRoot,
            "app/src/main/java/com/renyxin/localalbum/data/repo/AlbumRepository.kt",
        ).readText()
        val incrementalSnapshot = repository.substringAfter("private suspend fun publishIncrementalAlbumSnapshot(")
            .substringBefore("private suspend fun publishCommittedAlbumSnapshot(")
        assertTrue(
            "增量快照必须只查询受影响目录",
            incrementalSnapshot.contains("getDirectorySummariesForPaths("),
        )
        assertFalse(
            "增量快照不得聚合全库目录",
            incrementalSnapshot.contains("getDirectorySummaries()"),
        )
        assertTrue(
            "增量树发布必须关闭推荐重建",
            incrementalSnapshot.contains("rebuildRecommendations = false"),
        )

        val scanCompletion = repository.substringAfter("val changedCount = publishedCoreResult?.let")
            .substringBefore("ScanExecutionResult.Completed")
        assertTrue("无变化增量必须跳过统计刷新", scanCompletion.contains("changedCount == null || changedCount > 0"))
        assertFalse(
            "普通增量不得重建全库推荐池",
            scanCompletion.contains("rebuildRecommendationPool("),
        )
        assertTrue(
            "普通增量推荐必须转交可取消增强 Worker",
            scanCompletion.contains("RecommendationRefreshWorker.enqueue("),
        )
        assertTrue(
            "所有已发布核心扫描的推荐都必须携带有界受影响目录",
            scanCompletion.contains("publishedCoreResult?.affectedDirectoryPaths.orEmpty()"),
        )
    }

    @Test
    fun `pipeline execution is isolated from capability registration and legacy builtin factory`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val pipeline = File(
            sourceRoot,
            "com/renyxin/localalbum/core/pipeline/PluginAnalysisPipeline.kt",
        ).readText()
        val factory = File(
            sourceRoot,
            "com/renyxin/localalbum/core/pipeline/AnalysisStageFactory.kt",
        ).readText()

        assertFalse("Pipeline 不得直接依赖能力注册表", pipeline.contains("CapabilityRegistryV2"))
        assertFalse("Pipeline 不得遍历槽位决定产品行为", pipeline.contains("slotMetadataList"))
        listOf(
            "BuiltinFaceStage",
            "BuiltinSceneStage",
            "BuiltinSemanticStage",
            "BuiltinQualityStage",
            "BuiltinOcrStage",
        ).forEach { forbidden ->
            assertFalse("Pipeline 恢复了硬编码兼容工厂: $forbidden", pipeline.contains(forbidden))
        }
        assertTrue("Pipeline 必须只从已解析计划创建", pipeline.contains("plan: AnalysisStagePlan"))
        assertTrue("Provider 到 Stage 的映射必须集中在显式工厂", factory.contains("createBinding(stageId"))
        assertFalse("显式工厂不得遍历所有注册槽位", factory.contains("slotMetadataList"))
    }

    @Test
    fun `restore publishes a local baseline but cannot bypass explicit rebuild admission`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val container = File(sourceRoot, "com/renyxin/localalbum/AppContainer.kt").readText()
        val importer = File(
            sourceRoot,
            "com/renyxin/localalbum/data/backup/DatabaseImporter.kt",
        ).readText()

        assertTrue(
            "组合根必须使用编译期 edition policy",
            container.contains("editionFeatures.scanFeaturePolicy"),
        )
        assertFalse(
            "组合根不得重新硬编码 Full policy",
            container.contains("FullScanFeaturePolicy"),
        )
        assertTrue(
            "导入切表事务必须重建本机主页基线并标记待显式校验",
            importer.contains("publishImportedBaselineAndRequireValidation(db)") &&
                importer.contains("DELETE FROM home_media_snapshot") &&
                importer.contains("'PLACEHOLDER'") &&
                importer.contains("'NEEDS_REBUILD'") &&
                importer.contains("'backup_import_requires_validation'"),
        )
        assertFalse(
            "导入不得预建无 activeRunId 的孤儿恢复任务",
            importer.contains("postRestoreTaskSeeder") ||
                importer.contains("INSERT OR IGNORE INTO enhancement_outbox") ||
                importer.contains("INSERT OR IGNORE INTO analysis_tasks") ||
                importer.contains("INSERT OR IGNORE INTO thumbnail_tasks"),
        )
        assertFalse(
            "恢复器不得保留 edition、pipeline scope 或旧 Full 默认判断",
            importer.contains("restoredAnalysisPipelineScope") ||
                importer.contains("restoredAnalysisEnabled") ||
                importer.contains("restoredProfileId") ||
                importer.contains("'core:v1'"),
        )
        val switchGenericStaging = importer.substringAfter("private suspend fun switchGenericStaging(")
            .substringBefore("private fun insertJsonRow(")
        val maintenanceClear = switchGenericStaging.indexOf("\"maintenance_runs\"")
        val backupInsertion = switchGenericStaging.indexOf("BackupContract.tables.filter")
        assertTrue(
            "complete 覆盖恢复必须在插入备份行前整体清空 maintenance_runs",
            maintenanceClear >= 0 && backupInsertion >= 0 && maintenanceClear < backupInsertion,
        )
        assertFalse(
            "恢复后不得保留备份未声明的 maintenance run",
            switchGenericStaging.contains("DELETE FROM maintenance_runs WHERE taskType != 'FACE_PROTOTYPES'"),
        )
        val backupContract = File(
            sourceRoot,
            "com/renyxin/localalbum/data/backup/BackupContract.kt",
        ).readText()
        assertTrue(
            "complete backup 的 deletion intent capability 必须属于已知能力",
            backupContract.substringAfter("val knownCapabilities")
                .substringBefore("data class Table")
                .contains("CAP_DELETION_INTENT"),
        )
    }

    @Test
    fun `scene and quality automatic admission is report bound and persistence is batched`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val policy = File(
            sourceRoot,
            "com/renyxin/localalbum/core/pipeline/ScanFeaturePolicy.kt",
        ).readText()
        val frozenReport = File(
            projectRoot,
            "plans/evidence/lite-phase7-enhancement-admission-report.json",
        )
        val stagePolicy = File(
            sourceRoot,
            "com/renyxin/localalbum/core/pipeline/StageInclusionPolicy.kt",
        ).readText()
        val scene = File(
            sourceRoot,
            "com/renyxin/localalbum/core/pipeline/stages/SceneStage.kt",
        ).readText()
        val quality = File(
            sourceRoot,
            "com/renyxin/localalbum/core/pipeline/stages/QualityStage.kt",
        ).readText()

        val litePolicy = policy.substringAfter("object LiteScanFeaturePolicy")
        assertTrue("Lite 自动增强必须 fail-closed", litePolicy.contains("AnalysisPlanType.ENHANCEMENT"))
        assertTrue("Lite policy 必须绑定准入报告身份", litePolicy.contains("enhancementAdmissionIdentity"))
        assertTrue("冻结报告制品缺失", frozenReport.isFile)
        val reportHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(frozenReport.readBytes())
            .joinToString("") { "%02x".format(it) }
        assertTrue(
            "Lite policy 必须绑定冻结报告文件 SHA-256",
            litePolicy.contains("reportHash = \"sha256:$reportHash\""),
        )
        val report = frozenReport.readText()
        assertTrue("Scene 冻结结论必须为 AUTO_DISABLED", report.contains("\"stage\": \"SCENE\"") && report.contains("\"decision\": \"AUTO_DISABLED\""))
        assertTrue("Quality 冻结报告必须保留解码复用缺口", report.contains("bitmap_decode_reuse_unverified"))
        assertTrue("增强 scope 必须持久化 report identity", stagePolicy.contains("scopePart()"))
        listOf(scene, quality).forEach { stage ->
            assertTrue("Scene/Quality 必须使用批量 DAO", stage.contains("ENHANCEMENT_WRITE_BATCH_SIZE"))
            assertFalse("Scene/Quality 不得逐图直接写数据库", stage.contains("mediaDao.setSceneType(path"))
            assertFalse("Scene/Quality 不得逐图直接写数据库", stage.contains("mediaDao.setQualityScore(path"))
            assertTrue("Scene/Quality 必须记录独立 stage-only operation", stage.contains("metricOperation = \"pipeline:file:\$stageId\""))
        }
        assertTrue("Quality 未证明复用解码时必须显式报告 false", quality.contains("\"bitmapDecodeReused\" to \"false\""))
    }

    @Test
    fun `core commit writes only enhancement outbox and workers honor post-core barrier`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val indexer = File(
            sourceRoot,
            "com/renyxin/localalbum/core/index/HybridIndexer.kt",
        ).readText()
        val deltaCommit = indexer.substringAfter("private suspend fun commitDeltaBatch(")
            .substringBefore("private fun preserveIndexedState(")
        val stagedCommit = indexer.substringAfter("private suspend fun commitStagedBatch(")
            .substringBefore("private fun requireScanStagingDao(")
        listOf(deltaCommit, stagedCommit).forEach { commit ->
            assertTrue("核心事务必须写 enhancement outbox", commit.contains("enqueueEnhancementOutbox("))
            assertFalse("核心事务不得直接创建分析任务", commit.contains("enqueueAnalysisTasks("))
            assertFalse("核心事务不得直接创建缩略图任务", commit.contains("thumbnailTaskDao"))
        }

        val handoff = File(
            sourceRoot,
            "com/renyxin/localalbum/data/worker/EnhancementHandoffWorker.kt",
        ).readText()
        val outboxDao = File(
            sourceRoot,
            "com/renyxin/localalbum/data/db/dao/EnhancementOutboxDao.kt",
        ).readText()
        val thumbnailTaskDao = File(
            sourceRoot,
            "com/renyxin/localalbum/data/db/dao/ThumbnailTaskDao.kt",
        ).readText()
        val analysisTaskDao = File(
            sourceRoot,
            "com/renyxin/localalbum/data/db/dao/AnalysisTaskDao.kt",
        ).readText()
        assertTrue("交接 Worker 必须有界领取", handoff.contains("limit = BATCH_SIZE"))
        assertTrue("交接 Worker 必须在任意核心扫描活跃时退避", handoff.contains("isCoreScanActive()"))
        assertTrue("交接 Worker 必须进入严格自动资源闸门", handoff.contains("tryWithAutomaticEnhancement"))
        assertTrue("交接取消必须立即释放租约", handoff.contains("releaseLease("))
        assertTrue("交接必须在单独 Worker 创建分析任务", handoff.contains("enqueueAllForScan("))
        assertTrue("交接必须在单独 Worker 创建缩略图任务", handoff.contains("thumbnailTaskDao().enqueueAll("))
        assertTrue("交接正常续批必须追加后继而非消耗失败退避", handoff.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertTrue("交接必须区分当前可领取任务与失败等待任务", outboxDao.contains("hasClaimableEntries"))
        assertTrue("outbox 领取必须要求核心完成", outboxDao.contains("coreScanState = 'COMPLETED'"))
        assertTrue("outbox 领取必须要求快照已发布", outboxDao.contains("indexAvailability = 'PUBLISHED'"))
        listOf(
            "enhancement outbox" to outboxDao,
            "缩略图任务" to thumbnailTaskDao,
            "分析任务" to analysisTaskDao,
        ).forEach { (label, daoSource) ->
            assertTrue(
                "$label 失败统计必须由 Room Flow 实时驱动",
                daoSource.contains("observeFailedCount()") &&
                    daoSource.contains("status = 'FAILED'"),
            )
        }

        val thumbnailWorker = File(
            sourceRoot,
            "com/renyxin/localalbum/data/worker/ThumbnailWorker.kt",
        ).readText()
        assertFalse(
            "缩略图 Worker 不得启动时全库 seed",
            thumbnailWorker.contains("getMissingThumbnailsAfter("),
        )
        assertTrue("后台缩略图必须有延迟入口", thumbnailWorker.contains("enqueueBackground("))
        assertTrue("后台缩略图必须进入严格资源闸门", thumbnailWorker.contains("tryWithAutomaticEnhancement"))
        assertTrue("可视缩略图必须走独立有界交互闸门", thumbnailWorker.contains("withInteractiveThumbnail"))
        assertTrue("缩略图取消必须立即释放租约", thumbnailWorker.contains("releaseLease("))
        val thumbnailDispatcher = File(
            sourceRoot,
            "com/renyxin/localalbum/data/repo/ThumbnailWakeDispatcher.kt",
        ).readText()
        assertTrue(
            "交互和后台缩略图必须使用两个固定且不同的 unique pump",
            thumbnailDispatcher.contains("thumbnail_interactive_pump") &&
                thumbnailDispatcher.contains("thumbnail_automatic_pump") &&
                thumbnailDispatcher.contains("awaitTerminal()"),
        )

        val analysisWorker = File(
            sourceRoot,
            "com/renyxin/localalbum/data/worker/AnalysisWorker.kt",
        ).readText()
        assertTrue("分析 Worker 必须在核心扫描活跃时退避", analysisWorker.contains("isCoreScanActive()"))
        assertTrue("分析推理必须持有严格自动资源闸门", analysisWorker.contains("tryWithAutomaticEnhancement"))
        assertTrue("核心抢占必须保留恢复语义", analysisWorker.contains("cancelForCorePreemption"))
        assertTrue("分析正常续批必须追加后继而非消耗失败退避", analysisWorker.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
        assertTrue(
            "分析阶段选择必须跳过尚在失败退避的前序 scope，并遵守用户暂停",
            analysisWorker.contains("dao.countClaimable(") &&
                analysisWorker.contains("now = recoveryNow") &&
                analysisWorker.contains("includeUserTasks = includeUserTasks") &&
                analysisWorker.contains("admittedScanId = admittedScanId"),
        )

        val repository = File(
            sourceRoot,
            "com/renyxin/localalbum/data/repo/AlbumRepository.kt",
        ).readText()
        assertTrue("核心扫描必须持有严格资源闸门", repository.contains("EnhancementResourceGate.withCoreScan("))
        assertTrue(
            "备份维护 helper 必须独占自动与交互资源 lane",
            repository.contains("EnhancementResourceGate.withExclusiveMaintenance("),
        )
        val exporterBody = repository.substringAfter("suspend fun exportDatabase(")
            .substringBefore("suspend fun importDatabase(")
        assertTrue(
            "导出必须同时与核心扫描和增强资源互斥",
            exporterBody.contains("withExclusiveBackupMaintenance"),
        )
        val importerHandoff = repository.substringAfter("suspend fun importDatabase(")
            .substringBefore("fun validateImportFile(")
        assertTrue(
            "导入切表必须同时与核心扫描和增强资源互斥",
            importerHandoff.contains("withExclusiveBackupMaintenance"),
        )
        assertTrue(
            "导入只能持久化校验需求，不得自动遍历扫描根或绕过流水线释放 outbox",
            importerHandoff.contains("markRebuildRequired") &&
                importerHandoff.contains("libraryPipelineCoordinator?.wake()") &&
                !importerHandoff.contains("EnhancementHandoffWorker::enqueue") &&
                !importerHandoff.contains("rescan()"),
        )

        val fullSourceRoot = File(projectRoot, "app/src/full/java")
        val maintenanceWorkers = mapOf(
            "人物原型维护" to "FaceClusterMaintenanceWorker.kt",
            "语义回填维护" to "SemanticMaintenanceWorker.kt",
            "语义主题维护" to "SemanticClusterMaintenanceWorker.kt",
        )
        maintenanceWorkers.forEach { (label, fileName) ->
            val worker = File(
                fullSourceRoot,
                "com/renyxin/localalbum/data/worker/$fileName",
            ).readText()
            assertTrue("$label 必须保留编译 edition 防御", worker.contains("EditionConfiguration.features"))
            assertTrue("$label 必须在核心扫描活跃时退避", worker.contains("isCoreScanActive()"))
            assertTrue("$label 必须按有界区段进入自动资源闸门", worker.contains("tryWithAutomaticEnhancement"))
        }
        val semanticCluster = File(
            fullSourceRoot,
            "com/renyxin/localalbum/data/worker/SemanticClusterMaintenanceWorker.kt",
        ).readText()
        assertTrue(
            "跨页语义主题快照必须在核心/备份请求后失效",
            semanticCluster.contains("currentExclusiveEpoch"),
        )

        val container = File(sourceRoot, "com/renyxin/localalbum/AppContainer.kt").readText()
        val pipelineCoordinator = File(
            sourceRoot,
            "com/renyxin/localalbum/data/repo/LibraryPipelineCoordinator.kt",
        ).readText()
        val automaticAnalysisAdmission = pipelineCoordinator
            .substringAfter("private suspend fun startOrFinishAnalysisLocked")
            .substringBefore("private suspend fun updateAnalysisProgressLocked")
        assertTrue(
            "自动分析阶段必须追加交接和分析 successor",
            automaticAnalysisAdmission.contains("EnhancementHandoffWorker.appendSuccessor(context)") &&
                automaticAnalysisAdmission.contains("AnalysisWorker.appendSuccessor(context)"),
        )
        assertFalse(
            "自动分析阶段不得使用外部 KEEP 唤醒入口",
            automaticAnalysisAdmission.contains("EnhancementHandoffWorker.enqueue(context)") ||
                automaticAnalysisAdmission.contains("AnalysisWorker.enqueue(context)"),
        )
        val thumbnailGateAdmission = pipelineCoordinator
            .substringAfter("private suspend fun advanceThumbnailGateLocked")
            .substringBefore("private suspend fun updateThumbnailProgressLocked")
        assertFalse(
            "后台缩略图 gate 不得按交互 participant 数追加交互 successor",
            thumbnailGateAdmission.contains("replayInteractiveWake") ||
                thumbnailGateAdmission.contains("countInteractiveActive") ||
                thumbnailGateAdmission.contains("interactiveActive > 0"),
        )
        assertTrue(
            "后台 gate 只能在交互 completion 修复 scan target 时追加一次后台 successor",
            thumbnailGateAdmission.contains("repairedTargets && expectedScanId == null") &&
                thumbnailGateAdmission.contains("ThumbnailWorker.appendBackgroundSuccessor(context)"),
        )
        val failedRetryAdmission = pipelineCoordinator.substringAfter("suspend fun retryFailedThumbnails()")
        val analysisResumePrefs = File(
            sourceRoot,
            "com/renyxin/localalbum/data/worker/AnalysisResumePrefs.kt",
        ).readText()
        assertTrue(
            "手动缩略图失败重试必须转换成交互 ownership 并消费持久 generation wake 权",
            failedRetryAdmission.contains("retryAllFailedInteractive") &&
                failedRetryAdmission.contains("if (wakeRequired)") &&
                failedRetryAdmission.contains("ThumbnailWorker.replayInteractiveWake"),
        )
        assertTrue(
            "手动分析失败重试必须在 ownership 转换前同步持久化用户恢复标记",
            failedRetryAdmission.contains("AnalysisResumePrefs.resumeUserWork(context)") &&
                failedRetryAdmission.indexOf("AnalysisResumePrefs.resumeUserWork(context)") <
                failedRetryAdmission.indexOf("retryFailedAsUserForScopes") &&
                failedRetryAdmission.contains("analysisPipeline().claimableTaskScopes") &&
                failedRetryAdmission.contains("countFailedForScopes(scopes)") &&
                failedRetryAdmission.contains("AnalysisWorker.enqueue(context)") &&
                analysisResumePrefs.contains("fun resumeUserWork(context: Context)") &&
                analysisResumePrefs.contains(".commit()"),
        )
        assertTrue(
            "失败 outbox 重试必须在事务中展开为 null-scan 用户任务并原子完成",
            failedRetryAdmission.contains("retryFailedEnhancementOutbox") &&
                failedRetryAdmission.contains("database.withTransaction") &&
                failedRetryAdmission.contains("getFailedForUserRetry") &&
                failedRetryAdmission.contains("scanId = null") &&
                failedRetryAdmission.contains("markRetriedForUser") &&
                failedRetryAdmission.contains("recordUserRetryFailure") &&
                failedRetryAdmission.contains("ThumbnailWorker.replayInteractiveWake") &&
                failedRetryAdmission.contains("AnalysisWorker.enqueue(context)"),
        )
        assertTrue(
            "失败重试 UI 准入必须观察协调器的同一持久状态规则",
            pipelineCoordinator.contains("val userTaskRetryAllowed: Flow<Boolean>") &&
                pipelineCoordinator.contains("state?.let(::canAdmitUserTasks) ?: false") &&
                failedRetryAdmission.contains("if (!canAdmitUserTasks(state))"),
        )
        val pipelineWorker = File(
            sourceRoot,
            "com/renyxin/localalbum/data/worker/LibraryPipelineWorker.kt",
        ).readText()
        assertTrue(
            "启动恢复必须覆盖持久 thumbnail-only 阶段且不得绕过流水线门禁",
            container.contains("libraryPipelineCoordinator.wake()") &&
                pipelineCoordinator.contains("stage.isThumbnail") &&
                pipelineWorker.contains("ThumbnailWorker.appendBackgroundSuccessor"),
        )
        assertTrue(
            "启动恢复必须从 Room lane level 重放交互缩略图 wake",
            pipelineCoordinator.contains("thumbnailTaskDao().laneState(") &&
                pipelineCoordinator.contains("STATE_PENDING") &&
                pipelineCoordinator.contains("if (admission.thumbnails)") &&
                pipelineCoordinator.contains("ThumbnailWorker.replayInteractiveWake"),
        )

        val viewModel = File(
            sourceRoot,
            "com/renyxin/localalbum/ui/vm/AlbumViewModel.kt",
        ).readText()
        val gridThumbnailRequest = viewModel
            .substringAfter("fun requestGridThumbnails")
            .substringBefore("suspend fun resolvePreviewThumbnail")
        assertTrue(
            "主页可见/预取窗口必须一次提交批量缩略图请求",
            gridThumbnailRequest.contains("repository.requestThumbnails("),
        )
        assertFalse(
            "主页可见/预取窗口不得逐媒体调用单项缩略图调度",
            gridThumbnailRequest.contains("repository.requestThumbnail("),
        )
        val scanControls = File(
            sourceRoot,
            "com/renyxin/localalbum/ui/screens/settings/ScanControlSection.kt",
        ).readText()
        val rescan = viewModel.substringAfter("fun rescan()")
            .substringBefore("fun forceReanalyzeAll()")
        assertFalse("UI 扫描完成不得直接调度缩略图", rescan.contains("ThumbnailWorker.enqueue("))
        assertTrue("人物页必须直接观察 Room-backed Repository Flow", viewModel.contains("repository.faceClusters"))
        assertTrue(
            "Repository 必须暴露三类 Room-backed 失败统计与持久重试准入",
            repository.contains("val failedThumbnailCount: Flow<Int>") &&
                repository.contains("val failedAnalysisTaskCount: Flow<Int>") &&
                repository.contains("val failedEnhancementOutboxCount: Flow<Int>") &&
                repository.contains("libraryPipelineCoordinator?.userTaskRetryAllowed"),
        )
        assertTrue(
            "设置页必须显示三类失败并提供独立手动重试入口",
            scanControls.contains("failedThumbnailCount") &&
                scanControls.contains("failedAnalysisTaskCount") &&
                scanControls.contains("failedEnhancementOutboxCount") &&
                scanControls.contains("albumViewModel::retryFailedThumbnails") &&
                scanControls.contains("albumViewModel::retryFailedAnalysis") &&
                scanControls.contains("albumViewModel::retryFailedEnhancementOutbox") &&
                scanControls.contains("retryAllowed && !anyRetryRunning"),
        )

        val fullUi = File(
            projectRoot,
            "app/src/full/java/com/renyxin/localalbum/edition/EditionUiContribution.kt",
        ).readText()
        assertFalse("人物页不得恢复定时快照轮询", fullUi.contains("delay(1_500L)"))
        assertFalse("人物页不得在管道循环中手动刷新快照", fullUi.contains("loadFaceClusters()"))
    }

    @Test
    fun `complete rebuild stays user confirmed and scan scope changes are durably linearized`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val repository = File(
            sourceRoot,
            "com/renyxin/localalbum/data/repo/AlbumRepository.kt",
        ).readText()
        val viewModel = File(
            sourceRoot,
            "com/renyxin/localalbum/ui/vm/AlbumViewModel.kt",
        ).readText()
        val scanControls = File(
            sourceRoot,
            "com/renyxin/localalbum/ui/screens/settings/ScanControlSection.kt",
        ).readText()
        val settingsStore = File(
            sourceRoot,
            "com/renyxin/localalbum/data/prefs/SettingsStore.kt",
        ).readText()
        val settingsRepository = File(
            sourceRoot,
            "com/renyxin/localalbum/data/repo/SettingsRepository.kt",
        ).readText()
        val container = File(sourceRoot, "com/renyxin/localalbum/AppContainer.kt").readText()
        val coordinator = File(
            sourceRoot,
            "com/renyxin/localalbum/data/repo/LibraryPipelineCoordinator.kt",
        ).readText()

        val repositoryRefresh = repository.substringAfter("suspend fun rescan(): Boolean")
            .substringBefore("suspend fun requestFullRebuild()")
        assertTrue(
            "普通刷新必须走仅授权首次扫描/否则只唤醒 journal 的用户扫描入口",
            repositoryRefresh.contains("libraryPipelineCoordinator?.requestUserScan()"),
        )
        assertFalse("普通刷新不得授权完整重建", repositoryRefresh.contains("requestExplicitRebuild"))
        val repositoryRebuild = repository.substringAfter("suspend fun requestFullRebuild()")
            .substringBefore("suspend fun retryFailedThumbnails()")
        assertTrue("Repository 完整重建入口必须委托显式准入", repositoryRebuild.contains("requestExplicitRebuild()"))

        val viewModelRefresh = viewModel.substringAfter("fun rescan()")
            .substringBefore("fun requestFullRebuild()")
        assertTrue("ViewModel 普通刷新必须调用增量刷新入口", viewModelRefresh.contains("repository.rescan()"))
        assertFalse("ViewModel 普通刷新不得转为完整重建", viewModelRefresh.contains("requestFullRebuild"))
        val viewModelRebuild = viewModel.substringAfter("fun requestFullRebuild()")
            .substringBefore("fun forceReanalyzeAll()")
        assertTrue("ViewModel 显式重建必须只提交 Repository 请求", viewModelRebuild.contains("repository.requestFullRebuild()"))
        assertFalse("ViewModel 不得直接触达流水线 DAO", viewModelRebuild.contains("requestExplicitRebuild"))

        val confirmation = scanControls.substringAfter("confirmButton = {")
            .substringBefore("dismissButton = {")
        assertTrue("设置页完整重建必须使用确认对话框", scanControls.contains("AlertDialog("))
        assertTrue("只有确认动作才能调用完整重建", confirmation.contains("albumViewModel.requestFullRebuild()"))
        val controlCard = scanControls.substringAfter("ElevatedCard(")
        val incrementalButton = controlCard.substringAfter("Button(")
            .substringBefore("OutlinedButton(")
        assertTrue("普通设置页按钮必须只检查增量更新", incrementalButton.contains("albumViewModel.rescan()"))
        assertFalse("普通设置页按钮不得调用完整重建", incrementalButton.contains("requestFullRebuild"))
        val rebuildButton = controlCard.substringAfter("OutlinedButton(")
            .substringBefore("Text(\"完整重建图库\")")
        assertTrue("完整重建按钮必须先打开确认状态", rebuildButton.contains("showRebuildConfirmation.value = true"))
        assertFalse("完整重建按钮不得绕过确认直接提交", rebuildButton.contains("requestFullRebuild"))

        fun callSites(call: String): Set<String> = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains(call) }
            .map { it.relativeTo(projectRoot).path.replace('\\', '/') }
            .toSet()

        assertTrue(
            "完整重建 UI 调用链出现了额外入口: ${callSites(".requestFullRebuild(")}",
            callSites(".requestFullRebuild(") == setOf(
                "app/src/main/java/com/renyxin/localalbum/ui/screens/settings/ScanControlSection.kt",
                "app/src/main/java/com/renyxin/localalbum/ui/vm/AlbumViewModel.kt",
            ),
        )
        assertTrue(
            "显式重建协调器出现了额外调用方: ${callSites(".requestExplicitRebuild(")}",
            callSites(".requestExplicitRebuild(") == setOf(
                "app/src/main/java/com/renyxin/localalbum/data/repo/AlbumRepository.kt",
            ),
        )
        assertTrue(
            "rebuildRequested 出现了绕过协调器的写入方: ${callSites(".requestRebuild(")}",
            callSites(".requestRebuild(") == setOf(
                "app/src/main/java/com/renyxin/localalbum/data/repo/LibraryPipelineCoordinator.kt",
            ),
        )

        assertTrue(
            "扫描范围变化必须持久化 DataStore 重放标记",
            settingsStore.contains("stringPreferencesKey(\"pending_scan_scope_change\")") &&
                settingsStore.contains("val pendingScanScopeChangeReason"),
        )
        listOf(
            Triple("setShowNomediaDirectories(", "setShowAnalysisProgressUi(", "nomedia_policy_changed"),
            Triple("addScanRoot(", "removeScanRoot(", "scan_root_added"),
            Triple("removeScanRoot(", "addIgnoreDir(", "scan_root_removed"),
            Triple("addIgnoreDir(", "removeIgnoreDir(", "ignore_rule_added"),
            Triple("removeIgnoreDir(", "acknowledgeScanScopeChange(", "ignore_rule_removed"),
        ).forEach { (start, end, reason) ->
            val mutation = settingsStore.substringAfter("suspend fun $start")
                .substringBefore("suspend fun $end")
            assertTrue("$start 必须在同一 DataStore edit 中写入待重放标记", mutation.contains("store.edit"))
            assertTrue("$start 缺少待重放标记", mutation.contains("KEY_PENDING_SCAN_SCOPE_CHANGE"))
            assertTrue("$start 缺少稳定原因 $reason", mutation.contains("\"$reason\""))
        }
        val acknowledgement = settingsStore.substringAfter("suspend fun acknowledgeScanScopeChange(")
            .substringBefore("private companion object")
        assertTrue(
            "范围变化确认必须条件删除，避免清除并发的新标记",
            acknowledgement.contains("prefs[KEY_PENDING_SCAN_SCOPE_CHANGE] == reason") &&
                acknowledgement.contains("prefs.remove(KEY_PENDING_SCAN_SCOPE_CHANGE)"),
        )

        listOf(
            "addScanRoot(" to "removeScanRoot(",
            "removeScanRoot(" to "addIgnoreDir(",
            "addIgnoreDir(" to "removeIgnoreDir(",
            "removeIgnoreDir(" to "setShowNomediaDirectories(",
            "setShowNomediaDirectories(" to "replayPendingScanScopeChange(",
        ).forEach { (start, end) ->
            val mutation = settingsRepository.substringAfter("suspend fun $start")
                .substringBefore("suspend fun $end")
            assertTrue("$start 必须与扫描配置读取共享互斥锁", mutation.contains("scanScopeMutex.withLock"))
            assertTrue("$start 必须先提交 Room 控制面再确认 DataStore", mutation.contains("submitScanScopeChange("))
        }
        val replay = settingsRepository.substringAfter("suspend fun replayPendingScanScopeChange()")
            .substringBefore("suspend fun <T> withStableScanSettings")
        assertTrue("冷启动重放必须持有扫描范围互斥锁", replay.contains("scanScopeMutex.withLock"))
        assertTrue("冷启动重放必须消费持久标记", replay.contains("pendingScanScopeChangeReason.first()"))
        assertTrue("冷启动重放必须复用正常提交路径", replay.contains("submitScanScopeChange(reason)"))
        val stableSettings = settingsRepository.substringAfter("suspend fun <T> withStableScanSettings")
            .substringBefore("private suspend fun submitScanScopeChange")
        assertTrue("扫描配置读取必须持有扫描范围互斥锁", stableSettings.contains("scanScopeMutex.withLock"))
        assertTrue("扫描配置必须在锁内读取最新快照", stableSettings.contains("block(state.first())"))
        val submitScopeChange = settingsRepository.substringAfter("private suspend fun submitScanScopeChange")
            .substringBefore("suspend fun setShowAnalysisProgressUi")
        val roomCommit = submitScopeChange.indexOf("onScanScopeChanged(reason)")
        val dataStoreAck = submitScopeChange.indexOf("acknowledgeScanScopeChange(reason)")
        assertTrue(
            "范围变化必须先写 Room 控制面，成功后才确认 DataStore 标记",
            roomCommit >= 0 && dataStoreAck > roomCommit,
        )

        val stableReservation = repository.substringAfter("val admitted = withTimeoutOrNull(5000L)")
            .substringBefore("val settings = admitted?.first")
        val stableBoundary = stableReservation.indexOf("settingsRepository.withStableScanSettings")
        val runReservation = stableReservation.indexOf("getOrCreateAdmittedScanRun()")
        assertTrue(
            "扫描设置读取和持久 run 预留必须处于同一线性化边界",
            stableBoundary >= 0 && runReservation > stableBoundary,
        )

        val scopeCallback = container.substringAfter("SettingsRepository(settingsStore) { reason ->")
            .substringBefore("private val mediaSource")
        assertTrue("设置变化必须提交为待重建提示", scopeCallback.contains("markScanScopeChanged(reason)"))
        assertTrue("设置变化提交后必须唤醒已授权的首次阶段", scopeCallback.contains("libraryPipelineCoordinator.wake()"))
        assertFalse("设置变化不得授权完整重建", scopeCallback.contains("requestExplicitRebuild"))
        val startupRecovery = container.substringAfter("fun maybeResumePendingScanChanges()")
            .substringBefore("fun maybeRescanOnForeground()")
        val ensureState = startupRecovery.indexOf("libraryPipelineCoordinator.ensureState()")
        val replayMarker = startupRecovery.indexOf("settingsRepository.replayPendingScanScopeChange()")
        val wakePipeline = startupRecovery.lastIndexOf("libraryPipelineCoordinator.wake()")
        assertTrue(
            "冷启动必须先恢复控制面，再重放 DataStore 标记，最后唤醒持久阶段",
            ensureState >= 0 && replayMarker > ensureState && wakePipeline > replayMarker,
        )
        assertFalse("冷启动恢复不得授权完整重建", startupRecovery.contains("requestExplicitRebuild"))

        val scopeChange = coordinator.substringAfter("suspend fun markScanScopeChanged(")
            .substringBefore("suspend fun startQueuedWorkIfIdle()")
        assertTrue("范围变化必须受流水线互斥锁保护", scopeChange.contains("mutex.withLock"))
        assertTrue("已有基线或 active run 时范围变化只能标记需要重建", scopeChange.contains("dao.requireRebuild("))
        assertFalse("范围变化不得写 rebuildRequested", scopeChange.contains("dao.requestRebuild("))
        val queuedWork = coordinator.substringAfter("private suspend fun startQueuedWorkIfIdleLocked()")
            .substringBefore("suspend fun admittedScanStage()")
        assertTrue("模糊 MediaStore 事件只能标记需要重建", queuedWork.contains("dao.requireRebuild(\"ambiguous_media_store_change\")"))
        assertTrue("只有已持久化的显式请求才能进入重建扫描", queuedWork.contains("if (state.rebuildRequested)"))
        assertTrue(
            "待重建提示不得永久阻塞可精确解析的 MEDIA journal",
            queuedWork.contains("LibraryPipelineStage.NEEDS_REBUILD") &&
                queuedWork.indexOf("hasOutstandingMediaChanges()") >
                queuedWork.indexOf("consumeReconciliationHintAsAdvisory()"),
        )

        val observer = container.substringAfter("fun registerContentObserver()")
            .substringBefore("fun maybeResumePendingScanChanges()")
        val persistJournal = observer.indexOf("hybridIndexer.recordMediaChanges(changes)")
        val notifyCoordinator = observer.indexOf("libraryPipelineCoordinator.onMediaChangesRecorded()")
        assertTrue(
            "ContentObserver 必须先持久化 journal，再通知唯一协调器",
            persistJournal >= 0 && notifyCoordinator > persistJournal,
        )
        assertFalse("ContentObserver 不得直接调度扫描 Worker", observer.contains("ScanWorker.schedule("))
        assertFalse("ContentObserver 不得直接调度流水线 Worker", observer.contains("LibraryPipelineWorker."))

        val pipelineWorker = File(
            sourceRoot,
            "com/renyxin/localalbum/data/worker/LibraryPipelineWorker.kt",
        ).readText()
        val scanWorker = File(
            sourceRoot,
            "com/renyxin/localalbum/data/worker/ScanWorker.kt",
        ).readText()
        assertTrue(
            "外部 level-triggered 流水线唤醒必须用 KEEP 去重",
            pipelineWorker.contains("fun enqueue(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)"),
        )
        assertTrue(
            "阶段完成后的有序 successor 必须使用 APPEND_OR_REPLACE",
            pipelineWorker.contains(
                "fun appendSuccessor(context: Context) = enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)",
            ),
        )
        assertTrue(
            "已准入扫描的内部调度必须追加 successor，避免上一 Worker 退出窗口吞掉唤醒",
            scanWorker.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"),
        )
        assertTrue(
            "扫描 Worker 最终异常必须使用预留身份终结当前 run",
            scanWorker.contains("admittedScanId") &&
                scanWorker.contains("markActiveScanFailed(") &&
                scanWorker.contains("if (runAttemptCount < MAX_RETRIES)"),
        )
        assertTrue(
            "ScanWorker 调度出现了绕过流水线 pump 的调用方: ${callSites("ScanWorker.schedule(")}",
            callSites("ScanWorker.schedule(") == setOf(
                "app/src/main/java/com/renyxin/localalbum/data/worker/LibraryPipelineWorker.kt",
            ),
        )
    }

    @Test
    fun `ML Kit OCR implementation dependency and artifacts are full only`() {
        val mainRoot = File(projectRoot, "app/src/main/java")
        val fullRoot = File(projectRoot, "app/src/full/java")
        val concreteImplementations = listOf(
            "com/renyxin/localalbum/core/analysis/OcrProvider.kt",
            "com/renyxin/localalbum/core/plugin/capability/builtin/MlKitOcrProvider.kt",
            "com/renyxin/localalbum/core/pipeline/stages/OcrStage.kt",
        )
        concreteImplementations.forEach { relativePath ->
            assertFalse("共享源码不得包含 Full-only OCR 实现: $relativePath", File(mainRoot, relativePath).exists())
            assertTrue("Full source set 缺少 OCR 实现: $relativePath", File(fullRoot, relativePath).isFile)
        }

        val container = File(mainRoot, "com/renyxin/localalbum/AppContainer.kt").readText()
        assertTrue(
            "共享组合根必须委托 edition capability contribution",
            container.contains("EditionConfiguration.registerEditionCapabilityProviders("),
        )
        listOf("MlKitOcrProvider", "PaddleOCRProvider", "GLMOcrProvider").forEach { concreteType ->
            assertFalse("共享组合根直接引用 Full-only OCR Provider: $concreteType", container.contains(concreteType))
        }

        val fullComposition = File(
            projectRoot,
            "app/src/full/java/com/renyxin/localalbum/edition/EditionConfiguration.kt",
        ).readText()
        val liteComposition = File(
            projectRoot,
            "app/src/lite/java/com/renyxin/localalbum/edition/EditionConfiguration.kt",
        ).readText()
        assertTrue("Full 组合根必须注册 OCR 槽位", fullComposition.contains("slotId = \"ocr\""))
        assertTrue("Full 组合根必须保留 ML Kit OCR 可选 Provider", fullComposition.contains("MlKitOcrProvider"))
        assertFalse("Lite 组合根不得解析 OCR 具体实现", liteComposition.contains("OcrProvider"))
        assertFalse("Lite 组合根不得解析 ML Kit OCR", liteComposition.contains("MlKitOcrProvider"))

        // Version Catalog 迁移后：坐标/版本定义于 gradle/libs.versions.toml，
        // build 脚本通过 libs.* 访问器引用；守护意图不变（必须 fullImplementation、不得共享 implementation）。
        val buildScript = File(projectRoot, "app/build.gradle.kts").readText()
        val catalog = File(projectRoot, "gradle/libs.versions.toml").readText()
        listOf(
            "mlkit-text-recognition" to "libs.mlkit.text.recognition",
            "mlkit-text-recognition-chinese" to "libs.mlkit.text.recognition.chinese",
        ).forEach { (alias, accessor) ->
            assertTrue(
                "ML Kit OCR 依赖必须声明为 fullImplementation: $accessor",
                buildScript.contains("add(\"fullImplementation\", $accessor)"),
            )
            assertFalse(
                "ML Kit OCR 依赖不得回到共享 implementation: $accessor",
                buildScript.contains("add(\"implementation\", $accessor)"),
            )
            assertTrue(
                "Version Catalog 必须定义 ML Kit OCR 条目: $alias",
                catalog.contains(alias),
            )
        }
        assertTrue(
            "ML Kit OCR 版本必须保持 16.0.1",
            catalog.contains("mlkitTextRecognition = \"16.0.1\""),
        )

        val purposePolicy = File(projectRoot, "scripts/lite-artifact-purpose-policy.json").readText()
        assertTrue(
            "制品策略必须治理 ML Kit OCR 模型资产",
            purposePolicy.contains("\"pattern\": \"assets/mlkit-google-ocr-models/\""),
        )
        assertTrue(
            "制品策略必须治理 ML Kit OCR native runtime",
            purposePolicy.contains("\"pattern\": \"lib/*/libmlkit_google_ocr_pipeline.so\""),
        )
        val releaseGuard = File(projectRoot, "scripts/Generate-LiteReleaseEvidence.ps1").readText()
        assertTrue("制品守卫必须支持 native runtime 通配规则", releaseGuard.contains("\$Path -clike \$pattern"))
    }

    @Test
    fun `full only batch analysis maintenance and UI implementations stay out of lite graph`() {
        val mainRoot = File(projectRoot, "app/src/main/java")
        val fullRoot = File(projectRoot, "app/src/full/java")
        val liteRoot = File(projectRoot, "app/src/lite/java")
        val fullOnlyFiles = listOf(
            "com/renyxin/localalbum/core/pipeline/stages/FaceStage.kt",
            "com/renyxin/localalbum/core/pipeline/stages/SemanticStage.kt",
            "com/renyxin/localalbum/core/pipeline/stages/OcrStage.kt",
            "com/renyxin/localalbum/data/worker/FaceClusterMaintenanceWorker.kt",
            "com/renyxin/localalbum/data/worker/SemanticMaintenanceWorker.kt",
            "com/renyxin/localalbum/data/worker/SemanticClusterMaintenanceWorker.kt",
            "com/renyxin/localalbum/ui/screens/FacesScreen.kt",
            "com/renyxin/localalbum/ui/screens/AiAnalysisPreferencesScreen.kt",
        )
        fullOnlyFiles.forEach { relativePath ->
            assertFalse("共享源码不得包含 Full-only 实现: $relativePath", File(mainRoot, relativePath).exists())
            assertTrue("Full source set 缺少实现: $relativePath", File(fullRoot, relativePath).isFile)
        }

        val stageFactory = File(
            mainRoot,
            "com/renyxin/localalbum/core/pipeline/AnalysisStageFactory.kt",
        ).readText()
        assertTrue("共享 Stage factory 必须委托 edition binding", stageFactory.contains("EditionAnalysisStageBindings.createBinding("))
        listOf("FaceStage", "SemanticStage", "OcrStage").forEach { concreteType ->
            assertFalse("共享 Stage factory 直接解析 Full-only 类型: $concreteType", stageFactory.contains("import com.renyxin.localalbum.core.pipeline.stages.$concreteType"))
        }
        val liteStageBindings = File(
            liteRoot,
            "com/renyxin/localalbum/edition/EditionAnalysisStageBindings.kt",
        ).readText()
        listOf("FaceStage(", "SemanticStage(", "OcrStage(").forEach { forbidden ->
            assertFalse("Lite stage contribution 解析 Full-only 实现: $forbidden", liteStageBindings.contains(forbidden))
        }

        val sharedUi = File(mainRoot, "com/renyxin/localalbum/ui/LocalAlbumApp.kt").readText()
        assertTrue("共享导航宿主必须委托 edition UI contribution", sharedUi.contains("EditionUiContribution.renderDestination("))
        listOf("FacesScreen", "AiAnalysisPreferencesScreen", "Screen.Faces", "Screen.AiAnalysisPreferences").forEach { forbidden ->
            assertFalse("共享导航仍解析 Full-only UI: $forbidden", sharedUi.contains(forbidden))
        }
        val fullUi = File(fullRoot, "com/renyxin/localalbum/edition/EditionUiContribution.kt").readText()
        assertTrue("Full UI contribution 必须注册人物页面", fullUi.contains("FacesScreen("))
        assertTrue("Full UI contribution 必须注册 AI 偏好页面", fullUi.contains("AiAnalysisPreferencesScreen("))
        val liteUi = File(liteRoot, "com/renyxin/localalbum/edition/EditionUiContribution.kt").readText()
        assertTrue("Lite UI destination 集必须为空", liteUi.contains("registeredDestinationIds: Set<String> = emptySet()"))
        listOf("FacesScreen", "AiAnalysisPreferencesScreen", "people-albums", "analysis-preferences").forEach { forbidden ->
            assertFalse("Lite UI contribution 泄漏 Full-only 入口: $forbidden", liteUi.contains(forbidden))
        }

        val sharedSearch = File(mainRoot, "com/renyxin/localalbum/ui/screens/SearchScreen.kt").readText()
        assertTrue("共享搜索 UI 必须消费 edition-neutral 可选模式", sharedSearch.contains("optionalSearchMode: OptionalSearchModeState"))
        listOf("SemanticSearchResult", "SemanticSearchState", "semanticSearchResults", "语义搜索").forEach { forbidden ->
            assertFalse("共享搜索 UI 泄漏 Full-only 语义入口: $forbidden", sharedSearch.contains(forbidden))
        }
        assertTrue("共享导航宿主必须委托 edition 搜索 contribution", sharedUi.contains("EditionSearchContribution.state(albumViewModel)"))
        listOf("semanticSearchResults", "semanticSearchState", "isSemanticMode", "semanticSearch(").forEach { forbidden ->
            assertFalse("共享导航宿主直接绑定语义搜索: $forbidden", sharedUi.contains(forbidden))
        }
        val fullSearch = File(fullRoot, "com/renyxin/localalbum/edition/EditionSearchContribution.kt").readText()
        assertTrue("Full 搜索 contribution 必须映射语义结果", fullSearch.contains("semanticSearchResults"))
        assertTrue("Full 搜索 contribution 必须保留语义入口文案", fullSearch.contains("语义搜索"))
        val liteSearch = File(liteRoot, "com/renyxin/localalbum/edition/EditionSearchContribution.kt").readText()
        assertTrue("Lite 搜索 contribution 必须禁用可选搜索模式", liteSearch.contains("OptionalSearchModeState.DISABLED"))
        listOf("semantic", "Semantic", "语义").forEach { forbidden ->
            assertFalse("Lite 搜索 contribution 泄漏语义入口: $forbidden", liteSearch.contains(forbidden))
        }

        val repository = File(mainRoot, "com/renyxin/localalbum/data/repo/AlbumRepository.kt").readText()
        assertFalse("共享 Repository 不得直接创建人物维护 Worker", repository.contains("FaceClusterMaintenanceWorker"))
        assertTrue("共享 Repository 必须委托 edition 维护 contribution", repository.contains("EditionConfiguration::enqueueFaceClusterMaintenance"))
        val liteComposition = File(liteRoot, "com/renyxin/localalbum/edition/EditionConfiguration.kt").readText()
        assertTrue("Lite 人物维护 contribution 必须为 no-op", liteComposition.contains("fun enqueueFaceClusterMaintenance(context: Context) = Unit"))
        assertFalse("Lite 组合根不得解析人物维护 Worker", liteComposition.contains("FaceClusterMaintenanceWorker"))
    }

    @Test
    fun `lite release guard audits final DEX descriptors fail closed`() {
        val purposePolicy = File(
            projectRoot,
            "scripts/lite-artifact-purpose-policy.json",
        ).readText()
        val releaseGuard = File(
            projectRoot,
            "scripts/Generate-LiteReleaseEvidence.ps1",
        ).readText()

        listOf(
            "com.renyxin.localalbum.ui.screens.FacesScreenKt",
            "com.renyxin.localalbum.ui.screens.AiAnalysisPreferencesScreenKt",
            "com.renyxin.localalbum.data.worker.FaceClusterMaintenanceWorker",
            "com.renyxin.localalbum.data.worker.SemanticMaintenanceWorker",
            "com.renyxin.localalbum.data.worker.SemanticClusterMaintenanceWorker",
            "com.renyxin.localalbum.core.pipeline.stages.FaceStage",
            "com.renyxin.localalbum.core.pipeline.stages.SemanticStage",
            "com.renyxin.localalbum.core.pipeline.stages.OcrStage",
            "com.renyxin.localalbum.core.plugin.capability.builtin.MlKitOcrProvider",
            "com.renyxin.localalbum.core.plugin.capability.builtin.PaddleOCRProvider",
            "com.renyxin.localalbum.core.plugin.model.GLMOcrProvider",
        ).forEach { classRoot ->
            assertTrue("Lite DEX 策略缺少禁止类根: $classRoot", purposePolicy.contains("\"$classRoot\""))
        }
        listOf(
            "com.renyxin.localalbum.core.plugin.capability.FaceProvider",
            "com.renyxin.localalbum.core.analysis.OcrProvider",
        ).forEach { allowedContract ->
            assertFalse("Lite DEX 策略误禁共享/交互能力契约: $allowedContract", purposePolicy.contains("\"$allowedContract\""))
        }

        listOf(
            "Read-R8ForbiddenClassMappings",
            "Read-DexDescriptors",
            "^classes(?:[0-9]+)?\\.dex$",
            "lite_r8_mapping_missing",
            "lite_dex_zero_descriptors",
            "lite_forbidden_dex_class_present",
            "dexDescriptorGuard = [ordered]@{",
        ).forEach { requiredGuardToken ->
            assertTrue("Lite 发布脚本缺少 DEX fail-closed 守卫: $requiredGuardToken", releaseGuard.contains(requiredGuardToken))
        }
    }

    @Test
    fun `thumbnail LRU is metadata based and never uses cache file mtime`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val mediaSource = File(sourceRoot, "com/renyxin/localalbum/data/source/MediaSource.kt").readText()
        val trimmer = File(sourceRoot, "com/renyxin/localalbum/data/repo/ThumbnailCacheTrimmer.kt").readText()
        val cacheDao = File(sourceRoot, "com/renyxin/localalbum/data/db/dao/ThumbnailCacheDao.kt").readText()

        assertFalse("旧文件目录 LRU API 不得恢复", mediaSource.contains("trimThumbnailCache("))
        assertFalse("metadata trimmer 不得读取文件 mtime", trimmer.contains("lastModified("))
        assertTrue("LRU 必须按 metadata lastAccessAt keyset", cacheDao.contains("ORDER BY lastAccessAt ASC"))
        assertTrue("LRU 必须保护最近 touch", cacheDao.contains("lastAccessAt <= :protectedBefore"))
        assertTrue("LRU 必须保护租约", cacheDao.contains("leaseUntil <= :now"))
    }
}
