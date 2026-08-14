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
}
