package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.ThumbnailCacheDao
import java.io.File

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
        var cursorAccess = Long.MIN_VALUE
        var cursorPath = ""
        var cursorSizeClass = ""
        var cursorVersion = ""
        var removed = 0
        val protectedBefore = now - ThumbnailSpec.RECENT_TOUCH_PROTECTION_MS

        while (remaining > budgetBytes) {
            val candidates = dao.evictionCandidatesAfter(
                now = now,
                protectedBefore = protectedBefore,
                afterAccess = cursorAccess,
                afterPath = cursorPath,
                afterSizeClass = cursorSizeClass,
                afterSourceVersion = cursorVersion,
                limit = pageSize,
            )
            if (candidates.isEmpty()) break
            for (entry in candidates) {
                cursorAccess = entry.lastAccessAt
                cursorPath = entry.filePath
                cursorSizeClass = entry.sizeClass
                cursorVersion = entry.sourceVersion
                val file = File(entry.path)
                val deleted = !file.exists() || deleteFile(file)
                if (deleted) {
                    if (dao.deleteAfterFileRemoved(
                            entry.filePath,
                            entry.sizeClass,
                            entry.sourceVersion,
                            entry.path,
                            now,
                        ) > 0
                    ) {
                        remaining -= entry.byteSize
                        removed++
                    }
                } else {
                    dao.markDeleteRetry(entry.filePath, entry.sizeClass, entry.sourceVersion, entry.path)
                }
                if (remaining <= budgetBytes) break
            }
            if (candidates.size < pageSize) break
        }
        return removed
    }
}
