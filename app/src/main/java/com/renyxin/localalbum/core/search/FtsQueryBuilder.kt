package com.renyxin.localalbum.core.search

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
        val tokens = input
            .split(Regex("\\s+"))
            .map(::sanitizeToken)
            .filter(String::isNotBlank)

        if (tokens.isEmpty()) return "fileName:\"\"*"

        return tokens
            .flatMap { token ->
                profile.columns.map { column -> "$column:\"$token\"*" }
            }
            .joinToString(" OR ")
    }

    private fun sanitizeToken(token: String): String = token
        .replace("\u0000", "")
        .replace("\"", "")
}
