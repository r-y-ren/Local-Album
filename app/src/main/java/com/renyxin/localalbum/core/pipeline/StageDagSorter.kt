package com.renyxin.localalbum.core.pipeline

import android.util.Log

/**
 * 基于 DAG（有向无环图）的分析阶段拓扑排序器。
 *
 * 根据每个 [AnalysisStage] 的 [AnalysisStage.dependencies] 关系计算安全的执行顺序。
 * 无依赖的阶段排在前面，依赖其他阶段的排在后面，保证前置阶段先执行。
 *
 * ## 使用方式
 *
 * ```kotlin
 * val stages = listOf(qualityStage, similarityStage, semanticStage)
 * val sorted = StageDagSorter.sort(stages)
 * // sorted = [qualityStage, semanticStage, similarityStage]
 * ```
 *
 * ## 排序规则
 *
 * 1. 基于入度（in-degree）= 0 的 Kahn 算法
 * 2. 同层无依赖阶段保持原始插入顺序（稳定排序）
 * 3. 检测循环依赖，抛出 [CyclicDependencyException]
 *
 * @throws CyclicDependencyException 当 detectCycle = true 时检测到循环依赖
 */
object StageDagSorter {

    private const val TAG = "StageDagSorter"

    /**
     * 对阶段列表执行拓扑排序。
     *
     * @param stages 待排序的分析阶段列表
     * @param detectCycle 是否检测循环依赖（默认 true）；设为 false 可在生产环境跳过高开销检测
     * @return 按拓扑顺序排列的阶段列表
     * @throws CyclicDependencyException 当 detectCycle = true 且存在循环依赖时
     */
    fun sort(stages: List<AnalysisStage>, detectCycle: Boolean = true): List<AnalysisStage> {
        if (stages.isEmpty()) return emptyList()
        if (stages.size == 1) return stages.toList()

        // 构建 stageId → stage 索引映射
        val stageById = stages.associateBy { it.stageId }
        val stageIds = stages.map { it.stageId }.toSet()

        // 入度表：每个 stageId 有多少未处理的前置依赖
        val inDegree = mutableMapOf<String, Int>()
        stages.forEach { inDegree[it.stageId] = 0 }

        // 邻接表：stageId → 依赖它的后置 stageId 列表
        val adj = mutableMapOf<String, MutableList<String>>()
        stages.forEach { adj[it.stageId] = mutableListOf() }

        for (stage in stages) {
            for (depId in stage.dependencies) {
                if (depId !in stageIds) {
                    Log.w(TAG, "阶段 '${stage.stageId}' 依赖的 '$depId' 不在当前阶段列表中，忽略此依赖")
                    continue
                }
                inDegree[stage.stageId] = (inDegree[stage.stageId] ?: 0) + 1
                adj.getOrPut(depId) { mutableListOf() }.add(stage.stageId)
            }
        }

        // Kahn 算法拓扑排序
        val queue = ArrayDeque<String>()
        for (stage in stages) {
            if (inDegree[stage.stageId] == 0) {
                queue.addLast(stage.stageId)
            }
        }

        val sortedIds = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sortedIds.add(current)

            for (next in adj[current].orEmpty()) {
                val newDegree = (inDegree[next] ?: 1) - 1
                inDegree[next] = newDegree
                if (newDegree == 0) {
                    queue.addLast(next)
                }
            }
        }

        // 循环依赖检测
        if (detectCycle && sortedIds.size < stages.size) {
            val unresolved = stageIds - sortedIds.toSet()
            val cycleDesc = unresolved.joinToString(", ") { id ->
                val stage = stageById[id]!!
                "$id → [${stage.dependencies.joinToString()}]"
            }
            throw CyclicDependencyException(
                "检测到循环依赖：${stages.size - sortedIds.size} 个阶段无法拓扑排序。\n" +
                    "未解析阶段: $cycleDesc"
            )
        }

        // 将排序后的 ID 映射回 AnalysisStage 对象
        val sortedStages = sortedIds.mapNotNull { stageById[it] }

        // 若有遗留未排入的阶段（detectCycle = false 时可能发生），追加到末尾
        val remainingIds = stageIds - sortedIds.toSet()
        val remainingStages = remainingIds.mapNotNull { stageById[it] }

        return sortedStages + remainingStages
    }
}

/**
 * 循环依赖异常。
 *
 * 当分析阶段的 [AnalysisStage.dependencies] 关系中存在回环时抛出。
 * 例如 A 依赖 B，B 依赖 C，C 依赖 A。
 */
class CyclicDependencyException(message: String) : IllegalStateException(message)