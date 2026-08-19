package com.renyxin.localalbum.data.repo

import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.core.thumbnail.SourceVersionCodec
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.InteractiveThumbnailEnqueueResult
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailSchedulerTest {
    @Test
    fun `window persists full identities once and unconditionally kicks dispatcher`() = runBlocking {
        val committed = mutableListOf<List<ThumbnailTaskEntity>>()
        var kicks = 0
        val scheduler = scheduler(
            enqueue = { tasks -> committed += tasks; InteractiveThumbnailEnqueueResult(tasks.size,false) },
            kick = { kicks++ },
        )
        val image = media(1,MediaType.IMAGE)
        val video = media(2,MediaType.VIDEO)
        assertEquals(
            listOf(null,null),
            scheduler.requestBatch(
                listOf(
                    ThumbnailRequest(image,ThumbnailSpec.SIZE_GRID,100),
                    ThumbnailRequest(video,ThumbnailSpec.SIZE_PREVIEW,50),
                ),
            ),
        )
        assertEquals(1,committed.size)
        assertEquals(setOf("IMAGE","VIDEO"),committed.single().map { it.mediaType }.toSet())
        assertEquals(setOf("grid","preview"),committed.single().map { it.sizeClass }.toSet())
        assertEquals(setOf(ThumbnailIdentity.CURRENT_FORMAT_VERSION),committed.single().map { it.formatVersion }.toSet())
        assertEquals(1,kicks)
    }

    @Test
    fun `ready exact current cache hit touches without enqueue or kick`() = runBlocking {
        val item = media(7,MediaType.IMAGE)
        val identity = identity(item,ThumbnailSpec.SIZE_GRID)
        var enqueueCount = 0
        var touches = 0
        var kicks = 0
        val scheduler = ThumbnailScheduler(
            enqueueInteractive = { enqueueCount += it.size; InteractiveThumbnailEnqueueResult(it.size,false) },
            reactivateInfrastructureDone = { _,_,_,_,_,_,_,_ -> InteractiveThumbnailEnqueueResult(0,false) },
            findExact = { _,_,_,_,_ ->
                ThumbnailCacheEntryEntity(
                    filePath=identity.canonicalPath,mediaType=identity.mediaType,
                    sourceVersion=identity.sourceVersion,sizeClass=identity.sizeClass,
                    formatVersion=identity.formatVersion,
                    path="/cache/${identity.sha256()}_grid_v5.webp",byteSize=10,lastAccessAt=1,createdAt=1,
                )
            },
            touchReady = { _,_,_,_,_,_,_ -> touches++ },
            kickDispatcher = { kicks++ },
            cacheFileExists = { true },
            cacheFileDecodable = { true },
            now = { 10_000 },
        )
        assertEquals(
            "/cache/${identity.sha256()}_grid_v5.webp",
            scheduler.request(item,ThumbnailSpec.SIZE_GRID,100),
        )
        assertEquals(0,enqueueCount)
        assertEquals(1,touches)
        assertEquals(0,kicks)
    }

    @Test
    fun `metadata absent invokes controlled done repair and ordinary insert seam`() = runBlocking {
        val item = media(11,MediaType.IMAGE)
        var repairs = 0
        var enqueued = 0
        var kicks = 0
        val scheduler = ThumbnailScheduler(
            enqueueInteractive={ tasks -> enqueued += tasks.size; InteractiveThumbnailEnqueueResult(tasks.size,false) },
            reactivateInfrastructureDone={ _,_,_,_,_,badPath,_,_ ->
                assertEquals("",badPath)
                repairs++
                InteractiveThumbnailEnqueueResult(1,true)
            },
            findExact={ _,_,_,_,_ -> null },
            touchReady={ _,_,_,_,_,_,_ -> },
            kickDispatcher={ kicks++ },
            cacheFileExists={ false },
            now={ 25_000 },
        )

        assertNull(scheduler.request(item,ThumbnailSpec.SIZE_GRID,100))
        assertEquals(1,repairs)
        assertEquals(1,enqueued)
        assertEquals(1,kicks)
    }

    @Test
    fun `canonical fingerprint identity replaces degraded ui identity before enqueue`() = runBlocking {
        val item=media(9,MediaType.IMAGE)
        val exact=SourceVersionCodec.encode(item.modifiedAt.toEpochMilli(),item.fileSize,"abcdef")
        var committed: ThumbnailTaskEntity?=null
        val scheduler=ThumbnailScheduler(
            enqueueInteractive={ tasks -> committed=tasks.single(); InteractiveThumbnailEnqueueResult(1,false) },
            reactivateInfrastructureDone={ _,_,_,_,_,_,_,_ -> InteractiveThumbnailEnqueueResult(0,false) },
            findExact={ _,_,version,_,_ -> assertEquals(exact,version); null },
            touchReady={ _,_,_,_,_,_,_ -> },
            resolveCanonicalSourceVersion={ _,_,_,_ -> exact },
            kickDispatcher={},
            cacheFileExists={ false },
            now={ 30_000 },
        )

        assertNull(scheduler.request(item,ThumbnailSpec.SIZE_GRID,100))
        assertEquals(exact,committed?.sourceVersion)
    }

    @Test
    fun `missing canonical row never enqueues degraded identity`() = runBlocking {
        val item=media(10,MediaType.IMAGE)
        var enqueueCount=0
        var kicks=0
        val scheduler=ThumbnailScheduler(
            enqueueInteractive={ enqueueCount += it.size; InteractiveThumbnailEnqueueResult(it.size,false) },
            reactivateInfrastructureDone={ _,_,_,_,_,_,_,_ -> InteractiveThumbnailEnqueueResult(0,false) },
            findExact={ _,_,_,_,_ -> error("cache must not be queried without canonical identity") },
            touchReady={ _,_,_,_,_,_,_ -> },
            resolveCanonicalSourceVersion={ _,_,_,_ -> null },
            kickDispatcher={ kicks++ },
        )

        assertNull(scheduler.request(item,ThumbnailSpec.SIZE_GRID,100))
        assertEquals(0,enqueueCount)
        assertEquals(0,kicks)
    }

    @Test
    fun `evicted or missing exact cache uses infrastructure repair but never ordinary failed reopen`() = runBlocking {
        val item = media(8,MediaType.IMAGE)
        val identity = identity(item,ThumbnailSpec.SIZE_PREVIEW)
        var repairs = 0
        var kicks = 0
        val scheduler = ThumbnailScheduler(
            enqueueInteractive = { InteractiveThumbnailEnqueueResult(it.size,false) },
            reactivateInfrastructureDone = { path,type,version,size,format,badPath,_,_ ->
                assertEquals(identity.canonicalPath,path)
                assertEquals(identity.mediaType,type)
                assertEquals(identity.sourceVersion,version)
                assertEquals(identity.sizeClass,size)
                assertEquals(identity.formatVersion,format)
                assertEquals("/cache/evicted_preview_v5.webp",badPath)
                repairs++
                InteractiveThumbnailEnqueueResult(1,false)
            },
            findExact = { _,_,_,_,_ ->
                ThumbnailCacheEntryEntity(
                    filePath=identity.canonicalPath,
                    sizeClass=identity.sizeClass,
                    sourceVersion=identity.sourceVersion,
                    path="/cache/evicted_preview_v5.webp",
                    byteSize=0,
                    lastAccessAt=1,
                    createdAt=1,
                    mediaType=identity.mediaType,
                    formatVersion=identity.formatVersion,
                    state=ThumbnailCacheEntryEntity.STATE_EVICTED,
                )
            },
            touchReady = { _,_,_,_,_,_,_ -> },
            kickDispatcher = { kicks++ },
            now = { 20_000 },
        )
        assertNull(scheduler.request(item,ThumbnailSpec.SIZE_PREVIEW,100))
        assertEquals(1,repairs)
        assertEquals(1,kicks)
    }

    private fun scheduler(
        enqueue: suspend (List<ThumbnailTaskEntity>) -> InteractiveThumbnailEnqueueResult,
        kick: () -> Unit = {},
    ) = ThumbnailScheduler(
        enqueueInteractive=enqueue,
        reactivateInfrastructureDone={ _,_,_,_,_,_,_,_ -> InteractiveThumbnailEnqueueResult(0,false) },
        findExact={ _,_,_,_,_ -> null },
        touchReady={ _,_,_,_,_,_,_ -> },
        kickDispatcher=kick,
        cacheFileExists={ false },
        now={ 10_000 },
    )

    private fun identity(item: MediaItem,size: String) = ThumbnailIdentity.create(
        item.filePath,item.type.name,item.modifiedAt.toEpochMilli(),item.fileSize,null,size,
    )

    private fun media(index: Int,type: MediaType) = MediaItem(
        id=index.toString(),filePath="/media/$index.${if (type == MediaType.IMAGE) "jpg" else "mp4"}",
        fileName=index.toString(),type=type,capturedAt=Instant.ofEpochMilli(index.toLong()),
        modifiedAt=Instant.ofEpochMilli(index.toLong()),fileSize=index * 100L,
    )
}
