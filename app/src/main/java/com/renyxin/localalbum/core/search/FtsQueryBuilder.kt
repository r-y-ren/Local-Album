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

        return tokens
            .flatMap { token ->
                // FTS4 only recognizes the prefix marker on an unquoted token. The token extractor
                // removes query grammar, while lower-casing prevents AND/OR/NOT/NEAR from becoming
                // operators, so this remains safe for untrusted text and actually performs prefixes.
                profile.columns.map { column -> "$column:$token*" }
            }
            .joinToString(" OR ")
    }

    private val SEARCH_TOKEN = Regex("""[\p{L}\p{N}][\p{L}\p{N}\p{M}_]*""")
}
