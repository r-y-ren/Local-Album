package com.renyxin.localalbum.core.model

import com.renyxin.localalbum.data.repo.MediaSearchQuery

/**
 * 可稳定重建查看器内容的查询上下文。
 *
 * 导航状态只保存初始路径和此上下文；数据库实体由 Repository 在进入查看器后重新查询。
 * [StablePaths] 仅用于推荐等天然有界的数据源，并限制路径数量，避免误把大图库复制进导航栈。
 */
sealed interface MediaQueryContext {
    data object Single : MediaQueryContext
    data object Timeline : MediaQueryContext
    data class Directory(val query: DirectoryMediaQuery) : MediaQueryContext {
        constructor(path: String) : this(DirectoryMediaQuery(path))
        val path: String get() = query.directoryPath
    }
    data class Search(val query: MediaSearchQuery) : MediaQueryContext
    data class Face(val clusterId: String) : MediaQueryContext
    data object Favorites : MediaQueryContext

    data class StablePaths private constructor(val paths: List<String>) : MediaQueryContext {
        companion object {
            const val MAX_PATHS = 500

            fun of(paths: List<String>): StablePaths = StablePaths(
                paths.asSequence().filter(String::isNotBlank).distinct().take(MAX_PATHS).toList()
            )
        }
    }
}
