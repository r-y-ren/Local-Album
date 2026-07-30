package com.renyxin.localalbum.core.analysis

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.Test

class BoundedDuplicateBatchProcessorTest {
    @Test
    fun `同内容文件产生同组精确哈希`() {
        val first = File.createTempFile("duplicate-a", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val second = File.createTempFile("duplicate-b", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val different = File.createTempFile("duplicate-c", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3, 5)) }
        try {
            val result = BoundedDuplicateBatchProcessor.process(
                listOf(first, second, different).map {
                    BoundedDuplicateBatchProcessor.Input(it.absolutePath, it.length())
                },
                maxBatchSize = 3,
                hash = { ExactFileHasher.sha256(File(it)) },
            )
            assertEquals(result[0].exactHash, result[1].exactHash)
            assertNotEquals(result[0].exactHash, result[2].exactHash)
        } finally {
            first.delete()
            second.delete()
            different.delete()
        }
    }

    @Test
    fun `输入超过批次上界立即拒绝`() {
        val inputs = (0..64).map { BoundedDuplicateBatchProcessor.Input("/$it", 1) }
        assertFailsWith<IllegalArgumentException> {
            BoundedDuplicateBatchProcessor.process(inputs, maxBatchSize = 64, hash = { "hash" })
        }
    }

    @Test
    fun `算法源文件绝不依赖整库路径接口`() {
        val source = File("src/main/java/com/renyxin/localalbum/data/worker/DuplicateMaintenanceWorker.kt").readText()
        assertEquals(false, source.contains("getAllPaths"))
        assertEquals(false, source.contains("getWithPerceptualHash"))
    }
}
