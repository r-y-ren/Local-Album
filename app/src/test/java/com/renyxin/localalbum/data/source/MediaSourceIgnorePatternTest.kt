package com.renyxin.localalbum.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 验证 [MediaSource] 的正则忽略规则对**目录名**的端到端过滤。
 *
 * 文件名过滤逻辑委托给 [com.renyxin.localalbum.core.index.IgnorePatternMatcher]，
 * 其语义由 IgnorePatternMatcherTest 覆盖；此处聚焦目录树剪枝，且仅使用非媒体文件
 * 占位以避免触发 ExifInterface / MediaMetadataRetriever 等 Android 依赖。
 */
class MediaSourceIgnorePatternTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `regex ignore pattern prunes matching directories`() {
        val root = tempFolder.newFolder("root")
        // 应被 ^trash 规则跳过的目录
        val trashDir = File(root, "trashdir").apply { mkdirs() }
        File(trashDir, "note.txt").writeText("x")
        // 应保留的目录
        val keepDir = File(root, "keepdir").apply { mkdirs() }
        File(keepDir, "note.txt").writeText("x")

        val source = MediaSource()
        val tree = source.scanRootsSync(
            rootPaths = listOf(root.absolutePath),
            ignorePatterns = listOf("^trash"),
        )

        assertEquals(1, tree.size)
        val rootNode = tree.first()
        val childNames = rootNode.children.map { it.name }
        assertTrue("keepdir 应被保留", childNames.contains("keepdir"))
        assertTrue("trashdir 应被 ^trash 规则跳过", childNames.none { it == "trashdir" })
    }

    @Test
    fun `no ignore patterns keeps all directories`() {
        val root = tempFolder.newFolder("root")
        File(root, "trashdir").mkdirs()
        File(root, "keepdir").mkdirs()

        val source = MediaSource()
        val tree = source.scanRootsSync(
            rootPaths = listOf(root.absolutePath),
            ignorePatterns = emptyList(),
        )

        val childNames = tree.first().children.map { it.name }
        assertTrue(childNames.contains("trashdir"))
        assertTrue(childNames.contains("keepdir"))
    }

    @Test
    fun `dot-prefixed hidden directories always skipped regardless of patterns`() {
        val root = tempFolder.newFolder("root")
        File(root, ".hidden").mkdirs()
        File(root, "visible").mkdirs()

        val source = MediaSource()
        val tree = source.scanRootsSync(
            rootPaths = listOf(root.absolutePath),
            ignorePatterns = emptyList(),
        )

        val childNames = tree.first().children.map { it.name }
        assertTrue("visible 应保留", childNames.contains("visible"))
        assertTrue(".hidden 应被默认跳过", childNames.none { it == ".hidden" })
    }
}
