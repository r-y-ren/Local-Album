package com.renyxin.localalbum.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.renyxin.localalbum.core.analysis.AiAnalysisPreferences
import com.renyxin.localalbum.core.analysis.AiAnalysisPreferencesRuntime
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingMode
import com.renyxin.localalbum.data.repo.SettingsRepository
import com.renyxin.localalbum.data.repo.SettingsState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<SettingsState> = repository.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SettingsState(scanRoots = emptyList(), ignoreDirNames = emptyList()),
    )

    val gestureGuideShown: StateFlow<Boolean> = repository.gestureGuideShown.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        false,
    )

    fun setThemeMode(mode: Int) = viewModelScope.launch { repository.setThemeMode(mode) }
    fun setAnalysisSchedulingMode(mode: AnalysisSchedulingMode) = viewModelScope.launch {
        repository.setAnalysisSchedulingMode(mode)
    }
    fun setAiAnalysisPreferences(preferences: AiAnalysisPreferences) = viewModelScope.launch {
        val normalized = preferences.normalized()
        repository.setAiAnalysisPreferences(normalized)
        AiAnalysisPreferencesRuntime.update(normalized)
    }
    fun setOnboardingCompleted(completed: Boolean) = viewModelScope.launch {
        repository.setOnboardingCompleted(completed)
    }
    fun addScanRoot(path: String) = viewModelScope.launch { repository.addScanRoot(path) }
    fun removeScanRoot(path: String) = viewModelScope.launch { repository.removeScanRoot(path) }
    fun addIgnoreDir(name: String) = viewModelScope.launch { repository.addIgnoreDir(name) }
    fun removeIgnoreDir(name: String) = viewModelScope.launch { repository.removeIgnoreDir(name) }
    fun setShowNomediaDirectories(show: Boolean) = viewModelScope.launch {
        repository.setShowNomediaDirectories(show)
    }
    fun setShowAnalysisProgressUi(show: Boolean) = viewModelScope.launch {
        repository.setShowAnalysisProgressUi(show)
    }
    fun setAlbumSortMode(mode: Int) = viewModelScope.launch {
        repository.setAlbumSortMode(mode)
    }
    fun setGestureGuideShown(shown: Boolean) = viewModelScope.launch {
        repository.setGestureGuideShown(shown)
    }
}