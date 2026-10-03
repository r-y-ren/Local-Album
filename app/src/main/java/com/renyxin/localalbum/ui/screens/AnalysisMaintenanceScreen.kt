package com.renyxin.localalbum.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.ui.vm.AlbumViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 分析维护页：按阶段单独重跑分析（文字识别/人脸/场景/画质/语义等）。
 * 预处理或模型升级后由此显式触发对应阶段的全部图片重分析，
 * 不重新扫描文件系统，也不影响其他阶段的结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisMaintenanceScreen(
    targets: List<AlbumViewModel.AnalysisStageTarget>,
    rerunState: AlbumViewModel.StageRerunState,
    onRerun: (String) -> Unit,
    onStateConsumed: () -> Unit,
    onLoadTargets: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmTarget by remember { mutableStateOf<AlbumViewModel.AnalysisStageTarget?>(null) }
    val running = rerunState is AlbumViewModel.StageRerunState.Running

    LaunchedEffect(Unit) { onLoadTargets() }

    LaunchedEffect(rerunState) {
        val message = when (val s = rerunState) {
            is AlbumViewModel.StageRerunState.Completed ->
                "${s.stageId} 已排入 ${s.queued} 个任务，后台执行中"
            is AlbumViewModel.StageRerunState.Failed ->
                "${s.stageId} 重跑任务创建失败：${s.message}"
            else -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Long)
            onStateConsumed()
        }
    }

    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("重新执行「${target.displayName}」？") },
            text = {
                Text(
                    "将对扫描范围内的全部图片重新执行该阶段分析（当前引擎版本 v${target.modelVersion}）。" +
                        "任务在后台排队执行，期间可正常使用应用。",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !running,
                    onClick = {
                        confirmTarget = null
                        onRerun(target.stageId)
                    },
                ) { Text("开始") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("分析重建") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
        androidx.compose.animation.AnimatedVisibility(
            visible = running,
            enter = androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.shrinkVertically(),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hint") {
                Text(
                    text = "模型或识别算法升级后，已完成的分析不会自动重跑。" +
                        "在此可按阶段对全部图片重新执行分析；其他阶段的结果保持不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(targets.size, key = { targets[it].stageId }) { index ->
                val target = targets[index]
                StageRerunCard(
                    target = target,
                    enabled = !running,
                    onClick = { confirmTarget = target },
                )
            }
        }
    }
}

@Composable
private fun StageRerunCard(
    target: AlbumViewModel.AnalysisStageTarget,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stageDescription(target.stageId) + " · 引擎版本 v${target.modelVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = onClick,
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("重新执行")
            }
        }
    }
}

/** 阶段用途的用户向说明；未知阶段回退为中性描述。 */
internal fun stageDescription(stageId: String): String = when (stageId) {
    "core:ocr" -> "识别截图与文档中的文字，供关键词搜索"
    "core:face" -> "检测人脸并聚类，供人物页与检索"
    "core:scene" -> "图像场景分类（自然、城市、美食等）"
    "core:quality" -> "画质评分，供精选与清理建议"
    "core:semantic" -> "语义向量索引，供自然语言与以文搜图"
    else -> "重新执行该阶段分析"
}
