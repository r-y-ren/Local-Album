package com.renyxin.localalbum.edition

import android.content.Context
import com.renyxin.localalbum.core.pipeline.LiteScanFeaturePolicy
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.model.ModelManager
import com.renyxin.localalbum.core.search.KeywordSearchProfile

/**
 * Lite 版 edition 配置（本文件仅存在于 lite 源集）。
 *
 * - [features]：lite 功能开关矩阵（裁剪槽位 face/scene/quality，无语义搜索与插件管理）；
 * - [registerEditionCapabilityProviders] / [enqueueFaceClusterMaintenance]：保持与 full 版
 *   相同的签名以便 AppContainer 统一调用，lite 下均为 no-op（参数标记 UNUSED_PARAMETER）。
 */
object EditionConfiguration {
    val features = EditionFeatures(
        editionId = "lite",
        scanFeaturePolicy = LiteScanFeaturePolicy,
        registeredCapabilitySlots = EditionFeatures.LITE_CAPABILITY_SLOTS,
        keywordSearchProfile = KeywordSearchProfile.LITE,
        showPeopleAlbums = false,
        enableSemanticSearch = false,
        showAiAnalysisPreferences = false,
        showPluginManager = false,
        showFaceSwap = true,
        allowFaceClusterMaintenance = false,
    )

    @Suppress("UNUSED_PARAMETER")
    fun registerEditionCapabilityProviders(
        registry: CapabilityRegistryV2,
        modelManager: ModelManager,
        appContext: Context,
    ) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun enqueueFaceClusterMaintenance(context: Context) = Unit
}
