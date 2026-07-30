# LocalAlbum 代码质量与安全审查报告

> 审查日期：2026-07-30  
> 基线提交：`893bdc155ab2431edbbe3022bc82bebefad16d73`，但工作区存在大量未提交改动；本报告针对审查时的实际工作区快照。  
> 证据原则：仅使用实际源码、构建配置、测试源码和本轮命令结果，不使用旧审查报告、变更日志或发布说明作为实现证据。  
> 审查性质：静态审查 + JVM 单元测试 + Android Lint Debug + Debug 构建；未进行动态渗透测试、真机功能回归或性能压测。

---

## 1. 执行摘要

项目具备较完整的本地相册、媒体扫描、Room 持久化、可恢复后台任务、备份恢复、端侧 AI、语义搜索、人物聚类、重复文件维护、回收站和换脸能力。当前实现中已有多项积极工程实践：扫描和导入使用 generation/staging 隔离，删除失败不会直接清除媒体记录，后台分析任务使用租约和心跳，语义检索按向量空间分页并用 Top-K 最小堆控制内存，文件推理采用有界分波调度。

本轮确认 **3 项 P0、5 项 P1、7 项 P2**。发布前最关键的阻断项是：

1. 外部 APK/Dex 可在宿主进程执行代码，且签名指纹由插件自身声明，不能建立可信发布者身份。
2. 模型下载及插件/模型文件名未形成强制可信输入边界，存在目录穿越和未校验模型执行风险。
3. [`FileProvider`](../app/src/main/AndroidManifest.xml:35) 同时映射设备根目录和外部存储根目录，授权能力远超分享媒体文件所需范围。
4. 完整 Lint 门禁失败；若 OpenCV 摄像头类被调用，权限处理不足可造成运行时崩溃。
5. 三个关键原生推理依赖未满足 16 KB 页对齐，面向 16 KB-only 设备存在安装或加载兼容风险。

当前 **Debug 编译和 343 个 JVM 测试通过**，说明主源码可编译且已覆盖的纯逻辑行为稳定；但这不能替代 Android 设备上的 Room、权限、MediaStore、WorkManager、原生库和进程恢复验证。

---

## 2. 范围、规模与验证结果

### 2.1 审查范围

- 210 个主 Kotlin 文件。
- 39 个 JVM 测试文件，11 个 instrumentation 测试文件。
- Android Manifest、FileProvider、Gradle、R8/ProGuard、CMake、Room v27 迁移链。
- 媒体扫描、索引、备份、删除、任务租约、缩略图、重复项、人脸、语义搜索、插件、模型下载、推理资源和主要 UI 状态链路。
- OpenCV Java 模块作为项目内源码参与 Lint 结果评估。

### 2.2 构建环境

- Gradle 8.13。
- Gradle Daemon JVM：Java 21；命令行 Launcher JVM：OpenJDK 8。
- 应用版本：0.1.0，versionCode 1，minSdk 29，targetSdk 35，见 [`defaultConfig`](../app/build.gradle.kts:24)。
- 工作区不是干净提交，报告结论不可直接等同于基线提交内容。

### 2.3 本轮命令结果

| 验证项                                                         |     结果 | 说明                                                                     |
| -------------------------------------------------------------- | -------: | ------------------------------------------------------------------------ |
| `./gradlew testDebugUnitTest assembleDebug --warning-mode all` |     通过 | 88 tasks；Kotlin、KAPT/Room、Java、CMake arm64-v8a/x86_64、打包成功      |
| JVM 测试                                                       |     通过 | 39 suites，343 tests，0 failures，0 errors，0 skipped                    |
| Debug APK                                                      |     通过 | 约 1,765,305,156 bytes（约 1.64 GiB），体积异常大                        |
| `./gradlew lintDebug`                                          | **失败** | 应用模块 0 errors/147 warnings/2 hints；OpenCV 模块 3 errors/18 warnings |
| instrumentation                                                |   未执行 | 环境中没有 `adb` 命令，不能判断设备是否连接                              |

