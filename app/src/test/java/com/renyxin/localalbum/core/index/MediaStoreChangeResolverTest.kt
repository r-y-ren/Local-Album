package com.renyxin.localalbum.core.index

import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.entity.MediaChangeEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreChangeResolverTest {
    @Test
    fun `image item uri retains volume type and id`() {
        val identity = MediaStoreChangeResolver.parseIdentity(
            "content://media/external_primary/images/media/42",
        )

        assertEquals(
            MediaStoreIdentity("external_primary", MediaType.IMAGE, 42L),
            identity,
        )
        assertEquals(
            "content://media/external_primary/images/media/42",
            identity?.canonicalContentUri(),
        )
    }

    @Test
    fun `video item uri retains volume type and id`() {
        val identity = MediaStoreChangeResolver.parseIdentity(
            "content://media/0123-4567/video/media/9001",
        )

        assertEquals(
            MediaStoreIdentity("0123-4567", MediaType.VIDEO, 9001L),
            identity,
        )
        assertEquals(
            "content://media/0123-4567/video/media/9001",
            identity?.canonicalContentUri(),
        )
    }

    @Test
    fun `collection null and malformed uris become reconciliation hints`() {
        val invalidUris = listOf<String?>(
            null,
            "content://media/external/images/media",
            "content://media/external/files/42",
            "content://settings/external/images/media/42",
            "file:///storage/emulated/0/DCIM/photo.jpg",
            "content://media/external/images/media/-1",
            "not a uri",
        )

        invalidUris.forEachIndexed { index, uri ->
            assertNull(uri?.let(MediaStoreChangeResolver::parseIdentity))
            val event = MediaStoreChangeResolver.toEvent(
                profileId = "lite",
                notification = notification(uri, observedAtMs = 100L + index),
            )
            assertEquals(MediaChangeEventEntity.TYPE_RECONCILIATION, event.eventType)
            assertEquals("lite:reconciliation", event.eventKey)
            assertNull(MediaStoreChangeResolver.identityOf(event))
        }
    }

    @Test
    fun `media event round trips identity and keeps observer metadata`() {
        val event = MediaStoreChangeResolver.toEvent(
            profileId = "lite",
            notification = MediaStoreChangeNotification(
                contentUri = "content://media/external/images/media/7",
                flags = 0x05,
                firstObservedAtMs = 10L,
                observedAtMs = 20L,
            ),
        )

        assertEquals(MediaChangeEventEntity.TYPE_MEDIA, event.eventType)
        assertEquals("lite:external:IMAGE:7", event.eventKey)
        assertEquals(0x05, event.flags)
        assertEquals(10L, event.firstObservedAtMs)
        assertEquals(20L, event.observedAtMs)
        assertEquals(
            MediaStoreIdentity("external", MediaType.IMAGE, 7L),
            MediaStoreChangeResolver.identityOf(event),
        )
    }

    @Test
    fun `stable identity is isolated by edition profile`() {
        val identity = MediaStoreIdentity("external", MediaType.IMAGE, 7L)
        val fullKey = identity.stableKey("full")
        val liteKey = identity.stableKey("lite")

        assertNotEquals(fullKey, liteKey)
        assertTrue(fullKey.startsWith("full:"))
        assertTrue(liteKey.startsWith("lite:"))
    }

    private fun notification(uri: String?, observedAtMs: Long) = MediaStoreChangeNotification(
        contentUri = uri,
        flags = 0,
        firstObservedAtMs = observedAtMs,
        observedAtMs = observedAtMs,
    )
}
