package com.renyxin.localalbum.edition

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renyxin.localalbum.data.repo.SemanticSearchState
import com.renyxin.localalbum.ui.screens.OptionalSearchCopy
import com.renyxin.localalbum.ui.screens.OptionalSearchModeState
import com.renyxin.localalbum.ui.screens.OptionalSearchResult
import com.renyxin.localalbum.ui.screens.OptionalSearchStatus
import com.renyxin.localalbum.ui.vm.AlbumViewModel

/** Full-only adapter from semantic search state to the edition-neutral search UI contract. */
object EditionSearchContribution {
    const val quickAccessSubtitle = "关键词/语义"

    @Composable
    fun state(albumViewModel: AlbumViewModel): OptionalSearchModeState {
        val results by albumViewModel.semanticSearchResults.collectAsStateWithLifecycle()
        val active by albumViewModel.isSemanticMode.collectAsStateWithLifecycle()
        val status by albumViewModel.semanticSearchState.collectAsStateWithLifecycle()
        return OptionalSearchModeState(
            enabled = true,
            active = active,
            status = status.toOptionalStatus(),
            results = results.map { result ->
                OptionalSearchResult(
                    mediaItem = result.mediaItem,
                    relevance = result.similarity,
                )
            },
            copy = copy,
            onActiveChange = albumViewModel::setSemanticMode,
            onSearch = albumViewModel::semanticSearch,
            onClear = albumViewModel::clearSemanticSearch,
        )
    }

    private fun SemanticSearchState.toOptionalStatus(): OptionalSearchStatus = when (this) {
        SemanticSearchState.IDLE -> OptionalSearchStatus.IDLE
        SemanticSearchState.SEARCHING -> OptionalSearchStatus.SEARCHING
        SemanticSearchState.SUCCESS -> OptionalSearchStatus.SUCCESS
        SemanticSearchState.NO_RESULTS -> OptionalSearchStatus.NO_RESULTS
        SemanticSearchState.MODEL_NOT_READY -> OptionalSearchStatus.UNAVAILABLE
        SemanticSearchState.NO_INDEX -> OptionalSearchStatus.NO_INDEX
    }

    private val copy = OptionalSearchCopy(
        modeLabel = "语义搜索",
        activeModeDescription = "（英文自然语言匹配图片内容）",
        inactiveModeDescription = "（关键词匹配）",
        activePlaceholder = "语义搜索（请用英文）：sunset, beach…",
        emptyTitle = "输入英文描述搜索图片内容",
        emptyDescription = "如 sunset, beach, food, night view 等（语义模型对中文支持较差，请使用英文）",
        suggestionsTitle = "试试搜索",
        suggestions = listOf(
            "sunset",
            "beach",
            "food",
            "night view",
            "pet",
            "selfie",
            "landscape",
            "document",
        ),
        loadingTitle = "正在语义搜索…",
        unavailableTitle = "语义搜索模型加载失败",
        unavailableDescription = "CLIP 模型加载未成功，请稍后重试。如持续失败请检查设备可用内存（需约 500MB）",
        noIndexTitle = "尚未建立语义索引",
        noIndexDescription = "请先在设置中扫描并分析图片，完成后再使用语义搜索",
        noResultsTitleTemplate = "未找到与 \"{query}\" 语义匹配的媒体",
        noResultsDescription = "尝试更换英文关键词，如 sunset, beach, food, night view",
        fallbackNoResultsTitleTemplate = "未找到与 \"{query}\" 语义匹配的媒体",
        fallbackNoResultsDescription = "语义搜索需要先完成媒体分析，请确保已扫描并分析图片",
    )
}
