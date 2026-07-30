package com.renyxin.localalbum.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * N1 源码架构守卫：阻止运行时层重新引入整库 DAO 读取，并锁定扫描 staging 路径。
 * 仅检查项目相对目录；不依赖开发机绝对路径或当前工作目录恰好位于仓库根目录。
 */
class BoundedDataAccessArchitectureTest {
    private val projectRoot: File by lazy {
        generateSequence(File(requireNotNull(System.getProperty("user.dir"))).absoluteFile) { current ->
            current.parentFile?.takeUnless { it == current }
        }.firstOrNull { File(it, "app/src/main/java").isDirectory }
            ?: error("无法从工作目录定位 app/src/main/java")
    }

    @Test
    fun `runtime layers do not call unbounded media face or embedding DAO APIs`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val guardedRoots = listOf(
            "com/renyxin/localalbum/ui",
            "com/renyxin/localalbum/data/repo",
            "com/renyxin/localalbum/data/worker",
            "com/renyxin/localalbum/core/index",
        ).map { File(sourceRoot, it) }
        val forbidden = Regex(
            "\\b(?:mediaDao|faceDao|embeddingDao)\\s*(?:\\?\\.|\\.)\\s*" +
                "(?:getAll|getAllFlow|getAllPaths|getImagePaths|getAllTimelineItems)\\s*\\(",
        )

        val violations = guardedRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbidden.containsMatchIn(line.substringBefore("//"))) {
                        "${file.relativeTo(projectRoot).path}:${index + 1}"
                    } else null
                }
            }

        assertTrue("发现运行时整库 DAO 调用: $violations", violations.isEmpty())
    }

    @Test
    fun `regular face assignment reads only active persisted prototypes`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val assigner = File(
            sourceRoot,
            "com/renyxin/localalbum/core/analysis/IncrementalFaceClusterAssigner.kt",
        ).readText()
        assertTrue("常规路径必须读取 active generation 原型", assigner.contains("activePrototypesAfter("))
        listOf(
            "getAllForLegacyExport(",
            "getClusteredAfter(",
            "getMaintenanceBatchAfter(",
            "getClusterRepresentatives(",
            "getClustered(",
        ).forEach { forbidden ->
            assertFalse("常规人物分配器调用 FaceDao 全量/维护接口: $forbidden", assigner.contains(forbidden))
        }
        val stages = listOf("FaceStage.kt", "BuiltinFaceStage.kt").map {
            File(sourceRoot, "com/renyxin/localalbum/core/pipeline/stages/$it").readText()
        }
        assertTrue("常规 FaceStage 不得触发人物维护", stages.none { it.contains("FaceClusterMaintenanceWorker") })
    }

    @Test
    fun `legacy full reads are named deprecated and confined to exporter`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val legacyCall = Regex("\\.getAllForLegacyExport\\s*\\(")
        val callSites = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { legacyCall.containsMatchIn(it.readText()) }
            .map { it.relativeTo(projectRoot).path }
            .toList()

        assertTrue(
            "legacy 全量读取越出 DatabaseExporter: $callSites",
            callSites.all { it == "app/src/main/java/com/renyxin/localalbum/data/backup/DatabaseExporter.kt" },
        )
        assertTrue("DatabaseExporter 应明确使用 legacy 接口", callSites.isNotEmpty())

        listOf("MediaDao.kt", "FaceDao.kt", "EmbeddingDao.kt").forEach { name ->
            val dao = File(sourceRoot, "com/renyxin/localalbum/data/db/dao/$name").readText()
            assertTrue("$name 缺少 legacy-only 命名", dao.contains("getAllForLegacyExport"))
            assertTrue("$name legacy 接口缺少 Deprecated", dao.contains("@Deprecated"))
        }
    }

    @Test
    fun `public scans are staging only and legacy materialization helpers are absent`() {
        val indexerFile = File(
            projectRoot,
            "app/src/main/java/com/renyxin/localalbum/core/index/HybridIndexer.kt",
        )
        assertNotNull(indexerFile)
        val source = indexerFile.readText()

        val fullScan = source.substringAfter("suspend fun fullScan(").substringBefore("suspend fun incrementalScan(")
        val incrementalScan = source.substringAfter("suspend fun incrementalScan(").substringBefore("private suspend fun scanViaStaging(")
        assertTrue("fullScan 未进入 staging", fullScan.contains("scanViaStaging("))
        assertTrue("incrementalScan 未进入 staging", incrementalScan.contains("scanViaStaging("))

        listOf(
            "fullScanGuarded(",
            "incrementalScanGuarded(",
            "queryMediaStore(",
            "queryFileSystem(",
            "collectMediaItems(",
            "mergeAndDeduplicate(",
            "getModifiedTimeMap(",
            "scanRootsSync(",
        ).forEach { forbidden ->
            assertFalse("HybridIndexer 残留旧全量物化路径: $forbidden", source.contains(forbidden))
        }
    }

    @Test
    fun `thumbnail LRU is metadata based and never uses cache file mtime`() {
        val sourceRoot = File(projectRoot, "app/src/main/java")
        val mediaSource = File(sourceRoot, "com/renyxin/localalbum/data/source/MediaSource.kt").readText()
        val trimmer = File(sourceRoot, "com/renyxin/localalbum/data/repo/ThumbnailCacheTrimmer.kt").readText()
        val cacheDao = File(sourceRoot, "com/renyxin/localalbum/data/db/dao/ThumbnailCacheDao.kt").readText()

        assertFalse("旧文件目录 LRU API 不得恢复", mediaSource.contains("trimThumbnailCache("))
        assertFalse("metadata trimmer 不得读取文件 mtime", trimmer.contains("lastModified("))
        assertTrue("LRU 必须按 metadata lastAccessAt keyset", cacheDao.contains("ORDER BY lastAccessAt ASC"))
        assertTrue("LRU 必须保护最近 touch", cacheDao.contains("lastAccessAt <= :protectedBefore"))
        assertTrue("LRU 必须保护租约", cacheDao.contains("leaseUntil <= :now"))
    }
}
