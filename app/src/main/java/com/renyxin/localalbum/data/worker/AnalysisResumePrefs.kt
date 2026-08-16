package com.renyxin.localalbum.data.worker

import android.content.Context

/**
 * Explicit user/import analysis admission and crash-resume marker.
 *
 * Scan-owned tasks derive admission from scan lifecycle state. Only explicit null-scanId work uses
 * this marker, so a later automatic scan cannot silently undo a user pause. Process death preserves
 * true; the final bounded user-task window or explicit cancellation clears it.
 */
object AnalysisResumePrefs {

    private const val PREFS_NAME = "analysis_resume"
    private const val KEY_PENDING = "analysis_pending"
    private const val KEY_USER_PAUSED = "analysis_user_paused"

    fun setPending(context: Context, pending: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PENDING, pending)
            .apply()
    }

    fun isPending(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PENDING, false)
    }

    fun setUserPaused(context: Context, paused: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_USER_PAUSED, paused)
            .apply()
    }

    fun isUserPaused(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USER_PAUSED, false)
    }
}
