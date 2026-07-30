package com.renyxin.localalbum.core.model

/** 纯 Kotlin SQL 规格，便于 JVM 测试查询身份、绑定参数及稳定排序。 */
data class DirectorySqlSpec(
    val sql: String,
    val args: List<Any>,
)

object DirectoryMediaQueryMapper {
    fun paging(query: DirectoryMediaQuery): DirectorySqlSpec {
        val (where, args) = where(query)
        return DirectorySqlSpec(
            sql = "SELECT * FROM media_items WHERE $where ORDER BY ${orderBy(query.sort)}",
            args = args,
        )
    }

    fun offset(query: DirectoryMediaQuery, item: DirectoryMediaAnchor): DirectorySqlSpec {
        val (where, args) = where(query)
        val before = beforeAnchor(query.sort)
        return DirectorySqlSpec(
            sql = "SELECT COUNT(*) FROM media_items WHERE $where AND ($before)",
            args = args + anchorArgs(query.sort, item),
        )
    }

    private fun where(query: DirectoryMediaQuery): Pair<String, List<Any>> {
        val clauses = mutableListOf("isTrashed = 0", "parentPath = ?")
        val args = mutableListOf<Any>(query.directoryPath)
        query.mediaType?.let {
            clauses += "mediaType = ?"
            args += it.name
        }
        return clauses.joinToString(" AND ") to args
    }

    private fun orderBy(sort: DirectoryMediaSort): String = when (sort) {
        DirectoryMediaSort.NAME_ASC -> "fileName COLLATE NOCASE ASC, filePath ASC"
        DirectoryMediaSort.NAME_DESC -> "fileName COLLATE NOCASE DESC, filePath DESC"
        DirectoryMediaSort.DATE_DESC -> "capturedAtMs DESC, filePath ASC"
        DirectoryMediaSort.DATE_ASC -> "capturedAtMs ASC, filePath ASC"
        DirectoryMediaSort.MODIFIED_DESC -> "modifiedAtMs DESC, filePath ASC"
        DirectoryMediaSort.MODIFIED_ASC -> "modifiedAtMs ASC, filePath ASC"
        DirectoryMediaSort.SIZE_DESC -> "fileSize DESC, filePath ASC"
        DirectoryMediaSort.SIZE_ASC -> "fileSize ASC, filePath ASC"
    }

    private fun beforeAnchor(sort: DirectoryMediaSort): String = when (sort) {
        DirectoryMediaSort.NAME_ASC ->
            "fileName COLLATE NOCASE < ? COLLATE NOCASE OR (fileName COLLATE NOCASE = ? COLLATE NOCASE AND filePath < ?)"
        DirectoryMediaSort.NAME_DESC ->
            "fileName COLLATE NOCASE > ? COLLATE NOCASE OR (fileName COLLATE NOCASE = ? COLLATE NOCASE AND filePath > ?)"
        DirectoryMediaSort.DATE_DESC -> "capturedAtMs > ? OR (capturedAtMs = ? AND filePath < ?)"
        DirectoryMediaSort.DATE_ASC -> "capturedAtMs < ? OR (capturedAtMs = ? AND filePath < ?)"
        DirectoryMediaSort.MODIFIED_DESC -> "modifiedAtMs > ? OR (modifiedAtMs = ? AND filePath < ?)"
        DirectoryMediaSort.MODIFIED_ASC -> "modifiedAtMs < ? OR (modifiedAtMs = ? AND filePath < ?)"
        DirectoryMediaSort.SIZE_DESC -> "fileSize > ? OR (fileSize = ? AND filePath < ?)"
        DirectoryMediaSort.SIZE_ASC -> "fileSize < ? OR (fileSize = ? AND filePath < ?)"
    }

    private fun anchorArgs(sort: DirectoryMediaSort, item: DirectoryMediaAnchor): List<Any> = when (sort) {
        DirectoryMediaSort.NAME_ASC, DirectoryMediaSort.NAME_DESC ->
            listOf(item.fileName, item.fileName, item.filePath)
        DirectoryMediaSort.DATE_DESC, DirectoryMediaSort.DATE_ASC ->
            listOf(item.capturedAtMs, item.capturedAtMs, item.filePath)
        DirectoryMediaSort.MODIFIED_DESC, DirectoryMediaSort.MODIFIED_ASC ->
            listOf(item.modifiedAtMs, item.modifiedAtMs, item.filePath)
        DirectoryMediaSort.SIZE_DESC, DirectoryMediaSort.SIZE_ASC ->
            listOf(item.fileSize, item.fileSize, item.filePath)
    }
}

data class DirectoryMediaAnchor(
    val filePath: String,
    val fileName: String,
    val capturedAtMs: Long,
    val modifiedAtMs: Long,
    val fileSize: Long,
)
