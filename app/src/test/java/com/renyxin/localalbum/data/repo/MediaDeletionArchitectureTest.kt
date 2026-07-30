package com.renyxin.localalbum.data.repo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 防止未来新增调用点绕过关联一致性 coordinator。 */
class MediaDeletionArchitectureTest {
    @Test fun `direct media deletion is restricted to coordinator and import switch`() {
        val root = File("src/main/java/com/renyxin/localalbum")
        val violations = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("data/repo/MediaDeletionCoordinator.kt") }
            .filterNot { it.invariantSeparatorsPath.endsWith("data/backup/DatabaseImporter.kt") }
            .filterNot { it.invariantSeparatorsPath.endsWith("data/db/dao/MediaDao.kt") }
            .filter { file ->
                val source = file.readText()
                source.contains("mediaDao.deleteByPaths(") || source.contains("mediaDao().deleteByPaths(")
            }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toList()
        assertTrue("直接删除 media_items，必须改走 MediaDeletionCoordinator: $violations", violations.isEmpty())
    }
}
