package com.renyxin.localalbum.core.recommendation

/**
 * 推荐多样性控制器（Phase 5 推荐增强）。
 *
 * 替换 Repository 层的 [shuffled] 随机打乱，采用贪心选择算法保证推荐列表的
 * 视觉与内容丰富度：
 * - 按评分降序遍历候选推荐
 * - 同类别连续不超过 [maxConsecutivePerCategory] 条
 * - 同相册来源连续不超过 [maxConsecutivePerAlbum] 条
 * - 优先选择评分高的推荐，但受多样性约束限制
 *
 * @param maxConsecutivePerCategory 同类别连续最大条数，默认 2
 * @param maxConsecutivePerAlbum 同相册来源连续最大条数，默认 2
 */
class RecommendationDiversifier(
    private val maxConsecutivePerCategory: Int = 2,
    private val maxConsecutivePerAlbum: Int = 2,
) {
    /**
     * 对候选推荐列表执行多样性有序选择。
     *
     * @param candidates 候选推荐列表（将按评分降序处理）
     * @param batchSize 本次需要的推荐数量
     * @return 多样性选择后的推荐列表，长度 ≤ batchSize
     */
    fun select(candidates: List<Recommendation>, batchSize: Int): List<Recommendation> {
        if (candidates.isEmpty() || batchSize <= 0) return emptyList()

        // 按评分降序排列
        val sorted = candidates.sortedByDescending { it.score }
        val selected = mutableListOf<Recommendation>()
        val remaining = sorted.toMutableList()

        // 记录最近选择的类别和相册连续计数
        var lastCategory: RecommendationCategory? = null
        var categoryStreak = 0
        var lastAlbumId: String? = null
        var albumStreak = 0

        while (selected.size < batchSize && remaining.isNotEmpty()) {
            var picked: Recommendation? = null
            val iter = remaining.iterator()

            while (iter.hasNext()) {
                val candidate = iter.next()
                val canPickCategory = candidate.category != lastCategory || categoryStreak < maxConsecutivePerCategory
                val canPickAlbum = candidate.albumId != lastAlbumId || albumStreak < maxConsecutivePerAlbum

                if (canPickCategory && canPickAlbum) {
                    picked = candidate
                    iter.remove()
                    break
                }
            }

            if (picked != null) {
                // 更新连续计数
                if (picked.category == lastCategory) {
                    categoryStreak++
                } else {
                    lastCategory = picked.category
                    categoryStreak = 1
                }
                if (picked.albumId == lastAlbumId) {
                    albumStreak++
                } else {
                    lastAlbumId = picked.albumId
                    albumStreak = 1
                }
                selected.add(picked)
            } else {
                // 所有剩余候选都违反多样性约束，放宽限制：取评分最高的
                if (remaining.isNotEmpty()) {
                    val fallback = remaining.first()
                    remaining.remove(fallback)
                    lastCategory = fallback.category
                    categoryStreak = 1
                    lastAlbumId = fallback.albumId
                    albumStreak = 1
                    selected.add(fallback)
                } else {
                    break
                }
            }
        }

        return selected
    }

    /**
     * 对候选推荐列表执行多样性有序选择，返回全量有序结果（不截断）。
     * 用于生成全量推荐池，供 Repository 批次分发。
     */
    fun selectAll(candidates: List<Recommendation>): List<Recommendation> {
        return select(candidates, candidates.size)
    }
}
