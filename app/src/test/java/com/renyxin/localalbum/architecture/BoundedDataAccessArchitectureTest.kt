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
            .substringBefore("ScanExecutionResult.COMPLETED")
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
    fun `edition composition and restore seeding do not bypass policy scope`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val container = File(sourceRoot, "com/renyxin/localalbum/AppContainer.kt").readText()
        val importer = File(
            sourceRoot,
            "com/renyxin/localalbum/data/backup/DatabaseImporter.kt",
        ).readText()
        val seeder = File(
            sourceRoot,
            "com/renyxin/localalbum/data/backup/PostRestoreTaskSeeder.kt",
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
            "恢复 policy 必须使用当前管线 scope",
            container.contains("pipelineScope = pluginAnalysisPipeline.pipelineScope"),
        )
        assertTrue(
            "恢复 policy 必须由显式 Stage plan 决定是否创建分析交接",
            container.contains(
                "enqueueAutomaticAnalysis = pluginAnalysisPipeline.requiredStageIds.isNotEmpty()",
            ),
        )
        assertTrue(
            "恢复器只能委托显式 policy seeder",
            importer.contains("postRestoreTaskSeeder?.seed(db)"),
        )
        assertFalse(
            "恢复器不得保留 edition 或 pipeline 判断",
            importer.contains("restoredAnalysisPipelineScope") ||
                importer.contains("restoredAnalysisEnabled") ||
                importer.contains("restoredProfileId"),
        )
        assertTrue(
            "policy seeder 必须只创建 durable handoff",
            seeder.contains("INSERT OR IGNORE INTO enhancement_outbox"),
        )
        assertFalse(
            "恢复器与 policy seeder 均不得直接 seed analysis task",
            importer.contains("INSERT OR IGNORE INTO analysis_tasks") ||
                seeder.contains("INSERT OR IGNORE INTO analysis_tasks"),
        )
        assertFalse(
            "恢复器与 policy seeder 均不得直接 seed thumbnail task",
            importer.contains("INSERT OR IGNORE INTO thumbnail_tasks") ||
                seeder.contains("INSERT OR IGNORE INTO thumbnail_tasks"),
        )
        assertFalse(
            "恢复器不得创建旧 Full 默认 scope",
            importer.contains("'core:v1'") || seeder.contains("'core:v1'"),
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
        assertTrue("交互和后台缩略图必须使用不同 unique work", thumbnailWorker.contains("INTERACTIVE_WORK_NAME") && thumbnailWorker.contains("BACKGROUND_WORK_NAME"))

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
            analysisWorker.contains("countClaimable(recoveryNow, scope, includeUserTasks)"),
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
            "恢复 outbox 必须在导入后 reconciliation 完成后释放",
            importerHandoff.indexOf("rescan()") < importerHandoff.indexOf("EnhancementHandoffWorker::enqueue"),
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
        assertTrue("启动恢复必须覆盖 thumbnail-only 队列", container.contains("countAutomaticRunnable()"))

        val viewModel = File(
            sourceRoot,
            "com/renyxin/localalbum/ui/vm/AlbumViewModel.kt",
        ).readText()
        val rescan = viewModel.substringAfter("fun rescan()")
            .substringBefore("fun forceReanalyzeAll()")
        assertFalse("UI 扫描完成不得直接调度缩略图", rescan.contains("ThumbnailWorker.enqueue("))
        assertTrue("人物页必须直接观察 Room-backed Repository Flow", viewModel.contains("repository.faceClusters"))

        val fullUi = File(
            projectRoot,
            "app/src/full/java/com/renyxin/localalbum/edition/EditionUiContribution.kt",
        ).readText()
        assertFalse("人物页不得恢复定时快照轮询", fullUi.contains("delay(1_500L)"))
        assertFalse("人物页不得在管道循环中手动刷新快照", fullUi.contains("loadFaceClusters()"))
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
