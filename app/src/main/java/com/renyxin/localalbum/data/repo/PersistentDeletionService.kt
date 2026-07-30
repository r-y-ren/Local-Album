package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.data.db.dao.DeletionTombstoneDao
import com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity

/** 用户删除意图不是错误日志：它在成功后仍保留 tombstone，直到显式 restore。 */
class PersistentDeletionService(
    private val dao: DeletionTombstoneDao,
    private val coordinator: MediaDeletionCoordinator,
) {
    suspend fun delete(
        paths: Collection<String>,
        operation: String,
        sourceVersionByPath: Map<String, String> = emptyMap(),
        deletedAtMs: Long = System.currentTimeMillis(),
    ): List<String> {
        val distinct = paths.distinct()
        if (distinct.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val existing = dao.getByPaths(distinct).associateBy { it.filePath }
        dao.upsertAll(distinct.map { path ->
            existing[path]?.copy(operation = operation, updatedAt = now) ?: DeletionTombstoneEntity(
                pathKey = DeletionFailurePolicy.pathKey(path),
                filePath = path,
                operation = operation,
                sourceVersion = sourceVersionByPath[path] ?: "unknown",
                deletedAtMs = deletedAtMs,
                createdAt = now,
                updatedAt = now,
            )
        })
        return execute(distinct)
    }

    suspend fun retryClaimed(items: List<DeletionTombstoneEntity>): Int = execute(items.map { it.filePath }).size

    private suspend fun execute(paths: List<String>): List<String> {
        val result = PhysicalFileDeletion.delete(paths)
        val now = System.currentTimeMillis()
        val byPath = dao.getByPaths(paths).associateBy { it.filePath }
        val completed = mutableListOf<String>()
        result.items.forEach { item ->
            val record = byPath[item.path] ?: return@forEach
            if (item.outcome == PhysicalFileDeletion.Outcome.FAILED) {
                val nextAttempt = record.attemptCount + 1
                val exhausted = nextAttempt >= DeletionFailurePolicy.MAX_FAST_ATTEMPTS
                dao.markFailed(
                    pathKey = record.pathKey,
                    state = if (exhausted) DeletionTombstoneEntity.STATE_EXHAUSTED else DeletionTombstoneEntity.STATE_PENDING,
                    nextRetryAt = DeletionFailurePolicy.nextRetryAt(now, nextAttempt),
                    errorType = item.errorType ?: "DeleteFailed",
                    errorCode = item.errorCode,
                    errorMessage = DeletionFailurePolicy.sanitizeMessage(item.safeErrorMessage, item.path),
                    now = now,
                )
            } else {
                dao.markCompleted(record.pathKey, now)
                completed += item.path
            }
        }
        // 物理失败路径永不进入 purge。
        coordinator.purge(completed)
        return completed
    }

    suspend fun restore(paths: List<String>) {
        dao.clearForRestore(paths)
    }
}
