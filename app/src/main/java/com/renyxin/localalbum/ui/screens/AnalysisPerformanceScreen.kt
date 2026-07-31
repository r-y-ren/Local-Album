package com.renyxin.localalbum.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.core.concurrent.AnalysisDeviceCapabilityDetector
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingMode
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingResolver
import com.renyxin.localalbum.core.concurrent.AnalysisSchedulingRuntime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisPerformanceScreen(
    selectedMode: AnalysisSchedulingMode,
    onModeSelected: (AnalysisSchedulingMode) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val capabilities = remember { AnalysisDeviceCapabilityDetector.detect(context) }
    val recommended = remember(capabilities) { AnalysisSchedulingResolver.recommend(capabilities) }
    var pendingMode by remember { mutableStateOf(selectedMode) }
    var savedMode by remember { mutableStateOf(selectedMode) }
    val effectiveProfile = remember(pendingMode, capabilities) {
        AnalysisSchedulingResolver.resolve(pendingMode, capabilities)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("扫描与分析性能") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("设备概况", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("逻辑核心：${capabilities.logicalCpuCores}")
                        Text("总内存：${"%.1f".format(capabilities.totalMemoryGb)} GB")
                        Text("当前可用：${"%.1f".format(capabilities.availableMemoryGb)} GB")
                        Text(
                            "推荐模式：${recommended.displayName()}",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        effectiveProfile.protectionReason?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(
                            "调度模式",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        AnalysisSchedulingMode.entries.forEachIndexed { index, mode ->
                            ListItem(
                                modifier = Modifier.clickable { pendingMode = mode },
                                headlineContent = { Text(mode.displayName()) },
                                supportingContent = { Text(mode.description(recommended)) },
                                leadingContent = {
                                    RadioButton(
                                        selected = pendingMode == mode,
                                        onClick = { pendingMode = mode },
                                    )
                                },
                                colors = ListItemDefaults.colors(
                                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                ),
                            )
                            if (index != AnalysisSchedulingMode.entries.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("当前有效配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("分析窗口：最多 ${effectiveProfile.workerLeaseGroups * 250} 个媒体")
                        Text(if (effectiveProfile.allowFaceQualityParallel) "人脸与质量评估：安全并行" else "所有分析阶段：串行")
                        Text("人脸 / 场景 / 质量并发：${effectiveProfile.faceConcurrency} / ${effectiveProfile.sceneConcurrency} / ${effectiveProfile.qualityConcurrency}")
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "语义分析与 OCR 始终保持单路执行；系统内存紧张或严重发热时会自动降级。修改将在下一个分析窗口生效，不会重置现有任务。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        val profile = AnalysisSchedulingResolver.resolve(pendingMode, capabilities)
                        AnalysisSchedulingRuntime.update(profile)
                        onModeSelected(pendingMode)
                        savedMode = pendingMode
                    },
                    enabled = pendingMode != savedMode,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (pendingMode == savedMode) "当前方案已保存" else "保存并应用方案")
                }
                Text(
                    text = if (pendingMode == savedMode) {
                        "正在使用：${savedMode.displayName()}"
                    } else {
                        "尚未保存，当前仍使用：${savedMode.displayName()}"
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun AnalysisSchedulingMode.displayName(): String = when (this) {
    AnalysisSchedulingMode.AUTO -> "自动推荐"
    AnalysisSchedulingMode.STABILITY -> "稳定优先"
    AnalysisSchedulingMode.BALANCED -> "均衡模式"
    AnalysisSchedulingMode.PERFORMANCE -> "性能优先"
}

private fun AnalysisSchedulingMode.description(recommended: AnalysisSchedulingMode): String = when (this) {
    AnalysisSchedulingMode.AUTO -> "根据设备能力自动选择，当前推荐 ${recommended.displayName()}"
    AnalysisSchedulingMode.STABILITY -> "250 个媒体窗口，阶段串行，适合低内存或易发热设备"
    AnalysisSchedulingMode.BALANCED -> "500–1000 个媒体窗口，恢复经过验证的安全组合并行"
    AnalysisSchedulingMode.PERFORMANCE -> "使用最大安全窗口和并发，适合 12 GB 以上高性能设备"
}
