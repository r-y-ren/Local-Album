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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val showAnalysisProgressUi: Boolean = true,
    /** 相册排序模式: 0=名称, 1=日期, 2=大小, 3=数量 */
    val albumSortMode: Int = 0,
)

/** [SettingsRepository.state] 的中间层之一：扫描与基础显示设置（命名解构，字段与流一一对应）。 */
private data class ScanAndBasicSettings(
    val scanRoots: List<String>,
    val ignoreDirNames: List<String>,
    val themeMode: Int,
    val analysisSchedulingMode: AnalysisSchedulingMode,
    val onboardingCompleted: Boolean,
)

/** [SettingsRepository.state] 的中间层之一：AI 分析偏好的原始持久化值（推荐偏好单独在 [MiscSettings]）。 */
private data class AiRawSettings(
    val faceGroupingStrictness: Int,
    val faceMinimumGroupSize: Int,
    val ocrAnalysisScope: Int,
    val semanticSearchStrictness: Int,
    val semanticSearchResultCount: Int,
)

/** [SettingsRepository.state] 的中间层之一：推荐偏好与显示开关。 */
private data class MiscSettings(
    val recommendationPreference: Int,
    val showNomediaDirectories: Boolean,
    val showAnalysisProgressUi: Boolean,
    val albumSortMode: Int,
)

/**
 * 设置仓库：封装 [SettingsStore]，对外提供合并后的 [SettingsState] 流与增删操作。
 * 扫描域实际变化只提交持久“需要重建”提示；是否开始全库遍历仍由用户显式确认。
 */
class SettingsRepository(
    private val store: SettingsStore,
    private val onScanScopeChanged: suspend (reason: String) -> Unit,
) {
    private val scanScopeMutex = Mutex()

    val state: Flow<SettingsState> = combine(
        combine(
            store.scanRoots,
            store.ignoreDirNames,
            store.themeMode,
            store.analysisSchedulingMode,
            store.onboardingCompleted,
        ) { scanRoots, ignoreDirNames, themeMode, analysisSchedulingMode, onboardingCompleted ->
            ScanAndBasicSettings(
                scanRoots = scanRoots,
                ignoreDirNames = ignoreDirNames,
                themeMode = themeMode,
                analysisSchedulingMode = AnalysisSchedulingMode.fromPersistedValue(analysisSchedulingMode),
                onboardingCompleted = onboardingCompleted,
            )
        },
        combine(
            store.faceGroupingStrictness,
            store.faceMinimumGroupSize,
            store.ocrAnalysisScope,
            store.semanticSearchStrictness,
            store.semanticSearchResultCount,
        ) { faceGroupingStrictness, faceMinimumGroupSize, ocrAnalysisScope, semanticSearchStrictness, semanticSearchResultCount ->
            AiRawSettings(
                faceGroupingStrictness = faceGroupingStrictness,
                faceMinimumGroupSize = faceMinimumGroupSize,
                ocrAnalysisScope = ocrAnalysisScope,
                semanticSearchStrictness = semanticSearchStrictness,
                semanticSearchResultCount = semanticSearchResultCount,
            )
        },
        combine(
            store.recommendationPreference,
            store.showNomediaDirectories,
            store.showAnalysisProgressUi,
            store.albumSortMode,
        ) { recommendationPreference, showNomediaDirectories, showAnalysisProgressUi, albumSortMode ->
            MiscSettings(
                recommendationPreference = recommendationPreference,
                showNomediaDirectories = showNomediaDirectories,
                showAnalysisProgressUi = showAnalysisProgressUi,
                albumSortMode = albumSortMode,
            )
        },
    ) { basic, ai, misc ->
        val aiPreferences = AiAnalysisPreferences(
            faceGroupingStrictness = FaceGroupingStrictness.fromPersistedValue(ai.faceGroupingStrictness),
            faceMinimumGroupSize = ai.faceMinimumGroupSize,
            ocrAnalysisScope = OcrAnalysisScope.fromPersistedValue(ai.ocrAnalysisScope),
            semanticSearchStrictness = SemanticSearchStrictness.fromPersistedValue(ai.semanticSearchStrictness),
            semanticSearchResultCount = ai.semanticSearchResultCount,
            recommendationPreference = RecommendationPreference.fromPersistedValue(misc.recommendationPreference),
        ).normalized()
        SettingsState(
            scanRoots = basic.scanRoots,
            ignoreDirNames = basic.ignoreDirNames,
            themeMode = basic.themeMode,
            analysisSchedulingMode = basic.analysisSchedulingMode,
            aiAnalysisPreferences = aiPreferences,
            onboardingCompleted = basic.onboardingCompleted,
            showNomediaDirectories = misc.showNomediaDirectories,
            showAnalysisProgressUi = misc.showAnalysisProgressUi,
            albumSortMode = misc.albumSortMode,
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

    suspend fun addScanRoot(path: String) = scanScopeMutex.withLock {
        if (store.addScanRoot(path)) submitScanScopeChange("scan_root_added")
    }

    suspend fun removeScanRoot(path: String) = scanScopeMutex.withLock {
        if (store.removeScanRoot(path)) submitScanScopeChange("scan_root_removed")
    }

    suspend fun addIgnoreDir(name: String) = scanScopeMutex.withLock {
        if (store.addIgnoreDir(name)) submitScanScopeChange("ignore_rule_added")
    }

    suspend fun removeIgnoreDir(name: String) = scanScopeMutex.withLock {
        if (store.removeIgnoreDir(name)) submitScanScopeChange("ignore_rule_removed")
    }

    suspend fun setShowNomediaDirectories(show: Boolean) = scanScopeMutex.withLock {
        if (store.setShowNomediaDirectories(show)) submitScanScopeChange("nomedia_policy_changed")
    }

    /** Replays the DataStore half of an interrupted DataStore -> Room scope-change commit. */
    suspend fun replayPendingScanScopeChange() = scanScopeMutex.withLock {
        store.pendingScanScopeChangeReason.first()?.let { reason -> submitScanScopeChange(reason) }
    }

    /**
     * Linearization boundary shared with scope mutations. Callers may reserve a persistent scan run
     * inside [block], guaranteeing the returned settings cannot change between read and reservation.
     */
    suspend fun <T> withStableScanSettings(block: suspend (SettingsState) -> T): T =
        scanScopeMutex.withLock {
            block(state.first())
        }

    private suspend fun submitScanScopeChange(reason: String) {
        onScanScopeChanged(reason)
        // Conditional acknowledgement cannot erase a newer concurrent scope change.
        store.acknowledgeScanScopeChange(reason)
    }

    suspend fun setShowAnalysisProgressUi(show: Boolean) = store.setShowAnalysisProgressUi(show)
    suspend fun setAlbumSortMode(mode: Int) = store.setAlbumSortMode(mode)
    suspend fun setGestureGuideShown(shown: Boolean) = store.setGestureGuideShown(shown)
}