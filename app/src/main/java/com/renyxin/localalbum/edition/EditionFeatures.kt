package com.renyxin.localalbum.edition

import com.renyxin.localalbum.core.pipeline.ScanFeaturePolicy
import com.renyxin.localalbum.core.search.KeywordSearchProfile

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
