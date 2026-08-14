package com.renyxin.localalbum.core.index

/**
 * Lite 扫描基准的单一配置来源。
 *
 * 该配置只描述测量口径和暂定门槛，不参与扫描策略决策。最终设备数据由基准报告
 * 绑定设备、数据集和 policyVersion 后冻结，避免把一次偶然运行当成发布门槛。
 *
 * 仅被 JVM 测试（ScanBenchmarkConfigTest）引用，故置于 test 源集；
 * 活代码使用的 ScanMilestone / ScanTelemetry / LogScanTelemetry 保留在 main 源集。
 */
/** 基准数据集定义：[id] 稳定标识、[mediaCount] 为数据集媒体规模（张）、[description] 中文说明。 */
data class ScanBenchmarkDataset(
    val id: String,
    val mediaCount: Int,
    val description: String,
)

/**
 * 扫描基准暂定门槛。所有字段单位均为毫秒（ms），测量口径为 nearest-rank P95
 * （见 [ScanBenchmarkConfig.percentile]），样本取自对应数据集规模的扫描运行。
 *
 * 命名约定：`full{n}Tti` = n 张媒体首次全量扫描的 TTI（首帧时间线可用）；
 * `full{n}Core` = 核心扫描完成；`incremental*` = 增量场景的核心扫描完成耗时。
 *
 * @param full1kTtiP95Ms 1k 数据集首次全量扫描 TTI P95 门槛（ms）
 * @param full1kCoreP95Ms 1k 数据集首次全量扫描核心完成 P95 门槛（ms）
 * @param full10kTtiP95Ms 10k 数据集首次全量扫描 TTI P95 门槛（ms）
 * @param full10kCoreP95Ms 10k 数据集首次全量扫描核心完成 P95 门槛（ms）
 * @param noChangeIncrementalCoreP95Ms 无变更增量扫描核心完成 P95 门槛（ms）
 * @param singleAddCoreP95Ms 单张新增增量扫描核心完成 P95 门槛（ms）
 * @param singleDeleteCoreP95Ms 单张删除增量扫描核心完成 P95 门槛（ms）
 * @param batch100CoreP95Ms 100 张批量新增增量扫描核心完成 P95 门槛（ms）
 */
data class ScanBenchmarkThresholds(
    val full1kTtiP95Ms: Long = 3_000L,
    val full1kCoreP95Ms: Long = 30_000L,
    val full10kTtiP95Ms: Long = 5_000L,
    val full10kCoreP95Ms: Long = 240_000L,
    val noChangeIncrementalCoreP95Ms: Long = 1_000L,
    val singleAddCoreP95Ms: Long = 2_000L,
    val singleDeleteCoreP95Ms: Long = 1_500L,
    val batch100CoreP95Ms: Long = 10_000L,
)

object ScanBenchmarkConfig {
    val datasets: List<ScanBenchmarkDataset> = listOf(
        ScanBenchmarkDataset("full-1000", 1_000, "首次全量扫描，图片/视频混合"),
        ScanBenchmarkDataset("full-10000", 10_000, "首次全量扫描，图片/视频混合"),
    )

    val scenarios: List<String> = listOf(
        "full_cold",
        "full_warm",
        "incremental_no_change",
        "incremental_single_add",
        "incremental_single_modify",
        "incremental_single_delete",
        "incremental_batch_add_100",
        "incremental_rename_move",
        "observer_event_storm",
        "process_restart_resume",
    )

    val thresholds = ScanBenchmarkThresholds()

    /**
     * 计算 nearest-rank 百分位。空样本返回 null，调用方必须显式报告缺失数据。
     */
    fun percentile(samplesMs: List<Long>, percentile: Int): Long? {
        require(percentile in 0..100) { "percentile must be between 0 and 100" }
        if (samplesMs.isEmpty()) return null
        val sorted = samplesMs.sorted()
        val rank = kotlin.math.ceil(percentile / 100.0 * sorted.size).toInt().coerceAtLeast(1)
        return sorted[rank - 1]
    }

    fun median(samplesMs: List<Long>): Long? = percentile(samplesMs, 50)

    /**
     * 返回“核心终点”是否可与基准比较。增强终点不能被误传为核心终点。
     */
    fun isCoreMilestone(milestone: ScanMilestone): Boolean = when (milestone) {
        ScanMilestone.TRIGGERED,
        ScanMilestone.FIRST_BATCH_COMMITTED,
        ScanMilestone.INDEX_AVAILABLE,
        ScanMilestone.CORE_SCAN_COMPLETED,
        -> true
        ScanMilestone.SNAPSHOT_PUBLISHED,
        ScanMilestone.ENHANCEMENT_COMPLETED,
        ScanMilestone.ANALYSIS_IDLE,
        -> false
    }
}

/** JVM/Instrumentation 测试都可使用的线程安全测量收集器。 */
class InMemoryScanTelemetry : ScanTelemetry {
    private val events = java.util.Collections.synchronizedList(mutableListOf<ScanMeasurementEvent>())

    override fun record(event: ScanMeasurementEvent) {
        events += event
    }

    fun snapshot(): List<ScanMeasurementEvent> = synchronized(events) { events.toList() }

    fun eventsFor(scanId: String, milestone: ScanMilestone? = null): List<ScanMeasurementEvent> =
        snapshot().filter { it.scanId == scanId && (milestone == null || it.milestone == milestone) }
}
