package com.renyxin.localalbum.core.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailIdentityTest {
    @Test
    fun `source version codec round trips full and explicit degraded identities`() {
        val full = SourceVersionCodec.encode(123L,456L,"A0B1")
        assertEquals(
            ThumbnailSourceVersion(123L,456L,"a0b1",degraded=false),
            SourceVersionCodec.decode(full),
        )
        val degraded = SourceVersionCodec.encode(123L,456L,null)
        assertEquals(
            ThumbnailSourceVersion(123L,456L,null,degraded=true),
            SourceVersionCodec.decode(degraded),
        )
        assertTrue(SourceVersionCodec.matchesPhysical(degraded,123L,456L,"ffff"))
        assertFalse(SourceVersionCodec.matchesPhysical(full,123L,456L,"ffff"))
        assertNull(SourceVersionCodec.decode("sv1|bad"))
    }

    @Test
    fun `media type size and format are independent identity dimensions`() {
        val base = ThumbnailIdentity.create("a/../media.jpg","IMAGE",1,2,"aa","grid",5)
        val video = base.copy(mediaType="VIDEO")
        val preview = base.copy(sizeClass="preview")
        val nextFormat = base.copy(formatVersion=6)
        assertNotEquals(base.sha256(),video.sha256())
        assertNotEquals(base.sha256(),preview.sha256())
        assertNotEquals(base.sha256(),nextFormat.sha256())
        assertEquals(ThumbnailIdentity.canonicalizePath("media.jpg"),base.canonicalPath)
    }
}
