package com.renyxin.localalbum.ui.screens.settings

/**
 * 设置页 Section 3：扫描操作卡片（自 LocalAlbumApp.kt SettingsTab 纯 cut-paste 提取，行为不变）。
 *
 * 内容：开始扫描按钮与核心扫描/后台增强状态文案。
 */
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.repo.ScanState
import com.renyxin.localalbum.ui.coreScanStatusText
import com.renyxin.localalbum.ui.enhancementStatusText
import com.renyxin.localalbum.ui.vm.AlbumViewModel

@Composable
internal fun ScanControlSection(
    coreScanActive: Boolean,
    hasScanRoots: Boolean,
    scanState: ScanState,
    indexAvailability: IndexAvailability,
    coreScanState: CoreScanState,
    enhancementState: EnhancementState,
    albumViewModel: AlbumViewModel,
) {
    val showRebuildConfirmation = remember { mutableStateOf(false) }
    val failedThumbnailCount by albumViewModel.failedThumbnailCount.collectAsStateWithLifecycle()
    val failedAnalysisCount by albumViewModel.failedAnalysisTaskCount.collectAsStateWithLifecycle()
    val failedOutboxCount by albumViewModel.failedEnhancementOutboxCount.collectAsStateWithLifecycle()
    val retryAllowed by albumViewModel.userTaskRetryAllowed.collectAsStateWithLifecycle()

    val thumbnailRetryState by albumViewModel.thumbnailRetryState.collectAsStateWithLifecycle()
    val analysisRetryState by albumViewModel.analysisRetryState.collectAsStateWithLifecycle()
    val outboxRetryState by albumViewModel.enhancementOutboxRetryState.collectAsStateWithLifecycle()

    val thumbnailRetryRunning = thumbnailRetryState is AlbumViewModel.FailedTaskRetryState.Running
    val analysisRetryRunning = analysisRetryState is AlbumViewModel.FailedTaskRetryState.Running
    val outboxRetryRunning = outboxRetryState is AlbumViewModel.EnhancementOutboxRetryState.Running
    val anyRetryRunning = thumbnailRetryRunning || analysisRetryRunning || outboxRetryRunning
    val totalFailedCount = failedThumbnailCount + failedAnalysisCount + failedOutboxCount

    val thumbnailRetryResult =
        (thumbnailRetryState as? AlbumViewModel.FailedTaskRetryState.Completed)?.result
    val thumbnailRetryFailure =
        (thumbnailRetryState as? AlbumViewModel.FailedTaskRetryState.Failed)?.message
    val analysisRetryResult =
        (analysisRetryState as? AlbumViewModel.FailedTaskRetryState.Completed)?.result
    val analysisRetryFailure =
        (analysisRetryState as? AlbumViewModel.FailedTaskRetryState.Failed)?.message
    val outboxRetryResult =
        (outboxRetryState as? AlbumViewModel.EnhancementOutboxRetryState.Completed)?.result
    val outboxRetryFailure =
        (outboxRetryState as? AlbumViewModel.EnhancementOutboxRetryState.Failed)?.message

    if (showRebuildConfirmation.value) {
        AlertDialog(
            onDismissRequest = { showRebuildConfirmation.value = false },
            title = { Text("完整重建图库？") },
            text = {
                Text(
                    "这会重新遍历全部扫描目录并重建 Grid 缩略图。" +
                        "当前扫描、缩略图或识别会先完整结束；重建期间主页继续显示上一份完整结果。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRebuildConfirmation.value = false
                        albumViewModel.requestFullRebuild()
                    },
                ) {
                    Text("确认完整重建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildConfirmation.value = false }) {
                    Text("取消")
                }
            },
        )
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "扫描操作",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )

            Button(
                onClick = { albumViewModel.rescan() },
                enabled = !coreScanActive && hasScanRoots,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (coreScanActive) "扫描中…"
                    else "检查增量更新",
                )
            }

            OutlinedButton(
                onClick = { showRebuildConfirmation.value = true },
                enabled = hasScanRoots,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("完整重建图库")
            }

            Text(
                text = coreScanStatusText(indexAvailability, coreScanState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = enhancementStatusText(enhancementState),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            if (totalFailedCount > 0) {
                Text(
                    text = "失败任务：${totalFailedCount} 项" +
                        "（缩略图 $failedThumbnailCount，分析 $failedAnalysisCount，交接 $failedOutboxCount）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                if (!retryAllowed) {
                    Text(
                        text = "当前流水线阶段正在收敛，完成后可手动重试失败项。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            FailedWorkRetryControl(
                label = "缩略图",
                failedCount = failedThumbnailCount,
                running = thumbnailRetryRunning,
                retryAllowed = retryAllowed,
                anyRetryRunning = anyRetryRunning,
                runningText = "正在重新安排失败缩略图…",
                buttonText = "重试失败缩略图",
                successText = thumbnailRetryResult?.let { result ->
                    if (result.retried > 0) "已重新安排 ${result.retried} 个缩略图任务"
                    else "没有可重试的缩略图任务"
                },
                failureText = thumbnailRetryFailure,
                onRetry = albumViewModel::retryFailedThumbnails,
            )

            FailedWorkRetryControl(
                label = "分析",
                failedCount = failedAnalysisCount,
                running = analysisRetryRunning,
                retryAllowed = retryAllowed,
                anyRetryRunning = anyRetryRunning,
                runningText = "正在重新安排失败分析…",
                buttonText = "重试失败分析",
                successText = analysisRetryResult?.let { result ->
                    if (result.retried > 0) "已重新安排 ${result.retried} 个分析任务"
                    else "没有可重试的分析任务"
                },
                failureText = analysisRetryFailure,
                onRetry = albumViewModel::retryFailedAnalysis,
            )

            FailedWorkRetryControl(
                label = "交接",
                failedCount = failedOutboxCount,
                running = outboxRetryRunning,
                retryAllowed = retryAllowed,
                anyRetryRunning = anyRetryRunning,
                runningText = "正在准备失败交接任务…",
                buttonText = "重试失败交接",
                successText = outboxRetryResult?.let { result ->
                    "已转为用户任务：分析 ${result.analysisTasks}，缩略图 ${result.thumbnailTasks}" +
                        if (result.unsupportedOutboxes > 0) {
                            "；仍有 ${result.unsupportedOutboxes} 项等待当前版本支持"
                        } else {
                            ""
                        }
                },
                failureText = outboxRetryFailure,
                onRetry = albumViewModel::retryFailedEnhancementOutbox,
            )

            when (val s = scanState) {
                is ScanState.Scanning -> {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                is ScanState.Failed -> {
                    Text(
                        text = "扫描失败: ${s.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                ScanState.Done, ScanState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun FailedWorkRetryControl(
    label: String,
    failedCount: Int,
    running: Boolean,
    retryAllowed: Boolean,
    anyRetryRunning: Boolean,
    runningText: String,
    buttonText: String,
    successText: String?,
    failureText: String?,
    onRetry: () -> Unit,
) {
    if (failedCount > 0) {
        Text(
            text = "$label 失败：$failedCount 项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    when {
        running -> Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = runningText,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        failedCount > 0 -> OutlinedButton(
            onClick = onRetry,
            enabled = retryAllowed && !anyRetryRunning,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(buttonText)
        }
    }

    successText?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    failureText?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