构建还持续报告 Kotlin KAPT 调用已弃用的 Gradle [`Configuration.fileCollection(Spec)`](../build.gradle.kts:7)，Gradle 9 将移除该 API。

---

## 3. 发现总览

| ID        | 等级 | 发现                                                          | 置信度 |
| --------- | ---- | ------------------------------------------------------------- | ------ |
| SEC-01    | P0   | 外部 APK/Dex 以宿主 UID 执行，插件自声明证书指纹不构成信任根  | 高     |
| SEC-02    | P0   | 模型/插件文件名可目录穿越，远程模型哈希可省略                 | 高     |
| SEC-03    | P0   | FileProvider 暴露 root-path 与 external-path 根目录           | 高     |
| SEC-04    | P1   | ZIP 解压未校验 canonical path，存在 Zip Slip 写出目标目录风险 | 高     |
| PRIV-01   | P1   | 自动备份未排除数据库、模型、插件和人脸/语义数据               | 高     |
| PRIV-02   | P1   | Release 仍保留含查询词、路径和生物特征诊断的 I/W/E 日志       | 高     |
| BUILD-01  | P1   | 完整 Lint 门禁失败，OpenCV Camera2 缺权限检查                 | 高     |
| BUILD-02  | P1   | 原生依赖未满足 16 KB 页对齐                                   | 高     |
| DATA-01   | P2   | 物理删除成功后数据库 purge 失败时存在不可逆不一致窗口         | 中高   |
| DATA-02   | P2   | Room v27 未导出 schema，迁移链缺少完整连续验证                | 高     |
| FUNC-01   | P2   | Android 14+ Selected Photos Access 未适配                     | 高     |
| BACKUP-01 | P2   | 旧 JSON 导入使用全文件读取，无明确体积上限                    | 高     |
| BUILD-03  | P2   | R8 keep 规则过宽，削弱压缩并放大 APK                          | 高     |
| BUILD-04  | P2   | Debug APK 约 1.64 GiB，发布与测试反馈周期风险高               | 高     |
| MAINT-01  | P2   | 重复下载器与旧 DAO 接口并存，增加安全修复漂移概率             | 高     |

---

## 4. P0：必须在开放相关能力或发布前整改

### SEC-01：外部 APK/Dex 在宿主进程执行，签名校验没有可信根

**证据**

- [`PluginLoader.loadInternal()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/PluginLoader.kt:135) 读取外部 APK manifest，随后创建 [`DexClassLoader`](../app/src/main/java/com/renyxin/localalbum/core/plugin/PluginLoader.kt:245)，通过反射实例化任意入口类，见 [`Class.forName()` 调用](../app/src/main/java/com/renyxin/localalbum/core/plugin/PluginLoader.kt:195)。
- manifest 未提供指纹时直接通过，见 [`PluginLoader.verifySignature()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/PluginLoader.kt:323)。
- 即使提供指纹，该指纹来自同一不可信 APK 内的 manifest；攻击者可以让自签名 APK 声明自己的证书指纹，因此只能证明“APK 与自述一致”，不能证明发布者受宿主信任。
- 应用启动容器实际构造该加载器，见 [`AppContainer.extensionPluginRegistry`](../app/src/main/java/com/renyxin/localalbum/AppContainer.kt:220)。

**影响**

恶意插件可在应用 UID、进程和权限上下文中运行，读取 Room 数据、媒体路径、人脸与语义特征、模型文件，并可使用宿主网络权限外传数据。ClassLoader 不是安全沙箱。

**整改**

1. 在正式构建中编译期禁用或删除外部 Dex/APK 加载入口。
2. 优先改为“声明式模型包 + 宿主白名单适配器”，包内禁止 dex、so 和脚本。
3. 若业务必须支持代码插件：使用宿主维护的发布者证书 allowlist/签名透明日志，不接受插件自声明为信任依据，并通过独立 UID/隔离进程与最小 IPC 数据面运行。
4. 增加恶意 APK、无签名、多签名、签名轮换和证书不在 allowlist 的 instrumentation 测试。

