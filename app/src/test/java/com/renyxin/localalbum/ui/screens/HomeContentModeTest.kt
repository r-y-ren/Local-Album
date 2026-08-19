package com.renyxin.localalbum.ui.screens

import com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
import com.renyxin.localalbum.data.db.entity.LibraryPipelineState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeContentModeTest {
    @Test
    fun `initial thumbnails without baseline shows building instead of empty`() {
        assertEquals(
            HomeContentMode.BUILDING,
            resolveHomeContentMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.INITIAL_THUMBNAILS,
                    hasBaseline = false,
                ),
                itemCount = 0,
                refresh = HomeRefreshState.NOT_LOADING,
            ),
        )
    }

    @Test
    fun `initial analysis with published items shows grid immediately`() {
        assertEquals(
            HomeContentMode.GRID,
            resolveHomeContentMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.INITIAL_ANALYSIS,
                    hasBaseline = true,
                ),
                itemCount = 3,
                refresh = HomeRefreshState.LOADING,
            ),
        )
    }

    @Test
    fun `refresh loading without items is not reported as empty`() {
        assertEquals(
            HomeContentMode.LOADING,
            resolveHomeContentMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.READY,
                    hasBaseline = true,
                ),
                itemCount = 0,
                refresh = HomeRefreshState.LOADING,
            ),
        )
    }

    @Test
    fun `refresh error without items is reported`() {
        assertEquals(
            HomeContentMode.ERROR,
            resolveHomeContentMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.READY,
                    hasBaseline = true,
                ),
                itemCount = 0,
                refresh = HomeRefreshState.ERROR,
            ),
        )
    }

    @Test
    fun `incremental stages preserve published grid`() {
        listOf(
            LibraryPipelineStage.INCREMENTAL_SCAN,
            LibraryPipelineStage.INCREMENTAL_THUMBNAILS,
            LibraryPipelineStage.INCREMENTAL_PUBLISH,
            LibraryPipelineStage.INCREMENTAL_ANALYSIS,
        ).forEach { stage ->
            assertEquals(
                stage.name,
                HomeContentMode.GRID,
                resolveHomeContentMode(
                    pipelineState = pipeline(stage = stage, hasBaseline = true),
                    itemCount = 5,
                    refresh = HomeRefreshState.LOADING,
                ),
            )
        }
    }

    @Test
    fun `empty is shown only after baseline publication and settled refresh`() {
        assertEquals(
            HomeContentMode.EMPTY,
            resolveHomeContentMode(
                pipelineState = pipeline(
                    stage = LibraryPipelineStage.READY,
                    hasBaseline = true,
                ),
                itemCount = 0,
                refresh = HomeRefreshState.NOT_LOADING,
            ),
        )
    }

    private fun pipeline(
        stage: LibraryPipelineStage,
        hasBaseline: Boolean,
    ) = LibraryPipelineState(
        stage = stage,
        hasPublishedBaseline = hasBaseline,
        publishedGeneration = if (hasBaseline) 7L else 0L,
    )
}
