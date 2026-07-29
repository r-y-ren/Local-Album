# LocalAlbum — Android Local AI Photo Manager

<p align="center">
  <img src="LocalAlbum.svg" alt="LocalAlbum Logo" width="120" />
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0" /></a>
  <a href="https://github.com/r-y-ren/Local-Album/actions/workflows/android.yml"><img src="https://github.com/r-y-ren/Local-Album/actions/workflows/android.yml/badge.svg" alt="Android CI" /></a>
  <a href="https://android-arsenal.com/api?level=29"><img src="https://img.shields.io/badge/API-29%2B-brightgreen.svg" alt="API: 29+" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-1.9%2B-purple.svg" alt="Kotlin: 1.9+" /></a>
</p>

<p align="center">
  <strong><a href="#english">English</a></strong> | <strong><a href="#chinese-中文">中文</a></strong>
</p>

---

<a name="english"></a>

## English

> A Kotlin + Jetpack Compose Android local photo manager with a dynamic AI plugin system, on-device model inference, and intelligent media analysis.

---

### Table of Contents

- [1. Overview](#1-overview)
- [2. Tech Stack & Build](#2-tech-stack--build)
- [3. Architecture](#3-architecture)
- [4. Core Modules](#4-core-modules)
  - [4.1 Media Indexing Engine](#41-media-indexing-engine-hybridindexer)
  - [4.2 AI Plugin System](#42-ai-plugin-system)
  - [4.3 Model Runtime](#43-model-runtime-modelruntime)
  - [4.4 Plugin Analysis Pipeline](#44-plugin-analysis-pipeline-pluginanalysispipeline)
  - [4.5 Progress Management](#45-progress-management-progressmanager)
  - [4.6 AI Capabilities](#46-ai-capabilities)
  - [4.7 Data Persistence](#47-data-persistence)
  - [4.8 Search System](#48-search-system)
  - [4.9 Import/Export](#49-database-importexport)
- [5. Model Setup](#5-model-setup)
- [6. Configuration & Customization](#6-configuration--customization)
- [7. Usage Examples](#7-usage-examples)
- [8. Project Structure](#8-project-structure)
- [9. Contributing](#9-contributing)
- [10. License](#10-license)

---

### 1. Overview

LocalAlbum is a fully offline Android smart photo manager. No cloud services required — all indexing, classification, search, and AI analysis runs entirely on-device.

**Highlights:**

- **Local Album Management**: Auto-built album tree from directory structure, timeline view, favorites, trash
- **AI Analysis**: On-device face detection/clustering, scene classification, quality scoring, OCR text recognition, semantic embedding
- **Dynamic AI Plugin System**: Runtime hot-loading of external AI model plugins (TFLite / ONNX / PyTorch Mobile) without recompiling the host app
- **Smart Search**: FTS4 keyword search + semantic search (natural language) + hybrid retrieval
- **Similar Photo Detection**: Perceptual hash-based duplicate grouping
- **Map View**: Geographic clustering and reverse geocoding
- **Database Import/Export**: Full JSON export of index data for cross-device recovery

### Screenshots

> _Screenshots coming soon. Contributions welcome!_

---

### 2. Tech Stack & Build

#### 2.1 Key Dependencies

| Category   | Dependency                              | Version        |
| ---------- | --------------------------------------- | -------------- |
| Language   | Kotlin                                  | 1.9+           |
| UI         | Jetpack Compose + Material 3            | BOM 2024.09.02 |
| Arch       | ViewModel + Repository + Room           | Room 2.6.1     |
| Async      | Kotlin Coroutines + Flow                | 1.8.1          |
| Image      | Coil Compose                            | 2.6.0          |
| Video      | Media3 ExoPlayer                        | 1.4.1          |
| Paging     | Paging 3                                | 3.3.2          |
| Background | WorkManager                             | 2.9.1          |
| Storage    | DataStore Preferences + Room            | —              |
| ML Kit     | Text Recognition (incl. Chinese) + Face | —              |
| TFLite     | TensorFlow Lite + Support               | 2.14.0 / 0.4.4 |
| ONNX       | ONNX Runtime Android                    | 1.19.2         |
| PyTorch    | PyTorch Mobile Lite                     | 1.13.1         |
| Map        | osmdroid                                | 6.1.20         |
| Test       | JUnit 4 + Mockito                       | 4.13.2 / 5.5.0 |

#### 2.2 Build Environment

- **compileSdk**: 35
- **minSdk**: 29 (Android 10+)
- **targetSdk**: 35
- **JDK**: 17 (with coreLibraryDesugaring for `java.time`)
- **ABI**: arm64-v8a, x86_64
- **Gradle**: Kotlin DSL (`build.gradle.kts`)

#### 2.3 Build Steps

```bash
# 1. Clone the repository
git clone https://github.com/r-y-ren/Local-Album.git
cd Local-Album

# 2. Download AI model files via the provided script.
#    The script fetches all models (large + small/medium) from GitHub Releases;
#    small/medium models (EVA02-CLIP, PP-OCR) are already bundled in the repo
#    and will be skipped if present.
chmod +x scripts/download_models.sh
./scripts/download_models.sh

# 3. (Optional) Regenerate the face-swap emap matrix locally instead of downloading:
#    pip install onnx numpy && python scripts/extract_emap.py

# 4. Open in Android Studio and wait for Gradle sync

# 5. Build debug APK
./gradlew assembleDebug

# 6. Run unit tests
./gradlew testDebugUnitTest

# 7. Install on device
./gradlew installDebug
```

#### 2.4 Release Build (Signed APK)

Release builds are minified, resource-shrunk, and signed with a user-provided keystore. The keystore credentials are injected via `keystore.properties` (git-ignored; template at `keystore.properties.example`).

```bash
# 1. Generate a release keystore (one-time)
keytool -genkeypair -v \
  -keystore localalbum.jks \
  -alias localalbum \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=LocalAlbum, OU=Dev, O=renyxin, L=Beijing, ST=Beijing, C=CN"

# 2. Copy the template and fill in real values
cp keystore.properties.example keystore.properties
# Edit keystore.properties: storeFile / storePassword / keyAlias / keyPassword

# 3. Build the signed release APK
./gradlew assembleRelease

# Output: app/build/outputs/apk/release/LocalAlbum-v0.1.0-c1-release.apk
```

> Without `keystore.properties`, `assembleRelease` still runs (producing an unsigned APK) so CI can verify release compilation without exposing signing keys.

**Notes:**

- PyTorch Mobile requires JitPack repository (configured in `settings.gradle.kts`)
- AI models bundled in `assets/` dominate APK size (~1.6 GB); native libraries are limited to `arm64-v8a` and `x86_64` via `ndk.abiFilters`
- The Gradle daemon runs on JDK 21 (auto-provisioned via the foojay toolchain resolver in `gradle/gradle-daemon-jvm.properties`); compilation uses JDK 17
- First build downloads Gradle/AGP dependencies and native runtime libraries — may take a while

---

### 3. Architecture

#### 3.1 MVVM + Repository + Manual DI

```
┌──────────────────────────────────────────────────────┐
│  UI Layer (Compose Screens + ViewModels)              │
│  Timeline / Albums / Faces / Map / Search / Plugin    │
├──────────────────────────────────────────────────────┤
│  ViewModel Layer                                      │
│  AlbumViewModel / PluginViewModel / SettingsViewModel │
├──────────────────────────────────────────────────────┤
│  Repository Layer                                     │
│  AlbumRepository / SettingsRepository                │
├──────────────────────────────────────────────────────┤
│  Core Business Layer                                  │
│  PluginAnalysisPipeline / PluginRegistry              │
│  HybridIndexer / AnalysisPipeline                     │
│  FaceDetector / SceneClassifier / SemanticEmbedder   │
├──────────────────────────────────────────────────────┤
│  Data Layer (Room v11)                                │
│  MediaDao / FaceDao / EmbeddingDao /                  │
│  FeatureStoreDao / PluginManifestDao                  │
└──────────────────────────────────────────────────────┘
```

#### 3.2 Module Layout

| Package                 | Responsibility                                                  |
| ----------------------- | --------------------------------------------------------------- |
| `core/plugin/`          | AI Plugin SDK: interfaces, models, JSON codec, loading/registry |
| `core/plugin/runtime/`  | 3 model runtime implementations (TFLite/ONNX/PyTorch)           |
| `core/pipeline/`        | Plugin analysis pipeline: DAG orchestration, stages, progress   |
| `core/pipeline/stages/` | 7 built-in analysis stage implementations                       |
| `core/analysis/`        | Analysis algorithms: face/scene/OCR/quality/semantic/similarity |
| `core/index/`           | Hybrid indexing engine (HybridIndexer + MediaContentObserver)   |
| `core/model/`           | Core data models (MediaItem, Album, DirectoryNode)              |
| `core/exif/`            | EXIF metadata extraction                                        |
| `core/search/`          | Semantic search engine                                          |
| `core/recommendation/`  | Recommendation engine                                           |
| `core/timeline/`        | Timeline grouping logic                                         |
| `data/db/`              | Room database, entities, DAOs, migrations                       |
| `data/repo/`            | Repository implementations                                      |
| `data/backup/`          | Database JSON import/export                                     |
| `data/worker/`          | WorkManager background tasks                                    |
| `ui/`                   | Compose UI (Screens + Components + Theme)                       |
| `ui/vm/`                | ViewModel implementations                                       |

#### 3.3 Key Design Patterns

- **Factory Method**: `PluginAnalysisPipeline.create()` assembles core analysis stages from the active providers in `CapabilityRegistryV2`
- **Strategy**: `ModelRuntime.create()` returns runtime implementation by model format
- **Strategy + Capability Slots**: `CapabilityRegistryV2` exposes swappable provider slots (face/scene/semantic/quality/ocr); `PluginAnalysisPipeline.create()` assembles stages from the active provider of each slot
- **Adapter** (legacy): `PluginAnalysisStage` wraps `AiPlugin` into unified `AnalysisStage` (deprecated path; extension plugins are now invoked interactively via `ExtensionPluginRegistry`)
- **Observer**: Extensive use of Kotlin `StateFlow` / `SharedFlow` for reactive state
- **Dependency Injection**: Manual DI via `AppContainer`
- **Sealed Class Polymorphism**: `PluginInput` / `PluginOutput` type-safe I/O

---

### 4. Core Modules

#### 4.1 Media Indexing Engine (HybridIndexer)

Dual-channel strategy for coverage and performance:

- **MediaStore channel**: Queries system media DB via `ContentResolver`
- **File API channel**: Traverses user-specified root directories
- **Deduplication**: Merged by `filePath`, MediaStore data takes priority
- **Incremental scanning**: Compares `modifiedAtMs` + SHA-256 fingerprint (first 4096 bytes) snapshots
- **ContentObserver**: Monitors `MediaStore` URIs with debounced incremental scan triggers

```kotlin
suspend fun fullScan(roots: List<String>, allowNomedia: Boolean = false): Int
suspend fun incrementalScan(roots: List<String>, allowNomedia: Boolean = false): IncrementalResult
```

#### 4.2 AI Plugin System

Runtime hot-loading of external APK plugins via `DexClassLoader`. Plugins implement the `AiPlugin` SPI interface:

```kotlin
interface AiPlugin {
    fun getId(): String
    fun getManifest(): PluginManifest
    fun getInputSchema(): List<FeatureSchema.FieldSpec>
    fun getOutputSchema(): FeatureSchema
    suspend fun initialize(context: Context, pluginContext: PluginContext)
    fun isReady(): Boolean
    suspend fun execute(input: PluginInput): PluginOutput
    suspend fun release()
}
```

Four specialized sub-interfaces: `ClassificationPlugin`, `FeatureExtractionPlugin`, `DetectionPlugin`, `GenerativePlugin`.

**Security**: Optional APK signature verification against manifest-declared SHA-256 certificate fingerprints.

**UI**: Visual import wizard (4-step flow), JSON manifest editor with 800ms-debounced real-time validation, and a plugin manager screen showing core-feature model readiness status (face clustering, map clustering, semantic recognition, face swap) with a face-swap entry point.

#### 4.3 Model Runtime (ModelRuntime)

Unified interface across three on-device formats:

| Runtime             | Format  | Size     |
| ------------------- | ------- | -------- |
| TfliteModelRuntime  | TFLite  | ~4-35MB  |
| OnnxModelRuntime    | ONNX    | ~5-180MB |
| PyTorchModelRuntime | PyTorch | varies   |

Factory: `ModelRuntime.create(format)` returns the appropriate implementation.

#### 4.4 Plugin Analysis Pipeline (PluginAnalysisPipeline)

Core orchestration engine with DAG topological sort (Kahn's algorithm). Stages are assembled from the active provider of each capability slot in `CapabilityRegistryV2`, plus two fixed stages (geo, similarity). Extension AI plugins (face swap, style transfer) are **not** part of the batch pipeline — they are invoked interactively via `ExtensionPluginRegistry`.

| Stage ID             | Source                       | Function             |
| -------------------- | ---------------------------- | -------------------- |
| `core:face`          | `FaceProvider` slot          | Face detect + embed  |
| `core:quality`       | `QualityProvider` slot       | Photo quality score  |
| `core:scene`         | `SceneProvider` slot         | Scene classification |
| `core:semantic`      | `SemanticEmbedProvider` slot | Semantic embeddings  |
| `core:ocr`           | `OcrProvider` slot           | OCR text recognition |
| `builtin:geo`        | Fixed implementation         | GPS geocoding        |
| `builtin:similarity` | Fixed implementation         | Perceptual hash      |

The factory `PluginAnalysisPipeline.create(mediaDao, faceDao, embeddingDao, capabilityRegistry)` iterates registered capability slots and maps each to its corresponding stage. The legacy `PluginAnalysisStage` adapter (wrapping `AiPlugin` into the batch pipeline and persisting to `feature_store`) is deprecated.

#### 4.5 Progress Management (ProgressManager)

Two-level progress tracking: a pipeline-level `ProgressManager` (overall percent = completed-stage fraction + current-stage file fraction) with per-stage ETA based on current-stage processing rate; and a task-level `ProgressManager` with sliding-window ETA estimation (20-sample moving average) and weighted global aggregation. Suspendable cancellation is supported throughout.

#### 4.6 AI Capabilities

| Module          | Implementation                                          | Output                  |
| --------------- | ------------------------------------------------------- | ----------------------- |
| Face Detection  | InsightFace (default) + RetinaFace/SCRFD/ArcFace/ML Kit | Face boxes + embeddings |
| Face Clustering | DBSCAN                                                  | Person groups           |
| Scene Classify  | MobileNetV2 TFLite + heuristic                          | Scene labels            |
| Quality Score   | Heuristic algorithm                                     | 0~1 score               |
| OCR             | PaddleOCR + ML Kit + GLM-OCR                            | Recognized text         |
| Semantic Embed  | EVA02-CLIP ONNX + MobileCLIP TFLite                     | Feature vectors         |
| Similarity      | Perceptual hash                                         | Similar groups          |
| Geo Cluster     | Distance-based clustering                               | Location groups         |
| Reverse Geocode | Android Geocoder                                        | Place names             |
| EXIF            | ExifInterface                                           | Camera params, GPS      |

#### 4.7 Data Persistence

**Room database v11**, 6 entity tables:

| Table              | Entity                 | Description                                           |
| ------------------ | ---------------------- | ----------------------------------------------------- |
| `media_items`      | `MediaEntity`          | Main media table (40+ columns)                        |
| `media_items_fts`  | `MediaFts`             | FTS4 full-text index (filename + OCR text)            |
| `faces`            | `FaceEntity`           | Face detection results (coords + embedding + cluster) |
| `media_embeddings` | `MediaEmbedding`       | Semantic embedding vectors                            |
| `feature_store`    | `FeatureStoreEntity`   | Universal heterogeneous feature storage (JSON Blob)   |
| `plugin_manifest`  | `PluginManifestEntity` | Plugin manifest persistence (with `orderIndex`)       |

**Migrations**: v8→v9 (feature_store), v9→v10 (plugin_manifest), v10→v11 (orderIndex).

#### 4.8 Search System

- **Keyword**: FTS4 phrase matching + wildcard, fallback to LIKE + OCR text
- **Semantic**: Cosine similarity search on `media_embeddings` table
- **Hybrid**: Parallel semantic + keyword, deduplicated merge

#### 4.9 Database Import/Export

Full JSON export (media records + face records + embeddings) with metadata (device model, timestamp, record counts). Overwrite-style import with format validation and automatic album tree rebuild.

---

### 5. Model Setup

LocalAlbum requires AI model files for most intelligent features. Models are stored in `app/src/main/assets/models/`.

Small and medium models (EVA02-CLIP, PP-OCR) are **bundled in the repository** and arrive with `git clone`. Large models are **not tracked** (see `.gitignore`) and must be fetched before building.

#### Download Large Models

Use the provided script to download all large models from GitHub Releases:

```bash
chmod +x scripts/download_models.sh
./scripts/download_models.sh
```

The script is idempotent — it skips files already present. It fetches the face-swap, face detection/recognition, and emap models from `https://github.com/r-y-ren/Local-Album/releases/tag/v0.1.0`.

#### Face-Swap emap Matrix

`emap_512.bin` (the inswapper source-latent transform matrix) is also fetched by the script. Alternatively, regenerate it locally from `inswapper_128.onnx`:

```bash
pip install onnx numpy
python scripts/extract_emap.py
```

#### Model File Inventory

| File                                       | Feature                        | Bundled?                        |
| ------------------------------------------ | ------------------------------ | ------------------------------- |
| `eva02_clip/eva02_visual_336_int8.onnx`    | Semantic search (image)        | Yes (repo)                      |
| `eva02_clip/eva02_text_int8.onnx`          | Semantic search (text)         | Yes (repo)                      |
| `PP-OCRv6_small_det_infer/inference.onnx`  | OCR detection                  | Yes (repo)                      |
| `PP-OCRv5_mobile_rec_infer/inference.onnx` | OCR recognition                | Yes (repo)                      |
| `inswapper_128.onnx`                       | Face swap                      | No — `download_models.sh`       |
| `buffalo_l.zip`                            | Face detection + ArcFace embed | No — `download_models.sh`       |
| `retinaface-resnet50.onnx`                 | Face detection                 | No — `download_models.sh`       |
| `scrfd_person_2.5g.onnx`                   | Face/person detection          | No — `download_models.sh`       |
| `emap_512.bin`                             | Face swap (emap matrix)        | No — script / `extract_emap.py` |

> **Note**: Large model binaries (`*.onnx` > ~50 MB, `*.zip`, `*.bin`) are git-ignored. Only small/medium models and config files (vocab, merges, tokenizer, inference.yml) are committed.

---

### 6. Configuration & Customization

See [`app/build.gradle.kts`](app/build.gradle.kts) for:

- ABI filters (modify `ndk.abiFilters` for more architectures)
- Compose compiler version (`kotlinCompilerExtensionVersion`)
- Unit test configuration (`testOptions`)

### 7. Usage Examples

See the full Chinese README below for code examples covering:

- Triggering media scans
- Getting index results
- Executing searches
- Registering custom AI plugins
- Running analysis pipelines
- Querying feature storage
- Integrating the global progress indicator

---

<a name="chinese-中文"></a>

---

## 中文

> 基于 Kotlin + Jetpack Compose 的 Android 本地相册管理应用，内置动态 AI 插件系统，支持端侧模型推理与智能媒体分析。

---

### 目录

- [1. 项目简介](#1-项目简介)
- [2. 技术栈与构建](#2-技术栈与构建)
- [3. 项目架构](#3-项目架构)
- [4. 核心功能模块](#4-核心功能模块)
  - [4.1 媒体索引引擎](#41-媒体索引引擎-hybridindexer)
  - [4.2 AI 插件系统](#42-ai-插件系统)
  - [4.3 模型运行时](#43-模型运行时-modelruntime)
  - [4.4 插件化分析管道](#44-插件化分析管道-pluginanalysispipeline)
  - [4.5 进度管理](#45-进度管理-progressmanager)
  - [4.6 AI 分析能力](#46-ai-分析能力)
  - [4.7 数据持久化层](#47-数据持久化层)
  - [4.8 搜索系统](#48-搜索系统)
  - [4.9 导入导出](#49-数据库导入导出)
- [5. 模型配置](#5-模型配置)
- [6. 配置与定制](#6-配置与定制)
- [7. 代码示例与使用指南](#7-代码示例与使用指南)
- [8. 项目目录结构](#8-项目目录结构)
- [9. 贡献与扩展](#9-贡献与扩展)
- [10. 许可证](#10-许可证)

---

### 1. 项目简介

LocalAlbum 是一个纯本地的 Android 智能相册应用，无需云端服务即可实现媒体文件的索引、分类、检索和智能分析。核心亮点：

- **本地相册管理**：按目录结构自动构建相册树，支持时间线视图、收藏、回收站
- **AI 智能分析**：端侧运行人脸检测/聚类、场景分类、质量评分、OCR 文字识别、语义嵌入生成
- **动态 AI 插件系统**：支持运行时热加载外部 AI 模型插件（TFLite / ONNX / PyTorch Mobile），无需重新编译宿主应用
- **智能搜索**：FTS4 关键词搜索 + 语义搜索（自然语言查询）+ 混合检索
- **相似照片检测**：基于感知哈希的相似照片分组与去重
- **地图视图**：地理聚类与反向地理编码
- **数据库导入导出**：JSON 格式完整导出索引数据，支持跨设备恢复

---

### 2. 技术栈与构建

#### 2.1 关键依赖

| 类别     | 依赖                                       | 版本           |
| -------- | ------------------------------------------ | -------------- |
| 语言     | Kotlin                                     | 1.9+           |
| UI       | Jetpack Compose + Material 3               | BOM 2024.09.02 |
| 架构     | ViewModel + Repository + Room              | Room 2.6.1     |
| 异步     | Kotlin Coroutines + Flow                   | 1.8.1          |
| 图片加载 | Coil Compose                               | 2.6.0          |
| 视频     | Media3 ExoPlayer                           | 1.4.1          |
| 分页     | Paging 3                                   | 3.3.2          |
| 后台任务 | WorkManager                                | 2.9.1          |
| 数据存储 | DataStore Preferences + Room               | —              |
| ML Kit   | Text Recognition (含中文) + Face Detection | —              |
| TFLite   | TensorFlow Lite + Support                  | 2.14.0 / 0.4.4 |
| ONNX     | ONNX Runtime Android                       | 1.19.2         |
| PyTorch  | PyTorch Mobile Lite                        | 1.13.1         |
| 地图     | osmdroid                                   | 6.1.20         |
| 测试     | JUnit 4 + Mockito                          | 4.13.2 / 5.5.0 |

#### 2.2 构建环境

- **compileSdk**: 35
- **minSdk**: 29 (Android 10+)
- **targetSdk**: 35
- **JDK**: 17 (含 coreLibraryDesugaring 兼容 `java.time` API)
- **ABI**: arm64-v8a, x86_64
- **Gradle**: Kotlin DSL (`build.gradle.kts`)

#### 2.3 构建步骤

```bash
# 1. 克隆项目
git clone https://github.com/r-y-ren/Local-Album.git
cd Local-Album

# 2. 通过脚本下载 AI 模型文件。
#    脚本会从 GitHub Releases 拉取全部模型（大型 + 小/中型）；
#    小/中型模型（EVA02-CLIP、PP-OCR）已随仓库内置，存在时自动跳过。
chmod +x scripts/download_models.sh
./scripts/download_models.sh

# 3.（可选）本地重新生成换脸 emap 矩阵，替代下载：
#    pip install onnx numpy && python scripts/extract_emap.py

# 4. 使用 Android Studio 打开项目根目录，等待 Gradle Sync 完成

# 5. 命令行构建 Debug APK
./gradlew assembleDebug

# 6. 运行单元测试
./gradlew testDebugUnitTest

# 7. 安装到设备
./gradlew installDebug
```

#### 2.4 正式包构建（签名 APK）

正式包开启代码混淆、资源压缩，并使用用户提供的 keystore 签名。签名信息通过 `keystore.properties` 注入（已被 git 忽略；模板见 `keystore.properties.example`）。

```bash
# 1. 生成正式签名 keystore（一次性操作）
keytool -genkeypair -v \
  -keystore localalbum.jks \
  -alias localalbum \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=LocalAlbum, OU=Dev, O=renyxin, L=Beijing, ST=Beijing, C=CN"

# 2. 复制模板并填入真实值
cp keystore.properties.example keystore.properties
# 编辑 keystore.properties：storeFile / storePassword / keyAlias / keyPassword

# 3. 构建签名正式包
./gradlew assembleRelease

# 产物：app/build/outputs/apk/release/LocalAlbum-v0.1.0-c1-release.apk
```

> 若未提供 `keystore.properties`，`assembleRelease` 仍可执行（产出未签名 APK），便于 CI 在不暴露签名密钥的情况下验证 release 编译。

**注意事项**：

- PyTorch Mobile 依赖需要 JitPack 仓库（已在 `settings.gradle.kts` 中配置）
- 内置于 `assets/` 的 AI 模型主导 APK 体积（约 1.6 GB）；native 库已通过 `ndk.abiFilters` 仅保留 `arm64-v8a` 和 `x86_64`
- Gradle daemon 运行于 JDK 21（由 `gradle/gradle-daemon-jvm.properties` 通过 foojay 工具链自动配置）；编译使用 JDK 17
- 首次构建需下载 Gradle/AGP 依赖与模型运行时 native 库，耗时较长

---

### 3. 项目架构

#### 3.1 整体架构：MVVM + Repository + DI

```
┌──────────────────────────────────────────────────────┐
│  UI 层 (Compose Screens + ViewModels)                 │
│  Timeline / Albums / Faces / Map / Search / Plugin    │
├──────────────────────────────────────────────────────┤
│  ViewModel 层                                         │
│  AlbumViewModel / PluginViewModel / SettingsViewModel │
├──────────────────────────────────────────────────────┤
│  Repository 层                                        │
│  AlbumRepository / SettingsRepository                │
├──────────────────────────────────────────────────────┤
│  核心业务层                                           │
│  PluginAnalysisPipeline / CapabilityRegistryV2       │
│  HybridIndexer / ExtensionPluginRegistry             │
│  FaceDetector / SceneClassifier / SemanticEmbedder   │
├──────────────────────────────────────────────────────┤
│  数据层 (Room v11)                                    │
│  MediaDao / FaceDao / EmbeddingDao /                 │
│  FeatureStoreDao / PluginManifestDao                 │
└──────────────────────────────────────────────────────┘
```

#### 3.2 模块划分

| 包路径                  | 职责                                                           |
| ----------------------- | -------------------------------------------------------------- |
| `core/plugin/`          | AI 插件 SDK 契约层：接口定义、数据模型、JSON 编解码、加载/注册 |
| `core/plugin/runtime/`  | 3 种模型运行时实现 (TFLite/ONNX/PyTorch)                       |
| `core/pipeline/`        | 插件化分析管道：DAG 编排、阶段抽象、进度管理                   |
| `core/pipeline/stages/` | 7 个内置分析阶段实现                                           |
| `core/analysis/`        | 分析算法实现：人脸/场景/OCR/质量/语义/相似度/地理聚类          |
| `core/index/`           | 混合索引引擎 (HybridIndexer + MediaContentObserver)            |
| `core/model/`           | 核心数据模型 (MediaItem, Album, DirectoryNode)                 |
| `core/exif/`            | EXIF 元数据提取                                                |
| `core/search/`          | 语义搜索引擎                                                   |
| `core/recommendation/`  | 推荐引擎                                                       |
| `core/timeline/`        | 时间线分组逻辑                                                 |
| `data/db/`              | Room 数据库、实体、DAO、迁移                                   |
| `data/repo/`            | Repository 实现                                                |
| `data/backup/`          | 数据库 JSON 导入导出                                           |
| `data/worker/`          | WorkManager 后台任务                                           |
| `ui/`                   | Compose UI (Screens + Components + Theme)                      |
| `ui/vm/`                | ViewModel 实现                                                 |

#### 3.3 关键设计模式

- **工厂方法**: `PluginAnalysisPipeline.create()` 从 `CapabilityRegistryV2` 各槽位的激活 Provider 组装核心分析阶段
- **策略模式**: `ModelRuntime.create()` 根据模型格式返回对应运行时实现
- **策略 + 能力槽位**: `CapabilityRegistryV2` 提供可切换的 Provider 槽位（face/scene/semantic/quality/ocr）
- **适配器模式**（旧路径）: `PluginAnalysisStage` 将 `AiPlugin` 适配为统一的 `AnalysisStage` 接口（已废弃；扩展插件现通过 `ExtensionPluginRegistry` 交互式调用）
- **观察者模式**: 大量使用 Kotlin `StateFlow` / `SharedFlow` 进行响应式状态传递
- **依赖注入**: 手动 DI 容器 `AppContainer`
- **Sealed Class 多态**: `PluginInput` / `PluginOutput` 类型安全的输入输出模型

---

### 4. 核心功能模块

#### 4.1 媒体索引引擎 (HybridIndexer)

`HybridIndexer` 是媒体文件的全量/增量索引引擎，采用双通道策略保证覆盖率与性能。

**混合索引策略**：

- **MediaStore 通道**：通过 Android `ContentResolver` 查询系统媒体数据库，快速感知图片/视频增删
- **File API 通道**：遍历用户指定的扫描根目录，补充 MediaStore 未覆盖的文件（如自定义目录）
- **去重合并**：以 `filePath` 为键，MediaStore 数据优先（元数据更完整）

**增量扫描机制**：

1. 查询数据库中已有的 `(filePath, modifiedAtMs, fingerprintHead)` 快照
2. 重新枚举当前文件系统，对比 `modifiedAt` 和文件头部 SHA-256 指纹
3. `modifiedAt` 变化时二次校验指纹（`computeFingerprintHead()` 读取文件前 4096 字节），避免 MediaStore 时间戳漂移导致误判
4. 新增文件进入分析管道，修改文件重新分析，已删除记录从 DB 中移除

**ContentObserver 监听**：

- 通过 `MediaContentObserver` 监听 `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` 和 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`
- 变更后防抖触发增量扫描

**对外 API**：

```kotlin
// 全量扫描
suspend fun fullScan(roots: List<String>, allowNomedia: Boolean = false): Int

// 增量扫描
suspend fun incrementalScan(roots: List<String>, allowNomedia: Boolean = false): IncrementalResult

// 注册/注销 ContentObserver
fun registerContentObserver(onChanged: () -> Unit)
fun unregisterContentObserver()
```

#### 4.2 AI 插件系统

插件系统是 LocalAlbum 的核心扩展机制，允许用户在不修改宿主 APK 的情况下，通过安装外部插件 APK 来扩展 AI 分析能力。

##### 4.2.1 核心数据模型

**`PluginManifest`**：
描述插件的完整配置（即 `model_manifest.json` 的内存表示）：

| 字段                               | 类型                                       | 说明                                                                           |
| ---------------------------------- | ------------------------------------------ | ------------------------------------------------------------------------------ |
| `pluginId`                         | String                                     | 插件唯一标识，如 `"face_swap_v1"`                                              |
| `name`                             | String                                     | 显示名称                                                                       |
| `taskType`                         | TaskType                                   | 任务类型：`CLASSIFICATION` / `FEATURE_EXTRACTION` / `DETECTION` / `GENERATIVE` |
| `modelFormat`                      | ModelFormat                                | 模型格式：`TFLITE` / `ONNX` / `PYTORCH`                                        |
| `modelFilePath`                    | String                                     | 模型文件相对路径                                                               |
| `entryClass`                       | String                                     | 插件入口类全限定名（实现 `AiPlugin`）                                          |
| `inputTensors` / `outputTensors`   | List\<TensorSpec\>                         | 输入/输出张量规格                                                              |
| `preprocessing` / `postprocessing` | PreprocessingConfig / PostprocessingConfig | 预处理/后处理参数                                                              |
| `pipelineStage`                    | String                                     | 管道阶段标识（决定执行顺序）                                                   |
| `dependsOn`                        | List\<String\>                             | 前置依赖插件 ID 列表                                                           |
| `authorizedCertificateFingerprint` | String?                                    | 签名证书 SHA-256 指纹（可选）                                                  |

**`AiPlugin`**：
所有插件必须实现的顶层 SPI 接口。生命周期：

1. `initialize(context, pluginContext)` — 加载模型文件
2. `execute(input): PluginOutput` — 执行推理
3. `release()` — 释放资源（`suspend` 方法）

此外还须实现 `getId()`、`getManifest()`、`getInputSchema()`、`getOutputSchema()`、`isReady()` 等元信息方法。

根据任务类型细分为 4 种子接口：

- `ClassificationPlugin`：图像 → 标签 + 置信度
- `FeatureExtractionPlugin`：图像 → 特征向量
- `DetectionPlugin`：图像 → 边界框列表
- `GenerativePlugin`：多模态输入 → 生成图像

**`PluginInput` / `PluginOutput`**：
使用 `sealed class` 实现类型安全的多模态数据流：

- `ImageInput` / `TensorInput` / `MultiModalInput`
- `ClassificationOutput` / `FeatureOutput` / `DetectionOutput` / `ImageOutput`

**`FeatureSchema`**：
描述插件输出的异构特征数据结构，支持任意维度向量、自定义标签、坐标等。序列化为 JSON 后与数据一同持久化到 `feature_store` 表。

##### 4.2.2 插件加载与注册

> **架构说明**：当前插件体系拆分为两个注册中心：
>
> - **`CapabilityRegistryV2`**：管理批处理分析管道的能力槽位（face/scene/semantic/quality/ocr），每个槽位可注册多个可切换的 Provider，由 `PluginAnalysisPipeline` 取激活 Provider 组装阶段。
> - **`ExtensionPluginRegistry`**：管理交互式扩展插件（换脸、风格迁移等），不参与批处理管道，通过 UI 入口触发。下方 `PluginLoader` / `PluginRegistry` 描述适用于扩展插件加载链路。

**`PluginLoader`**：
使用 `DexClassLoader` 在运行时动态加载外部 APK 插件，实现真正的热插拔：

1. 扫描 `context.filesDir/plugins/` 目录下的 `.apk` 文件
2. 从 APK 的 `assets/plugin_manifest.json` 读取插件清单
3. 使用 `PluginJsonCodec` 校验清单完整性
4. 可选：通过 `PackageManager` 获取 APK 签名证书 SHA-256 指纹，与 manifest 中声明的指纹对比
5. 创建 `DexClassLoader`（`parent = context.classLoader`，使插件可访问宿主 `AiPlugin` 接口）
6. 通过 `Class.forName(entryClass, true, classLoader)` 实例化插件，校验是否实现 `AiPlugin` 接口

**安全机制**：

- `AtomicBoolean` 防并发重入（`loadAll` 调用安全）
- 签名校验：`verifySignature()` 提取 APK 签名并与 manifest 声明指纹比对

**`PluginRegistry`**：
插件注册中心，管理插件生命周期并提供查询能力：

- **状态机**：`DISCOVERED → LOADING → INITIALIZING → READY → UNLOADED`（含 `ERROR` 终态）
- **并发保护**：所有对 `plugins`/`manifests`/`stateMap` 的写操作通过 `Mutex.withLock` 串行化
- **异步初始化**：插件 `initialize()` 在各自专属的 `CoroutineScope` 中并发执行，通过 `awaitAll` 等待就绪
- **资源释放**：`shutdown()` 释放所有插件资源 + 取消协程作用域 + 取消注册中心自身 scope
- **StateFlow 暴露**：`pluginStates: StateFlow<List<PluginInfo>>` 供 UI 观察所有插件状态
- **内置插件支持**：`registerBuiltInPlugin()` 跳过 APK 加载链路，直接注册编译期内置的插件

**查询 API**：

```kotlin
fun getPlugin(pluginId: String): AiPlugin?
fun getByTaskType(taskType: PluginManifest.TaskType): List<AiPlugin>
fun getLoadedPlugins(): List<AiPlugin>  // 仅返回 isReady() 的插件
fun listAll(): List<AiPlugin>
suspend fun loadAll(): LoadSummary
suspend fun unload(pluginId: String): Boolean
```

##### 4.2.3 插件配置管理

三个 Screen 共享同一 `PluginViewModel`：

- **可视化向导** `ModelImportWizardScreen`：
  4 步流程：选择模型文件（SAF）→ 确认模型元数据（格式、文件名，自动解析张量规格回填）→ 配置插件信息（名称、任务类型、输入/输出张量、特征 Schema）→ 保存完成

- **JSON 编辑器** `ModelJsonEditorScreen`：
  面向高级用户的 `model_manifest.json` 直接编辑器，支持：
  - 手动编辑 + 一键重置模板（`initEditorFromTemplate()`）
  - 从 `.json` 文件导入（SAF `OpenDocument`）
  - 800ms 防抖自动校验 + 手动「校验」按钮
  - 错误信息含行号/字符位置定位（`extractJsonErrorLine()` 解析 `at line N` / `at character N`）

- **插件管理** `PluginManagerScreen`：
  核心功能模型状态看板，展示人脸聚类、地图聚类、语义识别、换脸四大核心功能的模型可用性（就绪 / 未下载 / 下载中 / 出错）；换脸功能卡片提供「进入换脸」入口跳转 `FaceSwapScreen`。

**持久化**：导入的插件清单存于 `plugin_manifest` 表，通过 `PluginManifestDao` 管理（含 `orderIndex` 排序字段）。

#### 4.3 模型运行时 (ModelRuntime)

`ModelRuntime` 接口统一封装三种端侧模型格式的推理调用，使插件开发者无需关心底层框架差异：

```kotlin
interface ModelRuntime {
    suspend fun initialize(context: Context, modelFile: File, manifest: PluginManifest)
    fun isReady(): Boolean
    suspend fun invoke(inputs: Map<String, ByteBuffer>): Map<String, ByteBuffer>
    fun getInputTensors(): List<TensorSpec>
    fun getOutputTensors(): List<TensorSpec>
    fun close()
}
```

**三种实现**：

| 实现                  | 核心 API                                    | 特点                                                            |
| --------------------- | ------------------------------------------- | --------------------------------------------------------------- |
| `TfliteModelRuntime`  | `Interpreter.runForMultipleInputsOutputs()` | 自动分配 ByteBuffer；线程数优化（≤4 核）                        |
| `OnnxModelRuntime`    | `OrtSession.run()`                          | `OnnxTensor.createTensor()` 包装；FloatArray/LongArray 双向提取 |
| `PyTorchModelRuntime` | `Module.forward()`                          | 多输入变长参数；Tensor Blob 创建/提取                           |

**工厂方法**：`ModelRuntime.create(format)` 根据 `PluginManifest.ModelFormat` 返回对应实现。

#### 4.4 插件化分析管道 (PluginAnalysisPipeline)

`PluginAnalysisPipeline` 是 AI 分析的核心编排引擎。各阶段从 `CapabilityRegistryV2` 各槽位的激活 Provider 组装，另含两个固定实现阶段（geo、similarity）。扩展 AI 插件（换脸、风格迁移等）**不参与批处理管道**，改由 `ExtensionPluginRegistry` 交互式调用。

**DAG 拓扑排序**：

- `StageDagSorter` 使用 Kahn 算法根据每个阶段的 `dependencies` 计算执行顺序
- 检测循环依赖，抛出 `CyclicDependencyException`
- 未知依赖记录警告后忽略

**执行模式**：

| 方法                                         | 说明                                             |
| -------------------------------------------- | ------------------------------------------------ |
| `runFullScan(filePaths)`                     | 对所有文件按拓扑顺序逐阶段执行                   |
| `runIncremental(incrementalPaths, allPaths)` | 缓存型阶段仅处理增量文件，非缓存型阶段对全量重算 |

**异常隔离**：单个阶段执行失败不影响后续阶段，错误记录到 `StageResult.extra` 并通过 `StageProgress.StageStatus.ERROR` 暴露。

**进度暴露**：

- `stageProgressFlow: SharedFlow<StageProgress>` — 每阶段推出一组进度事件
- `pipelineStatusFlow: StateFlow<Status>` — IDLE → RUNNING → COMPLETED / ERROR
- `pipelineResults: StateFlow<Map<String, StageResult>>` — 管道完成后所有阶段的汇总结果

**工厂方法 `PluginAnalysisPipeline.create(mediaDao, faceDao, embeddingDao, capabilityRegistry)`** 遍历已注册能力槽位并映射到对应阶段，共 7 个阶段（5 个能力槽位 + 2 个固定实现）：

| 阶段 ID              | 来源                         | 功能                |
| -------------------- | ---------------------------- | ------------------- |
| `core:face`          | `FaceProvider` 槽位          | 人脸检测 + 嵌入提取 |
| `core:quality`       | `QualityProvider` 槽位       | 图片质量评分        |
| `core:scene`         | `SceneProvider` 槽位         | 场景分类            |
| `core:semantic`      | `SemanticEmbedProvider` 槽位 | 语义嵌入生成        |
| `core:ocr`           | `OcrProvider` 槽位           | OCR 文字识别        |
| `builtin:geo`        | 固定实现                     | GPS 地理编码        |
| `builtin:similarity` | 固定实现                     | 感知哈希相似度      |

旧版 `PluginAnalysisStage` 适配器（将 `AiPlugin` 包裹进批处理管道并持久化到 `feature_store`）已废弃。

#### 4.5 进度管理 (ProgressManager)

系统提供双轨进度管理：

**管道级** `ProgressManager`：

- 全局进度 = 已完成阶段占比 + 当前阶段文件进度（按 `1/总阶段数` 加权），非固定 60/40 比例
- ETA 估算：按当前阶段处理速率（已处理文件数 / 已耗时）独立估算，每阶段重置计时基准
- 暴露 `progress: StateFlow<AnalysisProgress>` 供 UI 观察

**任务级** `ProgressManager`：

- 维护多任务（多阶段管道）进度状态
- 滑动窗口 ETA 估算（最近 20 个样本的移动平均速率）
- 加权全局进度聚合
- 提供 `getReporter(taskId)` 返回 `ProgressReporter` 接口供插件内部调用

**UI 展示** `GlobalProgressIndicator`：

- 悬浮卡片：`AnimatedVisibility` 自动显示/隐藏
- 折叠态：进度条 + 百分比 + 当前阶段 + 文件/阶段统计 + ETA
- 展开态：阶段详情 + 文件详情 + 处理速率 + 错误信息 + **取消按钮**
- 3 个 `@Preview` 覆盖运行中/出错/接近完成状态

#### 4.6 AI 分析能力

| 分析模块     | 实现方式                                             | 输出              |
| ------------ | ---------------------------------------------------- | ----------------- |
| 人脸检测     | InsightFace（默认）+ RetinaFace/SCRFD/ArcFace/ML Kit | 人脸框 + 特征嵌入 |
| 人脸聚类     | DBSCAN 聚类                                          | 人物分组          |
| 场景分类     | MobileNetV2 TFLite + 启发式                          | 场景标签          |
| 质量评分     | 启发式算法                                           | 0~1 质量分        |
| OCR          | PaddleOCR + ML Kit + GLM-OCR                         | 识别文字          |
| 语义嵌入     | EVA02-CLIP ONNX + MobileCLIP TFLite + 概念向量       | 特征向量          |
| 相似度       | 感知哈希                                             | 相似组            |
| 地理聚类     | 基于距离的聚类                                       | 地理位置分组      |
| 反向地理编码 | Android Geocoder                                     | 地名              |
| EXIF 提取    | ExifInterface                                        | 拍摄参数、GPS     |

#### 4.7 数据持久化层

**Room 数据库 v11**，6 张实体表：

| 表                 | 实体                   | 说明                                                           |
| ------------------ | ---------------------- | -------------------------------------------------------------- |
| `media_items`      | `MediaEntity`          | 媒体主表 (40+ 字段：EXIF、场景、质量、指纹、OCR、地理、相似组) |
| `media_items_fts`  | `MediaFts`             | FTS4 全文索引（文件名 + OCR 文本）                             |
| `faces`            | `FaceEntity`           | 人脸检测结果（坐标 + 嵌入 + 聚类 ID）                          |
| `media_embeddings` | `MediaEmbedding`       | 语义嵌入向量                                                   |
| `feature_store`    | `FeatureStoreEntity`   | **通用异构特征存储**（JSON Blob + Schema 描述）                |
| `plugin_manifest`  | `PluginManifestEntity` | 插件清单持久化（含 `orderIndex`）                              |

**数据库迁移**：

- `MIGRATION_8_9`：新增 `feature_store` 表（4 个索引）
- `MIGRATION_9_10`：新增 `plugin_manifest` 表（3 个索引）
- `MIGRATION_10_11`：`plugin_manifest` 新增 `orderIndex` 列

#### 4.8 搜索系统

**关键词搜索** (`AlbumRepository.search()`)：

- 优先 FTS4 全文索引（短语匹配 + 通配符 + OR 连接）
- 失败时回退到 `LIKE` + OCR 文本模糊匹配

**语义搜索** (`AlbumRepository.semanticSearch()`)：

- `SemanticSearcher` 将自然语言查询编码为语义向量
- 余弦相似度检索 `media_embeddings` 表
- 支持跨语言搜索（中文查询 ↔ 英文概念 ↔ 图像特征）

**混合检索** (`AlbumRepository.hybridSearch()`)：

- 并行执行语义搜索和关键词搜索
- 语义结果在前，关键词结果为辅，去重后合并

#### 4.9 数据库导入导出

**导出** (`DatabaseExporter`)：

- 将全部索引数据（媒体记录、人脸记录、语义嵌入）序列化为 JSON
- 包含元数据（设备型号、导出时间、记录计数）

**导入** (`DatabaseImporter`)：

- 覆盖式恢复：清空现有数据后从 JSON 导入
- 导入前校验文件格式完整性
- 导入后自动重建相册树

---

### 5. 模型配置

LocalAlbum 的大部分 AI 功能需要模型文件。模型文件存放在 `app/src/main/assets/models/` 目录下。

小/中型模型（EVA02-CLIP、PP-OCR）**已内置在仓库中**，随 `git clone` 获得。大型模型**不入库**（见 `.gitignore`），构建前需自行下载。

#### 下载大型模型

使用提供的脚本从 GitHub Releases 下载全部大型模型：

```bash
chmod +x scripts/download_models.sh
./scripts/download_models.sh
```

该脚本幂等——已存在的文件会跳过。它从 `https://github.com/r-y-ren/Local-Album/releases/tag/v0.1.0` 获取换脸、人脸检测/识别与 emap 模型。

#### 换脸 emap 矩阵

`emap_512.bin`（inswapper source latent 变换矩阵）同样由脚本下载。也可从 `inswapper_128.onnx` 本地重新生成：

```bash
pip install onnx numpy
python scripts/extract_emap.py
```

#### 模型文件清单

| 文件                                       | 功能                    | 是否内置                      |
| ------------------------------------------ | ----------------------- | ----------------------------- |
| `eva02_clip/eva02_visual_336_int8.onnx`    | 语义搜索（图像）        | 是（仓库内置）                |
| `eva02_clip/eva02_text_int8.onnx`          | 语义搜索（文本）        | 是（仓库内置）                |
| `PP-OCRv6_small_det_infer/inference.onnx`  | OCR 检测                | 是（仓库内置）                |
| `PP-OCRv5_mobile_rec_infer/inference.onnx` | OCR 识别                | 是（仓库内置）                |
| `inswapper_128.onnx`                       | 换脸                    | 否 — `download_models.sh`     |
| `buffalo_l.zip`                            | 人脸检测 + ArcFace 嵌入 | 否 — `download_models.sh`     |
| `retinaface-resnet50.onnx`                 | 人脸检测                | 否 — `download_models.sh`     |
| `scrfd_person_2.5g.onnx`                   | 人脸/人体检测           | 否 — `download_models.sh`     |
| `emap_512.bin`                             | 换脸（emap 矩阵）       | 否 — 脚本 / `extract_emap.py` |

> **注意**：大型模型二进制（`*.onnx` > ~50 MB、`*.zip`、`*.bin`）均被 git 忽略；仅小/中型模型与配置文件（vocab、merges、tokenizer、inference.yml）入库。

---

### 6. 配置与定制

#### 6.1 build.gradle.kts 关键配置

位于 `app/build.gradle.kts`：

```kotlin
// ABI 过滤器 — 若需支持更多架构，修改此列表
ndk {
    abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
}

// Compose 编译器版本
composeOptions {
    kotlinCompilerExtensionVersion = "1.5.14"
}

// 单元测试配置
testOptions {
    unitTests.isReturnDefaultValues = true  // 允许 android.graphics.RectF 等类在 JVM 测试中使用
}

// Release 混淆 + 资源压缩 + 签名（见 2.4 节正式包构建）
buildTypes {
    release {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
        if (rootProject.file("keystore.properties").exists()) {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

#### 6.2 应用入口与 DI 初始化

`AppContainer` 在 `LocalAlbumApplication.onCreate()` 中创建：

- 初始化 Room 数据库
- 创建 `CapabilityRegistryV2`（注册 5 个能力槽位及各 Provider）与 `ExtensionPluginRegistry`
- 调用 `loadPlugins()` 加载扩展插件（含重试机制：最多 2 次，间隔 1 秒）
- 懒加载创建 `PluginAnalysisPipeline`（从 `CapabilityRegistryV2` 激活 Provider 组装核心阶段）
- 注册 `MediaContentObserver` 监听系统媒体库变更

#### 6.3 扫描目录与过滤

- 扫描根目录通过 `SettingsStore` 持久化（DataStore Preferences）
- 用户可在首次引导界面或设置中选择扫描根目录
- `allowNomedia` 选项控制是否展示含 `.nomedia` 文件的目录

---

### 7. 代码示例与使用指南

#### 7.1 触发媒体扫描

```kotlin
// 通过 AlbumViewModel 触发重新扫描
albumViewModel.rescan()

// 或直接通过 Repository
viewModelScope.launch {
    albumRepository.rescan()
}
```

#### 7.2 获取索引结果

```kotlin
// 获取相册树 (响应式)
albumViewModel.albumTree.collect { albums ->
    // albums: List<Album> — 按目录层级构建的相册树
}

// 获取叶子相册 (扁平列表)
albumViewModel.leafAlbums.collect { leafs ->
    // leafs: List<Album> — 不含子相册的叶子节点
}

// 获取分页媒体流 (时间线)
albumViewModel.pagedMedia.collect { pagingData ->
    // pagingData: PagingData<MediaItem>
}
```

#### 7.3 执行搜索

```kotlin
// 关键词搜索
albumViewModel.search("日落")

// 语义搜索
albumViewModel.setSemanticMode(true)
albumViewModel.semanticSearch("有山有水的风景照")

// 智能搜索 (根据当前模式自动选择)
albumViewModel.smartSearch("生日聚会")
```

#### 7.4 注册自定义 AI 插件

**方式一：通过 UI 导入**

1. 准备模型文件（`.tflite` / `.onnx` / `.ptl`）和 `model_manifest.json`
2. 在应用内进入"插件管理" → "导入插件"
3. 使用可视化向导或 JSON 编辑器完成导入

**方式二：编写插件 APK**

1. 创建一个 Android Library 模块
2. 实现 `AiPlugin` 接口（或其子接口）
3. 在 `assets/plugin_manifest.json` 中描述插件元数据
4. 构建 APK 后放入 `context.filesDir/plugins/` 目录
5. 调用 `extensionPluginRegistry.loadAll()` 加载

**`model_manifest.json` 示例**：

```json
{
  "pluginId": "my_custom_classifier",
  "name": "自定义分类器",
  "version": "1.0.0",
  "taskType": "CLASSIFICATION",
  "modelFormat": "TFLITE",
  "modelFilePath": "model.tflite",
  "entryClass": "com.example.plugin.MyClassifierPlugin",
  "inputTensors": [
    { "name": "input", "dataType": "FLOAT32", "shape": [1, 224, 224, 3] }
  ],
  "outputTensors": [
    { "name": "output", "dataType": "FLOAT32", "shape": [1, 1000] }
  ],
  "preprocessing": {
    "resizeWidth": 224,
    "resizeHeight": 224,
    "normalizeMean": [0.485, 0.456, 0.406],
    "normalizeStd": [0.229, 0.224, 0.225]
  },
  "pipelineStage": "classification"
}
```

#### 7.5 在管道中执行分析

```kotlin
// 获取管道实例 (已在 AppContainer 中组装完成)
val pipeline = appContainer.pluginAnalysisPipeline

// 全量分析
viewModelScope.launch {
    val results = pipeline.runFullScan(allMediaPaths)
    // results: Map<String, StageResult>
}

// 观察进度
viewModelScope.launch {
    pipeline.stageProgressFlow.collect { stageProgress ->
        // 更新 UI 进度条
    }
}

// 观察管道状态
viewModelScope.launch {
    pipeline.pipelineStatusFlow.collect { status ->
        when (status) {
            PluginAnalysisPipeline.Status.IDLE -> { /* 空闲 */ }
            PluginAnalysisPipeline.Status.RUNNING -> { /* 运行中 */ }
            PluginAnalysisPipeline.Status.COMPLETED -> { /* 完成 */ }
            PluginAnalysisPipeline.Status.ERROR -> { /* 出错 */ }
        }
    }
}
```

#### 7.6 查询特征存储

```kotlin
// 获取某个文件的所有插件特征
val features = featureStoreDao.getByFilePath("/sdcard/DCIM/photo.jpg")

// 获取某个插件生成的所有特征（用于全库相似度检索）
val allFeatures = featureStoreDao.getByPlugin("my_custom_classifier")

// 查询缺失某插件特征的文件（增量补齐）
val missingPaths = featureStoreDao.getMissingFeatures("my_custom_classifier", limit = 100)

// 获取模型版本过期的特征（模型升级后重建）
val stalePaths = featureStoreDao.getStaleFeatures("my_custom_classifier", currentVersion = 2)
```

#### 7.7 UI 集成全局进度指示器

```kotlin
// 在 Composable 顶层 (如 LocalAlbumApp) 中放置
Box(modifier = Modifier.fillMaxSize()) {
    // 主内容
    MainContent()

    // 全局进度指示器 (悬浮于底部)
    GlobalProgressIndicator(
        progressManager = appContainer.pluginAnalysisPipeline.progressManager,
        onCancel = { albumViewModel.cancelTask() },
        modifier = Modifier.align(Alignment.BottomCenter),
    )
}
```

---

### 8. 项目目录结构

```
LocalAlbum/
├── app/
│   ├── build.gradle.kts                    # 应用构建配置（依赖、ABI、Compose 版本）
│   ├── proguard-rules.pro                  # R8/ProGuard 混淆规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/models/              # AI 模型文件（小/中型入库，大型由脚本下载）
│       │   ├── cpp/CMakeLists.txt          # emutls 兼容层
│       │   ├── java/com/renyxin/localalbum/
│       │   │   ├── AppContainer.kt          # DI 容器
│       │   │   ├── LocalAlbumApplication.kt # Application 入口
│       │   │   ├── MainActivity.kt
│       │   │   ├── core/
│       │   │   │   ├── album/AlbumBuilder.kt
│       │   │   │   ├── analysis/            # AI 分析算法
│       │   │   │   ├── concurrent/           # 并发调度器
│       │   │   │   ├── exif/ExifExtractor.kt
│       │   │   │   ├── index/               # 混合增量索引引擎
│       │   │   │   ├── model/               # 核心数据模型
│       │   │   │   ├── pipeline/            # 插件化分析管道
│       │   │   │   │   └── stages/          # 7 个内置阶段
│       │   │   │   ├── plugin/              # AI 插件系统
│       │   │   │   │   ├── capability/      # 能力抽象层
│       │   │   │   │   ├── demo/            # 演示插件
│       │   │   │   │   ├── extension/       # 扩展插件
│       │   │   │   │   ├── model/           # 模型管理
│       │   │   │   │   └── runtime/         # 模型运行时
│       │   │   │   ├── recommendation/
│       │   │   │   ├── saf/                 # SAF 文件操作
│       │   │   │   ├── search/SemanticSearcher.kt
│       │   │   │   └── timeline/TimelineGrouper.kt
│       │   │   ├── data/
│       │   │   │   ├── backup/              # 数据库导入导出
│       │   │   │   ├── db/                  # Room 数据库 + 迁移
│       │   │   │   ├── prefs/SettingsStore.kt
│       │   │   │   ├── repo/                # Repository 实现
│       │   │   │   ├── source/              # 媒体数据源
│       │   │   │   └── worker/              # WorkManager 后台任务
│       │   │   └── ui/
│       │   │       ├── LocalAlbumApp.kt     # 应用根 Composable
│       │   │       ├── components/          # 可复用组件
│       │   │       ├── screen/              # 时间线页面
│       │   │       ├── screens/             # 各功能页面
│       │   │       ├── theme/Theme.kt       # Material 3 主题
│       │   │       └── vm/                  # ViewModel 实现
│       │   └── res/
│       └── test/                            # 单元测试
├── opencv/java/                             # OpenCV Java 封装模块
├── scripts/                                 # 辅助脚本
├── .github/                                 # CI/CD + Issue/PR 模板
├── build.gradle.kts                         # 根构建脚本
├── settings.gradle.kts                      # 模块配置 + 仓库
├── gradle.properties
├── gradlew / gradlew.bat                    # Gradle Wrapper
├── .gitignore
├── LICENSE                                  # Apache 2.0
├── CHANGELOG.md
├── CONTRIBUTING.md
├── SECURITY.md
├── CODE_OF_CONDUCT.md
└── README.md
```

---

### 9. 贡献与扩展

#### 9.1 参与开发

1. Fork 项目并创建功能分支
2. 遵循现有代码风格（KDoc 注释 + sealed class 多态 + Flow 响应式）
3. 为新功能编写单元测试
4. 提交 Pull Request

详见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

#### 9.2 扩展指南

**添加新的内置分析阶段**：

1. 实现 `AnalysisStage` 接口
2. 在 `PluginAnalysisPipeline.create()` 的槽位映射 `when` 分支中添加新阶段，或注册新的 `CapabilitySlot` + Provider
3. 设置正确的 `stageId`、`dependencies` 和 `displayName`

**添加新的模型格式支持**：

1. 实现 `ModelRuntime` 接口
2. 在 `ModelRuntime.create()` 的 `when` 分支中添加新格式映射
3. 在 `PluginManifest.ModelFormat` 枚举中添加新值
4. 在 `TensorMetadataParser` 中添加新格式的解析逻辑

**添加新的 UI 页面**：

1. 在 `ui/screens/` 下创建新的 Screen Composable
2. 在 `ui/vm/` 下创建对应的 ViewModel
3. 在 `LocalAlbumApp` 导航图中注册路由

---

### 10. 许可证

本项目采用 [Apache License 2.0](LICENSE)。

---

### 附录

#### A. 术语表

| 术语               | 说明                                                 |
| ------------------ | ---------------------------------------------------- |
| **DAG**            | 有向无环图，用于编排分析阶段的执行顺序               |
| **DexClassLoader** | Android 运行时类加载器，用于动态加载外部 APK 代码    |
| **ETA**            | 预估剩余时间（Estimated Time of Arrival）            |
| **FTS4**           | SQLite 全文搜索扩展，支持前缀匹配与短语查询          |
| **Kahn 算法**      | 基于入度为零的拓扑排序算法                           |
| **SAF**            | Android Storage Access Framework，用于跨应用文件访问 |
| **SPI**            | 服务提供者接口（Service Provider Interface）         |
| **JSON Blob**      | 以 JSON 字符串形式存储的异构数据，schema 描述其结构  |

#### B. 数据库版本演进

| 版本 | 变更                                                            |
| ---- | --------------------------------------------------------------- |
| v8   | 初始版本：media_items, faces, media_embeddings, media_items_fts |
| v9   | + feature_store（通用异构特征存储）                             |
| v10  | + plugin_manifest（插件清单持久化）                             |
| v11  | plugin_manifest + orderIndex（管道阶段排序）                    |
