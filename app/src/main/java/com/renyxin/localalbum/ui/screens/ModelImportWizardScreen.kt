package com.renyxin.localalbum.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.content.Context
import android.provider.OpenableColumns
import com.renyxin.localalbum.core.plugin.PluginManifest.ModelFormat
import com.renyxin.localalbum.core.plugin.PluginManifest.TensorDataType
import com.renyxin.localalbum.core.plugin.PluginManifest.TensorSpec
import com.renyxin.localalbum.ui.vm.JsonValidationState
import com.renyxin.localalbum.ui.vm.PluginViewModel
import com.renyxin.localalbum.ui.vm.SaveState
import com.renyxin.localalbum.ui.vm.TensorParseState

/**
 * 模型导入 4 步可视化向导（Phase 4.3）。
 *
 * - Step 0: 选择模型文件（SAF 文件选择器）
 * - Step 1: 确认模型元数据（格式、文件名）
 * - Step 2: 编辑张量规格与插件元信息
 * - Step 3: 确认与保存
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelImportWizardScreen(
    viewModel: PluginViewModel,
    onNavigateBack: () -> Unit,
) {
    val wizardStep by viewModel.wizardStep.collectAsState()
    val modelFileName by viewModel.modelFileName.collectAsState()
    val parsedTensors by viewModel.parsedTensors.collectAsState()
    val detectedFormat by viewModel.detectedFormat.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

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
            viewModel.resetWizard()
            onNavigateBack()
        }
    }

    val steps = listOf(
        "选择模型" to Icons.Filled.UploadFile,
        "确认元数据" to Icons.Filled.CloudUpload,
        "配置信息" to Icons.Filled.Tune,
        "保存完成" to Icons.Filled.CheckCircle,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("导入 AI 模型") },
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
            // 步骤指示器
            StepIndicator(
                steps = steps.map { it.first },
                currentStep = wizardStep,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 步骤内容
            Box(modifier = Modifier.weight(1f)) {
                when (wizardStep) {
                    0 -> StepSelectModel(
                        modelFileName = modelFileName,
                        parsedTensors = parsedTensors,
                        onFileSelected = { uri, name ->
                            viewModel.onModelFileSelected(uri, name)
                        },
                    )
                    1 -> StepConfirmMetadata(
                        detectedFormat = detectedFormat,
                        modelFileName = modelFileName,
                        parsedTensors = parsedTensors,
                    )
                    2 -> StepEditConfig(
                        viewModel = viewModel,
                    )
                    3 -> StepConfirmSave(
                        viewModel = viewModel,
                        saveState = saveState,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 底部导航按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OutlinedButton(
                    onClick = {
                        if (wizardStep == 0) onNavigateBack()
                        else viewModel.previousStep()
                    },
                    enabled = saveState !is SaveState.Saving,
                ) {
                    Text(if (wizardStep == 0) "取消" else "上一步")
                }

                when (wizardStep) {
                    0 -> {
                        Button(
                            onClick = { viewModel.nextStep() },
                            enabled = modelFileName != null && parsedTensors is TensorParseState.Success,
                        ) {
                            Text("下一步")
                        }
                    }
                    1 -> {
                        Button(onClick = { viewModel.nextStep() }) {
                            Text("下一步")
                        }
                    }
                    2 -> {
                        Button(onClick = { viewModel.nextStep() }) {
                            Text("下一步")
                        }
                    }
                    3 -> {
                        Button(
                            onClick = { viewModel.saveFromWizard() },
                            enabled = saveState !is SaveState.Saving,
                        ) {
                            if (saveState is SaveState.Saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("保存插件")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- Step 0: Select Model File ----

@Composable
private fun StepSelectModel(
    modelFileName: String?,
    parsedTensors: TensorParseState,
    onFileSelected: (Uri, String) -> Unit,
) {
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val fileName = resolveDisplayName(context, uri) ?: "model.tflite"
            onFileSelected(uri, fileName)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.UploadFile,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "选择 AI 模型文件",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "支持 .tflite, .onnx, .pt/,ptl, .pth 格式",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                filePickerLauncher.launch(arrayOf(
                    "application/octet-stream",
                    "*/*",
                ))
            },
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("从文件管理器选择")
        }

        // 选中后显示解析状态
        if (modelFileName != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "已选择: $modelFileName",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    when (parsedTensors) {
                        is TensorParseState.Loading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("正在解析张量元数据...")
                            }
                        }
                        is TensorParseState.Success -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "解析成功: ${parsedTensors.inputTensors.size} 入, ${parsedTensors.outputTensors.size} 出",
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                        is TensorParseState.Error -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    parsedTensors.message,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        is TensorParseState.Idle -> {}
                    }
                }
            }
        }
    }
}

