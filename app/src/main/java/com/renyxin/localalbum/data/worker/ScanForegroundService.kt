package com.renyxin.localalbum.data.worker

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.renyxin.localalbum.MainActivity
import com.renyxin.localalbum.R

/**
 * 扫描/AI 分析前台服务（Phase 0）。
 *
 * 由 [ScanServiceController] 通过引用计数驱动启停。服务启动后立即调用
 * [ServiceCompat.startForeground] 提升进程优先级，状态栏常驻进度通知，
 * 大幅降低切后台/锁屏时被系统 LMK 回收的概率。
 *
 * ## Android 14 兼容
 *
 * - `foregroundServiceType="dataSync"` 在 Manifest 声明；
 * - [ServiceCompat.startForeground] 显式传 [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]；
 * - Android 12+ 后台启动受限时 `startForeground` 抛
 *   `ForegroundServiceStartNotAllowedException`，此处 try/catch 后 `stopSelf` 降级。
 */
class ScanForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ScanServiceController.attachService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val reason = intent?.getStringExtra(EXTRA_REASON)
            ?: getString(R.string.scan_notif_scanning)
        startForegroundCompat(reason)
        return START_NOT_STICKY
    }

    /**
     * 启动前台并展示通知。后台启动被拒时降级为无 FGS（不阻塞扫描主流程）。
     */
    private fun startForegroundCompat(reason: String) {
        val notification = buildNotification(reason)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    ScanServiceController.CORE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(ScanServiceController.CORE_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android 12+ 后台启动 FGS 被拒（ForegroundServiceStartNotAllowedException），
            // 或其他 ROM 限制。降级为无 FGS：扫描仍以普通协程运行，接受可能被杀的风险。
            Log.w(
                TAG,
                "startForeground 失败，降级为无 FGS: ${e.javaClass.simpleName}: ${e.message}",
            )
            stopSelf()
        }
    }

    /**
     * 更新通知正文（由 [ScanServiceController.updateProgress] 调用）。
     */
    fun updateNotification(text: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        mgr.notify(ScanServiceController.CORE_NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ScanServiceController.CORE_CHANNEL_ID)
            .setContentTitle(getString(R.string.scan_notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        ScanServiceController.detachService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScanForegroundService"
        const val EXTRA_REASON = "reason"
    }
}
