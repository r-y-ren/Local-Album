package com.renyxin.localalbum.ui.screens

internal fun shouldShowAnalysisProgressGrid(
    showAnalysisProgressUi: Boolean,
    isPipelineRunning: Boolean,
    hasFileProgress: Boolean,
): Boolean = showAnalysisProgressUi && isPipelineRunning && hasFileProgress
