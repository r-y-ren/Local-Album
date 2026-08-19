package com.renyxin.localalbum.core.thumbnail

/** 缩略图尺寸与调度策略的单一事实来源。 */
object ThumbnailSpec {
    const val SIZE_GRID = ThumbnailIdentity.SIZE_GRID
    const val SIZE_PREVIEW = ThumbnailIdentity.SIZE_PREVIEW

    const val GRID_PX = 256
    const val PREVIEW_PX = 1280
    const val CACHE_FORMAT_VERSION = ThumbnailIdentity.CURRENT_FORMAT_VERSION

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

    /**
     * 返回缩略图的编码尺寸。网格缩略图保持方形裁剪；查看器预览必须保持原始宽高比，
     * 否则查看器命中 preview 缓存后会把完整原图替换成方形裁剪图。
     */
    fun encodedSize(sourceWidth: Int, sourceHeight: Int, sizeClass: String): Pair<Int, Int> {
        require(sourceWidth > 0 && sourceHeight > 0)
        val target = targetPx(sizeClass)
        if (sizeClass == SIZE_GRID) return target to target

        val scale = minOf(1f, target.toFloat() / maxOf(sourceWidth, sourceHeight))
        return maxOf(1, (sourceWidth * scale).toInt()) to
            maxOf(1, (sourceHeight * scale).toInt())
    }

    fun isCurrentCachePath(sizeClass: String, path: String): Boolean =
        path.endsWith("_${sizeClass}_v$CACHE_FORMAT_VERSION.webp")

    fun sourceVersion(
        modifiedAtMs: Long,
        fileSize: Long,
        fingerprintHead: String? = null,
    ): String = SourceVersionCodec.encode(modifiedAtMs, fileSize, fingerprintHead)
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
