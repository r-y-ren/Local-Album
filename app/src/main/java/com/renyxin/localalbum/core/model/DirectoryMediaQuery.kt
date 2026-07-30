package com.renyxin.localalbum.core.model

/**
 * 目录媒体分页查询的完整身份。
 *
 * 路径、媒体类型与排序全部参与 [equals]，因此任一条件变化都会创建新的 Paging flow。
 * 所有排序都以 filePath 作为最终稳定决胜键，保证分页与查看器前后导航不重不漏。
 */
data class DirectoryMediaQuery(
    val directoryPath: String,
    val mediaType: MediaType? = null,
    val sort: DirectoryMediaSort = DirectoryMediaSort.DATE_DESC,
) {
    init {
        require(directoryPath.isNotBlank()) { "directoryPath must not be blank" }
    }
}

enum class DirectoryMediaSort {
    NAME_ASC,
    NAME_DESC,
    DATE_DESC,
    DATE_ASC,
    MODIFIED_DESC,
    MODIFIED_ASC,
    SIZE_DESC,
    SIZE_ASC,
}
