package com.renyxin.localalbum.core.pipeline

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 管道编排器单元测试（Phase 3.2）。
 *
 * 验证 [PluginAnalysisPipeline] 的核心行为：
 * - 空文件列表快速返回
 * - 全量扫描按拓扑排序执行并生成进度事件
 * - 阶段异常隔离（单阶段失败不中断后续阶段）
 * - 增量扫描对可缓存阶段的路径过滤
 */
class PluginAnalysisPipelineTest {

    // ---- 空列表 ----

    @Test
    fun `empty file list should return empty results`() = runBlocking {
        val pipeline = PluginAnalysisPipeline(
            stages = listOf(mockStage("a", "Stage A")),
        )
        val results = pipeline.runFullScan(emptyList())
        assertTrue("空文件列表应返回空映射", results.isEmpty())
        assertEquals(PluginAnalysisPipeline.Status.IDLE, pipeline.pipelineStatusFlow.value)
    }

    // ---- 全量扫描 ----

    @Test
    fun `full scan should execute all stages and collect progress events`() = runBlocking {
        val files = listOf("/a.jpg", "/b.jpg", "/c.jpg")
        val stages = listOf(
            mockStage("q", "Quality", deps = emptyList(), success = 3, extra = mapOf("avgScore" to "0.92")),
            mockStage("s", "Scene", deps = emptyList(), success = 3),
            mockStage("c", "Cluster", deps = listOf("q", "s"), success = 3),
        )

        val pipeline = PluginAnalysisPipeline(stages = stages)

        val results = pipeline.runFullScan(files)

        // 验证每个阶段都执行并返回结果
        assertEquals(3, results.size)
        assertTrue(results.containsKey("q"))
        assertTrue(results.containsKey("s"))
        assertTrue(results.containsKey("c"))
        assertEquals(3, results["q"]?.successCount)
        assertEquals("0.92", results["q"]?.extra?.get("avgScore"))

        // 通过 replayCache 获取已缓存的进度事件（避免 launch/cancel 竞态）
        val completedEventIds = pipeline.stageProgressFlow.replayCache
            .filter { it.stageStatus == StageProgress.StageStatus.COMPLETED }
            .map { it.stageId }
            .toSet()
        assertEquals(setOf("q", "s", "c"), completedEventIds)

        assertEquals(PluginAnalysisPipeline.Status.COMPLETED, pipeline.pipelineStatusFlow.value)
    }

    // ---- 拓扑排序 ----

    @Test
    fun `stages should execute in topological order`() = runBlocking {
        val executionOrder = mutableListOf<String>()

        val stageA = LambdaStage(
            stageId = "a",
            displayName = "A",
            dependencies = emptyList(),
        ) { _, _ ->
            executionOrder.add("a")
            StageResult(1, 0)
        }

        val stageB = LambdaStage(
            stageId = "b",
            displayName = "B",
            dependencies = listOf("a"),
        ) { _, _ ->
            executionOrder.add("b")
            StageResult(1, 0)
        }

        val stageC = LambdaStage(
            stageId = "c",
            displayName = "C",
            dependencies = listOf("a"),
        ) { _, _ ->
            executionOrder.add("c")
            StageResult(1, 0)
        }

        val stageD = LambdaStage(
            stageId = "d",
            displayName = "D",
            dependencies = listOf("b", "c"),
        ) { _, _ ->
            executionOrder.add("d")
            StageResult(1, 0)
        }

        val pipeline = PluginAnalysisPipeline(stages = listOf(stageC, stageB, stageD, stageA)) // 乱序输入
        pipeline.runFullScan(listOf("/x.jpg"))

        // a 必须最先，d 必须最后，但 b/c 相对顺序取决于稳定排序（输入顺序中 b 在 c 前）
        assertEquals("a", executionOrder[0])
        assertEquals("d", executionOrder[3])
        assertTrue(
            executionOrder == listOf("a", "c", "b", "d") || executionOrder == listOf("a", "b", "c", "d"),
        )
    }

    @Test
    fun `heavy model stages execute serially and release resources after batch`() = runBlocking {
        var activeStages = 0
        var maxActiveStages = 0
        val releasedStages = mutableListOf<String>()
        val stages = listOf("core:face", "core:semantic", "core:ocr").map { id ->
            LambdaStage(stageId = id, displayName = id) { _, _ ->
                activeStages++
                maxActiveStages = maxOf(maxActiveStages, activeStages)
                delay(10)
                activeStages--
                StageResult(1, 0)
            }
        }
        val pipeline = PluginAnalysisPipeline(
            stages = stages,
            releaseStageResources = { stageId -> releasedStages.add(stageId) },
        )

        pipeline.runFullScan(listOf("/x.jpg"))

        assertEquals(1, maxActiveStages)
        assertEquals(listOf("core:face", "core:semantic", "core:ocr"), releasedStages)
    }

