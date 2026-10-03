package com.renyxin.localalbum.core.search

/**
 * FTS4 unicode61 分词器把连续汉字当成一整个不可拆的词元，导致中文 OCR 文本只有
 * "整句前缀"能命中，句中任意词都搜不到。本编解码器在索引与查询两端同时改造：
 *
 * - 索引端：CJK 连续串展开为「逐字 + 相邻二元词组」的空格分隔序列
 *   （"今天天气" → "今 天 天 气 今天 天天 天气"）；非 CJK 片段原样保留。
 * - 查询端：CJK 串长度 1/2 时精确匹配字/二元词组，长度 ≥3 时构造相邻二元词组的
 *   FTS 短语查询（`"天气 气很 很好"`），等价于原文的精确子串匹配。
 *
 * 原始文本永远保存在 media_items 主表；FTS 表内容是可再生的派生数据，
 * 升级后可通过"重建搜索索引"全量回填新格式。
 */
object FtsTextCodec {

    private val CJK_CHAR = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u3040-\\u30FF\\u31F0-\\u31FF\\uFF66-\\uFF9D\\uAC00-\\uD7AF]")

    /** 索引端转换：任意文本 → unicode61 可按字/二元词组命中的词元序列。 */
    fun indexable(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val out = StringBuilder()
        var runStart = -1

        fun flushRun(end: Int) {
            if (runStart < 0) return
            val run = raw.substring(runStart, end)
            if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
            // 先逐字再二元词组，全部以空格收尾：单字查询走字组，多字短语走词组区的相邻序列
            if (run.length == 1) {
                out.append(run).append(' ')
            } else {
                for (ch in run) out.append(ch).append(' ')
                run.windowed(2).forEach { out.append(it).append(' ') }
            }
            runStart = -1
        }

        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (CJK_CHAR.matches(ch.toString())) {
                if (runStart < 0) runStart = i
            } else {
                flushRun(i)
                out.append(ch)
            }
            i++
        }
        flushRun(raw.length)
        return out.toString().trim()
    }

    /** 查询端：单个输入 token 拆出的可匹配单元（CJK 精确 token / CJK 短语 / 拉丁前缀）。 */
    sealed interface QueryTerm {
        /** CJK 串：1 字为单字 token，2 字为二元词组 token，≥3 字为短语（二元词组序列）。 */
        data class CjkExact(val token: String) : QueryTerm
        data class CjkPhrase(val bigrams: List<String>) : QueryTerm
        /** 非 CJK 片段：保持原有前缀语义，由调用方补 `col:token*`。 */
        data class Other(val token: String) : QueryTerm
    }

    fun queryTerms(token: String): List<QueryTerm> {
        if (token.isEmpty()) return emptyList()
        val terms = mutableListOf<QueryTerm>()
        var runStart = -1
        fun flushRun(end: Int) {
            if (runStart < 0) return
            val run = token.substring(runStart, end)
            when {
                run.length == 1 -> terms += QueryTerm.CjkExact(run)
                run.length == 2 -> terms += QueryTerm.CjkExact(run)
                else -> terms += QueryTerm.CjkPhrase(run.windowed(2))
            }
            runStart = -1
        }
        val sb = StringBuilder()
        var i = 0
        while (i < token.length) {
            val ch = token[i]
            if (CJK_CHAR.matches(ch.toString())) {
                if (sb.isNotEmpty()) {
                    terms += QueryTerm.Other(sb.toString())
                    sb.clear()
                }
                if (runStart < 0) runStart = i
            } else {
                flushRun(i)
                sb.append(ch)
            }
            i++
        }
        flushRun(token.length)
        if (sb.isNotEmpty()) terms += QueryTerm.Other(sb.toString())
        return terms
    }
}
