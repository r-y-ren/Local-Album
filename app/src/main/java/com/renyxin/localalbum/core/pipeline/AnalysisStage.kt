package com.renyxin.localalbum.core.pipeline

/**
 * 增强型进度回调类型别名。
 *
 * 在原有 [(Int, Int) -> Unit] 回调基础上新增两个可选参数：
 * - [filePath]：当前正在处理的文件绝对路径，用于 per-file 进度追踪
 * - [status]：文件的处理状态，用于区分处理中 vs 已完成
 *
 * 阶段实现可在处理每个文件后调用此回调，以启用 UI 层的 per-file 进度可视化。
 */
typealias EnhancedProgressCallback = suspend (
    processed: Int,
    total: Int,
    filePath: String?,
    status: FileProcessingStatus?,
) -> Unit

/**
 * 分析管道中的单个分析阶段抽象接口。
 *
 * 统一内置分析器和外部 AI 插件，使管道编排器可以按 DAG 拓扑排序统一调度。
 * 每个阶段拥有独立的 [stageId]、[dependencies] 关系和 [progressCallback] 回调，
 * 执行后返回 [StageResult]。
 *
 * ## 使用方式
 *
 * - 内置分析器：创建具体实现类（如 [BuiltinQualityStage]），在构造时注入其所需依赖
 * - 外部插件：由 [PluginAnalysisStage] 包裹，通过 [com.renyxin.localalbum.core.plugin.PluginRegistry] 获取
 *
 * @see StageDagSorter DAG 拓扑排序器，用于根据 dependencies 计算执行顺序
 * @see PluginAnalysisPipeline 核心管道编排器
 * @see ScanProgress 统一进度模型
 */
interface AnalysisStage {

    /** 阶段唯一标识（如 "builtin:quality"、"plugin:face_swap_v1"） */
    val stageId: String

    companion object {
        /** 统一的 stageId 常量，供 Stage 实现与 UI 层（ViewModel/进度组件）共用，避免字符串硬编码漂移 */
        const val STAGE_FACE = "core:face"
        const val STAGE_SCENE = "core:scene"
        const val STAGE_SEMANTIC = "core:semantic"
        const val STAGE_QUALITY = "core:quality"
        const val STAGE_OCR = "core:ocr"
        const val STAGE_GEO = "builtin:geo"
        const val STAGE_SIMILARITY = "builtin:similarity"

        /** 旧版 Builtin*Stage 使用的 stageId（保留用于兼容旧数据/旧管线） */
        const val STAGE_BUILTIN_FACE = "builtin:face"
        const val STAGE_BUILTIN_SCENE = "builtin:scene"
    }

    /** 阶段类型（内置 or 插件） */
    val stageType: StageType

    /** UI 进度显示名称（如 "质量评分"、"场景识别"） */
    val displayName: String

    /**
     * 前置阶段的 [stageId] 列表。
     *
     * 空列表表示无依赖，可与其他无依赖阶段并行调度。
     * 管道编排器通过 [StageDagSorter] 拓扑排序保证依赖阶段在前。
     */
    val dependencies: List<String>

    /**
     * 可跳过判定的缓存性质。
     *
     * 返回 true 表示该阶段为缓存型分析（如质量评分结果不会随时间变化），
     * 在增量扫描中若文件已存在该阶段的结果则可跳过。
     * 返回 false 表示该阶段每次都必须执行（如相似度聚类需全量重算）。
     */
    val isCacheable: Boolean get() = true

    /**
     * 执行分析阶段（简化回调签名）。
     *
     * @param filePaths 待分析的文件路径列表
     * @param progressCallback 进度回调：第一个参数为已处理数，第二个参数为总数。
     *   实现类应在处理过程中周期性调用此回调以更新 UI 进度。
     * @return [StageResult] 含成功/失败计数及扩展信息
     */
    suspend fun execute(
        filePaths: List<String>,
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> },
    ): StageResult

    /**
     * 执行分析阶段（增强回调签名，支持 per-file 进度）。
     *
     * 默认实现委托给简化签名版本（忽略 filePath/status），保持向后兼容。
     * 需要 per-file 进度追踪的阶段可覆盖此方法。
     *
     * @param filePaths 待分析的文件路径列表
     * @param enhancedCallback 增强进度回调，含 [filePath] 和 [status] 字段
     * @return [StageResult]
     */
    suspend fun executeEnhanced(
        filePaths: List<String>,
        enhancedCallback: EnhancedProgressCallback = { _, _, _, _ -> },
    ): StageResult {
        // 默认实现：将增强回调桥接为简化回调
        return execute(filePaths) { processed, total ->
            enhancedCallback(processed, total, null, null)
        }
    }
}

/**
 * 单个阶段的执行结果。
 *
 * @param successCount 成功处理数
 * @param failedCount 失败处理数
 * @param extra 扩展信息键值对（如聚类数量、标签分布等，供上层决策/日志记录）
 */
data class StageResult(
    val successCount: Int,
    val failedCount: Int,
    val extra: Map<String, String> = emptyMap(),
)

/**
 * 分析阶段分类。
 */
enum class StageType {
    /** 应用内置分析器 */
    BUILTIN,
    /** 外部动态 AI 插件 */
    PLUGIN,
}