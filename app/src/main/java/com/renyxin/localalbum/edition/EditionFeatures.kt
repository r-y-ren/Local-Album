package com.renyxin.localalbum.edition

import com.renyxin.localalbum.core.pipeline.ScanFeaturePolicy
import com.renyxin.localalbum.core.search.KeywordSearchProfile

/**
 * Edition（full/lite）功能开关矩阵（main 共享层，由各 flavor 的
 * `edition/EditionConfiguration.features` 提供唯一实例）。
 *
 * 字段在两版的取值与影响：
 * - [editionId]："full" / "lite"，同时作为扫描 profileId 与删除协调器隔离键；
 * - [scanFeaturePolicy]：full = FullScanFeaturePolicy（人脸/场景/语义/质量/OCR 全量阶段），
 *   lite = LiteScanFeaturePolicy（裁剪后的阶段集合）。该 policy 经 AnalysisStageFactory
 *   的 StageInclusionPolicy 决定管线包含哪些阶段，与各 flavor 的
 *   `EditionAnalysisStageBindings` 提供的阶段实现联动；
 * - [registeredCapabilitySlots]：full = [ALL_CAPABILITY_SLOTS]（face/scene/semantic/quality/ocr），
 *   lite = [LITE_CAPABILITY_SLOTS]（face/scene/quality）。AppContainer 依据它决定注册哪些槽位；
 * - [keywordSearchProfile]：关键词搜索（FTS）可检索字段的裁剪口径；
 * - [showPeopleAlbums]：full=true（人物相册页），lite=false；
 * - [enableSemanticSearch]：full=true（注入 SemanticClusterRecommender 与语义 Provider 工厂），
 *   lite=false（搜索仅走关键词）；
 * - [showAiAnalysisPreferences]：full=true（AI 分析偏好设置卡片），lite=false；
 * - [showPluginManager]：full=true（插件管理/模型向导/JSON 编辑器入口 + Demo 插件初始化），
 *   lite=false（`LocalAlbumApp.pluginViewModel` 传 null，相关 Screen 不可达）；
 * - [showFaceSwap]：full/lite 均 true（换脸为独立交互能力，不依赖插件管理入口）；
 * - [allowFaceClusterMaintenance]：full=true（人脸聚类维护 Worker），lite=false。
 */
data class EditionFeatures(
    val editionId: String,
    val scanFeaturePolicy: ScanFeaturePolicy,
    val registeredCapabilitySlots: Set<String>,
    val keywordSearchProfile: KeywordSearchProfile,
    val showPeopleAlbums: Boolean,
    val enableSemanticSearch: Boolean,
    val showAiAnalysisPreferences: Boolean,
    val showPluginManager: Boolean,
    val showFaceSwap: Boolean,
    val allowFaceClusterMaintenance: Boolean,
) {
    fun allowsCapabilitySlot(slotId: String): Boolean = slotId in registeredCapabilitySlots

    companion object {
        val ALL_CAPABILITY_SLOTS: Set<String> = linkedSetOf(
            "face",
            "scene",
            "semantic",
            "quality",
            "ocr",
        )
        val LITE_CAPABILITY_SLOTS: Set<String> = linkedSetOf(
            "face",
            "scene",
            "quality",
        )
    }
}