### SEC-02：文件名目录穿越与可选哈希导致模型供应链边界失效

**证据**

- [`ModelDownloadManagerV2.ensureModel()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/model/ModelDownloadManagerV2.kt:36) 直接执行 `File(getModelDir(), modelFileName)`，没有拒绝绝对路径、`..`、路径分隔符或 canonical 越界。
- UI 允许调用者传入 `modelFileName`，见 [`PluginViewModel.installModelFromUrl()`](../app/src/main/java/com/renyxin/localalbum/ui/vm/PluginViewModel.kt:926)，模型目录路径还来自远端/目录元数据，见 [`PluginViewModel.downloadModel()`](../app/src/main/java/com/renyxin/localalbum/ui/vm/PluginViewModel.kt:838)。
- [`PluginLoader.copyToPluginDir()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/PluginLoader.kt:277) 和 [`deleteFromPluginDir()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/PluginLoader.kt:296) 同样直接拼接调用者文件名。
- `expectedSha256` 默认可空；缺失时已有文件直接被接受，下载后也不校验，见 [`ModelDownloadManagerV2`](../app/src/main/java/com/renyxin/localalbum/core/plugin/model/ModelDownloadManagerV2.kt:44) 和 [`ModelDownloadManager`](../app/src/main/java/com/renyxin/localalbum/core/plugin/model/ModelDownloadManager.kt:71)。
- 用户 URL 安装接口默认不要求哈希，见 [`PluginViewModel.installModelFromUrl()`](../app/src/main/java/com/renyxin/localalbum/ui/vm/PluginViewModel.kt:926)。

**影响**

若文件名受导入清单、远程目录或未来外部 Intent 影响，可能覆盖应用私有目录中的其他文件；未固定哈希的模型可被上游仓库、重定向终点或传输链替换。模型解析器和 native runtime 接收攻击者控制的复杂二进制，风险不止是结果错误，也包括 native 解析漏洞面。

**整改**

- 只接受严格 basename：拒绝 `/`、`\\`、`..`、空名、控制字符和绝对路径；创建后校验目标 canonical path 位于固定目录下。
- 下载仅允许 HTTPS，限制重定向后的协议和主机策略；强制 SHA-256/签名，禁止正式路径使用空哈希。
- 临时文件使用随机名并 `fsync`，检查原子移动结果；当前 [`renameTo()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/model/ModelDownloadManagerV2.kt:93) 返回值被忽略。
- 对插件复制/删除使用同一安全路径解析器并加入目录穿越单测。

### SEC-03：FileProvider 映射整个文件系统根与外部存储根

**证据**

[`file_provider_paths.xml`](../app/src/main/res/xml/file_provider_paths.xml:1) 配置：

- `external-path path="."`
- `root-path path="."`

Provider 虽然 `exported=false`，但 [`MediaViewerScreen`](../app/src/main/java/com/renyxin/localalbum/ui/screens/MediaViewerScreen.kt:345) 会为分享 Intent 授予临时读权限。PathStrategy 决定哪些文件能够生成受授权 URI；当前策略理论上覆盖设备根目录中应用可读的所有文件。

**影响**

当前 UI 传入的是媒体路径，暂未看到任意路径外部输入直接到达分享点；但一旦路径记录被恶意备份、插件或数据损坏控制，就可能把不应分享的可读文件转换为 content URI。配置违反最小授权原则，后续代码改动极易扩大漏洞可达性。

**整改**

仅声明业务确需目录，例如应用专用分享缓存的 `cache-path path="shared/"`。分享前将媒体复制到该目录；不要使用 `root-path`，也不要映射整个 external root。加入“数据库伪造路径不能生成 URI”的测试。

---

## 5. P1：高优先级质量与安全问题

### SEC-04：通用 ZIP 解压存在 Zip Slip