// ---- Step 1: Confirm Metadata ----

@Composable
private fun StepConfirmMetadata(
    detectedFormat: ModelFormat?,
    modelFileName: String?,
    parsedTensors: TensorParseState,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "确认模型信息",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                DetailRow("文件名", modelFileName ?: "未知")
                DetailRow("格式", formatDisplayName(detectedFormat))
                DetailRow("状态", "解析完成")
            }
        }

        if (parsedTensors is TensorParseState.Success) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "输入张量 (${parsedTensors.inputTensors.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            parsedTensors.inputTensors.forEach { tensor ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("名称: ${tensor.name}")
                        Text("类型: ${tensor.dataType}")
                        Text("形状: ${tensor.shape}")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "输出张量 (${parsedTensors.outputTensors.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            parsedTensors.outputTensors.forEach { tensor ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("名称: ${tensor.name}")
                        Text("类型: ${tensor.dataType}")
                        Text("形状: ${tensor.shape}")
                    }
                }
            }
        }
    }
}

// ---- Step 2: Edit Config ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StepEditConfig(viewModel: PluginViewModel) {
    val pluginName by viewModel.pluginName.collectAsState()
    val pluginDescription by viewModel.pluginDescription.collectAsState()
    val pluginVersion by viewModel.pluginVersion.collectAsState()
    val pluginAuthor by viewModel.pluginAuthor.collectAsState()
    val entryClass by viewModel.entryClass.collectAsState()
    val pipelineStage by viewModel.pipelineStage.collectAsState()
    val inputTensors by viewModel.inputTensors.collectAsState()
    val outputTensors by viewModel.outputTensors.collectAsState()
    val featureSchemaJson by viewModel.featureSchemaJson.collectAsState()

    val pipelineStages = listOf(
        "preprocessing" to "预处理",
        "analysis" to "分析",
        "postprocessing" to "后处理",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "配置插件信息",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 基本信息
        SectionTitle("基本信息")
        OutlinedTextField(
            value = pluginName,
            onValueChange = { viewModel.updatePluginName(it) },
            label = { Text("插件名称 *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = pluginDescription,
            onValueChange = { viewModel.updatePluginDescription(it) },
            label = { Text("描述") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = pluginVersion,
                onValueChange = { viewModel.updatePluginVersion(it) },
                label = { Text("版本") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = pluginAuthor,
                onValueChange = { viewModel.updatePluginAuthor(it) },
                label = { Text("作者") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = entryClass,
            onValueChange = { viewModel.updateEntryClass(it) },
            label = { Text("插件入口类名 *") },
            supportingText = {
                Text(
                    "Provider 实现的全限定类名，例如 com.renyxin.localalbum.core.plugin.model.MobileNetSceneProvider",
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))
        SectionTitle("管道阶段")

        var stagesExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = stagesExpanded,
            onExpandedChange = { stagesExpanded = it },
        ) {
            val selectedLabel = pipelineStages.find { it.first == pipelineStage }?.second ?: pipelineStage
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stagesExpanded) },
            )
            ExposedDropdownMenu(
                expanded = stagesExpanded,
                onDismissRequest = { stagesExpanded = false },
            ) {
                pipelineStages.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            viewModel.updatePipelineStage(value)
                            stagesExpanded = false
                        },
                    )
                }
            }
        }

        // 张量规格
        Spacer(modifier = Modifier.height(16.dp))
        SectionTitle("输入张量")
        inputTensors.forEachIndexed { index, tensor ->
            TensorSpecEditor(
                spec = tensor,
                label = "输入 #${index + 1}",
                onUpdate = { viewModel.updateInputTensor(index, it) },
            )
        }
        if (inputTensors.isEmpty()) {
            Text("无输入张量", style = MaterialTheme.typography.bodySmall)
        }

        Spacer(modifier = Modifier.height(16.dp))
        SectionTitle("输出张量")
        outputTensors.forEachIndexed { index, tensor ->
            TensorSpecEditor(
                spec = tensor,
                label = "输出 #${index + 1}",
                onUpdate = { viewModel.updateOutputTensor(index, it) },
            )
        }
        if (outputTensors.isEmpty()) {
            Text("无输出张量", style = MaterialTheme.typography.bodySmall)
        }

        // 特征 Schema
        Spacer(modifier = Modifier.height(16.dp))
        SectionTitle("特征 Schema (可选)")
        OutlinedTextField(
            value = featureSchemaJson,
            onValueChange = { viewModel.updateFeatureSchemaJson(it) },
            label = { Text("JSON Schema") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
    }
}

