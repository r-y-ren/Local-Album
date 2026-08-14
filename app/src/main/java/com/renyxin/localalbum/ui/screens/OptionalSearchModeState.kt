package com.renyxin.localalbum.ui.screens

import com.renyxin.localalbum.core.model.MediaItem

data class OptionalSearchResult(
    val mediaItem: MediaItem,
    val relevance: Float,
)

enum class OptionalSearchStatus {
    IDLE,
    SEARCHING,
    UNAVAILABLE,
    NO_INDEX,
    NO_RESULTS,
    SUCCESS,
}

data class OptionalSearchCopy(
    val modeLabel: String,
    val activeModeDescription: String,
    val inactiveModeDescription: String,
    val activePlaceholder: String,
    val emptyTitle: String,
    val emptyDescription: String,
    val suggestionsTitle: String,
    val suggestions: List<String>,
    val loadingTitle: String,
    val unavailableTitle: String,
    val unavailableDescription: String,
    val noIndexTitle: String,
    val noIndexDescription: String,
    val noResultsTitleTemplate: String,
    val noResultsDescription: String,
    val fallbackNoResultsTitleTemplate: String,
    val fallbackNoResultsDescription: String,
)

data class OptionalSearchModeState(
    val enabled: Boolean,
    val active: Boolean,
    val status: OptionalSearchStatus,
    val results: List<OptionalSearchResult>,
    val copy: OptionalSearchCopy?,
    val onActiveChange: (Boolean) -> Unit,
    val onSearch: (String) -> Unit,
    val onClear: () -> Unit,
) {
    companion object {
        val DISABLED = OptionalSearchModeState(
            enabled = false,
            active = false,
            status = OptionalSearchStatus.IDLE,
            results = emptyList(),
            copy = null,
            onActiveChange = {},
            onSearch = {},
            onClear = {},
        )
    }
}
