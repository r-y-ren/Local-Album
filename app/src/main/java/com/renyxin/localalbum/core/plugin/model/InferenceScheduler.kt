package com.renyxin.localalbum.core.plugin.model

import android.util.Log
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 推理调度器（Phase 6 高级特性）。
 *
 * 使用信号量控制并发推理数量，避免多模型同时运行时 CPU 争抢和内存峰值过高。
 *
 * ## 使用方式
 *
 * ```kotlin
 * val scheduler = InferenceScheduler(maxConcurrentModels = 2)
 * val result = scheduler.schedule("face", Priority.HIGH) { faceProvider.detectFaces(file) }
 * ```
 */
class InferenceScheduler(
    private val maxConcurrentModels: Int = 2,
) {
    companion object {
        private const val TAG = "InferenceScheduler"
    }

    /** 推理优先级 */
    enum class Priority { HIGH, NORMAL, LOW }

    private val semaphore = Semaphore(maxConcurrentModels)

    private val _activeCount = java.util.concurrent.atomic.AtomicInteger(0)
    val activeCount: Int get() = _activeCount.get()

    /**
     * 调度一个推理任务。高优先级任务会获得信号量许可后执行。
     *
     * @param modelId 模型 ID（用于日志）
     * @param priority 优先级（当前版本通过信号量公平调度）
     * @param block 推理逻辑
     * @return 推理结果
     */
    suspend fun <T> schedule(
        modelId: String,
        priority: Priority = Priority.NORMAL,
        block: suspend () -> T,
    ): T {
        Log.d(TAG, "推理排队: model=$modelId priority=$priority active=${_activeCount.get()}")

        return semaphore.withPermit {
            _activeCount.incrementAndGet()
            try {
                Log.d(TAG, "推理开始: model=$modelId active=${_activeCount.get()}")
                block()
            } catch (e: Exception) {
                Log.w(TAG, "推理失败: model=$modelId", e)
                throw e
            } finally {
                _activeCount.decrementAndGet()
                Log.d(TAG, "推理完成: model=$modelId active=${_activeCount.get()}")
            }
        }
    }
}
