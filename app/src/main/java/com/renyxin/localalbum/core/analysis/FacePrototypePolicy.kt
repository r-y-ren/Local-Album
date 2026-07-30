package com.renyxin.localalbum.core.analysis

import kotlin.math.sqrt

/**
 * 纯 JVM 的有界人物多原型策略。
 *
 * - 每簇最多 [MAX_PROTOTYPES_PER_CLUSTER] 个原型；
 * - 命中阈值内吸收到最近原型并更新在线均值；
 * - 未命中且尚有槽位才新增；槽位已满则只在宽松更新阈值内吸收，否则拒绝污染簇。
 */
object FacePrototypePolicy {
    const val MAX_PROTOTYPES_PER_CLUSTER = 3
    const val MATCH_EPS = 0.30f
    const val UPDATE_EPS = 0.38f

    data class Prototype(
        val index: Int,
        val embedding: FloatArray,
        val sampleCount: Long,
    )

    data class Update(
        val prototypes: List<Prototype>,
        val updatedIndex: Int?,
        val accepted: Boolean,
    )

    fun nearest(sample: FloatArray, prototypes: List<Prototype>): Pair<Prototype, Float>? =
        prototypes.asSequence()
            .filter { it.embedding.size == sample.size }
            .map { it to cosineDistance(sample, it.embedding) }
            .minByOrNull { it.second }

    fun update(sample: FloatArray, current: List<Prototype>): Update {
        require(current.size <= MAX_PROTOTYPES_PER_CLUSTER)
        val nearest = nearest(sample, current)
        if (nearest == null) {
            val created = Prototype(0, normalize(sample), 1)
            return Update(listOf(created), created.index, true)
        }
        val (prototype, distance) = nearest
        if (distance <= MATCH_EPS || (current.size >= MAX_PROTOTYPES_PER_CLUSTER && distance <= UPDATE_EPS)) {
            val replacement = prototype.copy(
                embedding = weightedMean(prototype.embedding, prototype.sampleCount, sample),
                sampleCount = prototype.sampleCount + 1,
            )
            return Update(current.map { if (it.index == prototype.index) replacement else it }, prototype.index, true)
        }
        if (current.size < MAX_PROTOTYPES_PER_CLUSTER) {
            val nextIndex = (current.maxOfOrNull { it.index } ?: -1) + 1
            return Update(current + Prototype(nextIndex, normalize(sample), 1), nextIndex, true)
        }
        return Update(current, null, false)
    }

    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 2f
        var dot = 0f
        var aa = 0f
        var bb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            aa += a[i] * a[i]
            bb += b[i] * b[i]
        }
        val denominator = sqrt(aa) * sqrt(bb)
        return if (denominator <= 1e-6f) 2f else (1f - dot / denominator).coerceIn(0f, 2f)
    }

    private fun weightedMean(existing: FloatArray, count: Long, sample: FloatArray): FloatArray {
        val safeCount = count.coerceAtLeast(1).coerceAtMost(MAX_EFFECTIVE_SAMPLE_COUNT)
        val result = FloatArray(existing.size) { index ->
            ((existing[index] * safeCount) + sample[index]) / (safeCount + 1)
        }
        return normalize(result)
    }

    private fun normalize(input: FloatArray): FloatArray {
        val norm = sqrt(input.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm <= 1e-6f) input.copyOf() else FloatArray(input.size) { input[it] / norm }
    }

    private const val MAX_EFFECTIVE_SAMPLE_COUNT = 10_000L
}
