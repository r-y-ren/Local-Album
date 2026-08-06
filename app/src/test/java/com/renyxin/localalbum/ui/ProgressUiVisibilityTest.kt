package com.renyxin.localalbum.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressUiVisibilityTest {
    @Test
    fun `global progress is visible when enabled and manager exists`() {
        assertTrue(
            shouldShowGlobalProgressIndicator(
                showAnalysisProgressUi = true,
                hasProgressManager = true,
            ),
        )
    }

    @Test
    fun `disabled preference hides global progress`() {
        assertFalse(
            shouldShowGlobalProgressIndicator(
                showAnalysisProgressUi = false,
                hasProgressManager = true,
            ),
        )
    }

    @Test
    fun `missing progress manager never renders global progress`() {
        assertFalse(
            shouldShowGlobalProgressIndicator(
                showAnalysisProgressUi = true,
                hasProgressManager = false,
            ),
        )
    }
}
