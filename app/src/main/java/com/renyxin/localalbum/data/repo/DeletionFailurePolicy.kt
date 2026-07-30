package com.renyxin.localalbum.data.repo

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.min

/** 纯 JVM 可测的删除控制面策略；所有诊断值均不含原始路径。 */
object DeletionFailurePolicy {
    private const val BASE_DELAY_MS = 60_000L
    private const val MAX_DELAY_MS = 7L * 24 * 60 * 60 * 1_000
    const val MAX_FAST_ATTEMPTS = 8

    fun pathKey(filePath: String): String = MessageDigest.getInstance("SHA-256")
        .digest(filePath.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun nextRetryAt(now: Long, attemptCountAfterFailure: Int): Long {
        val exponent = min((attemptCountAfterFailure - 1).coerceAtLeast(0), 16)
        val delay = min(MAX_DELAY_MS, BASE_DELAY_MS * (1L shl exponent))
        return now + delay
    }

    fun sanitizeMessage(message: String?, filePath: String): String {
        val withoutPath = message.orEmpty().replace(filePath, "[path]")
        return withoutPath
            .replace(Regex("(?:/[^\\s:]+)+"), "[path]")
            .replace(Regex("content://[^\\s]+"), "[uri]")
            .take(160)
            .ifBlank { "文件删除失败（详情已脱敏）" }
    }
}
