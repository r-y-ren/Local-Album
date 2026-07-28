package com.renyxin.localalbum.core.index

import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log

/**
 * 监听 MediaStore（图片/视频）变更的 [ContentObserver] 封装。
 *
 * 内置防抖逻辑：MediaStore 短时间内可能触发大量 [onChange] 回调
 * （如批量写入、相册应用同步），直接每次都触发增量扫描会导致性能问题。
 * 因此使用 [Handler.postDelayed] 做防抖，在静默窗口
 * [DEBOUNCE_DELAY_MS] 内无新回调时才真正触发 [onChanged]。
 *
 * 生命周期由 [HybridIndexer.registerContentObserver] / [unregisterContentObserver] 管理。
 */
class MediaContentObserver(
    private val onChanged: () -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {

    companion object {
        private const val TAG = "MediaContentObserver"
        private const val DEBOUNCE_DELAY_MS = 2000L
        /** 标记当前是否有待触发的防抖任务 */
        private val TOKEN = Any()
    }

    // P1-3: 使用独立 HandlerThread 替代主线程 Handler，避免 onChange 阻塞 UI
    private val handlerThread = HandlerThread("MediaObserver").apply { start() }
    private val handler = Handler(handlerThread.looper)
    @Volatile
    private var isRegistered = false

    /**
     * ContentObserver 回调入口。
     * 每次收到变更都先移除上一次的防抖任务，再重新延迟触发，
     * 从而实现"连续变更只在最后一次结束后统一扫描一次"。
     */
    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        scheduleDebounced()
    }

    // 修复：Android 10+ 部分 ROM 仅回调带 URI 的重载，补充重写以保证变更不丢失。
    override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
        super.onChange(selfChange, uri)
        Log.d(TAG, "收到 MediaStore 变更通知 uri=$uri，防抖等待 ${DEBOUNCE_DELAY_MS}ms")
        scheduleDebounced()
    }

    private fun scheduleDebounced() {
        handler.removeCallbacksAndMessages(TOKEN)
        handler.postDelayed({
            Log.i(TAG, "防抖窗口结束，触发增量扫描")
            onChanged()
        }, DEBOUNCE_DELAY_MS)
    }

    /**
     * 标记注册状态，便于 [HybridIndexer] 追踪是否需要注销。
     */
    internal fun markRegistered() {
        isRegistered = true
    }

    internal fun markUnregistered() {
        isRegistered = false
        handler.removeCallbacksAndMessages(TOKEN)
        // P1-3: 注销时释放 HandlerThread 资源
        handlerThread.quitSafely()
    }

    fun isRegistered(): Boolean = isRegistered
}
