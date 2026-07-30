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
    fun `merge keeps MediaStore base and fills EXIF from file source`() {
        val mediaStore = media("/same.jpg").copy(capturedAt = Instant.ofEpochMilli(100), width = 4000)
        val file = media("/same.jpg").copy(
            capturedAt = Instant.ofEpochMilli(200),
            width = 10,
            make = "Canon",
            model = "R5",
            latitude = 12.3,
            orientation = 6,
        )

        val merged = ScanMediaCodec.merge(ScanMediaCodec.encode(mediaStore), ScanMediaCodec.encode(file))

        assertEquals(Instant.ofEpochMilli(100), merged.capturedAt)
        assertEquals(4000, merged.width)
        assertEquals("Canon", merged.make)
        assertEquals("R5", merged.model)
        assertEquals(12.3, merged.latitude!!, 0.0)
        assertEquals(6, merged.orientation)
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
