# 2026-10-20 审计修复立项（fn-divide 方法论适配版）

> 来源：2026-10-20 全项目审计（见 git 工作区与项目记忆 localalbum-audit-2026-10-20）。
> 方法：审计发现 = 需求条目；每条映射到修复任务（单一职责）；每任务带核验命令。
> 双向约束：每个发现至少一个任务负责（无遗漏）；每个任务回链发现编号（无孤儿工作）。

## 需求覆盖矩阵

| 发现 | 严重度 | 任务 | 核验 |
|---|---|---|---|
| F-P0 权限崩溃 | P0 | A1 | 编译；API 29/30-32/33+ 分支矩阵人工复核 |
| F-P1-1 FAILED 死角 | P1 | A2 | 编译 + 全量单测 |
| F-P1-2 导入清表不全 | P1 | A3 | 编译 + 全量单测 |
| F-P1-3 驱逐竞态 | P1 | A4 | 编译 + 全量单测 |
| F-P1-4 DBSCAN 边界点 | P1 | A5 | FaceClusterer 单测 + 新增边界点用例 |
| F-P1-5 导航不抗旋转 | P1 | A6 | 编译；Screen 编解码往返人工复核 |
| F-P1-6 导入后推荐陈旧 | P1 | A7 | 编译 + 全量单测 |
| F-P2-1..5（续排/死按钮/双写/先删后写/EXIF） | P2 | B1..B5 | 编译 + 全量单测 |
| F-DC 死代码簇 ×7 | P2 | C1..C7 | 每簇编译 + BoundedDataAccessArchitectureTest + 全量单测 |

## 任务定义（职责 = 重写规约）

- **A1** `MainActivity.checkPermission/requestPermission` [改造]：API 33+ 双媒体权限；API 30-32 MANAGE；API 29 改查 READ_EXTERNAL_STORAGE 运行时权限并在 requestPermission 提供对应请求分支。错误路径：拒绝 → PermissionGate。
- **A2** 流水线准入 [改造]：FAILED 视为可再启动的空闲终态——`startRequestedRebuild` 的 WHERE、协调器 `IDLE_ADMISSION_STAGES`、用户扫描/journal 准入的 fromStages 全部纳入 FAILED。不得改变 USER_ANALYSIS_ADMISSION_STAGES 既有语义。
- **A3** `DatabaseImporter.switchFromStaging` [改造]：清表集合与 `switchGenericStaging` 完全对齐（补 deletion_tombstones/analysis_state/feature_store/face_cluster_*/semantic_*/plugin_manifest/maintenance_runs/duplicate_*/semantic_maintenance_runs）。
- **A4** 模型生命周期 [改造]：ArcFace/MobileCLIP/PaddleOCR 按 InsightFace 模式补 registerConsumer/unregisterConsumer 配对（id = 模型 id，consumer = 稳定字符串）；`withOnnxSession/withInterpreter` 的池引用读取移入 permit 临界区内，杜绝驱逐后引用悬挂。
- **A5** `FaceClusterer.cluster` [改造]：BFS 扩展条件改为"未分类(-1)或噪声(-2)皆入队"；pop 时噪声点吸收为边界点（不扩展）；已归类点跳过。补边界点回归用例。
- **A6** `LocalAlbumApp` [改造]：currentTab → rememberSaveable(Int)；backStack → rememberSaveable(listSaver) + Screen 可逆字符串编解码；无解码器的屏幕回退 Main。
- **A7** `AlbumRepository.importDatabase` [改造]：导入成功后从新目录树重建推荐池并持久化快照（等效用户手动刷新），消除旧库推荐残留。
- **B1** `DeletionRetryWorker`：自续排改 APPEND_OR_REPLACE。
- **B2** `PhotosTab`：空态按钮接通真实回调（切换到设置 tab）。
- **B3** `IncrementalFaceClusterAssigner.assignFileToCluster`：双写包进同一 Room 事务。
- **B4** `SemanticStage`：删旧+插新改为逐批同事务。
- **B5** `MediaViewerScreen` EXIF 抽取移 LaunchedEffect(IO)；`ExifExtractor` 有理数 "a/b" 解析换算。
- **C1** 删 6bb84d6 断链方法（acknowledgePublishedReconciliation/acknowledgeEmptyReconciliationDomain/releaseWaitingForCoreEnhancements）+ finalizeEmptyScans + purgeOrphanPaths；同步修正引用它们的测试。
- **C2** 删 TimelineScreen 死簇 + core/timeline 包。
- **C3** 删不可达页面 ModelImportWizard/ModelJsonEditor（含 Screen 分支、resolveEditionScreen 条目、PluginViewModel 对应死成员）。
- **C4** AlbumViewModel 死成员（保留架构测试强制存在的委托链）。
- **C5** AlbumRepository 死符号（尊重 BoundedDataAccessArchitectureTest 文本断言；仅测试引用者连带改测试或保留并注明）。
- **C6** 删 InferenceScheduler/ModelVersionTracker/ThumbnailCachePolicy/Phase2 遗留批量入口（@VisibleForTesting 标注的测试缝保留）。
- **C7** 删 UiCommon.toMediaItem、AppContainer.sharedScope 等杂项。
- **不修**：DemoStyleTransferPlugin.setStyle（功能缺口需产品决策，保留 demo API）；@VisibleForTesting 测试缝；AppContainer dispose/shutdown（架构有意设计）。

## 状态（2026-10-20 执行完毕）

全部任务已实施并验证：full/lite 双 flavor 编译通过；全量单测（含 BoundedDataAccessArchitectureTest 全部架构契约）通过；新增 FaceClusterer 边界点回归用例通过。净变更 +473/-2929 行（43 文件）。

执行中的裁量决定：
- `ScanRunDao.releaseWaitingForCoreEnhancements` 保留——有活跃 androidTest 行为覆盖，是文档化的恢复校验缝隙。
- `AlbumRepository.startFaceClusterMaintenance`/`AlbumViewModel.maintainFaceClusters` 首轮误删后按架构契约（BoundedDataAccessArchitectureTest:1013 强制 `EditionConfiguration::enqueueFaceClusterMaintenance` 委托）恢复。
- `PluginViewModel.faceSwapState/performFaceSwap/storageStats/activateProvider` 经复核有外部使用者（修正审计报告），保留。
- `DemoStyleTransferPlugin.setStyle` 保留（demo 插件 API，功能缺口需产品决策）。
- `@VisibleForTesting` 标注的测试缝一律保留。
