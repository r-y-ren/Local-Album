package com.renyxin.localalbum.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacesProgressVisibilityTest {
    @Test
    fun `progress grid is visible only when enabled running and non-empty`() {
        assertTrue(
            shouldShowAnalysisProgressGrid(
                showAnalysisProgressUi = true,
                isPipelineRunning = true,
                hasFileProgress = true,
            ),
        )
    }

    @Test
    fun `disabled preference hides progress grid without changing pipeline state`() {
        assertFalse(
            shouldShowAnalysisProgressGrid(
                showAnalysisProgressUi = false,
                isPipelineRunning = true,
                hasFileProgress = true,
            ),
        )
    }

    @Test
    fun `idle or empty progress never shows grid`() {
        assertFalse(
            shouldShowAnalysisProgressGrid(
                showAnalysisProgressUi = true,
                isPipelineRunning = false,
                hasFileProgress = true,
            ),
        )
        assertFalse(
            shouldShowAnalysisProgressGrid(
                showAnalysisProgressUi = true,
                isPipelineRunning = true,
                hasFileProgress = false,
            ),
        )
    }
}
