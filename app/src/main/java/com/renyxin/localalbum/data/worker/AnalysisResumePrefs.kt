package com.renyxin.localalbum.data.worker

import android.content.Context

/**
 * AI 分析「中断待续跑」标志持久化（Phase 1）。
 *
 * 管道进入 RUNNING 时置 true，正常完成（COMPLETED/ERROR）时置 false。
 * 进程被杀后标志保持 true，下次启动据此触发自动续跑。
 */
object AnalysisResumePrefs {

    private const val PREFS_NAME = "analysis_resume"
    private const val KEY_PENDING = "analysis_pending"

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
}
