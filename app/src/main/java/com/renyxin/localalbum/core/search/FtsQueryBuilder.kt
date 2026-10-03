package com.renyxin.localalbum.core.search

import java.util.Locale

/**
 * Keyword-search columns are an edition capability, not a property of imported data.
 *
 * Both editions share one FTS table so Full AI rows can survive a Full -> Lite -> Full backup
 * round-trip. Lite deliberately omits OCR from MATCH expressions even when an imported row still
 * contains ocrText.
 */
enum class KeywordSearchProfile(internal val columns: List<String>) {
    FULL(listOf("fileName", "parentPath", "make", "model", "ocrText")),
    LITE(listOf("fileName", "parentPath", "make", "model")),
}

/** Builds a column-qualified FTS4 query from untrusted user text. */
internal object FtsQueryBuilder {
    fun build(input: String, profile: KeywordSearchProfile): String {
        val tokens = SEARCH_TOKEN.findAll(input)
            .map { match -> match.value.lowercase(Locale.ROOT) }
            .toList()

        if (tokens.isEmpty()) return "fileName:\"\""

        return tokens.flatMap { token -> matchExpressions(token, profile) }
            .joinToString(" OR ")
            .ifEmpty { "fileName:\"\"" }
    }

    /**
     * CJK 片段（unicode61 分词不可拆）走 [FtsTextCodec] 的字/二元词组方案：
     * 短语不带列过滤——FTS4 不支持 `col:"..."` 组合，且中文本就应跨列命中
     * （搜"真人"同时找文件夹名与 OCR 文本）。拉丁片段保持列限定前缀匹配。
     */
    private fun matchExpressions(token: String, profile: KeywordSearchProfile): List<String> =
        FtsTextCodec.queryTerms(token).flatMap { term ->
            when (term) {
                is FtsTextCodec.QueryTerm.CjkExact -> listOf("\"${term.token}\"")
                is FtsTextCodec.QueryTerm.CjkPhrase ->
                    listOf("\"${term.bigrams.joinToString(" ")}\"")
                is FtsTextCodec.QueryTerm.Other ->
                    profile.columns.map { column -> "$column:${term.token}*" }
            }
        }

    private val SEARCH_TOKEN = Regex("""[\p{L}\p{N}][\p{L}\p{N}\p{M}_]*""")
}
