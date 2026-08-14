package com.renyxin.localalbum.core.index

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
