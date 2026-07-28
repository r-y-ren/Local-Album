package com.renyxin.localalbum

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.renyxin.localalbum.data.worker.TrashCleanupWorker
import org.opencv.android.OpenCVLoader

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
        Log.i("CrashDebug", "=== Application.onCreate 开始 ===")
        // 必须最先加载 emutls shim：导出标准 __emutls_get_address 实现，供后续加载的
        // ONNX Runtime / OpenCV / TFLite / PyTorch 等 native 库共用，避免各库自带 emutls
        // 实现的线程局部存储控制块（__emutls_v.*）布局不兼容导致 SIGSEGV 闪退
        // （识别扫描 / 换脸时 ONNX↔OpenCV 交替调用触发）。
        // 加载顺序关键：必须在 OpenCVLoader.initLocal() 及任何 ONNX Runtime 调用之前，
        // 使 shim 的符号优先进入动态符号表，被 libonnxruntime.so 解析引用。
        try {
            System.loadLibrary("emutls_shim")
            Log.i("CrashDebug", "emutls_shim 加载成功")
        } catch (e: Throwable) {
            Log.e("CrashDebug", "emutls_shim 加载失败! ${e.javaClass.simpleName}: ${e.message}", e)
        }

        // 初始化 OpenCV（换脸 Reactor 流水线：仿射变换 + diff-mask 贴回）
        // libopencv_java5.so 为 NDK r27 + c++_static 自包含构建，无外部 libc++/emutls 依赖。
        Log.i("CrashDebug", "即将加载 OpenCV (initLocal)...")
        if (OpenCVLoader.initLocal()) {
            Log.i("CrashDebug", "OpenCV 初始化成功")
        } else {
            Log.e("CrashDebug", "OpenCV 初始化失败")
        }
        Log.i("CrashDebug", "=== Application.onCreate native 库加载完成 ===")
        container = AppContainer(this)
        TrashCleanupWorker.schedule(this)
        // 注册 MediaStore ContentObserver，监听图片/视频变更并触发防抖增量扫描
        container.registerContentObserver()
        // 加载所有已安装的 AI 插件（Phase 2.5）
        container.loadPlugins()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (activityCount == 0) {
                    // App 从后台回到前台，重新注册 Observer
                    container.registerContentObserver()
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
