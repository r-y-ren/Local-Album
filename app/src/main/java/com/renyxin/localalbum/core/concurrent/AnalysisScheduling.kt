package com.renyxin.localalbum.core.concurrent

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/** 用户可选择的分析调度模式。底层模型安全上限不受用户模式影响。 */
enum class AnalysisSchedulingMode(val persistedValue: Int) {
    AUTO(0),
    STABILITY(1),
    BALANCED(2),
    PERFORMANCE(3),
    ;

    companion object {
        fun fromPersistedValue(value: Int): AnalysisSchedulingMode =
            entries.firstOrNull { it.persistedValue == value } ?: AUTO
    }
}

/** 用于推荐调度策略的设备能力快照。 */
data class AnalysisDeviceCapabilities(
    val logicalCpuCores: Int,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val lowMemory: Boolean,
    val thermalStatus: Int,
) {
    val totalMemoryGb: Double
        get() = totalMemoryBytes.toDouble() / BYTES_PER_GB

    val availableMemoryGb: Double
        get() = availableMemoryBytes.toDouble() / BYTES_PER_GB

    // detect() 在 Android Q 以下固定返回 NONE；纯策略只判断快照值，便于隔离平台的 JVM 验证。
    val hasSeriousThermalPressure: Boolean
        get() = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

    private companion object {
        const val BYTES_PER_GB = 1024.0 * 1024.0 * 1024.0
    }
}

/**
 * 一次分析窗口使用的有效调度参数。
 *
 * [workerLeaseGroups] 每组固定 250 条，最多四组，以维持已经真机验证过的 1000 条硬上限。
 * [thumbnailConcurrency] 同时约束 automatic/interactive lane，且不得突破 1..4 的 Bitmap 内存安全边界。
 * Semantic 与 OCR 始终为 1；任何预设都不得突破阶段声明的模型安全并发。
 */
data class AnalysisSchedulingProfile(
    val requestedMode: AnalysisSchedulingMode,
    val effectiveMode: AnalysisSchedulingMode,
    val workerLeaseGroups: Int,
    val allowFaceQualityParallel: Boolean,
    val faceConcurrency: Int,
    val sceneConcurrency: Int,
    val thumbnailConcurrency: Int,
    val semanticConcurrency: Int = 1,
    val qualityConcurrency: Int,
    val ocrConcurrency: Int = 1,
    val protectionReason: String? = null,
) {
    fun requestedConcurrency(stageId: String): Int = when (stageId) {
        "core:face" -> faceConcurrency
        "core:scene" -> sceneConcurrency
        "core:semantic" -> semanticConcurrency
        "core:quality" -> qualityConcurrency
        "core:ocr" -> ocrConcurrency
        else -> 1
    }
}

/** 纯策略解析器，集中维护推荐阈值和不可绕过的安全边界。 */
object AnalysisSchedulingResolver {
    internal const val MIN_THUMBNAIL_CONCURRENCY = 1
    internal const val MAX_THUMBNAIL_CONCURRENCY = 4

    internal fun clampThumbnailConcurrency(value: Int): Int =
        value.coerceIn(MIN_THUMBNAIL_CONCURRENCY, MAX_THUMBNAIL_CONCURRENCY)

    fun recommend(capabilities: AnalysisDeviceCapabilities): AnalysisSchedulingMode = when {
        capabilities.lowMemory || capabilities.totalMemoryGb < 6.0 ->
            AnalysisSchedulingMode.STABILITY
        capabilities.totalMemoryGb >= 12.0 && capabilities.logicalCpuCores >= 8 ->
            AnalysisSchedulingMode.PERFORMANCE
        else -> AnalysisSchedulingMode.BALANCED
    }

    fun resolve(
        requestedMode: AnalysisSchedulingMode,
        capabilities: AnalysisDeviceCapabilities,
    ): AnalysisSchedulingProfile {
        val recommended = recommend(capabilities)
        val selected = if (requestedMode == AnalysisSchedulingMode.AUTO) recommended else requestedMode
        val protectedMode = when {
            capabilities.lowMemory -> AnalysisSchedulingMode.STABILITY
            capabilities.hasSeriousThermalPressure -> AnalysisSchedulingMode.STABILITY
            else -> selected
        }
        val protectionReason = when {
            capabilities.lowMemory -> "系统报告内存紧张，已临时切换为稳定优先"
            capabilities.hasSeriousThermalPressure -> "设备严重发热，已临时切换为稳定优先"
            else -> null
        }

        val base = when (protectedMode) {
            AnalysisSchedulingMode.AUTO -> error("AUTO 必须先解析为具体模式")
            AnalysisSchedulingMode.STABILITY -> AnalysisSchedulingProfile(
                requestedMode = requestedMode,
                effectiveMode = protectedMode,
                workerLeaseGroups = 1,
                allowFaceQualityParallel = false,
                faceConcurrency = 1,
                sceneConcurrency = 1,
                thumbnailConcurrency = clampThumbnailConcurrency(1),
                qualityConcurrency = 2,
            )
            AnalysisSchedulingMode.BALANCED -> AnalysisSchedulingProfile(
                requestedMode = requestedMode,
                effectiveMode = protectedMode,
                workerLeaseGroups = if (capabilities.totalMemoryGb >= 8.0) 4 else 2,
                allowFaceQualityParallel = true,
                faceConcurrency = 2,
                sceneConcurrency = 2,
                thumbnailConcurrency = clampThumbnailConcurrency(2),
                qualityConcurrency = 4,
            )
            AnalysisSchedulingMode.PERFORMANCE -> AnalysisSchedulingProfile(
                requestedMode = requestedMode,
                effectiveMode = protectedMode,
                workerLeaseGroups = 4,
                allowFaceQualityParallel = true,
                faceConcurrency = 2,
                sceneConcurrency = 2,
                thumbnailConcurrency = clampThumbnailConcurrency(4),
                qualityConcurrency = 4,
            )
        }
        return base.copy(protectionReason = protectionReason)
    }
}

/** 读取系统公开信息，不申请额外权限。 */
object AnalysisDeviceCapabilityDetector {
    fun detect(context: Context): AnalysisDeviceCapabilities {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        return AnalysisDeviceCapabilities(
            logicalCpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalMemoryBytes = memoryInfo.totalMem,
            availableMemoryBytes = memoryInfo.availMem,
            lowMemory = memoryInfo.lowMemory,
            thermalStatus = thermalStatus,
        )
    }
}

/**
 * 当前进程的调度快照。Worker 在每个分析窗口开始前刷新，Pipeline 和 Stage 仅读取快照。
 * 使用不可变对象的 volatile 替换，避免运行中重建 Dispatcher 或模型池。
 */
object AnalysisSchedulingRuntime {
    @Volatile
    var profile: AnalysisSchedulingProfile = AnalysisSchedulingResolver.resolve(
        AnalysisSchedulingMode.AUTO,
        AnalysisDeviceCapabilities(
            logicalCpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalMemoryBytes = 6L * 1024L * 1024L * 1024L,
            availableMemoryBytes = 2L * 1024L * 1024L * 1024L,
            lowMemory = false,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
        ),
    )
        private set

    fun update(profile: AnalysisSchedulingProfile) {
        this.profile = profile
    }

    fun effectiveStageConcurrency(stageId: String, stageMaximumSafeConcurrency: Int): Int =
        minOf(
            stageMaximumSafeConcurrency.coerceAtLeast(1),
            profile.requestedConcurrency(stageId).coerceAtLeast(1),
        )
}
