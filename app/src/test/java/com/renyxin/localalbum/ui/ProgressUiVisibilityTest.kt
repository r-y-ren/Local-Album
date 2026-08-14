package com.renyxin.localalbum.ui

import com.renyxin.localalbum.ui.components.resolveProgressOverlayMode
import org.junit.Assert.assertEquals
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

    // ---- resolveProgressOverlayMode 三态判定 ----

    @Test
    fun `analysis running takes priority over scan and waiting`() {
        assertEquals(
            com.renyxin.localalbum.ui.components.ProgressOverlayMode.ANALYSIS,
            resolveProgressOverlayMode(
                analysisRunning = true,
                scanActive = true,
                analysisPending = true,
            ),
        )
    }

    @Test
    fun `scan active shows scan card when pipeline idle`() {
        assertEquals(
            com.renyxin.localalbum.ui.components.ProgressOverlayMode.SCAN,
            resolveProgressOverlayMode(
                analysisRunning = false,
                scanActive = true,
                analysisPending = true,
            ),
        )
    }

    @Test
    fun `pending enhancement keeps overlay visible between batches`() {
        assertEquals(
            com.renyxin.localalbum.ui.components.ProgressOverlayMode.WAITING,
            resolveProgressOverlayMode(
                analysisRunning = false,
                scanActive = false,
                analysisPending = true,
            ),
        )
    }

    @Test
    fun `nothing active hides overlay`() {
        assertEquals(
            com.renyxin.localalbum.ui.components.ProgressOverlayMode.HIDDEN,
            resolveProgressOverlayMode(
                analysisRunning = false,
                scanActive = false,
                analysisPending = false,
            ),
        )
    }
}
