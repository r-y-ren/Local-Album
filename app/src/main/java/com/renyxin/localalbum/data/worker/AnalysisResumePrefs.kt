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
        preferences(context)
            .edit()
            .putBoolean(KEY_PENDING, pending)
            .apply()
    }

    fun isPending(context: Context): Boolean =
        preferences(context).getBoolean(KEY_PENDING, false)

    fun setUserPaused(context: Context, paused: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(KEY_USER_PAUSED, paused)
            .apply()
    }

    fun isUserPaused(context: Context): Boolean =
        preferences(context).getBoolean(KEY_USER_PAUSED, false)

    /**
     * Durably publishes explicit user-work admission before its Room ownership transition commits.
     * A stale true marker is harmless when the database mutation later fails; the reverse ordering
     * could strand null-scan tasks after process death.
     */
    fun resumeUserWork(context: Context): Boolean =
        preferences(context)
            .edit()
            .putBoolean(KEY_USER_PAUSED, false)
            .putBoolean(KEY_PENDING, true)
            .commit()

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
