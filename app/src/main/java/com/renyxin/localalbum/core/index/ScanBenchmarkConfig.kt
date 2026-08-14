package com.renyxin.localalbum.core.index

/**
 * Lite 扫描基准的单一配置来源。
 *
 * 该配置只描述测量口径和暂定门槛，不参与扫描策略决策。最终设备数据由基准报告
 * 绑定设备、数据集和 policyVersion 后冻结，避免把一次偶然运行当成发布门槛。
 */
data class ScanBenchmarkDataset(
    val id: String,
    val mediaCount: Int,
    val description: String,
)

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
    const val CONFIG_VERSION = "lite-v1-baseline-v1"
    const val DEFAULT_SAMPLE_COUNT = 5
    const val MEDIA_BATCH_SIZE = 250
    const val CHANGE_JOURNAL_BATCH_SIZE = 500
    const val MAX_EVENT_WINDOW_MS = 5_000L
    const val EVENT_FLUSH_THRESHOLD = 500

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

/** 扫描测量的用户可感知里程碑；增强终点与核心终点严格分开。 */
enum class ScanMilestone {
    TRIGGERED,
    FIRST_BATCH_COMMITTED,
    INDEX_AVAILABLE,
    SNAPSHOT_PUBLISHED,
    CORE_SCAN_COMPLETED,
    ENHANCEMENT_COMPLETED,
    ANALYSIS_IDLE,
}

data class ScanMeasurementEvent(
    val scanId: String,
    val scanType: String,
    val milestone: ScanMilestone,
    val elapsedMs: Long,
    val mediaCount: Int = 0,
    val changedCount: Int = 0,
    val timestampMs: Long = System.currentTimeMillis(),
)

fun interface ScanTelemetry {
    fun record(event: ScanMeasurementEvent)
}

object NoopScanTelemetry : ScanTelemetry {
    override fun record(event: ScanMeasurementEvent) = Unit
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

/** 设备基准默认写入 Logcat，便于 Perfetto/脚本按 scanId 聚合，不泄露媒体路径。 */
class LogScanTelemetry(
    private val tag: String = "ScanBenchmark",
) : ScanTelemetry {
    override fun record(event: ScanMeasurementEvent) {
        android.util.Log.i(
            tag,
            "scanId=${event.scanId} type=${event.scanType} milestone=${event.milestone} " +
                "elapsedMs=${event.elapsedMs} mediaCount=${event.mediaCount} changedCount=${event.changedCount}",
        )
    }
}
