# LocalAlbum

<p align="center">
  <img src="LocalAlbum.svg" alt="LocalAlbum Logo" width="120" />
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="Apache-2.0" /></a>
  <a href="https://android-arsenal.com/api?level=29"><img src="https://img.shields.io/badge/API-29%2B-brightgreen.svg" alt="API 29+" /></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-1.9-purple.svg" alt="Kotlin 1.9" /></a>
</p>

> Android 10+ 的本地智能相册。媒体索引、AI 分析、搜索和特征数据均在设备端完成。

[English](#english) · [中文](#中文) · [开发贡献](CONTRIBUTING.md) · [变更记录](CHANGELOG.md) · [安全策略](SECURITY.md)

---

## 中文

### 当前功能

- **本地媒体库**：选择扫描目录后，使用 MediaStore 与文件系统双通道索引图片和视频；支持增量扫描、目录相册、时间线、收藏、回收站与媒体查看。
- **检索与整理**：支持文件名、相机信息、场景和 OCR 文本的全文检索；支持端侧语义搜索、按人物浏览、完全重复文件检测和推荐内容。
- **端侧 AI 分析**：对图片执行人脸检测/聚类、场景分类、质量评分、OCR 与语义嵌入。视频会参与媒体索引和播放，但当前不会进入图片 AI 分析流程。
- **模型与后端**：内置 TFLite、ONNX Runtime、PyTorch Mobile 运行时，以及可切换的能力 Provider。已对部分模型提供 CPU 回退和可观测性；不同设备、模型和 NNAPI 驱动的实际加速效果不同。
- **数据维护**：支持 JSON 导出/导入媒体索引、FTS、人脸与语义嵌入。导入是覆盖式恢复，写入过程使用单个 Room 事务，失败时不会提交部分数据。
- **后台体验**：扫描与分析期间显示进度；应用在前台时监听媒体库变更并防抖触发增量扫描。

### 当前边界与注意事项

- 应用的核心价值是**本地媒体管理和端侧分析**，不包含云端同步、账户系统或远程相册备份。
- AI 结果依赖模型文件、设备内存、图片质量与模型版本；首次全量分析可能耗时较长，并会增加发热和耗电。
- 语义搜索当前使用精确余弦相似度计算；媒体库很大时，搜索时间和内存占用会随嵌入数量增长。
- 导出文件含媒体路径、OCR 文本、人脸与语义特征等敏感索引数据。请仅保存到可信位置；跨设备导入后，若目录结构不同或原媒体不存在，记录可能指向无效路径。
- 外部 APK/Dex 动态插件加载代码仅保留为**隐藏的实验性能力**，不是面向普通用户的正式扩展接口。请勿导入来源不明的插件 APK；后续扩展方向见 [`plans/code-review-report.md`](plans/code-review-report.md)。

### 系统要求与权限

| 项目     | 要求                                                                   |
| -------- | ---------------------------------------------------------------------- |
| Android  | Android 10（API 29）及以上                                             |
| ABI      | `arm64-v8a` 真机；`x86_64` 模拟器                                      |
| 媒体权限 | Android 13+ 需要“照片和视频”权限；Android 10–12 需要“所有文件访问权限” |
| 通知权限 | 可选；用于显示长时间扫描/分析的前台通知                                |
| 网络     | 仅模型下载和远程模型目录使用；本地扫描与 AI 推理不需要网络             |

### 快速开始

1. 安装 Debug APK 或自行构建应用。
2. 首次启动时授予媒体访问权限，完成引导。
3. 在设置或引导页选择需要扫描的目录，例如 `DCIM`、`Pictures` 或 `Download`。
4. 等待媒体索引完成。图片 AI 分析会在后台继续运行，可通过全局进度查看阶段状态。
5. 在“搜索”中使用关键词或语义模式；在“相册”中查看目录、收藏、人物、重复项和回收站入口。

### 构建

#### 前置条件

- JDK 17 或更高版本
- Android SDK 35
- Android NDK `27.0.12077973`
- CMake `3.22.1`
- 已连接设备或启动中的模拟器（仅安装步骤需要）

#### 下载模型

模型资源位于 [`app/src/main/assets/models/`](app/src/main/assets/models/)。仓库只提交小型配置文件和 [`MobileNet-v3-Large.tflite`](app/src/main/assets/models/MobileNet-v3-Large.tflite)；ONNX、OCR、人脸与换脸模型通过脚本下载。

```bash
chmod +x scripts/download_models.sh
./scripts/download_models.sh
```

脚本从项目 Release 下载 EVA02-CLIP、PaddleOCR、人脸和换脸模型，已存在的非空文件会被跳过。模型下载失败时可重新执行脚本；请不要将大型二进制模型提交到 Git。

#### Debug 构建、测试与安装

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew installDebug
```

正式包使用项目根目录的 `keystore.properties` 注入签名信息。该文件和密钥库均不应提交到版本控制；未提供签名文件时，`assembleRelease` 仍可用于编译验证，但输出 APK 未签名。

### 架构概览

```text
Compose UI / ViewModels
        ↓
AlbumRepository / SettingsRepository
        ↓
HybridIndexer ─── PluginAnalysisPipeline ─── CapabilityRegistryV2
        ↓                    ↓
Room / DataStore       Provider + ModelManager
```

| 模块                                                                                          | 责任                                                     |
| --------------------------------------------------------------------------------------------- | -------------------------------------------------------- |
| [`core/index/`](app/src/main/java/com/renyxin/localalbum/core/index/)                         | MediaStore + 文件系统混合索引、增量检测、ContentObserver |
| [`core/pipeline/`](app/src/main/java/com/renyxin/localalbum/core/pipeline/)                   | 分阶段 AI 管道、DAG 调度、断点续跑和进度                 |
| [`core/plugin/capability/`](app/src/main/java/com/renyxin/localalbum/core/plugin/capability/) | 人脸、场景、语义、质量、OCR Provider 能力槽位            |
| [`core/plugin/model/`](app/src/main/java/com/renyxin/localalbum/core/plugin/model/)           | 模型下载、加载、对象池与后端策略                         |
| [`core/search/`](app/src/main/java/com/renyxin/localalbum/core/search/)                       | 关键词、语义和混合检索                                   |
| [`data/db/`](app/src/main/java/com/renyxin/localalbum/data/db/)                               | Room 实体、DAO 与数据库迁移                              |
| [`data/backup/`](app/src/main/java/com/renyxin/localalbum/data/backup/)                       | JSON 索引导入与导出                                      |
| [`ui/`](app/src/main/java/com/renyxin/localalbum/ui/)                                         | Compose 页面、组件、主题与导航                           |

### AI 能力与默认实现

| 能力 | 默认实现           | 结果                            |
| ---- | ------------------ | ------------------------------- |
| 人脸 | InsightFace        | 人脸框、特征向量与人物聚类      |
| 场景 | MobileNetV3 TFLite | 场景标签                        |
| 质量 | 启发式质量分析     | 质量分数                        |
| OCR  | PaddleOCR          | 图片文字与全文索引              |
| 语义 | EVA02-CLIP         | 图片/文本语义向量与自然语言搜索 |

可在模型管理页面查看模型状态并选择已注册的 Provider。模型不可用时，相应能力会跳过或根据 Provider 实现回退；不会阻止基础媒体浏览。

### 数据与隐私

- 媒体内容和索引默认保留在设备上；应用不提供云端上传流程。
- Room 数据库可能保存文件路径、拍摄时间、EXIF/GPS、OCR、人脸和语义特征。
- Android 系统备份行为由设备和系统设置决定。若需迁移索引，请优先使用应用提供的导出/导入流程，并妥善保护备份文件。
- 发现安全问题请参阅 [SECURITY.md](SECURITY.md)。

---

## English

### What it does

LocalAlbum is an Android 10+ local media manager. It indexes selected folders, organizes photos and videos, and runs supported AI analysis on images entirely on-device.

- Hybrid MediaStore and file-system indexing with incremental rescans.
- Directory albums, timeline, favorites, trash, media viewing, and video playback.
- On-device face detection and clustering, scene classification, quality scoring, OCR, and semantic embeddings for images.
- Keyword search over filenames, metadata, scene labels, and OCR text; semantic and hybrid search are also available.
- JSON index export/import for media records, FTS entries, faces, and embeddings.
- Provider-based model management for TFLite, ONNX Runtime, and PyTorch Mobile.

### Important limitations

- Videos are indexed and playable but are not processed by the image AI pipeline.
- AI results, throughput, memory use, and hardware acceleration depend on models and device/ROM capabilities.
- Index exports contain sensitive metadata such as paths, OCR text, face data, and embeddings. Treat them as private.
- Import replaces the current index. It is transactional, but restored absolute paths can be invalid on another device.
- Loading external APK/Dex plugins is hidden and experimental, **not** a supported end-user extension mechanism. Do not load untrusted plugin APKs.

### Build

Requirements: JDK 17+, Android SDK 35, NDK `27.0.12077973`, and CMake `3.22.1`.

```bash
chmod +x scripts/download_models.sh
./scripts/download_models.sh

./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew installDebug
```

The model-download script fetches the binary models excluded from source control. The Debug APK can be built after required models are present. See the Chinese sections above for the detailed architecture, privacy notes, and setup flow.

### Documentation

- [Contributing guide](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)
- [Security policy](SECURITY.md)
- [Code review and planned improvements](plans/code-review-report.md)

## License

Licensed under the [Apache License 2.0](LICENSE).
