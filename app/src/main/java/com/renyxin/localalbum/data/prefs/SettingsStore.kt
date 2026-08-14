package com.renyxin.localalbum.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "local_album_settings"
)

/**
 * 持久化用户设置（DataStore Preferences）。
 *  - 扫描根目录列表、忽略目录名规则
 *  - 主题模式 (0=System, 1=Light, 2=Dark)
 *  - 扫描与 AI 识别进度 UI 是否显示
 *  - 首次引导是否已完成
 *
 * 各 Flow 属性与 [com.renyxin.localalbum.data.repo.SettingsRepository.state] 的嵌套
 * combine 分组（ScanAndBasicSettings / AiRawSettings / MiscSettings）按字段一一对应；
 * 新增设置项时需同步：本类 Flow + KEY + setter + Repository 对应中间数据类，
 * 全部为类型安全命名解构，无下标对应关系。
 */
class SettingsStore internal constructor(
    private val store: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.settingsDataStore)

    /** 扫描根目录绝对路径列表（去重、尾部 `/` 修剪，输出按字典序排序）。默认空列表 = 使用默认扫描策略。 */
    val scanRoots: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_SCAN_ROOTS]?.toList()?.sorted() ?: emptyList()
    }

    /** 忽略的目录名规则列表（按目录名精确/模式匹配，输出按字典序排序）。默认空列表。 */
    val ignoreDirNames: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_IGNORE_DIRS]?.toList()?.sorted() ?: emptyList()
    }

    /** 主题模式：0=跟随系统（默认），1=浅色，2=深色。写入时 coerceIn(0, 2)。 */
    val themeMode: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: 0
    }

    /** 分析调度模式: 0=自动, 1=稳定, 2=均衡, 3=性能 */
    val analysisSchedulingMode: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_ANALYSIS_SCHEDULING_MODE] ?: 0
    }

    /** 人脸聚类严格度：0=宽松，1=标准（默认），2=严格。写入时 coerceIn(0, 2)。 */
    val faceGroupingStrictness: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_FACE_GROUPING_STRICTNESS] ?: 1
    }

    /** 人脸最小成组人数：少于该值的簇不展示为分组。默认 2，范围 1..5。 */
    val faceMinimumGroupSize: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_FACE_MINIMUM_GROUP_SIZE] ?: 2
    }

    /** OCR 分析范围：0=关闭（默认），1=仅新图，2=全部。写入时 coerceIn(0, 2)。 */
    val ocrAnalysisScope: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_OCR_ANALYSIS_SCOPE] ?: 0
    }

    /** 语义搜索严格度：0=宽松，1=标准（默认），2=严格。写入时 coerceIn(0, 2)。 */
    val semanticSearchStrictness: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_SEMANTIC_SEARCH_STRICTNESS] ?: 1
    }

    /** 语义搜索返回结果数上限。默认 50，范围 20..100。 */
    val semanticSearchResultCount: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_SEMANTIC_SEARCH_RESULT_COUNT] ?: 50
    }

    /** 推荐偏好：0..4 枚举索引（由 RecommendationPreference.fromPersistedValue 解码）。默认 0。 */
    val recommendationPreference: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_RECOMMENDATION_PREFERENCE] ?: 0
    }

    /** 首次引导是否已完成。默认 false（未完成时 OnboardingScreen 接管首屏）。 */
    val onboardingCompleted: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    /** 扫描目录树时是否显示含 .nomedia 的目录。默认 false（遵循 .nomedia 约定跳过）。 */
    val showNomediaDirectories: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SHOW_NOMEDIA] ?: false
    }

    /** 默认保持现有行为：显示扫描与 AI 识别进度 UI。 */
    val showAnalysisProgressUi: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SHOW_ANALYSIS_PROGRESS_UI] ?: true
    }

    /** 相册排序模式: 0=名称, 1=日期, 2=大小, 3=数量 */
    val albumSortMode: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_ALBUM_SORT] ?: 0
    }

    /** 手势引导是否已展示过（仅首次安装后展示一次） */
    val gestureGuideShown: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_GESTURE_GUIDE_SHOWN] ?: false
    }

    suspend fun setShowNomediaDirectories(show: Boolean) {
        store.edit { prefs ->
            prefs[KEY_SHOW_NOMEDIA] = show
        }
    }

    suspend fun setShowAnalysisProgressUi(show: Boolean) {
        store.edit { prefs ->
            prefs[KEY_SHOW_ANALYSIS_PROGRESS_UI] = show
        }
    }

    suspend fun setAlbumSortMode(mode: Int) {
        store.edit { prefs ->
            prefs[KEY_ALBUM_SORT] = mode.coerceIn(0, 3)
        }
    }

    suspend fun setThemeMode(mode: Int) {
        store.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.coerceIn(0, 2)
        }
    }

    suspend fun setAnalysisSchedulingMode(mode: Int) {
        store.edit { prefs ->
            prefs[KEY_ANALYSIS_SCHEDULING_MODE] = mode.coerceIn(0, 3)
        }
    }

    suspend fun setAiAnalysisPreferences(
        faceGroupingStrictness: Int,
        faceMinimumGroupSize: Int,
        ocrAnalysisScope: Int,
        semanticSearchStrictness: Int,
        semanticSearchResultCount: Int,
        recommendationPreference: Int,
    ) {
        store.edit { prefs ->
            prefs[KEY_FACE_GROUPING_STRICTNESS] = faceGroupingStrictness.coerceIn(0, 2)
            prefs[KEY_FACE_MINIMUM_GROUP_SIZE] = faceMinimumGroupSize.coerceIn(1, 5)
            prefs[KEY_OCR_ANALYSIS_SCOPE] = ocrAnalysisScope.coerceIn(0, 2)
            prefs[KEY_SEMANTIC_SEARCH_STRICTNESS] = semanticSearchStrictness.coerceIn(0, 2)
            prefs[KEY_SEMANTIC_SEARCH_RESULT_COUNT] = semanticSearchResultCount.coerceIn(20, 100)
            prefs[KEY_RECOMMENDATION_PREFERENCE] = recommendationPreference.coerceIn(0, 4)
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        store.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setGestureGuideShown(shown: Boolean) {
        store.edit { prefs ->
            prefs[KEY_GESTURE_GUIDE_SHOWN] = shown
        }
    }

    suspend fun addScanRoot(path: String) {
        store.edit { prefs ->
            val current = prefs[KEY_SCAN_ROOTS] ?: emptySet()
            prefs[KEY_SCAN_ROOTS] = current + path.trimEnd('/')
        }
    }

    suspend fun removeScanRoot(path: String) {
        store.edit { prefs ->
            val current = prefs[KEY_SCAN_ROOTS] ?: emptySet()
            prefs[KEY_SCAN_ROOTS] = current - path.trimEnd('/')
        }
    }

    suspend fun addIgnoreDir(name: String) {
        store.edit { prefs ->
            val current = prefs[KEY_IGNORE_DIRS] ?: emptySet()
            prefs[KEY_IGNORE_DIRS] = current + name.trim()
        }
    }

    suspend fun removeIgnoreDir(name: String) {
        store.edit { prefs ->
            val current = prefs[KEY_IGNORE_DIRS] ?: emptySet()
            prefs[KEY_IGNORE_DIRS] = current - name.trim()
        }
    }

    private companion object {
        val KEY_SCAN_ROOTS = stringSetPreferencesKey("scan_roots")
        val KEY_IGNORE_DIRS = stringSetPreferencesKey("ignore_dirs")
        val KEY_THEME_MODE = intPreferencesKey("theme_mode")
        val KEY_ANALYSIS_SCHEDULING_MODE = intPreferencesKey("analysis_scheduling_mode")
        val KEY_FACE_GROUPING_STRICTNESS = intPreferencesKey("face_grouping_strictness")
        val KEY_FACE_MINIMUM_GROUP_SIZE = intPreferencesKey("face_minimum_group_size")
        val KEY_OCR_ANALYSIS_SCOPE = intPreferencesKey("ocr_analysis_scope")
        val KEY_SEMANTIC_SEARCH_STRICTNESS = intPreferencesKey("semantic_search_strictness")
        val KEY_SEMANTIC_SEARCH_RESULT_COUNT = intPreferencesKey("semantic_search_result_count")
        val KEY_RECOMMENDATION_PREFERENCE = intPreferencesKey("recommendation_preference")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_SHOW_NOMEDIA = booleanPreferencesKey("show_nomedia")
        val KEY_SHOW_ANALYSIS_PROGRESS_UI = booleanPreferencesKey("show_analysis_progress_ui")
        val KEY_ALBUM_SORT = intPreferencesKey("album_sort")
        val KEY_GESTURE_GUIDE_SHOWN = booleanPreferencesKey("gesture_guide_shown")
    }
}