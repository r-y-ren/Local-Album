package com.renyxin.localalbum.core.concurrent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * 推理专用调度器集合（多核并行优化）。
 *
 * ## 设计动机
 *
 * 原实现中所有分析 Stage 与 Provider 都运行在共享的 [Dispatchers.IO] 上，
 * 但 Stage 内部用 `for` 循环串行处理文件，导致即使天玑 9400+ 这种 8 核 SoC
 * 也只能跑满单核。本对象提供与 IO/DB 隔离的、有界并发的 CPU 密集型调度器，
 * 供文件级并行推理使用。
 *
 * ## 调度器说明
 *
 * - [cpuBound] — CPU 密集型推理（ONNX/TFLite run、Bitmap 解码预处理），
 *   并发度上限 = [cpuCores]，避免 oversubscription。
 * - [ioBound] — 混合型 IO+轻计算（缩略图生成、文件读写），并发度放宽到 2×CPU。
 * - [dbBound] — 数据库批量写入，并发度 2，避免 SQLite 写锁竞争。
 *
 * ## 并发度选择
 *
 * 天玑 9400+ 为 1×X925 + 3×X4 + 4×A720 共 8 核。[cpuCores] 取
 * `availableProcessors()`（上限 8）。配合「单文件单线程推理 + 多文件并发」策略，
 * 可将全量扫描吞吐量提升至接近线性加速。
 *
 * @see com.renyxin.localalbum.core.pipeline.stages 文件级并行改造
 */
object InferenceDispatchers {

    /**
     * 设备逻辑 CPU 核心数。天玑 9400+ 报告 8。
     * 限制在 [2, 8] 区间，避免低端设备过度并发或异常值。
     */
    val cpuCores: Int = Runtime.getRuntime().availableProcessors()
        .coerceIn(2, 8)

    /**
     * 推理并发度。默认等于 [cpuCores]。
     *
     * 对内存敏感场景（大模型 + 高分辨率 Bitmap）可通过 [withConcurrency] 临时调低。
     */
    @Volatile
    var inferenceConcurrency: Int = cpuCores
        private set

    /**
     * CPU 密集型推理调度器，并发度受 [inferenceConcurrency] 限制。
     *
     * 使用 [Dispatchers.IO] 的 [limitedParallelism] 派生，复用其线程池但限制并行数，
     * 避免新建独立线程池的额外开销，同时与普通 IO 任务隔离配额。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cpuBound: CoroutineDispatcher by lazy {
        Dispatchers.IO.limitedParallelism(inferenceConcurrency)
    }

    /**
     * 混合 IO+轻计算调度器（缩略图生成等），并发度 2×CPU。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val ioBound: CoroutineDispatcher by lazy {
        Dispatchers.IO.limitedParallelism((cpuCores * 2).coerceAtLeast(4))
    }

    /**
     * 数据库写入调度器，并发度 2，串行化批量写入以避免 SQLite 写锁竞争。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val dbBound: CoroutineDispatcher by lazy {
        Dispatchers.IO.limitedParallelism(2)
    }

    /**
     * 运行时调整推理并发度（仅在应用启动早期调用）。
     *
     * 调用后 [cpuBound] 不会立即重建（已 lazy 初始化），因此应在首次推理前调用。
     * 主要用于低内存设备降级或测试场景。
     *
     * @param concurrency 新并发度，会被 clamp 到 [1, cpuCores]
     */
    fun configureConcurrency(concurrency: Int) {
        inferenceConcurrency = concurrency.coerceIn(1, cpuCores)
    }
}