// ---- Step 3: Confirm & Save ----

@Composable
private fun StepConfirmSave(
    viewModel: PluginViewModel,
    saveState: SaveState,
) {
    val pluginName by viewModel.pluginName.collectAsState()
    val detectedFormat by viewModel.detectedFormat.collectAsState()
    val modelFileName by viewModel.modelFileName.collectAsState()
    val entryClass by viewModel.entryClass.collectAsState()
    val inputTensors by viewModel.inputTensors.collectAsState()
    val outputTensors by viewModel.outputTensors.collectAsState()
    val pipelineStage by viewModel.pipelineStage.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "确认并保存",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                DetailRow("插件名称", pluginName)
                DetailRow("模型文件", modelFileName ?: "未知")
                DetailRow("格式", formatDisplayName(detectedFormat))
                DetailRow("入口类名", entryClass.ifBlank { "（未填写）" })
                DetailRow("入张量数", "${inputTensors.size}")
                DetailRow("出张量数", "${outputTensors.size}")
                DetailRow("管道阶段", pipelineStage)
            }
        }

        if (saveState is SaveState.Saving) {
            Spacer(modifier = Modifier.height(24.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "正在保存...",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ---- Shared Components ----

@Composable
private fun StepIndicator(
    steps: List<String>,
    currentStep: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, label ->
            val isActive = index == currentStep
            val isCompleted = index < currentStep

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                // 步骤圆圈
                Text(
                    text = if (isCompleted) "✓" else "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isActive -> MaterialTheme.colorScheme.onPrimary
                        isCompleted -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(4.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive || isCompleted)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 连线
            if (index < steps.size - 1) {
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .height(2.dp)
                        .animateContentSize()
                        .padding(start = 4.dp, end = 4.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { if (isCompleted) 1f else 0f },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun formatDisplayName(format: ModelFormat?): String {
    return when (format) {
        ModelFormat.TFLITE -> "TensorFlow Lite"
        ModelFormat.ONNX -> "ONNX"
        ModelFormat.PYTORCH -> "PyTorch Mobile"
        null -> "未知"
    }
}

/**
 * 通过 [OpenableColumns.DISPLAY_NAME] 解析 SAF Uri 的真实文件名。
 *
 * `Uri.lastPathSegment` 对于 content Uri 通常返回数字 ID 而非文件名，
 * 会导致后续格式检测（依扩展名）失败，因此必须查询 DISPLAY_NAME。
 */
private fun resolveDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else {
                    null
                }
            } ?: uri.lastPathSegment?.substringAfterLast('/')
    } catch (_: Exception) {
        uri.lastPathSegment?.substringAfterLast('/')
    }
}

// ---- TensorSpec Editor ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TensorSpecEditor(
    spec: TensorSpec,
    label: String,
    onUpdate: (TensorSpec) -> Unit,
) {
    // 以 spec 为 key，当外部 spec 变化（如重新解析模型）时重置本地编辑态，避免显示陈旧数据
    var name by remember(spec) { mutableStateOf(spec.name) }
    var shapeStr by remember(spec) { mutableStateOf(spec.shape.joinToString(", ")) }
    var selectedDtype by remember(spec) { mutableStateOf(spec.dataType) }

    val dtypes = TensorDataType.entries
    var dtypeExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
        )
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                onUpdate(spec.copy(name = it))
            },
            label = { Text("名称") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = shapeStr,
            onValueChange = {
                shapeStr = it
                val shape = it.split(",").mapNotNull { s -> s.trim().toIntOrNull() }
                onUpdate(spec.copy(shape = shape))
            },
            label = { Text("形状 (逗号分隔)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
        Spacer(modifier = Modifier.height(4.dp))

        ExposedDropdownMenuBox(
            expanded = dtypeExpanded,
            onExpandedChange = { dtypeExpanded = it },
        ) {
            OutlinedTextField(
                value = selectedDtype.name,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dtypeExpanded) },
                label = { Text("数据类型") },
            )
            ExposedDropdownMenu(
                expanded = dtypeExpanded,
                onDismissRequest = { dtypeExpanded = false },
            ) {
                dtypes.forEach { dtype ->
                    DropdownMenuItem(
                        text = { Text(dtype.name) },
                        onClick = {
                            selectedDtype = dtype
                            dtypeExpanded = false
                            onUpdate(spec.copy(dataType = dtype))
                        },
                    )
                }
            }
        }
    }
}