[`ModelManagerImpl.extractZip()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/model/ModelManagerImpl.kt:764) 使用 `File(destDir, entry.name)` 并直接创建目录/写文件，没有 canonical containment 检查，也没有条目数、展开总量、单文件大小和压缩比限制。当前调用源主要是应用 assets，攻击面较窄；但该函数一旦复用于下载 ZIP，就可通过 `../` 或绝对路径写出模型目录，并可造成磁盘耗尽。

建议复用备份导入中已经实现的 ZIP 名称和展开量验证思路，见 [`DatabaseImporter.validateStreamingZip()`](../app/src/main/java/com/renyxin/localalbum/data/backup/DatabaseImporter.kt:431)，并将安全解压器做成唯一实现。

### PRIV-01：系统自动备份可能迁移敏感索引和可执行输入

[`AndroidManifest.xml`](../app/src/main/AndroidManifest.xml:26) 设置 `allowBackup=true`，没有 `dataExtractionRules` 或 `fullBackupContent`。应用数据库含绝对路径、EXIF/GPS、OCR、人脸嵌入、语义向量、插件清单和删除 tombstone；私有目录还包含模型及外部插件 APK。

建议默认关闭系统自动备份，或显式排除数据库、models、plugins、opt_dex、缩略图和日志，只保留经过用户确认、加密并可验证的手动备份。手动导出也应提供人脸、语义、位置字段开关和加密容器。

### PRIV-02：Release 日志仍泄露用户搜索词、路径与生物特征诊断

- [`SemanticSearcher.search()`](../app/src/main/java/com/renyxin/localalbum/core/search/SemanticSearcher.kt:115) 在 info/warn 中记录完整查询文本。
- [`ParallelFileProcessor.mapParallel()`](../app/src/main/java/com/renyxin/localalbum/core/pipeline/ParallelFileProcessor.kt:63) 在 error 中记录绝对路径和异常堆栈。
- [`InSwapperPlugin.execute()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/extension/InSwapperPlugin.kt:235) 记录输入路径、嵌入/latent 范数和前几项特征。
- R8 仅移除 verbose/debug，保留 info/warn/error，见 [`proguard-rules.pro`](../app/proguard-rules.pro:128)。

应建立结构化安全日志层：Release 不记录查询原文、绝对路径、URI、向量项、人脸框和模型输入；路径使用不可逆短哈希，错误使用稳定 code；诊断日志必须由 Debug 构建常量控制，而非仅依赖 R8 副作用规则。

### BUILD-01：完整 Lint 失败

OpenCV 模块报告 3 个 MissingPermission error：[`Camera2Renderer`](../opencv/java/src/org/opencv/android/Camera2Renderer.java:129) 与 [`JavaCamera2View`](../opencv/java/src/org/opencv/android/JavaCamera2View.java:345) 调用 `openCamera` 前没有形成可证明的权限检查/异常处理。项目当前相册功能未见调用这些摄像头 View，因此主要是构建门禁和未来误用风险；但 `lintDebug` 整体已失败，不能作为通过的发布门禁。

建议若不需要摄像头功能，从 OpenCV 模块排除 camera 源码；若保留，声明 CAMERA、调用侧做运行时授权并捕获 SecurityException。不要用 baseline 掩盖确定错误。

### BUILD-02：关键 native 库未满足 16 KB 页对齐

应用 Lint 对以下 arm64-v8a 库报告 Aligned16KB：

- PyTorch Lite 1.13.1 的 `libc++_shared.so`
- ONNX Runtime 1.19.2 的 `libonnxruntime.so`
- TensorFlow Lite 2.14.0 的 `libtensorflowlite_jni.so`

依赖声明见 [`app/build.gradle.kts`](../app/build.gradle.kts:214)。在要求 16 KB 页大小的设备上可能安装、加载或运行失败。应升级到明确支持 16 KB 的版本，检查最终 APK 中每个 `.so`，并在 16 KB 模拟器/真机上执行启动和全部推理后端 smoke test。

