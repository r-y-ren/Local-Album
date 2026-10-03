package com.renyxin.localalbum.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FtsTextCodecTest {
    @Test
    fun `cjk run expands to chars then consecutive bigrams`() {
        assertEquals(
            "今 天 天 气 今天 天天 天气",
            FtsTextCodec.indexable("今天天气"),
        )
    }

    @Test
    fun `single cjk char stays single token`() {
        assertEquals("天", FtsTextCodec.indexable("天"))
    }

    @Test
    fun `latin segments pass through unchanged`() {
        assertEquals("Sunset 2024", FtsTextCodec.indexable("Sunset 2024"))
    }

    @Test
    fun `mixed text keeps both representations and never crosses scripts`() {
        val tokens = FtsTextCodec.indexable("日落sunset很好").split(' ')
        assertTrue(tokens.containsAll(listOf("日", "落", "很", "好", "日落", "很好", "sunset")))
        // 跨语种不应产生混合 token（unicode61 会把连续字母数字并成一个词元）
        assertFalse(tokens.any { it.contains("日落sunset") || it.contains("sunset很") })
    }

    @Test
    fun `null and blank become empty`() {
        assertEquals("", FtsTextCodec.indexable(null))
        assertEquals("", FtsTextCodec.indexable("  "))
    }

    @Test
    fun `punctuation splits cjk runs`() {
        // 标点分隔的两个串各自展开，不生成跨标点的二元组
        val tokens = FtsTextCodec.indexable("今天，天气").split(' ')
        assertTrue(tokens.contains("今天"))
        assertTrue(tokens.contains("天气"))
        assertFalse(tokens.contains("天天"))
    }

    @Test
    fun `query terms classify runs correctly`() {
        val single = FtsTextCodec.queryTerms("天")
        assertTrue(single.single() is FtsTextCodec.QueryTerm.CjkExact)

        val pair = FtsTextCodec.queryTerms("天气")
        assertTrue(pair.single() is FtsTextCodec.QueryTerm.CjkExact)
        assertEquals("天气", (pair.single() as FtsTextCodec.QueryTerm.CjkExact).token)

        val phrase = FtsTextCodec.queryTerms("天气很好")
        val p = phrase.single() as FtsTextCodec.QueryTerm.CjkPhrase
        assertEquals(listOf("天气", "气很", "很好"), p.bigrams)

        val mixed = FtsTextCodec.queryTerms("ABC天气")
        assertEquals(2, mixed.size)
        assertTrue(mixed[0] is FtsTextCodec.QueryTerm.Other)
        assertTrue(mixed[1] is FtsTextCodec.QueryTerm.CjkExact)
    }
}
