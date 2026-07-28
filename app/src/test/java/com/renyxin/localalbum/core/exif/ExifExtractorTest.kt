package com.renyxin.localalbum.core.exif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifExtractorTest {

    @Test
    fun `formatExifInfo includes filename and filesize`() {
        val info = ExifExtractor.ExifInfo(
            fileName = "test.jpg",
            fileSize = 1048576L,
        )
        val formatted = ExifExtractor.formatExifInfo(info)
        val labels = formatted.map { it.first }
        assertTrue("文件名" in labels)
        assertTrue("文件大小" in labels)
        val fileSizePair = formatted.find { it.first == "文件大小" }
        assertNotNull(fileSizePair)
        assertEquals("1.00 MB", fileSizePair!!.second)
    }

    @Test
    fun `formatExifInfo includes camera info when present`() {
        val info = ExifExtractor.ExifInfo(
            fileName = "photo.jpg",
            fileSize = 2048L,
            make = "Canon",
            model = "EOS R5",
            aperture = "2.8",
            focalLength = "50",
            iso = "100",
        )
        val formatted = ExifExtractor.formatExifInfo(info)
        val map = formatted.toMap()
        assertEquals("Canon", map["设备品牌"])
        assertEquals("EOS R5", map["设备型号"])
        assertEquals("f/2.8", map["光圈"])
        assertEquals("50mm", map["焦距"])
        assertEquals("100", map["ISO"])
    }

    @Test
    fun `formatExifInfo includes resolution when width and height present`() {
        val info = ExifExtractor.ExifInfo(
            fileName = "photo.jpg",
            fileSize = 1024L,
            imageWidth = 4000,
            imageHeight = 3000,
        )
        val formatted = ExifExtractor.formatExifInfo(info)
        val map = formatted.toMap()
        assertEquals("4000x3000", map["分辨率"])
    }

    @Test
    fun `formatExifInfo omits null fields`() {
        val info = ExifExtractor.ExifInfo(
            fileName = "photo.jpg",
            fileSize = 512L,
            make = null,
            model = null,
        )
        val formatted = ExifExtractor.formatExifInfo(info)
        val labels = formatted.map { it.first }
        assertTrue("设备品牌" !in labels)
        assertTrue("设备型号" !in labels)
    }

    @Test
    fun `formatFileSize handles various sizes`() {
        val info1 = ExifExtractor.ExifInfo(fileName = "a.jpg", fileSize = 500L)
        val info2 = ExifExtractor.ExifInfo(fileName = "b.jpg", fileSize = 2048L)
        val info3 = ExifExtractor.ExifInfo(fileName = "c.jpg", fileSize = 1048576L)
        val info4 = ExifExtractor.ExifInfo(fileName = "d.jpg", fileSize = 1073741824L)

        assertEquals("500 B", ExifExtractor.formatExifInfo(info1).find { it.first == "文件大小" }!!.second)
        assertEquals("2.0 KB", ExifExtractor.formatExifInfo(info2).find { it.first == "文件大小" }!!.second)
        assertEquals("1.00 MB", ExifExtractor.formatExifInfo(info3).find { it.first == "文件大小" }!!.second)
        assertEquals("1.00 GB", ExifExtractor.formatExifInfo(info4).find { it.first == "文件大小" }!!.second)
    }
}
