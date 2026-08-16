package com.renyxin.localalbum.edition

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaQueryContext
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.ui.screens.QuickAccessCard
import com.renyxin.localalbum.ui.screens.settings.MoreFeatureListItem
import com.renyxin.localalbum.ui.screens.AiAnalysisPreferencesScreen
import com.renyxin.localalbum.ui.screens.FacesScreen
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import com.renyxin.localalbum.ui.vm.SettingsViewModel
/** Full-only navigation and UI implementation for people albums and AI analysis preferences. */
object EditionUiContribution {
    fun acceptsDestination(destinationId: String): Boolean = destinationId in destinationIds

    @Composable
    fun renderDestination(
        destinationId: String,
        albumViewModel: AlbumViewModel,
        settingsViewModel: SettingsViewModel,
        @Suppress("UNUSED_PARAMETER") scanState: ScanState,
        showAnalysisProgressUi: Boolean,
        onBack: () -> Unit,
        onMediaClick: (MediaItem, MediaQueryContext) -> Unit,
    ): Boolean = when (destinationId) {
        PEOPLE_DESTINATION -> {
            val faceClusters by albumViewModel.faceClusters.collectAsStateWithLifecycle()
            val faceStats by albumViewModel.faceStats.collectAsStateWithLifecycle()
            val faceProgress by albumViewModel.faceFileProgress.collectAsStateWithLifecycle()
            val isPipelineRunning by albumViewModel.isPipelineRunning.collectAsStateWithLifecycle()

            FacesScreen(
                faceClusters = faceClusters,
                faceStats = faceStats,
                pagedMediaForCluster = albumViewModel::pagedMediaForFaceCluster,
                isLoading = false,
                onBack = onBack,
                onMediaClick = { item, clusterId ->
                    onMediaClick(item, MediaQueryContext.Face(clusterId))
                },
                onSetName = albumViewModel::setPersonName,
                onRefresh = albumViewModel::forceReanalyzeAll,
                stageFileProgress = faceProgress,
                isPipelineRunning = isPipelineRunning,
                showAnalysisProgressUi = showAnalysisProgressUi,
            )
            true
        }

        ANALYSIS_PREFERENCES_DESTINATION -> {
            val settingsState by settingsViewModel.state.collectAsStateWithLifecycle()
            AiAnalysisPreferencesScreen(
                savedPreferences = settingsState.aiAnalysisPreferences,
                onSave = settingsViewModel::setAiAnalysisPreferences,
                onBack = onBack,
            )
            true
        }

        else -> false
    }

    @Composable
    fun primaryQuickAccessEntry(onNavigate: (String) -> Unit) {
        QuickAccessCard(
            icon = Icons.Filled.Face,
            title = "人物",
            subtitle = "按人脸分组",
            onClick = { onNavigate(PEOPLE_DESTINATION) },
        )
    }

    @Composable
    fun settingsFeatureEntries(onNavigate: (String) -> Unit) {
        MoreFeatureListItem(
            title = "AI 识别与结果偏好",
            description = "调整人物归并、OCR、语义搜索和精选推荐",
            imageVector = Icons.Default.AutoAwesome,
            iconTint = MaterialTheme.colorScheme.primary,
            onClick = { onNavigate(ANALYSIS_PREFERENCES_DESTINATION) },
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }

    private const val PEOPLE_DESTINATION = "people-albums"
    private const val ANALYSIS_PREFERENCES_DESTINATION = "analysis-preferences"
    private val destinationIds = setOf(
        PEOPLE_DESTINATION,
        ANALYSIS_PREFERENCES_DESTINATION,
    )
}
