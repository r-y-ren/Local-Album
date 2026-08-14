package com.renyxin.localalbum.core.plugin.model

/**
 * Atomic boundary between model-consumer admission and exact runtime eviction.
 *
 * The eviction callback intentionally executes while this coordinator owns its monitor. A consumer
 * that starts registering during native session close therefore waits until close is complete and
 * can subsequently reload the model; a consumer registered first prevents that close entirely.
 */
internal class ModelConsumerLifecycle {
    private val lock = Any()
    private val consumersByModel = mutableMapOf<String, Set<String>>()

    fun consumerIds(modelId: String): Set<String> = synchronized(lock) {
        consumersByModel[modelId].orEmpty()
    }

    fun register(
        modelId: String,
        consumerId: String,
        onChanged: (Set<String>) -> Unit = {},
    ): Set<String> = synchronized(lock) {
        val updated = consumersByModel[modelId].orEmpty() + consumerId
        consumersByModel[modelId] = updated
        onChanged(updated)
        updated
    }

    fun unregister(
        modelId: String,
        consumerId: String,
        onChanged: (Set<String>) -> Unit = {},
    ): Set<String> = synchronized(lock) {
        val updated = consumersByModel[modelId].orEmpty() - consumerId
        if (updated.isEmpty()) {
            consumersByModel.remove(modelId)
        } else {
            consumersByModel[modelId] = updated
        }
        onChanged(updated)
        updated
    }

    fun evictIfUnused(
        modelId: String,
        isLoaded: () -> Boolean,
        evict: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (consumersByModel[modelId].orEmpty().isNotEmpty() || !isLoaded()) {
            return@synchronized false
        }
        evict()
        true
    }
}
