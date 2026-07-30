package com.renyxin.localalbum.core.thumbnail

/** 缩略图尺寸与调度策略的单一事实来源。 */
object ThumbnailSpec {
    const val SIZE_GRID = "grid"
    const val SIZE_PREVIEW = "preview"

    const val GRID_PX = 256
    const val PREVIEW_PX = 1280

    const val PRIORITY_BACKGROUND = 0
    const val PRIORITY_PREFETCH = 50
    const val PRIORITY_VISIBLE = 100

    const val DEFAULT_CACHE_BUDGET_BYTES = 512L * 1024L * 1024L
    const val TOUCH_THROTTLE_MS = 60_000L
    const val RECENT_TOUCH_PROTECTION_MS = 2 * 60_000L

    fun targetPx(sizeClass: String): Int = when (sizeClass) {
        SIZE_GRID -> GRID_PX
        SIZE_PREVIEW -> PREVIEW_PX
        else -> error("未知缩略图尺寸档: $sizeClass")
    }

    fun sourceVersion(modifiedAtMs: Long, fileSize: Long): String = "$modifiedAtMs:$fileSize"
}

data class ThumbnailCacheRecord(
    val filePath: String,
    val sizeClass: String,
    val sourceVersion: String,
    val byteSize: Long,
    val lastAccessAt: Long,
    val state: String,
    val leaseUntil: Long = 0L,
)

/** 不接触 Android/Room 的 LRU 决策，供 JVM 测试覆盖预算、保护窗与版本隔离。 */
object ThumbnailCachePolicy {
    fun isHit(
        record: ThumbnailCacheRecord,
        filePath: String,
        sizeClass: String,
        sourceVersion: String,
    ): Boolean = record.filePath == filePath &&
        record.sizeClass == sizeClass &&
        record.sourceVersion == sourceVersion &&
        record.state == "READY"

    fun selectEvictions(
        records: List<ThumbnailCacheRecord>,
        budgetBytes: Long,
        now: Long,
        recentProtectionMs: Long = ThumbnailSpec.RECENT_TOUCH_PROTECTION_MS,
    ): List<ThumbnailCacheRecord> {
        require(budgetBytes >= 0)
        var total = records.filter { it.state == "READY" || it.state == "DELETE_RETRY" }.sumOf { it.byteSize }
        if (total <= budgetBytes) return emptyList()
        val protectedAfter = now - recentProtectionMs
        val result = mutableListOf<ThumbnailCacheRecord>()
        records.asSequence()
            .filter { (it.state == "READY" || it.state == "DELETE_RETRY") && it.leaseUntil <= now && it.lastAccessAt <= protectedAfter }
            .sortedWith(compareBy<ThumbnailCacheRecord>({ it.lastAccessAt }, { it.filePath }, { it.sizeClass }, { it.sourceVersion }))
            .forEach { record ->
                if (total > budgetBytes) {
                    result += record
                    total -= record.byteSize
                }
            }
        return result
    }
}
