package com.renyxin.localalbum.edition

import androidx.compose.runtime.Composable
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaQueryContext
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import com.renyxin.localalbum.ui.vm.SettingsViewModel

/** Lite registers no people-album, semantic-search, or AI-analysis-preference destination. */
object EditionUiContribution {
    internal val registeredDestinationIds: Set<String> = emptySet()

    fun acceptsDestination(destinationId: String): Boolean = destinationId in registeredDestinationIds

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun renderDestination(
        destinationId: String,
        albumViewModel: AlbumViewModel,
        settingsViewModel: SettingsViewModel,
        scanState: ScanState,
        showAnalysisProgressUi: Boolean,
        onBack: () -> Unit,
        onMediaClick: (MediaItem, MediaQueryContext) -> Unit,
    ): Boolean = false

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun primaryQuickAccessEntry(onNavigate: (String) -> Unit) = Unit

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun settingsFeatureEntries(onNavigate: (String) -> Unit) = Unit
}