---

## 6. P2：中优先级问题

### DATA-01：物理删除与数据库事务之间存在不可逆窗口

[`PersistentDeletionService.execute()`](../app/src/main/java/com/renyxin/localalbum/data/repo/PersistentDeletionService.kt:37) 先删除物理文件并把 tombstone 标为 completed，再调用 [`MediaDeletionCoordinator.purge()`](../app/src/main/java/com/renyxin/localalbum/data/repo/MediaDeletionCoordinator.kt:18) 清理关联数据。文件系统删除无法参加 Room 事务；如果物理删除成功后数据库 purge 因磁盘满、数据库损坏或进程终止失败，数据库仍可能引用已不存在文件，且 tombstone 已完成。

这不是简单调整事务顺序即可完全解决。建议采用可恢复状态机：`INTENT -> FILE_DELETED -> DB_PURGED`，每一步幂等；启动和 Worker 对 `FILE_DELETED` 继续 purge。将 `markCompleted` 移到 purge 成功之后，并保留可重放中间状态。

### DATA-02：Room schema 未导出，连续迁移证明不足

[`AppDatabase`](../app/src/main/java/com/renyxin/localalbum/data/db/AppDatabase.kt:55) 已到 version 27，但 `exportSchema=false`。代码注册 8→27 的连续迁移，见 [`AppDatabase.getDatabase()`](../app/src/main/java/com/renyxin/localalbum/data/db/AppDatabase.kt:700)，instrumentation 仅有部分迁移测试文件，不能证明所有历史 schema、索引、默认值及数据保留。

应开启 schema 导出并提交版本化 JSON；用 `MigrationTestHelper` 覆盖每个单步和至少 8→27 全链路，检查 FTS、BLOB、索引、任务租约、删除 tombstone 和语义空间数据。

### FUNC-01：未适配 Android 14+ Selected Photos Access

Lint 对 [`READ_MEDIA_IMAGES`](../app/src/main/AndroidManifest.xml:6) 和 [`READ_MEDIA_VIDEO`](../app/src/main/AndroidManifest.xml:7) 报告 SelectedPhotoAccess。项目未声明 `READ_MEDIA_VISUAL_USER_SELECTED`，也未见部分授权生命周期处理。用户选择“仅部分照片”后，扫描数量、已删除判断和孤儿清理可能与权限可见范围混淆。

建议显式支持部分访问，并将“不可见”与“已删除”区分；完整扫描的删除门禁必须确认当前授权范围完整。

### BACKUP-01：旧 JSON 兼容导入仍可产生大内存峰值

[`DatabaseImporter.importFromFile()`](../app/src/main/java/com/renyxin/localalbum/data/backup/DatabaseImporter.kt:89) 对非 ZIP 文件调用 `readText`；随后构造完整 JSONObject 和多个实体列表，见 [`importFromJsonInternal()`](../app/src/main/java/com/renyxin/localalbum/data/backup/DatabaseImporter.kt:288)。该兼容路径没有与 ZIP 相同的 1 GiB/行长度限制，大文件可导致 OOM。

建议正式 UI 只接受流式 ZIP v3；旧 JSON 在读取前限制文件大小，并用流式 JSON reader 转为 staging，或明确只允许小型迁移文件。

### BUILD-03：R8 规则过宽

[`proguard-rules.pro`](../app/proguard-rules.pro:5) 保留整个 Kotlin、Coroutines、Compose、Lifecycle、Room、全部 ML runtime、OpenCV、Coil、Media3、Paging 和 WorkManager 类。大多数规则并非必要，会显著削弱 R8 的裁剪和混淆，也扩大方法数和审计面。

应依据各库官方 consumer rules，仅保留反射入口、JNI 方法和插件 ABI；用 release 构建与核心功能 smoke test 验证收敛规则。

### BUILD-04：Debug APK 约 1.64 GiB

