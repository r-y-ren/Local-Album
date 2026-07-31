package com.renyxin.localalbum.core.analysis

/** 用户可理解的人物归并严格程度。 */
enum class FaceGroupingStrictness(val persistedValue: Int) {
    STRICT(0), STANDARD(1), RELAXED(2);

    val clusterEps: Float
        get() = when (this) {
            STRICT -> 0.34f
            STANDARD -> FaceClusterer.DEFAULT_EPS
            RELAXED -> 0.46f
        }

    val matchEps: Float
        get() = when (this) {
            STRICT -> 0.26f
            STANDARD -> FacePrototypePolicy.MATCH_EPS
            RELAXED -> 0.34f
        }

    companion object {
        fun fromPersistedValue(value: Int) = entries.firstOrNull { it.persistedValue == value } ?: STANDARD
    }
}

enum class OcrAnalysisScope(val persistedValue: Int) {
    ALL_MEDIA(0), DOCUMENTS_AND_SCREENSHOTS(1), DISABLED(2);

    companion object {
        fun fromPersistedValue(value: Int) = entries.firstOrNull { it.persistedValue == value } ?: ALL_MEDIA
    }
}

enum class SemanticSearchStrictness(val persistedValue: Int) {
    MORE_RESULTS(0), STANDARD(1), PRECISE(2);

    val minSimilarity: Float
        get() = when (this) {
            MORE_RESULTS -> 0.05f
            STANDARD -> 0.10f
            PRECISE -> 0.20f
        }

    companion object {
        fun fromPersistedValue(value: Int) = entries.firstOrNull { it.persistedValue == value } ?: STANDARD
    }
}

enum class RecommendationPreference(val persistedValue: Int) {
    BALANCED(0), QUALITY(1), FAVORITES(2), MEMORIES(3), RECENT(4);

    companion object {
        fun fromPersistedValue(value: Int) = entries.firstOrNull { it.persistedValue == value } ?: BALANCED
    }
}

data class AiAnalysisPreferences(
    val faceGroupingStrictness: FaceGroupingStrictness = FaceGroupingStrictness.STANDARD,
    val faceMinimumGroupSize: Int = FaceClusterer.DEFAULT_MIN_PTS,
    val ocrAnalysisScope: OcrAnalysisScope = OcrAnalysisScope.ALL_MEDIA,
    val semanticSearchStrictness: SemanticSearchStrictness = SemanticSearchStrictness.STANDARD,
    val semanticSearchResultCount: Int = 50,
    val recommendationPreference: RecommendationPreference = RecommendationPreference.BALANCED,
) {
    fun normalized() = copy(
        faceMinimumGroupSize = faceMinimumGroupSize.coerceIn(1, 5),
        semanticSearchResultCount = semanticSearchResultCount.coerceIn(20, 100),
    )
}

/** 原子替换不可变快照；各阶段在新批次或新查询开始时读取。 */
object AiAnalysisPreferencesRuntime {
    @Volatile
    var current: AiAnalysisPreferences = AiAnalysisPreferences()
        private set

    fun update(preferences: AiAnalysisPreferences) {
        current = preferences.normalized()
    }
}