    @Test
    fun `face and quality execute in parallel while semantic remains exclusive`() = runBlocking {
        var activeStages = 0
        var maxActiveStages = 0
        var semanticObservedActive = -1
        val stages = listOf(
            LambdaStage(AnalysisStage.STAGE_FACE, "Face") { _, _ ->
                activeStages++
                maxActiveStages = maxOf(maxActiveStages, activeStages)
                delay(30)
                activeStages--
                StageResult(1, 0)
            },
            LambdaStage(AnalysisStage.STAGE_QUALITY, "Quality") { _, _ ->
                activeStages++
                maxActiveStages = maxOf(maxActiveStages, activeStages)
                delay(30)
                activeStages--
                StageResult(1, 0)
            },
            LambdaStage(AnalysisStage.STAGE_SEMANTIC, "Semantic") { _, _ ->
                semanticObservedActive = activeStages
                StageResult(1, 0)
            },
        )

        PluginAnalysisPipeline(stages = stages).runFullScan(listOf("/x.jpg"))

        assertEquals(2, maxActiveStages)
        assertEquals(0, semanticObservedActive)
    }

    @Test
    fun `batch resources are released when a stage is cancelled`() = runBlocking {
        var releaseCount = 0
        val cancellingStage = LambdaStage(stageId = "cancel", displayName = "Cancel") { _, _ ->
            throw kotlinx.coroutines.CancellationException("cancelled")
        }
        val pipeline = PluginAnalysisPipeline(
            stages = listOf(cancellingStage),
            releaseStageResources = { releaseCount++ },
        )

        runCatching { pipeline.runIncremental(listOf("/x.jpg"), emptyList()) }

        assertEquals(1, releaseCount)
    }

    // ---- 异常隔离 ----

    @Test
    fun `stage failure should be isolated and not break subsequent stages`() = runBlocking {
        val files = listOf("/a.jpg", "/b.jpg")
        val executionLog = mutableListOf<String>()

        val failingStage = LambdaStage(
            stageId = "fail",
            displayName = "Failing",
        ) { _, _ ->
            executionLog.add("fail")
            throw RuntimeException("simulated failure")
        }

        val normalStage = LambdaStage(
            stageId = "ok",
            displayName = "Normal",
        ) { _, _ ->
            executionLog.add("ok")
            StageResult(2, 0)
        }

        val pipeline = PluginAnalysisPipeline(stages = listOf(failingStage, normalStage))
        val results = pipeline.runFullScan(files)

        // 两个阶段都应该被尝试执行
        assertEquals(listOf("fail", "ok"), executionLog)

        // 失败阶段应返回错误计数
        assertEquals(0, results["fail"]?.successCount)
        assertEquals(2, results["fail"]?.failedCount)

        // 正常阶段不受影响
        assertEquals(2, results["ok"]?.successCount)

        // 管道最终状态为 COMPLETED（而不是 ERROR，因为异常已被吞掉）
        assertEquals(PluginAnalysisPipeline.Status.COMPLETED, pipeline.pipelineStatusFlow.value)
    }

    // ---- 增量扫描 ----

    @Test
    fun `incremental scan should use incremental paths for cacheable stages`() = runBlocking {
        val allPaths = listOf("/a.jpg", "/b.jpg", "/c.jpg")
        val incPaths = listOf("/b.jpg")

        val cacheableStage = mockStage(
            "qual",
            "Quality",
            deps = emptyList(),
            success = 1,
        )

        // 需要 isCacheable = false 的 stage 才会收到全量路径
        val overrideStage = LambdaStage(
            stageId = "cluster",
            displayName = "Cluster",
            isCacheable = false,
        ) { paths, _ ->
            StageResult(successCount = paths.size, failedCount = 0)
        }

        val pipeline = PluginAnalysisPipeline(stages = listOf(cacheableStage, overrideStage))
        val results = pipeline.runIncremental(incPaths, allPaths)

        // 可缓存阶段只获得增量路径
        assertEquals(1, results["qual"]?.successCount)
        // 非可缓存阶段获得全量路径
        assertEquals(3, results["cluster"]?.successCount)
    }

    // ---- 辅助工具 ----

    private fun mockStage(
        id: String,
        name: String,
        deps: List<String> = emptyList(),
        success: Int = 0,
        extra: Map<String, String> = emptyMap(),
    ): AnalysisStage {
        return LambdaStage(
            stageId = id,
            displayName = name,
            dependencies = deps,
        ) { _, _ ->
            StageResult(successCount = success, failedCount = 0, extra = extra)
        }
    }

    /**
     * 轻量级 [AnalysisStage] 实现，用于在测试中注入任意外部闭包行为。
     */
    private class LambdaStage(
        override val stageId: String,
        override val displayName: String,
        override val stageType: StageType = StageType.BUILTIN,
        override val dependencies: List<String> = emptyList(),
        override val isCacheable: Boolean = true,
        private val block: suspend (List<String>, suspend (Int, Int) -> Unit) -> StageResult,
    ) : AnalysisStage {
        override suspend fun execute(
            filePaths: List<String>,
            progressCallback: suspend (Int, Int) -> Unit,
        ): StageResult = block(filePaths, progressCallback)
    }
}