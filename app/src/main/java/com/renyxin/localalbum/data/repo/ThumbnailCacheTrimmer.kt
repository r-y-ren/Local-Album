package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.ThumbnailCacheDao
import java.io.File

/**
 * 缩略图缓存裁剪器：把缓存目录总占用压回字节预算内。
 *
 * 裁剪策略：
 * - 以 LRU 顺序（lastAccessAt 升序）分页枚举候选，逐个删除文件并同步删除 DAO 记录，
 *   直到总占用 ≤ 预算或无候选可删；
 * - 近期访问保护：lastAccessAt 在 [ThumbnailSpec.RECENT_TOUCH_PROTECTION_MS] 保护窗内的
 *   条目不参与本轮裁剪；
 * - 文件删除失败（如被占用）的条目标记为待重试（markDeleteRetry），不计入已回收字节。
 *
 * 触发时机：由 ThumbnailWorker 在每次缩略图生成批次收尾后调用（`trim()` 使用默认预算）。
 *
 * @param dao 缩略图缓存索引 DAO（提供总占用、候选枚举与记录删除）
 * @param deleteFile 文件删除函数，默认 `File.delete()`；测试可注入假实现
 */
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
