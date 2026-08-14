package com.renyxin.localalbum.edition

import androidx.compose.runtime.Composable
import com.renyxin.localalbum.ui.screens.OptionalSearchModeState
import com.renyxin.localalbum.ui.vm.AlbumViewModel

/** Lite exposes keyword/metadata search only. */
object EditionSearchContribution {
    const val quickAccessSubtitle = "关键词"

    @Composable
    @Suppress("UNUSED_PARAMETER")
    fun state(albumViewModel: AlbumViewModel): OptionalSearchModeState =
        OptionalSearchModeState.DISABLED
}
