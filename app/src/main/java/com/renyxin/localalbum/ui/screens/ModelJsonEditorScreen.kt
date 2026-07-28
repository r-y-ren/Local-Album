package com.renyxin.localalbum.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.renyxin.localalbum.ui.vm.JsonValidationState
import com.renyxin.localalbum.ui.vm.PluginViewModel
import com.renyxin.localalbum.ui.vm.SaveState
import kotlinx.coroutines.delay

/**
 * JSON 编辑器界面（Phase 4.4）。
 *
 * 提供 PluginManifest JSON 的模板初始化、语法高亮提示、
 * 实时校验、保存等功能，面向高级用户。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelJsonEditorScreen(
    viewModel: PluginViewModel,
    onNavigateBack: () -> Unit,
) {
    val editorJsonText by viewModel.editorJsonText.collectAsState()
    val jsonValidationResult by viewModel.jsonValidationResult.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // 首次进入自动初始化模板
    LaunchedEffect(Unit) {
        if (editorJsonText.isBlank()) {
            viewModel.initEditorFromTemplate()
        }
    }

    // Message Snackbar
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    // 保存成功后返回
    LaunchedEffect(saveState) {
        if (saveState is SaveState.Success) {
            viewModel.clearSaveState()
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("JSON 编辑器") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("取消")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            // 说明文字
            Text(
                text = "直接编辑 PluginManifest JSON 定义",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "适合已熟悉 manifest 格式的高级用户。编辑完成后点击「校验」确认格式，再「保存」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 顶部操作按钮
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { viewModel.validateEditorJson() },
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("校验")
                }

                Spacer(modifier = Modifier.width(8.dp))

                OutlinedButton(
                    onClick = { viewModel.initEditorFromTemplate() },
                ) {
                    Icon(
                        Icons.Filled.Code,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("重置模板")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // "从文件导入" 按钮（SAF 文件选择器）
            val jsonFilePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri: Uri? ->
                uri?.let { viewModel.importFromJsonFile(it) }
            }

            OutlinedButton(
                onClick = { jsonFilePicker.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Filled.FileOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("从文件导入 .json")
            }

            // 校验结果
            when (jsonValidationResult) {
                is JsonValidationState.Valid -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                val manifest = (jsonValidationResult as JsonValidationState.Valid).manifest
                                Text(
                                    "✓ 校验通过",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                                Text(
                                    "插件: ${manifest.name} (${manifest.pluginId})",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "格式: ${manifest.modelFormat}, 张量入=${manifest.inputTensors.size} 出=${manifest.outputTensors.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                is JsonValidationState.Error -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                (jsonValidationResult as JsonValidationState.Error).message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                is JsonValidationState.Idle -> {}
            }

            Spacer(modifier = Modifier.height(8.dp))

            // JSON 编辑区域
            OutlinedTextField(
                value = editorJsonText,
                onValueChange = { viewModel.updateEditorJsonText(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                label = { Text("PluginManifest JSON") },
                minLines = 15,
            )

            // 防抖自动校验：用户停止输入 800ms 后自动触发
            LaunchedEffect(editorJsonText) {
                if (editorJsonText.isBlank()) return@LaunchedEffect
                delay(800L)
                // 仅在文本未再变化时执行校验
                if (editorJsonText == viewModel.editorJsonText.value) {
                    viewModel.validateEditorJson()
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 保存按钮
            Button(
                onClick = { viewModel.saveFromEditor() },
                enabled = saveState !is SaveState.Saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (saveState is SaveState.Saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("保存插件")
            }
        }
    }
}