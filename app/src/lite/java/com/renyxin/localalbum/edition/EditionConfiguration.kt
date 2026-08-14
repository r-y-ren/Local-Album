package com.renyxin.localalbum.edition

import android.content.Context
import com.renyxin.localalbum.core.pipeline.LiteScanFeaturePolicy
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.model.ModelManager
import com.renyxin.localalbum.core.search.KeywordSearchProfile

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
