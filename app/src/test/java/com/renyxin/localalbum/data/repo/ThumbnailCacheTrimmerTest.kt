package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.core.thumbnail.SourceVersionCodec
import com.renyxin.localalbum.data.db.dao.ThumbnailCacheDao
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailCacheTrimmerTest {
    private class FakeDao(entries: List<ThumbnailCacheEntryEntity>) : ThumbnailCacheDao {
        val store = entries.associateBy { it.path }.toMutableMap()
        override suspend fun upsert(entry: ThumbnailCacheEntryEntity) { store[entry.path]=entry }
        override suspend fun getReadyExact(filePath:String,mediaType:String,sourceVersion:String,sizeClass:String,formatVersion:Int)=store.values.firstOrNull { it.filePath==filePath && it.mediaType==mediaType && it.sourceVersion==sourceVersion && it.sizeClass==sizeClass && it.formatVersion==formatVersion && it.state=="READY" }
        override suspend fun getExactIdentity(filePath:String,mediaType:String,sourceVersion:String,sizeClass:String,formatVersion:Int)=store.values.firstOrNull { it.filePath==filePath && it.mediaType==mediaType && it.sourceVersion==sourceVersion && it.sizeClass==sizeClass && it.formatVersion==formatVersion }
        override suspend fun touchThrottled(filePath:String,mediaType:String,sourceVersion:String,sizeClass:String,formatVersion:Int,now:Long,touchBefore:Long)=0
        override suspend fun totalManagedBytes()=store.values.filter { it.state != "EVICTED" }.sumOf { it.byteSize }
        override suspend fun evictionCandidatesAfter(now:Long,protectedBefore:Long,afterAccess:Long,afterPath:String,afterMediaType:String,afterSourceVersion:String,afterSizeClass:String,afterFormatVersion:Int,limit:Int)=store.values.filter { it.state in setOf("READY","DELETE_RETRY") && it.lastAccessAt<=protectedBefore && it.lastAccessAt>afterAccess }.sortedBy { it.lastAccessAt }.take(limit)
        override suspend fun beginEviction(filePath:String,mediaType:String,sourceVersion:String,sizeClass:String,formatVersion:Int,path:String,now:Long,leaseUntil:Long):Int { val old=store[path]?:return 0; store[path]=old.copy(state="EVICTING",leaseUntil=leaseUntil); return 1 }
        override suspend fun finishEviction(filePath:String,mediaType:String,sourceVersion:String,sizeClass:String,formatVersion:Int,path:String):Int { val old=store[path]?:return 0; store[path]=old.copy(state="EVICTED",byteSize=0,leaseUntil=0); return 1 }
        override suspend fun markDeleteRetry(filePath:String,mediaType:String,sourceVersion:String,sizeClass:String,formatVersion:Int,path:String):Int { val old=store[path]?:return 0; store[path]=old.copy(state="DELETE_RETRY",leaseUntil=0); return 1 }
        override suspend fun getByPaths(paths:List<String>)=store.values.filter { it.filePath in paths }
        override suspend fun deleteByPaths(paths:List<String>) { store.entries.removeIf { it.value.filePath in paths } }
        override suspend fun deleteExact(filePath:String,mediaType:String,sourceVersion:String,sizeClass:String,formatVersion:Int,path:String):Int {
            val entry=store[path]?:return 0
            if (entry.filePath!=filePath || entry.mediaType!=mediaType || entry.sourceVersion!=sourceVersion || entry.sizeClass!=sizeClass || entry.formatVersion!=formatVersion) return 0
            store.remove(path)
            return 1
        }
        override suspend fun retainManagedReadyPaths(paths:List<String>)=store.values.filter { it.path in paths && it.state=="READY" }.map { it.path }
    }

    @Test fun `successful eviction retains tombstone for controlled regeneration`()=runBlocking {
        val file=File.createTempFile("thumb",".webp").apply { writeText("x") }
        try {
            val dao=FakeDao(listOf(entry(file,70)))
            assertEquals(1,ThumbnailCacheTrimmer(dao).trim(0,1_000_000))
            assertEquals("EVICTED",dao.store.getValue(file.path).state)
            assertEquals(0,dao.store.getValue(file.path).byteSize)
        } finally { file.delete() }
    }

    @Test fun `delete failure becomes retry metadata`()=runBlocking {
        val file=File.createTempFile("thumb",".webp")
        try {
            val dao=FakeDao(listOf(entry(file,70)))
            assertEquals(0,ThumbnailCacheTrimmer(dao){ false }.trim(0,1_000_000))
            assertEquals("DELETE_RETRY",dao.store.getValue(file.path).state)
        } finally { file.delete() }
    }

    private fun entry(file:File,bytes:Long)=ThumbnailCacheEntryEntity(
        filePath="/media/a.jpg",mediaType="IMAGE",sourceVersion=SourceVersionCodec.encode(1,2,null),
        sizeClass="preview",formatVersion=ThumbnailIdentity.CURRENT_FORMAT_VERSION,path=file.path,
        byteSize=bytes,lastAccessAt=1,createdAt=1,
    )
}