本轮 APK 为 1,765,305,156 bytes。虽然包含 x86_64、arm64、多个 ML runtime 和模型，体积仍足以阻碍安装、CI 制品传输和反馈周期。正式包若接近此规模还会触发分发限制。

建议使用 App Bundle/ABI split；模型改为按需下载并强制签名哈希；删除未使用的 PyTorch/TFLite/ONNX runtime；用 APK Analyzer 建立每个依赖/asset/ABI 的体积预算。

### MAINT-01：重复下载实现和旧接口造成安全策略漂移

[`ModelDownloadManager`](../app/src/main/java/com/renyxin/localalbum/core/plugin/model/ModelDownloadManager.kt:25) 与 [`ModelDownloadManagerV2`](../app/src/main/java/com/renyxin/localalbum/core/plugin/model/ModelDownloadManagerV2.kt:18) 基本重复，MobileSAM 仍有旧实现回退，见 [`MobileSAMPlugin.initialize()`](../app/src/main/java/com/renyxin/localalbum/core/plugin/model/MobileSAMPlugin.kt:100)。安全修复容易只落在一个版本。EmbeddingDao 也保留未使用且不带 space 的缺失/过期查询，见 [`EmbeddingDao`](../app/src/main/java/com/renyxin/localalbum/data/db/dao/EmbeddingDao.kt:95)。

建议收敛唯一下载器和唯一安全路径校验器；删除或限制 legacy DAO，避免未来重新引入跨空间错误。

---

## 7. 积极实践

1. [`ParallelFileProcessor.mapParallel()`](../app/src/main/java/com/renyxin/localalbum/core/pipeline/ParallelFileProcessor.kt:63) 按并发度分波创建协程，不再为整个图库同时创建 Job，并正确重新抛出 CancellationException。
2. [`ExactPagedVectorIndex.search()`](../app/src/main/java/com/renyxin/localalbum/core/search/VectorIndex.kt:52) 使用 keyset 分页和固定容量最小堆，内存与页大小及 Top-K 成正比，并按 `spaceId` 隔离向量。
3. [`AnalysisTaskDao.claimBatch()`](../app/src/main/java/com/renyxin/localalbum/data/db/dao/AnalysisTaskDao.kt:47) 在事务中恢复过期租约、领取任务并通过 token 防止陈旧 Worker 提交结果。
4. [`AnalysisWorker`](../app/src/main/java/com/renyxin/localalbum/data/worker/AnalysisWorker.kt:20) 有租约心跳、指数退避、必需阶段核对和取消传播。
5. [`DatabaseImporter.validateStreamingZip()`](../app/src/main/java/com/renyxin/localalbum/data/backup/DatabaseImporter.kt:431) 检查重复条目、Zip Slip 名称、条目数、展开大小、压缩比、行长、SHA-256 和 capability。
6. [`DatabaseImporter.switchGenericStaging()`](../app/src/main/java/com/renyxin/localalbum/data/backup/DatabaseImporter.kt:186) 将生产表切换集中在 Room 事务中，解析失败不会提前破坏生产数据。
7. [`PhysicalFileDeletion.delete()`](../app/src/main/java/com/renyxin/localalbum/data/repo/PhysicalFileDeletion.kt:29) 区分删除、缺失和失败，持久错误信息不包含完整路径和原始异常消息。
8. [`MediaDeletionCoordinator.purge()`](../app/src/main/java/com/renyxin/localalbum/data/repo/MediaDeletionCoordinator.kt:18) 集中清理媒体关联数据，并对重复项/语义簇先失效后删除成员。
9. [`AppDatabase.getDatabase()`](../app/src/main/java/com/renyxin/localalbum/data/db/AppDatabase.kt:700) 升级缺迁移时抛错，仅降级允许 destructive migration，避免静默清空升级数据。

---

## 8. 测试与覆盖缺口

### 已验证

