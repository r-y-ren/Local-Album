package com.renyxin.localalbum.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 扫描前台服务引用计数控制器（Phase 0）。
 *
 * ## 设计动机
 *
 * 媒体扫描（文件枚举 + 入库）与 AI 分析（fire-and-forget 长任务）是两段独立的生命周期：
 * - 扫描由 [com.renyxin.localalbum.data.repo.AlbumRepository.rescan] 同步驱动，结束后立即返回；
 * - AI 分析在 [com.renyxin.localalbum.core.index.HybridIndexer.analysisScope] 内异步执行，可能远长于扫描。
 *
 * 若仅在扫描期间持有前台服务，分析阶段进程仍可能被系统回收（D1）。
 * 故采用「引用计数」：扫描 +1、分析 +1，双方结束各 -1，归零才 `stopSelf`。
 *
 * ## 后台启动 FGS 限制（Android 12+）
 *
 * `startForegroundService` 在应用处于后台时，服务内部 `startForeground` 会抛
 * `ForegroundServiceStartNotAllowedException`。[ScanForegroundService] 内部已 try/catch
 * 并降级为 `stopSelf`（无 FGS 的普通协程扫描），此处 [startService] 亦包裹 try/catch 兜底。
 *
 * ## 线程安全
 *
 * [refCount] 为 [AtomicInteger]，acquire/release 可从任意线程调用。
 */
object ScanServiceController {

    private const val TAG = "ScanServiceCtrl"
    const val CHANNEL_ID = "scan_progress"
    const val NOTIFICATION_ID = 1001

    private val refCount = AtomicInteger(0)
    // 持有运行中 Service 的强引用，detachService 时清空（不使用 WeakReference：其无 set 方法，
    // 且 Service 生命周期由引用计数显式管理，detach 即释放）。
    private val serviceRef = AtomicReference<ScanForegroundService?>(null)
    private val appContextRef = AtomicReference<Context?>(null)

    /**
     * 持有一次引用。首次 acquire 时启动前台服务；后续 acquire 仅更新通知文案。
     *
     * @param context 任意上下文，内部取 applicationContext
     * @param progressText 通知正文（如「正在扫描媒体…」）
     */
    fun acquire(context: Context, progressText: String) {
        val app = context.applicationContext
        ensureChannel(app)
        appContextRef.set(app)
        val prev = refCount.getAndIncrement()
        if (prev == 0) {
            startService(app, progressText)
        } else {
            updateProgress(progressText)
        }
    }

    /**
     * 释放一次引用。归零时停止前台服务。
     */
    fun release(context: Context) {
        val app = context.applicationContext
        val after = refCount.decrementAndGet()
        if (after <= 0) {
            refCount.set(0)
            stopService(app)
        }
    }

    /**
     * 更新通知正文（不改变引用计数）。服务未运行时静默忽略。
     */
    fun updateProgress(text: String) {
        serviceRef.get()?.updateNotification(text)
    }

    /** 当前是否仍有活跃引用（扫描或分析进行中）。 */
    fun isActive(): Boolean = refCount.get() > 0

    // ---- 内部钩子，供 ScanForegroundService 回调 ----

    internal fun attachService(service: ScanForegroundService) {
        serviceRef.set(service)
    }

    internal fun detachService() {
        serviceRef.set(null)
    }

    // ---- 实现 ----

    private fun startService(context: Context, reason: String) {
        val intent = Intent(context, ScanForegroundService::class.java).apply {
            putExtra(ScanForegroundService.EXTRA_REASON, reason)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // 部分 ROM / 后台限制下 startForegroundService 可能抛异常，降级为无 FGS。
            Log.w(TAG, "startForegroundService 失败，降级为无 FGS: ${e.javaClass.simpleName}: ${e.message}")
            refCount.set(0)
        }
    }

    private fun stopService(context: Context) {
        try {
            context.stopService(Intent(context, ScanForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "stopService 异常: ${e.message}")
        }
    }

    /**
     * 创建通知渠道（幂等）。API 26+ 必需。
     * 在 [acquire] 与 Application.onCreate 中调用，确保渠道就绪。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(com.renyxin.localalbum.R.string.scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(com.renyxin.localalbum.R.string.scan_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }
}
