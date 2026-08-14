package com.renyxin.localalbum.core.index

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/** Retains MediaStore identities through a bounded trailing-debounce window. */
class MediaContentObserver private constructor(
    private val onChanges: (List<MediaStoreChangeNotification>) -> Unit,
    private val handlerThread: HandlerThread,
    handler: Handler,
) : ContentObserver(handler) {
    constructor(onChanges: (List<MediaStoreChangeNotification>) -> Unit) : this(
        onChanges = onChanges,
        holder = Holder(),
    )

    /** Compatibility constructor for callers that only need a drain signal. */
    constructor(onChanged: () -> Unit) : this(onChanges = { onChanged() })

    private constructor(
        onChanges: (List<MediaStoreChangeNotification>) -> Unit,
        holder: Holder,
    ) : this(
        onChanges = onChanges,
        handlerThread = holder.thread,
        handler = holder.handler,
    )

    companion object {
        private const val TAG = "MediaContentObserver"
        internal const val TRAILING_DEBOUNCE_MS = 750L
        internal const val MAX_AGGREGATION_MS = 5_000L
        internal const val MAX_UNIQUE_KEYS = 500
        private const val UNKNOWN_URI_KEY = "<unknown>"
    }

    private data class PendingChange(
        val contentUri: String?,
        var flags: Int,
        val firstObservedAtMs: Long,
        var observedAtMs: Long,
    )

    private val handler = Handler(handlerThread.looper)
    private val pending = LinkedHashMap<String, PendingChange>()
    private val trailingFlush = Runnable(::flush)
    private val maximumFlush = Runnable(::flush)

    @Volatile
    private var isRegistered = false

    override fun onChange(selfChange: Boolean) {
        recordChange(uri = null, flags = 0)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        recordChange(uri, flags = 0)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?, flags: Int) {
        recordChange(uri, flags)
    }

    override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
        if (uris.isEmpty()) {
            recordChange(uri = null, flags = flags)
        } else {
            uris.forEach { uri -> recordChange(uri, flags) }
        }
    }

    private fun recordChange(uri: Uri?, flags: Int) {
        val now = System.currentTimeMillis()
        val uriText = uri?.toString()
        val key = uriText ?: UNKNOWN_URI_KEY
        val existing = pending[key]
        if (existing == null) {
            pending[key] = PendingChange(uriText, flags, now, now)
            if (pending.size == 1) {
                handler.postDelayed(maximumFlush, MAX_AGGREGATION_MS)
            }
        } else {
            existing.flags = existing.flags or flags
            existing.observedAtMs = now
        }

        handler.removeCallbacks(trailingFlush)
        if (pending.size >= MAX_UNIQUE_KEYS) {
            flush()
        } else {
            handler.postDelayed(trailingFlush, TRAILING_DEBOUNCE_MS)
        }
    }

    private fun flush() {
        if (pending.isEmpty()) return
        handler.removeCallbacks(trailingFlush)
        handler.removeCallbacks(maximumFlush)
        val changes = pending.values.map { change ->
            MediaStoreChangeNotification(
                contentUri = change.contentUri,
                flags = change.flags,
                firstObservedAtMs = change.firstObservedAtMs,
                observedAtMs = change.observedAtMs,
            )
        }
        pending.clear()
        Log.i(TAG, "提交 ${changes.size} 个去重 MediaStore 事件")
        onChanges(changes)
    }

    internal fun markRegistered() {
        isRegistered = true
    }

    internal fun markUnregistered() {
        isRegistered = false
        handler.removeCallbacks(trailingFlush)
        handler.removeCallbacks(maximumFlush)
        pending.clear()
        handlerThread.quitSafely()
    }

    fun isRegistered(): Boolean = isRegistered

    private class Holder(
        val thread: HandlerThread = HandlerThread("MediaObserver").apply { start() },
        val handler: Handler = Handler(thread.looper),
    )
}
