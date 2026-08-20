package com.renyxin.localalbum.core.thumbnail

/** Immutable facts used to admit one durable home Grid-thumbnail repair. */
data class ThumbnailRepairAdmissionFacts(
    val hasPublishedBaseline: Boolean,
    val visibleGridMediaCount: Int,
    val eligibleGapCount: Int,
    val idleStage: Boolean,
    val hasActiveRun: Boolean,
    val rebuildRequested: Boolean,
)

/** Pure admission and identity rules shared by the coordinator barrier and JVM tests. */
object ThumbnailRepairPolicy {
    private const val REPAIR_PROTOCOL_VERSION = 1

    fun shouldAdmit(facts: ThumbnailRepairAdmissionFacts): Boolean =
        facts.hasPublishedBaseline &&
            facts.visibleGridMediaCount > 0 &&
            facts.eligibleGapCount > 0 &&
            facts.idleStage &&
            !facts.hasActiveRun &&
            !facts.rebuildRequested

    /** One reusable durable repair identity per published baseline and thumbnail format. */
    fun repairRunId(publishedGeneration: Long, formatVersion: Int): String {
        require(publishedGeneration > 0L) { "publishedGeneration must be positive" }
        require(formatVersion > 0) { "formatVersion must be positive" }
        return "thumbnail-repair:v$REPAIR_PROTOCOL_VERSION:g=$publishedGeneration:f=$formatVersion"
    }
}
