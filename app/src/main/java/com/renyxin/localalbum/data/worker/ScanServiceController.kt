package com.renyxin.localalbum.data.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.renyxin.localalbum.MainActivity
import com.renyxin.localalbum.R
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 核心扫描前台服务引用计数控制器。
 *
 * ## 设计动机
 *
 * 媒体扫描（文件枚举 + 入库）由 Repository 同步驱动，引用计数只覆盖核心扫描。
 * 后台增强由 [AnalysisWorker] 的 WorkManager 前台执行和独立通知负责，不共享这里的引用。
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
    const val CORE_CHANNEL_ID = "scan_progress"
    const val CORE_NOTIFICATION_ID = 1001
    const val ENHANCEMENT_CHANNEL_ID = "enhancement_progress"
    const val ENHANCEMENT_NOTIFICATION_ID = 1003

    /** Legacy aliases retained for the existing ScanWorker and integrations. */
    const val CHANNEL_ID = CORE_CHANNEL_ID
    const val NOTIFICATION_ID = CORE_NOTIFICATION_ID

    private val refCount = AtomicInteger(0)
    // 持有运行中 Service 的强引用，detachService 时清空（不使用 WeakReference：其无 set 方法，
    // 且 Service 生命周期由引用计数显式管理，detach 即释放）。
    private val serviceRef = AtomicReference<ScanForegroundService?>(null)
    private val appContextRef = AtomicReference<Context?>(null)

    /**
     * 持有一次核心扫描引用。首次 acquire 时启动前台服务；后续 acquire 仅更新通知文案。
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

    /** 更新核心扫描通知正文（不改变引用计数）。服务未运行时静默忽略。 */
    fun updateCoreProgress(text: String) {
        serviceRef.get()?.updateNotification(text)
    }

    /** Legacy name retained for callers that only update the core scan notification. */
    fun updateProgress(text: String) = updateCoreProgress(text)

    /**
     * Updates a separate enhancement notification. It is intentionally independent from
     * the core foreground service reference count.
     */
    fun updateEnhancementProgress(context: Context, text: String) {
        val app = context.applicationContext
        ensureEnhancementChannel(app)
        app.getSystemService(NotificationManager::class.java)
            ?.notify(ENHANCEMENT_NOTIFICATION_ID, buildEnhancementNotification(app, text))
    }

    /** Shared notification builder for WorkManager foreground and ordinary updates. */
    internal fun buildEnhancementNotification(context: Context, text: String): Notification {
        val app = context.applicationContext
        val pendingIntent = PendingIntent.getActivity(
            app,
            ENHANCEMENT_NOTIFICATION_ID,
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(app, ENHANCEMENT_CHANNEL_ID)
            .setContentTitle(app.getString(R.string.enhancement_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    /** Removes the app-managed enhancement progress notification after terminal state. */
    fun clearEnhancementProgress(context: Context) {
        context.applicationContext
            .getSystemService(NotificationManager::class.java)
            ?.cancel(ENHANCEMENT_NOTIFICATION_ID)
    }

    /** 当前是否仍有活跃核心扫描引用。 */
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

    /** Creates both core and enhancement channels (idempotent). */
    fun ensureChannels(context: Context) {
        ensureChannel(context)
        ensureEnhancementChannel(context)
    }

    /** Creates the core scan notification channel (idempotent). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CORE_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CORE_CHANNEL_ID,
            context.getString(R.string.scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.scan_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    /** Creates the independent enhancement notification channel (idempotent). */
    fun ensureEnhancementChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(ENHANCEMENT_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            ENHANCEMENT_CHANNEL_ID,
            context.getString(R.string.enhancement_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.enhancement_channel_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }
}
