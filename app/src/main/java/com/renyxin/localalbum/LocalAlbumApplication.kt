package com.renyxin.localalbum

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.renyxin.localalbum.data.worker.DeletionRetryWorker
import com.renyxin.localalbum.data.worker.TrashCleanupWorker

class LocalAlbumApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer

    // P1-2: 追踪 Activity 数量，当所有 Activity 销毁时释放资源
    // onTerminate() 在真机上不会回调，改用 ActivityLifecycleCallbacks
    private var activityCount = 0

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Native AI runtimes are deliberately absent from cold start. ONNX loads the emutls shim
        // immediately before its first session, while interactive face swap acquires the shared
        // resource lane before loading shim -> OpenCV and its model sessions.
        container = AppContainer(this)
        // Phase 1: 核心扫描与后台增强使用独立通知渠道。
        com.renyxin.localalbum.data.worker.ScanServiceController.ensureChannels(this)
        TrashCleanupWorker.schedule(this)
        DeletionRetryWorker.enqueue(this)
        // 注册 MediaStore ContentObserver，监听图片/视频变更并触发防抖增量扫描
        container.registerContentObserver()
        // 进程重启后仅恢复 Room 中尚未确认的 changed-set，不创建伪增量全量扫描
        container.maybeResumePendingScanChanges()
        // 加载所有已安装的 AI 插件（Phase 2.5）
        container.loadPlugins()
        // Phase 1: 启动时若存在中断未完成的分析，自动续跑（跳过已完成阶段）
        container.maybeResumeAnalysis()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (activityCount == 0) {
                    // App 从后台回到前台，重新注册 Observer
                    container.registerContentObserver()
                    // Phase 2: 补偿后台期间遗漏的 MediaStore 变更（30s 节流）
                    container.maybeRescanOnForeground()
                }
                activityCount++
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount == 0) {
                    // 修复：所有 Activity 已停止时仅注销 ContentObserver，不再 shutdown 整个容器。
                    // 原实现调用 container.shutdown() 会取消 appScope 并 dispose AlbumRepository，
                    // 导致返回前台后 rescan 在已取消的 scope 上静默失败、加载链路永久断裂。
                    // 容器资源生命周期应与进程绑定，由系统回收。
                    container.unregisterContentObserver()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