- 343 个 JVM 测试全部通过，覆盖相册构建、推荐、语义向量、备份契约、忽略规则、删除策略、任务失败判定和多项架构约束。
- Debug 构建通过 Room/KAPT、Compose、Java、CMake 双 ABI 和 APK 打包。
- 应用模块 Lint 无 error，但有 147 warnings；其中 Selected Photos、16 KB 对齐和隐私相关项需要进入发布门禁。

### 未验证

- 11 个 instrumentation 测试未执行；环境无 `adb`，不能验证 Room 真实事务、迁移和 Paging。
- 未运行 Release/R8 构建，不能证明反射插件、Room、JNI 和 ML runtime 在压缩后可用。
- 未在 Android 14 部分媒体授权、Android 15/16、16 KB 页设备、低内存设备上测试。
- 未做 1 万/5 万/10 万媒体压力测试、进程强杀、磁盘满、数据库锁、损坏备份和网络中断故障注入。
- 未对模型文件、APK 插件、ZIP、NDJSON 做 fuzz。

### 应新增的关键测试

1. 文件名 `../x`、绝对路径、Unicode 分隔符和符号链接目录穿越。
2. Zip Slip、重复 entry、超高压缩比、超长行和解压磁盘预算。
3. 插件证书 allowlist、未签名/自签名/签名轮换、多签名。
4. FileProvider 只能分享受控缓存目录。
5. 物理删除后在 tombstone、Room purge 各阶段强杀进程并验证自动收敛。
6. 8→27 全迁移和每个单步迁移；导入事务中的约束失败、磁盘满和取消。
7. Android 14 部分照片权限下完整扫描不得误判不可见媒体为已删除。
8. 16 KB 设备上加载 ONNX、TFLite、PyTorch、OpenCV 全部 native 库。

---

## 9. 整改顺序与发布门禁

### 第一阶段：立即阻断

1. 正式构建禁用外部 APK/Dex 插件。
2. 收紧 FileProvider 到应用专用分享缓存。
3. 建立统一安全下载/文件名/解压组件，强制 HTTPS + SHA-256/签名。
4. 排除系统自动备份中的数据库、模型、插件、缓存和日志。
5. 清理 Release 敏感日志。

### 第二阶段：发布兼容性

1. 修复 OpenCV Lint error，使 `lintDebug` 全项目通过。
2. 升级 16 KB 对齐的 native 依赖并做真机验证。
3. 开启 Room schema 导出并补齐连续迁移测试。
4. 适配 Android 14 Selected Photos Access。
5. 收敛 R8 keep 规则与 APK 体积。

### 第三阶段：韧性与规模

1. 将删除链路升级为可重放状态机。
2. 禁止大体积 legacy JSON 导入或改为流式 staging。
3. 做超大图库、低内存、进程终止和磁盘故障测试。
4. 评估 Exact 分页检索达到规模阈值后引入可重建 ANN 索引。

### 建议发布门禁

- `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleRelease` 全部通过。
- 所有 8→27 迁移与当前 11 个 instrumentation 测试在设备/模拟器通过。
- 最终 APK/AAB 中所有 arm64 native 库通过 16 KB 对齐检查。
- Release 日志抽检不出现搜索词、绝对路径、URI、人脸/语义向量和模型输入。
- 正式包不存在可达的外部 Dex/APK 执行入口。
- FileProvider 无 `root-path` 和全 external root。
- 远程模型缺少可信哈希/签名时必须拒绝安装。

---

## 10. 总结

项目的数据管道和大图库基础设施已经采用了较成熟的 generation、staging、租约、keyset 分页、有界并发和集中关联清理设计；JVM 测试与 Debug 构建也表现稳定。但安全边界仍明显落后于功能复杂度：外部代码加载、模型供应链、文件路径处理、FileProvider、自动备份和 Release 日志共同构成高风险组合；同时 Lint、16 KB 页兼容和 APK 体积尚未达到稳健发布标准。

结论：**当前工作区适合继续开发和受控测试，不建议在未完成 P0 与 P1 整改前将外部插件、任意 URL 模型安装或当前 FileProvider 配置作为正式发布能力。**
