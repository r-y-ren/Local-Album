package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.ThumbnailCacheDao
import java.io.File

/** READY -> EVICTING -> EVICTED 的完整身份 LRU；删除失败稳定进入 DELETE_RETRY。 */
class ThumbnailCacheTrimmer(
    private val dao: ThumbnailCacheDao,
    private val deleteFile: (File) -> Boolean = { it.delete() },
) {
    suspend fun trim(
        budgetBytes: Long = ThumbnailSpec.DEFAULT_CACHE_BUDGET_BYTES,
        now: Long = System.currentTimeMillis(),
        pageSize: Int = 64,
    ): Int {
        require(budgetBytes >= 0 && pageSize > 0)
        var remaining = dao.totalManagedBytes()
        if (remaining <= budgetBytes) return 0
        var access = Long.MIN_VALUE
        var path = ""
        var mediaType = ""
        var sourceVersion = ""
        var sizeClass = ""
        var formatVersion = Int.MIN_VALUE
        var removed = 0
        val protectedBefore = now - ThumbnailSpec.RECENT_TOUCH_PROTECTION_MS
        while (remaining > budgetBytes) {
            val candidates = dao.evictionCandidatesAfter(
                now,protectedBefore,access,path,mediaType,sourceVersion,sizeClass,formatVersion,pageSize,
            )
            if (candidates.isEmpty()) break
            for (entry in candidates) {
                access = entry.lastAccessAt
                path = entry.filePath
                mediaType = entry.mediaType
                sourceVersion = entry.sourceVersion
                sizeClass = entry.sizeClass
                formatVersion = entry.formatVersion
                if (dao.beginEviction(
                        entry.filePath,entry.mediaType,entry.sourceVersion,entry.sizeClass,
                        entry.formatVersion,entry.path,now,now + EVICTION_LEASE_MS,
                    ) != 1
                ) continue
                val file = File(entry.path)
                if (!file.exists() || deleteFile(file)) {
                    if (dao.finishEviction(
                            entry.filePath,entry.mediaType,entry.sourceVersion,entry.sizeClass,
                            entry.formatVersion,entry.path,
                        ) == 1
                    ) {
                        remaining -= entry.byteSize
                        removed++
                    }
                } else {
                    dao.markDeleteRetry(
                        entry.filePath,entry.mediaType,entry.sourceVersion,entry.sizeClass,
                        entry.formatVersion,entry.path,
                    )
                }
                if (remaining <= budgetBytes) break
            }
            if (candidates.size < pageSize) break
        }
        return removed
    }

    companion object { private const val EVICTION_LEASE_MS = 60_000L }
}
