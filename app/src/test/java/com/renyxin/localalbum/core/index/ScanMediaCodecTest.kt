package com.renyxin.localalbum.core.index

import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ScanMediaCodecTest {
    @Test
    fun `round trip preserves scan metadata`() {
        val item = media("/a.jpg").copy(make = "Google", latitude = 31.2, orientation = 6)
        val decoded = ScanMediaCodec.decode(ScanMediaCodec.encode(item))
        // id 是由路径派生的非持久字段，其余扫描元数据必须无损。
        assertEquals(item.copy(id = decoded.id), decoded)
    }

    @Test
    fun `merge keeps MediaStore metadata but adopts filesystem physical version`() {
        val mediaStore = media("/same.jpg").copy(
            capturedAt = Instant.ofEpochMilli(100),
            modifiedAt = Instant.ofEpochMilli(1_787_037_277_000L),
            fileSize = 100,
            width = 4000,
        )
        val file = media("/same.jpg").copy(
            capturedAt = Instant.ofEpochMilli(200),
            modifiedAt = Instant.ofEpochMilli(1_787_037_277_145L),
            fileSize = 101,
            width = 10,
            make = "Canon",
            model = "R5",
            latitude = 12.3,
            orientation = 6,
        )

        val merged = ScanMediaCodec.merge(ScanMediaCodec.encode(mediaStore), ScanMediaCodec.encode(file))

        assertEquals(Instant.ofEpochMilli(100), merged.capturedAt)
        assertEquals(1_787_037_277_145L, merged.modifiedAt.toEpochMilli())
        assertEquals(101, merged.fileSize)
        assertEquals(4000, merged.width)
        assertEquals("Canon", merged.make)
        assertEquals("R5", merged.model)
        assertEquals(12.3, merged.latitude!!, 0.0)
        assertEquals(6, merged.orientation)
    }

    @Test
    fun `MediaStore-only merge keeps provider physical version`() {
        val mediaStore = media("/provider-only.jpg").copy(
            modifiedAt = Instant.ofEpochMilli(1_787_037_277_000L),
            fileSize = 100,
        )

        val merged = ScanMediaCodec.merge(ScanMediaCodec.encode(mediaStore), null)

        assertEquals(mediaStore.modifiedAt, merged.modifiedAt)
        assertEquals(mediaStore.fileSize, merged.fileSize)
    }

    private fun media(path: String) = MediaItem(
        id = "id",
        filePath = path,
        fileName = path.substringAfterLast('/'),
        type = MediaType.IMAGE,
        capturedAt = Instant.EPOCH,
        modifiedAt = Instant.ofEpochMilli(10),
        fileSize = 100,
        width = 100,
        height = 80,
        mimeType = "image/jpeg",
    )
}
