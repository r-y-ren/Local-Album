package com.renyxin.localalbum.data.worker

import android.content.Context

/**
 * 核心扫描的用户暂停标记。
 *
 * "停止本次分析"对核心扫描车道此前是空操作（只取消增强类 Worker，不碰 ScanWorker），
 * 造成"有时能挂起、有时挂不住"。本标记让用户暂停跨进程持久：泵在派发扫描前检查，
 * 未决的暂停请求在阶段边界兑现——已取消的运行由 journal/租约的幂等性保证恢复时
 * 安全重跑，已发布的快照继续展示（CoreScanState.PAUSED 的既定语义）。
 */
object CoreScanPausePrefs {

    private const val PREFS_NAME = "core_scan_pause"
    private const val KEY_REQUESTED = "pause_requested"

    fun setRequested(context: Context, requested: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(KEY_REQUESTED, requested)
            .apply()
    }

    fun isRequested(context: Context): Boolean =
        preferences(context).getBoolean(KEY_REQUESTED, false)

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
