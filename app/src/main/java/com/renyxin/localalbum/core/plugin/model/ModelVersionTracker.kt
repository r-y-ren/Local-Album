package com.renyxin.localalbum.core.plugin.model

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 模型版本追踪器（Phase 6 高级特性）。
 *
 * 持久化记录每个模型的当前版本号，在模型升级后检测版本变化，
 * 触发关联特征的重新计算。
 *
 * ## 工作流
 *
 * ```
 * ensureModelReady(modelId)
 *   ↓
 * ModelVersionTracker.checkVersion(modelId, newVersion)
 *   ↓ 版本变化?
 *   ├── 返回 true（版本已升级）
 *   └── 更新持久化版本号
 * ```
 *
 * ## 使用方式
 *
 * ```kotlin
 * val tracker = ModelVersionTracker(context)
 * val versionChanged = tracker.checkAndUpdateVersion("model:arcface", 2)
 * if (versionChanged) {
 *     // 触发人脸嵌入的重新计算
 * }
 * ```
 */
class ModelVersionTracker(context: Context) {
    companion object {
        private const val TAG = "ModelVersionTracker"
        private const val PREFS_NAME = "model_versions"
        private const val KEY_PREFIX = "model_version_"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 检查模型版本是否已变更，如果变更则自动更新记录。
     *
     * @param modelId 模型 ID
     * @param newVersion 新版本号
     * @return true 表示版本已升级，需要重新计算特征
     */
    fun checkAndUpdateVersion(modelId: String, newVersion: Int): Boolean {
        val key = "$KEY_PREFIX$modelId"
        val oldVersion = prefs.getInt(key, 0)

        if (oldVersion != newVersion && oldVersion > 0) {
            Log.i(TAG, "模型版本已升级: $modelId v$oldVersion → v$newVersion")
            prefs.edit().putInt(key, newVersion).apply()
            return true
        }

        if (oldVersion == 0) {
            // 首次记录
            prefs.edit().putInt(key, newVersion).apply()
            Log.d(TAG, "模型版本首次记录: $modelId v$newVersion")
        }

        return false
    }

    /**
     * 获取已记录的模型版本号。
     *
     * @param modelId 模型 ID
     * @return 版本号，未记录返回 0
     */
    fun getRecordedVersion(modelId: String): Int {
        return prefs.getInt("$KEY_PREFIX$modelId", 0)
    }

    /**
     * 清除指定模型的版本记录（用于卸载模型后清理）。
     */
    fun clearVersion(modelId: String) {
        prefs.edit().remove("$KEY_PREFIX$modelId").apply()
        Log.d(TAG, "模型版本记录已清除: $modelId")
    }

    /**
     * 清除所有模型的版本记录。
     */
    fun clearAll() {
        prefs.edit().clear().apply()
        Log.d(TAG, "所有模型版本记录已清除")
    }
}
