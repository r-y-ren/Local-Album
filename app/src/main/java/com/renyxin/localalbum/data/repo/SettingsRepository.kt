package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.core.analysis.AiAnalysisPreferences
import com.renyxin.localalbum.core.analysis.FaceGroupingStrictness
import com.renyxin.localalbum.core.analysis.OcrAnalysisScope
import com.renyxin.localalbum.core.analysis.RecommendationPreference
import com.renyxin.localalbum.core.analysis.SemanticSearchStrictness
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingMode
import com.renyxin.localalbum.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * 对外统一的设置状态。
 */
data class SettingsState(
    val scanRoots: List<String>,
    val ignoreDirNames: List<String>,
    val themeMode: Int = 0,
    val analysisSchedulingMode: AnalysisSchedulingMode = AnalysisSchedulingMode.AUTO,
    val aiAnalysisPreferences: AiAnalysisPreferences = AiAnalysisPreferences(),
    val onboardingCompleted: Boolean = false,
    val showNomediaDirectories: Boolean = false,
    /** 相册排序模式: 0=名称, 1=日期, 2=大小, 3=数量 */
    val albumSortMode: Int = 0,
)

/**
 * 设置仓库：封装 [SettingsStore]，对外提供合并后的 [SettingsState] 流与增删操作。
 */
class SettingsRepository(private val store: SettingsStore) {

    val state: Flow<SettingsState> = combine(
        store.scanRoots,
        store.ignoreDirNames,
        store.themeMode,
        store.analysisSchedulingMode,
        store.faceGroupingStrictness,
        store.faceMinimumGroupSize,
        store.ocrAnalysisScope,
        store.semanticSearchStrictness,
        store.semanticSearchResultCount,
        store.recommendationPreference,
        store.onboardingCompleted,
        store.showNomediaDirectories,
        store.albumSortMode,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val roots = values[0] as List<String>
        @Suppress("UNCHECKED_CAST")
        val ignores = values[1] as List<String>
        val theme = values[2] as Int
        val schedulingMode = AnalysisSchedulingMode.fromPersistedValue(values[3] as Int)
        val aiPreferences = AiAnalysisPreferences(
            faceGroupingStrictness = FaceGroupingStrictness.fromPersistedValue(values[4] as Int),
            faceMinimumGroupSize = values[5] as Int,
            ocrAnalysisScope = OcrAnalysisScope.fromPersistedValue(values[6] as Int),
            semanticSearchStrictness = SemanticSearchStrictness.fromPersistedValue(values[7] as Int),
            semanticSearchResultCount = values[8] as Int,
            recommendationPreference = RecommendationPreference.fromPersistedValue(values[9] as Int),
        ).normalized()
        val onboarding = values[10] as Boolean
        val showNomedia = values[11] as Boolean
        val albumSort = values[12] as Int
        SettingsState(
            scanRoots = roots,
            ignoreDirNames = ignores,
            themeMode = theme,
            analysisSchedulingMode = schedulingMode,
            aiAnalysisPreferences = aiPreferences,
            onboardingCompleted = onboarding,
            showNomediaDirectories = showNomedia,
            albumSortMode = albumSort,
        )
    }

    val gestureGuideShown: Flow<Boolean> = store.gestureGuideShown

    suspend fun setThemeMode(mode: Int) = store.setThemeMode(mode)
    suspend fun setAnalysisSchedulingMode(mode: AnalysisSchedulingMode) =
        store.setAnalysisSchedulingMode(mode.persistedValue)
    suspend fun setAiAnalysisPreferences(preferences: AiAnalysisPreferences) {
        val value = preferences.normalized()
        store.setAiAnalysisPreferences(
            value.faceGroupingStrictness.persistedValue,
            value.faceMinimumGroupSize,
            value.ocrAnalysisScope.persistedValue,
            value.semanticSearchStrictness.persistedValue,
            value.semanticSearchResultCount,
            value.recommendationPreference.persistedValue,
        )
    }
    suspend fun setOnboardingCompleted(completed: Boolean) = store.setOnboardingCompleted(completed)
    suspend fun addScanRoot(path: String) = store.addScanRoot(path)
    suspend fun removeScanRoot(path: String) = store.removeScanRoot(path)
    suspend fun addIgnoreDir(name: String) = store.addIgnoreDir(name)
    suspend fun removeIgnoreDir(name: String) = store.removeIgnoreDir(name)
    suspend fun setShowNomediaDirectories(show: Boolean) = store.setShowNomediaDirectories(show)
    suspend fun setAlbumSortMode(mode: Int) = store.setAlbumSortMode(mode)
    suspend fun setGestureGuideShown(shown: Boolean) = store.setGestureGuideShown(shown)
}