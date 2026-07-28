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
 * 持久化用户设置。
 *  - 扫描根目录列表、忽略目录名规则
 *  - 主题模式 (0=System, 1=Light, 2=Dark)
 *  - 首次引导是否已完成
 */
class SettingsStore(private val context: Context) {

    private val store = context.settingsDataStore

    val scanRoots: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_SCAN_ROOTS]?.toList()?.sorted() ?: emptyList()
    }

    val ignoreDirNames: Flow<List<String>> = store.data.map { prefs ->
        prefs[KEY_IGNORE_DIRS]?.toList()?.sorted() ?: emptyList()
    }

    val themeMode: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: 0
    }

    val onboardingCompleted: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val showNomediaDirectories: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SHOW_NOMEDIA] ?: false
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
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_SHOW_NOMEDIA = booleanPreferencesKey("show_nomedia")
        val KEY_ALBUM_SORT = intPreferencesKey("album_sort")
        val KEY_GESTURE_GUIDE_SHOWN = booleanPreferencesKey("gesture_guide_shown")
    }
}