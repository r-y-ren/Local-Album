package com.renyxin.localalbum.data.repo

import android.content.Context
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.thumbnail.SourceVersionCodec
import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.InteractiveThumbnailEnqueueResult
import com.renyxin.localalbum.data.db.dao.ThumbnailCacheDao
import com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailLaneWakeEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One item in a UI-visible thumbnail request wave. */
data class ThumbnailRequest(val item: MediaItem, val sizeClass: String, val priority: Int)

/** UI 到持久化缩略图队列的轻量边界；数据库提交后无条件 kick dispatcher。 */
class ThumbnailScheduler internal constructor(
    private val enqueueInteractive: suspend (List<ThumbnailTaskEntity>) -> InteractiveThumbnailEnqueueResult,
    private val reactivateInfrastructureDone: suspend (
        String,String,String,String,Int,String,Int,Long,
    ) -> InteractiveThumbnailEnqueueResult,
    private val findExact: suspend (String,String,String,String,Int) -> ThumbnailCacheEntryEntity?,
    private val touchReady: suspend (String,String,String,String,Int,Long,Long) -> Unit,
    private val resolveCanonicalSourceVersion: suspend (String,String,Long,Long) -> String? =
        { _,_,modifiedAtMs,fileSize -> SourceVersionCodec.encode(modifiedAtMs,fileSize,null) },
    private val kickDispatcher: () -> Unit,
    private val cacheFileExists: (String) -> Boolean = { File(it).isFile },
    private val cacheFileDecodable: (String) -> Boolean = { File(it).length() > 0L },
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(
        @Suppress("UNUSED_PARAMETER") context: Context,
        taskDao: ThumbnailTaskDao,
        cacheDao: ThumbnailCacheDao,
        dispatcher: ThumbnailWakeDispatcher,
        now: () -> Long = System::currentTimeMillis,
    ) : this(
        enqueueInteractive = taskDao::enqueueInteractive,
        reactivateInfrastructureDone = taskDao::reactivateInfrastructureDone,
        findExact = cacheDao::getExact,
        touchReady = { path,type,version,size,format,timestamp,touchBefore ->
            cacheDao.touchThrottled(path,type,version,size,format,timestamp,touchBefore)
        },
        resolveCanonicalSourceVersion = taskDao::resolveCanonicalSourceVersion,
        kickDispatcher = { dispatcher.kick(ThumbnailLaneWakeEntity.INTERACTIVE_LANE_ID) },
        now = now,
    )

    private val recentRequests = ConcurrentHashMap<String, Long>()

    suspend fun request(item: MediaItem, sizeClass: String, priority: Int): String? =
        requestBatch(listOf(ThumbnailRequest(item,sizeClass,priority))).single()

    suspend fun requestBatch(requests: List<ThumbnailRequest>): List<String?> {
        if (requests.isEmpty()) return emptyList()
        val timestamp = now()
        val resolved = MutableList<String?>(requests.size) { null }
        val pending = linkedMapOf<String,ThumbnailTaskEntity>()
        val repairs = linkedMapOf<String,Triple<ThumbnailRequest,ThumbnailCacheEntryEntity?,ThumbnailIdentity>>()

        requests.forEachIndexed { index, request ->
            val item = request.item
            val provisional = ThumbnailIdentity.create(
                path = item.filePath,
                mediaType = item.type.name,
                modifiedAtMs = item.modifiedAt.toEpochMilli(),
                fileSize = item.fileSize,
                fingerprintHead = null,
                sizeClass = request.sizeClass,
            )
            // MediaItem deliberately excludes fingerprint. Never enqueue its degraded f=- version when
            // the canonical row has a fingerprint; resolve the exact DB identity first.
            val sourceVersion = resolveCanonicalSourceVersion(
                provisional.canonicalPath,provisional.mediaType,
                item.modifiedAt.toEpochMilli(),item.fileSize,
            ) ?: return@forEachIndexed
            val identity = provisional.copy(sourceVersion=sourceVersion)
            val key = identity.sha256()
            val entry = findExact(
                identity.canonicalPath,identity.mediaType,identity.sourceVersion,
                identity.sizeClass,identity.formatVersion,
            )
            val healthy = entry?.let { candidate ->
                candidate.state == ThumbnailCacheEntryEntity.STATE_READY &&
                    cacheFileExists(candidate.path) && cacheFileDecodable(candidate.path) &&
                    ThumbnailSpec.isCurrentCachePath(identity.sizeClass,candidate.path)
            } == true
            if (healthy) {
                touchReady(
                    identity.canonicalPath,identity.mediaType,identity.sourceVersion,
                    identity.sizeClass,identity.formatVersion,timestamp,
                    timestamp - ThumbnailSpec.TOUCH_THROTTLE_MS,
                )
                recentRequests[key] = timestamp
                resolved[index] = requireNotNull(entry).path
                return@forEachIndexed
            }
            val repairableInfrastructure = entry == null ||
                entry.state != ThumbnailCacheEntryEntity.STATE_GENERATING
            if (repairableInfrastructure) {
                // metadata absent 也可能对应历史 exact-current DONE。受控 DAO 仅把 canonical DONE
                // 转 PENDING；FAILED 保持稳定。并保留普通 enqueue，以覆盖根本没有 task 的首次请求。
                repairs[key] = Triple(request,entry,identity)
            }
            val last = recentRequests[key]
            if (repairableInfrastructure || last == null || timestamp - last >= REQUEST_COOLDOWN_MS) {
                recentRequests[key] = timestamp
                pending[key] = ThumbnailTaskEntity(
                    filePath = identity.canonicalPath,
                    mediaType = identity.mediaType,
                    sourceVersion = identity.sourceVersion,
                    sizeClass = identity.sizeClass,
                    formatVersion = identity.formatVersion,
                    priority = request.priority,
                    createdAt = timestamp,
                    updatedAt = timestamp,
                )
            }
        }

        repairs.values.forEach { (request,entry,identity) ->
            reactivateInfrastructureDone(
                identity.canonicalPath,identity.mediaType,identity.sourceVersion,identity.sizeClass,
                identity.formatVersion,entry?.path.orEmpty(),request.priority,timestamp,
            )
        }
        if (pending.isNotEmpty()) enqueueInteractive(pending.values.toList())
        // Correctness is level-triggered: even promotion/no-op commits kick the replayable observer seam.
        if (repairs.isNotEmpty() || pending.isNotEmpty()) kickDispatcher()
        if (recentRequests.size > MAX_RECENT_KEYS) {
            recentRequests.entries.removeIf { timestamp - it.value >= REQUEST_COOLDOWN_MS }
        }
        return resolved
    }

    companion object {
        private const val REQUEST_COOLDOWN_MS = 15_000L
        private const val MAX_RECENT_KEYS = 2_000
    }
}
