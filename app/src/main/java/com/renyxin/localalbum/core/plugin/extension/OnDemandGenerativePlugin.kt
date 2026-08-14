package com.renyxin.localalbum.core.plugin.extension

import com.renyxin.localalbum.core.plugin.GenerativePlugin
import kotlinx.coroutines.flow.StateFlow

/** Runtime state for a descriptor-registered interactive plugin whose native resources are lazy. */
enum class OnDemandRuntimeState {
    AVAILABLE_NEEDS_LOAD,
    WAITING_FOR_CORE,
    LOADING,
    READY,
    ERROR,
}

/**
 * Interactive plugin contract that separates lightweight availability from native runtime residency.
 *
 * [GenerativePlugin.isReady] means the descriptor is registered and the feature may be invoked. It
 * does not mean that model sessions are resident. [runtimeState] exposes the transient, per-request
 * load lifecycle, and [releaseRuntimeResources] performs best-effort exact eviction when idle.
 */
interface OnDemandGenerativePlugin : GenerativePlugin {
    val runtimeState: StateFlow<OnDemandRuntimeState>

    suspend fun releaseRuntimeResources()
}
