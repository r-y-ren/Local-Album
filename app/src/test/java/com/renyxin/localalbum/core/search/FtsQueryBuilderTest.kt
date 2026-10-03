package com.renyxin.localalbum.core.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsQueryBuilderTest {
    @Test
    fun `lite query includes basic and directory columns but never imported AI columns`() {
        val query = FtsQueryBuilder.build("Camera Trips", KeywordSearchProfile.LITE)

        listOf("fileName", "parentPath", "make", "model").forEach { column ->
            assertTrue("Lite query must include $column", "$column:camera*" in query)
            assertTrue("Lite query must include $column for every token", "$column:trips*" in query)
        }
        assertFalse("Lite query must not match imported OCR", "ocrText:" in query)
        assertFalse("Lite query must not match scene data", "sceneType:" in query)
        assertFalse("Lite query must not match semantic data", "embedding" in query)
    }

    @Test
    fun `full query retains OCR while sharing parent path search`() {
        val query = FtsQueryBuilder.build("sunset", KeywordSearchProfile.FULL)

        assertTrue("parentPath:sunset*" in query)
        assertTrue("ocrText:sunset*" in query)
    }

    @Test
    fun `untrusted syntax is reduced to safe qualified prefix tokens`() {
        val query = FtsQueryBuilder.build("ocrText:\"secret\" OR *", KeywordSearchProfile.LITE)

        assertFalse("Lite profile cannot be escaped into the OCR column", "ocrText:secret" in query)
        assertFalse("user query grammar must be removed", '"' in query)
        assertTrue(query.split(" OR ").all { clause ->
            clause.startsWith("fileName:") || clause.startsWith("parentPath:") ||
                clause.startsWith("make:") || clause.startsWith("model:")
        })
        assertTrue("operator-shaped input must remain a lowercase token", "fileName:or*" in query)
    }

    @Test
    fun `single cjk char queries exact token without prefix star`() {
        val query = FtsQueryBuilder.build("天", KeywordSearchProfile.FULL)
        // 单字走精确 token（带引号转义），绝不能带 * 前缀——否则二元词组会大量误命中
        assertTrue("\"天\"" in query)
        assertFalse("*" in query)
    }

    @Test
    fun `two cjk char queries exact bigram token`() {
        val query = FtsQueryBuilder.build("天气", KeywordSearchProfile.LITE)
        assertTrue("\"天气\"" in query)
        assertFalse("*" in query)
    }

    @Test
    fun `long cjk phrase becomes consecutive bigram phrase query`() {
        val query = FtsQueryBuilder.build("天气很好", KeywordSearchProfile.FULL)
        // ≥3 字 → 相邻二元词组短语（等价精确子串匹配）；FTS4 不支持列过滤短语，不带列名
        assertTrue("\"天气 气很 很好\"" in query)
        assertFalse("ocrText:" in query.split("\"天气 气很 很好\"")[0])
    }

    @Test
    fun `mixed latin and cjk tokens keep their respective strategies`() {
        val query = FtsQueryBuilder.build("sunset 天气", KeywordSearchProfile.FULL)
        assertTrue("ocrText:sunset*" in query)
        assertTrue("\"天气\"" in query)
    }
}
