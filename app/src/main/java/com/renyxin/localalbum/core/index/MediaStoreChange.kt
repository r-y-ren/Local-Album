package com.renyxin.localalbum.core.index

import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.entity.MediaChangeEventEntity
import java.net.URI

/** Raw notification retained across the observer's bounded aggregation window. */
data class MediaStoreChangeNotification(
    val contentUri: String?,
    val flags: Int,
    val firstObservedAtMs: Long,
    val observedAtMs: Long,
)

data class MediaStoreIdentity(
    val volumeName: String,
    val mediaType: MediaType,
    val mediaStoreId: Long,
) {
    fun stableKey(profileId: String): String =
        listOf(profileId, volumeName, mediaType.name, mediaStoreId.toString()).joinToString(":")

    fun canonicalContentUri(): String {
        val collection = if (mediaType == MediaType.IMAGE) "images" else "video"
        return "content://media/$volumeName/$collection/media/$mediaStoreId"
    }
}

internal object MediaStoreChangeResolver {
    fun toEvent(
        profileId: String,
        notification: MediaStoreChangeNotification,
    ): MediaChangeEventEntity {
        val identity = notification.contentUri?.let(::parseIdentity)
        return if (identity == null) {
            MediaChangeEventEntity(
                eventKey = reconciliationKey(profileId),
                profileId = profileId,
                eventType = MediaChangeEventEntity.TYPE_RECONCILIATION,
                contentUri = notification.contentUri,
                flags = notification.flags,
                firstObservedAtMs = notification.firstObservedAtMs,
                observedAtMs = notification.observedAtMs,
            )
        } else {
            MediaChangeEventEntity(
                eventKey = identity.stableKey(profileId),
                profileId = profileId,
                eventType = MediaChangeEventEntity.TYPE_MEDIA,
                volumeName = identity.volumeName,
                mediaType = identity.mediaType.name,
                mediaStoreId = identity.mediaStoreId,
                contentUri = notification.contentUri,
                flags = notification.flags,
                firstObservedAtMs = notification.firstObservedAtMs,
                observedAtMs = notification.observedAtMs,
            )
        }
    }

    fun identityOf(event: MediaChangeEventEntity): MediaStoreIdentity? {
        if (event.eventType != MediaChangeEventEntity.TYPE_MEDIA) return null
        val volume = event.volumeName ?: return null
        val type = event.mediaType?.let { runCatching { MediaType.valueOf(it) }.getOrNull() } ?: return null
        val id = event.mediaStoreId ?: return null
        return MediaStoreIdentity(volume, type, id)
    }

    fun parseIdentity(contentUri: String): MediaStoreIdentity? {
        val parsed = runCatching { URI(contentUri) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("content", ignoreCase = true) ||
            !parsed.host.equals("media", ignoreCase = true)
        ) return null
        val segments = parsed.path.orEmpty().trim('/').split('/').filter(String::isNotBlank)
        if (segments.size != 4 || segments[2] != "media") return null
        val mediaType = when (segments[1]) {
            "images" -> MediaType.IMAGE
            "video" -> MediaType.VIDEO
            else -> return null
        }
        val id = segments[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        return MediaStoreIdentity(
            volumeName = segments[0],
            mediaType = mediaType,
            mediaStoreId = id,
        )
    }

    fun reconciliationKey(profileId: String): String = "$profileId:reconciliation"
}
