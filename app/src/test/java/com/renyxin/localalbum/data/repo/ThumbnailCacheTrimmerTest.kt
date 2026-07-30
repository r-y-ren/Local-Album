package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.data.db.dao.ThumbnailCacheDao
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThumbnailCacheTrimmerTest {
    private class FakeThumbnailCacheDao(
        entries: List<ThumbnailCacheEntryEntity>,
    ) : ThumbnailCacheDao {
        val store = entries.associateBy { Triple(it.filePath, it.sizeClass, it.sourceVersion) }.toMutableMap()
        var retryMarks = 0

        override suspend fun upsert(entry: ThumbnailCacheEntryEntity) { store[entry.key()] = entry }
        override suspend fun getReady(filePath: String, sizeClass: String, sourceVersion: String) =
            store[Triple(filePath, sizeClass, sourceVersion)]?.takeIf { it.state == "READY" }
        override suspend fun touchThrottled(filePath: String, sizeClass: String, sourceVersion: String, now: Long, touchBefore: Long): Int = 0
        override suspend fun totalManagedBytes() = store.values.sumOf { it.byteSize }
        override suspend fun evictionCandidatesAfter(now: Long, protectedBefore: Long, afterAccess: Long, afterPath: String, afterSizeClass: String, afterSourceVersion: String, limit: Int) =
            store.values.filter { it.leaseUntil <= now && it.lastAccessAt <= protectedBefore }
                .sortedWith(compareBy({ it.lastAccessAt }, { it.filePath }, { it.sizeClass }, { it.sourceVersion }))
                .filter { it.lastAccessAt > afterAccess || (it.lastAccessAt == afterAccess && it.filePath > afterPath) }
                .take(limit)
        override suspend fun deleteAfterFileRemoved(filePath: String, sizeClass: String, sourceVersion: String, path: String, now: Long): Int =
            if (store.remove(Triple(filePath, sizeClass, sourceVersion)) != null) 1 else 0
        override suspend fun markDeleteRetry(filePath: String, sizeClass: String, sourceVersion: String, path: String): Int {
            retryMarks++
            return 1
        }
        override suspend fun getByPaths(paths: List<String>) = store.values.filter { it.filePath in paths }
        override suspend fun deleteByPaths(paths: List<String>) { store.entries.removeIf { it.key.first in paths } }
        private fun ThumbnailCacheEntryEntity.key() = Triple(filePath, sizeClass, sourceVersion)
    }

    @Test
    fun `successful deletion removes metadata until budget`() = runBlocking {
        val oldFile = File.createTempFile("thumb-old", ".webp").apply { writeBytes(byteArrayOf(1)) }
        val freshFile = File.createTempFile("thumb-fresh", ".webp").apply { writeBytes(byteArrayOf(1)) }
        try {
            val old = entry("old", oldFile, 70, 1)
            val fresh = entry("fresh", freshFile, 40, 2)
            val dao = FakeThumbnailCacheDao(listOf(old, fresh))
            val deleted = mutableListOf<String>()
            val count = ThumbnailCacheTrimmer(dao) { deleted += it.name; it.delete() }.trim(50, now = 1_000_000, pageSize = 1)
            assertEquals(1, count)
            assertEquals(listOf(oldFile.name), deleted)
            assertEquals(setOf("fresh"), dao.store.values.map { it.filePath }.toSet())
        } finally {
            oldFile.delete()
            freshFile.delete()
        }
    }

    @Test
    fun `failed file deletion preserves metadata and marks retry`() = runBlocking {
        val file = File.createTempFile("thumb-fail", ".webp").apply { writeBytes(byteArrayOf(1)) }
        try {
            val dao = FakeThumbnailCacheDao(listOf(entry("old", file, 70, 1)))
            val count = ThumbnailCacheTrimmer(dao) { false }.trim(0, now = 1_000_000)
            assertEquals(0, count)
            assertEquals(1, dao.retryMarks)
            assertTrue(dao.store.isNotEmpty())
        } finally {
            file.delete()
        }
    }

    private fun entry(filePath: String, cacheFile: File, bytes: Long, access: Long) = ThumbnailCacheEntryEntity(
        filePath = filePath,
        sizeClass = "grid",
        sourceVersion = "v1",
        path = cacheFile.path,
        byteSize = bytes,
        lastAccessAt = access,
        createdAt = access,
    )
}
