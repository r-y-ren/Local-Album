package com.renyxin.localalbum.ui.vm

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.renyxin.localalbum.core.plugin.FeatureSchema
import com.renyxin.localalbum.core.plugin.PluginJsonCodec
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.PluginManifest.ModelFormat
import com.renyxin.localalbum.core.plugin.PluginManifest.TensorDataType
import com.renyxin.localalbum.core.plugin.PluginManifest.TensorSpec
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginOutput
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.capability.ModelReadiness
import com.renyxin.localalbum.core.plugin.capability.SemanticEmbedProvider
import com.renyxin.localalbum.core.plugin.capability.SlotMetadata
import com.renyxin.localalbum.core.plugin.capability.builtin.Eva02ClipProvider
import com.renyxin.localalbum.core.plugin.extension.ExtensionPluginRegistry
import com.renyxin.localalbum.core.plugin.TensorMetadataParser
import com.renyxin.localalbum.core.plugin.TensorParseException
import com.renyxin.localalbum.core.plugin.UnsupportedModelFormatException
import com.renyxin.localalbum.core.plugin.model.ModelCatalog
import com.renyxin.localalbum.core.plugin.model.ModelDownloadManagerV2
import com.renyxin.localalbum.core.plugin.model.ModelInfo
import com.renyxin.localalbum.core.plugin.model.ModelStorageManager
import com.renyxin.localalbum.data.db.AppDatabase
import com.renyxin.localalbum.data.db.entity.PluginManifestEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 插件管理 ViewModel（Phase 4.3-4.6 + Phase 5a+ 模型下载）。
 *
 * 管理插件导入、JSON 编辑、启用/禁用、删除、模型目录浏览与下载。
 * 三个 Screen 共享此 ViewModel：
 * - [ModelImportWizardScreen]: 4 步可视化向导
 * - [ModelJsonEditorScreen]: JSON 编辑器
 * - [PluginManagerScreen]: 插件管理列表 + 模型市场
 */
class PluginViewModel(
    private val appContext: Context,
    private val database: AppDatabase,
    private val capabilityRegistry: CapabilityRegistryV2,
    private val extensionRegistry: ExtensionPluginRegistry,
    private val modelManager: com.renyxin.localalbum.core.plugin.model.ModelManager? = null,
    private val modelStorageManager: ModelStorageManager? = null,
    private val modelDownloadManager: ModelDownloadManagerV2? = null,
    private val modelCatalog: ModelCatalog? = null,
) : ViewModel() {

    private val pluginDao = database.pluginManifestDao()
    private val jsonCodec = PluginJsonCodec

    // --- Wizard State ---

    /** 向导当前步骤 (0-based) */
    private val _wizardStep = MutableStateFlow(0)
    val wizardStep: StateFlow<Int> = _wizardStep.asStateFlow()

    /** 选择模型文件 (SAF Uri) */
    private val _selectedModelUri = MutableStateFlow<Uri?>(null)
    val selectedModelUri: StateFlow<Uri?> = _selectedModelUri.asStateFlow()

    /** 从模型文件解析出的张量元数据 */
    private val _parsedTensors = MutableStateFlow<TensorParseState>(TensorParseState.Idle)
    val parsedTensors: StateFlow<TensorParseState> = _parsedTensors.asStateFlow()

    /** 识别出的模型格式 */
    private val _detectedFormat = MutableStateFlow<ModelFormat?>(null)
    val detectedFormat: StateFlow<ModelFormat?> = _detectedFormat.asStateFlow()

    /** 文件显示名称 */
    private val _modelFileName = MutableStateFlow<String?>(null)
    val modelFileName: StateFlow<String?> = _modelFileName.asStateFlow()

    // --- Form Data (Step 3) ---

    /** 插件名称 (可编辑) */
    private val _pluginName = MutableStateFlow("")
    val pluginName: StateFlow<String> = _pluginName.asStateFlow()

    /** 插件描述 (可编辑) */
    private val _pluginDescription = MutableStateFlow("")
    val pluginDescription: StateFlow<String> = _pluginDescription.asStateFlow()

    /** 插件版本 */
    private val _pluginVersion = MutableStateFlow("1.0.0")
    val pluginVersion: StateFlow<String> = _pluginVersion.asStateFlow()

    /** 插件作者 */
    private val _pluginAuthor = MutableStateFlow("")
    val pluginAuthor: StateFlow<String> = _pluginAuthor.asStateFlow()

    /** 任务类型 (用户可选择) */
    private val _taskType = MutableStateFlow(PluginManifest.TaskType.FEATURE_EXTRACTION)
    val taskType: StateFlow<PluginManifest.TaskType> = _taskType.asStateFlow()

    /** 管道阶段 */
    private val _pipelineStage = MutableStateFlow("analysis")
    val pipelineStage: StateFlow<String> = _pipelineStage.asStateFlow()

    /** 输入张量规格 (可编辑) */
    private val _inputTensors = MutableStateFlow<List<TensorSpec>>(emptyList())
    val inputTensors: StateFlow<List<TensorSpec>> = _inputTensors.asStateFlow()

    /** 输出张量规格 (可编辑) */
    private val _outputTensors = MutableStateFlow<List<TensorSpec>>(emptyList())
    val outputTensors: StateFlow<List<TensorSpec>> = _outputTensors.asStateFlow()

    /** 特征 Schema JSON (可编辑) */
    private val _featureSchemaJson = MutableStateFlow("")
    val featureSchemaJson: StateFlow<String> = _featureSchemaJson.asStateFlow()

    // --- JSON Editor State ---

    /** JSON 编辑器的 manifest JSON 文本 */
    private val _editorJsonText = MutableStateFlow("")
    val editorJsonText: StateFlow<String> = _editorJsonText.asStateFlow()

    /** JSON 校验结果 */
    private val _jsonValidationResult = MutableStateFlow<JsonValidationState>(JsonValidationState.Idle)
    val jsonValidationResult: StateFlow<JsonValidationState> = _jsonValidationResult.asStateFlow()

    // --- Plugin List ---

    /** 所有插件清单的 Flow */
    val pluginList: StateFlow<List<PluginManifestEntity>> = pluginDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 保存/导入操作状态 */
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    /** 提示/错误消息 Snackbar */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ---- Wizard Actions ----

    /** 进入下一步 */
    fun nextStep() {
        val current = _wizardStep.value
        if (current < 3) {
            _wizardStep.value = current + 1
        }
    }

    /** 返回上一步 */
    fun previousStep() {
        val current = _wizardStep.value
        if (current > 0) {
            _wizardStep.value = current - 1
        }
    }

    /** 跳转到指定步骤 */
    fun goToStep(step: Int) {
        _wizardStep.value = step.coerceIn(0, 3)
    }

    /** 重置向导 */
    fun resetWizard() {
        _wizardStep.value = 0
        _selectedModelUri.value = null
        _parsedTensors.value = TensorParseState.Idle
        _detectedFormat.value = null
        _modelFileName.value = null
        _pluginName.value = ""
        _pluginDescription.value = ""
        _pluginVersion.value = "1.0.0"
        _pluginAuthor.value = ""
        _entryClass.value = ""
        _taskType.value = PluginManifest.TaskType.FEATURE_EXTRACTION
        _pipelineStage.value = "analysis"
        _inputTensors.value = emptyList()
        _outputTensors.value = emptyList()
        _featureSchemaJson.value = ""
        _saveState.value = SaveState.Idle
    }

    /** 选择模型文件并自动解析张量 */
    fun onModelFileSelected(uri: Uri, fileName: String) {
        _selectedModelUri.value = uri
        _modelFileName.value = fileName
        parseModelFile(uri, fileName)
    }

    /** 解析模型文件提取张量 meta */
    private fun parseModelFile(uri: Uri, fileName: String) {
        _parsedTensors.value = TensorParseState.Loading
        viewModelScope.launch {
            try {
                // 检测格式
                val format = TensorMetadataParser.detectFormat(fileName)
                _detectedFormat.value = format

                // 复制到临时文件并解析
                val tempFile = File.createTempFile("model_", ".$fileName")
                try {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw TensorParseException("无法打开文件")

                    val result = TensorMetadataParser.parse(appContext, tempFile, format)
                    _inputTensors.value = result.inputTensors
                    _outputTensors.value = result.outputTensors
                    _parsedTensors.value = TensorParseState.Success(result.inputTensors, result.outputTensors)

                    // 自动回填插件名称
                    if (_pluginName.value.isBlank()) {
                        _pluginName.value = fileName.substringBeforeLast(".")
                    }
                } finally {
                    tempFile.delete()
                }
            } catch (e: UnsupportedModelFormatException) {
                _parsedTensors.value = TensorParseState.Error("不支持的模型格式: ${e.message}")
            } catch (e: TensorParseException) {
                _parsedTensors.value = TensorParseState.Error(e.message ?: "解析失败")
            } catch (e: Exception) {
                _parsedTensors.value = TensorParseState.Error("解析异常: ${e.message}")
            }
        }
    }

    /** 向导模式下更新输入张量 */
    fun updateInputTensor(index: Int, spec: TensorSpec) {
        val current = _inputTensors.value.toMutableList()
        if (index in current.indices) {
            current[index] = spec
            _inputTensors.value = current
        }
    }

    /** 向导模式下更新输出张量 */
    fun updateOutputTensor(index: Int, spec: TensorSpec) {
        val current = _outputTensors.value.toMutableList()
        if (index in current.indices) {
            current[index] = spec
            _outputTensors.value = current
        }
    }

    /** 插件入口类名（由用户在向导步骤 3 中填写） */
    private val _entryClass = MutableStateFlow("")
    val entryClass: StateFlow<String> = _entryClass.asStateFlow()

    /** 构建 PluginManifest 并保存 */
    fun saveFromWizard() {
        val name = _pluginName.value.trim()
        if (name.isBlank()) {
            _message.value = "插件名称不能为空"
            return
        }

        val entryClassValue = _entryClass.value.trim()
        if (entryClassValue.isBlank()) {
            _message.value = "插件入口类名不能为空"
            return
        }

        val pluginId = "plugin.${name.lowercase().replace(Regex("[^a-z0-9]"), "_")}"

        val format = _detectedFormat.value ?: ModelFormat.TFLITE
        val manifest = PluginManifest(
            pluginId = pluginId,
            name = name,
            version = _pluginVersion.value.ifBlank { "1.0.0" },
            description = _pluginDescription.value.ifBlank { null },
            author = _pluginAuthor.value.ifBlank { "" },
            taskType = _taskType.value,
            modelFormat = format,
            modelFilePath = _modelFileName.value ?: "",
            entryClass = entryClassValue,
            inputTensors = _inputTensors.value,
            outputTensors = _outputTensors.value,
            pipelineStage = _pipelineStage.value.ifBlank { "analysis" },
        )

        val manifestJson = jsonCodec.manifestToJson(manifest)
        savePluginManifest(pluginId, name, manifestJson, _pipelineStage.value)

        // 特征 Schema JSON 已随清单保存，后续编辑时再解析校验
        val schemaJson = _featureSchemaJson.value.trim()
        if (schemaJson.isNotBlank()) {
            try {
                jsonCodec.schemaFromJson(schemaJson)
            } catch (_: Exception) {
                _message.value = "特征 Schema JSON 格式无效，已忽略"
            }
        }
    }

    // ---- JSON Editor Actions ----

    /** 初始化编辑器内容 */
    fun initEditorFromTemplate() {
        val template = """{
  "pluginId": "plugin.example_model",
  "name": { "display": "示例模型", "key": "example_model" },
  "version": "1.0.0",
  "description": "描述您的 AI 模型",
  "author": "",
  "modelFormat": "TFLITE",
  "modelFileName": "model.tflite",
  "inputTensors": [
    { "name": "input", "dataType": "FLOAT32", "shape": [1, 224, 224, 3] }
  ],
  "outputTensors": [
    { "name": "output", "dataType": "FLOAT32", "shape": [1, 1000] }
  ],
  "runtime": "tflite",
  "executionMode": "SYNC"
}"""
        _editorJsonText.value = template
        _jsonValidationResult.value = JsonValidationState.Idle
    }

    /** 校验编辑器中的 JSON */
    fun validateEditorJson() {
        val json = _editorJsonText.value.trim()
        if (json.isBlank()) {
            _jsonValidationResult.value = JsonValidationState.Error("JSON 为空")
            return
        }
        try {
            val manifest = jsonCodec.manifestFromJson(json)
            _jsonValidationResult.value = JsonValidationState.Valid(manifest)
        } catch (e: Exception) {
            val lineInfo = extractJsonErrorLine(e)
            _jsonValidationResult.value = JsonValidationState.Error(
                buildString {
                    append("JSON 解析错误: ${e.message}")
                    if (lineInfo != null) append(" ($lineInfo)")
                }
            )
        }
    }

    /**
     * 从 SAF Uri 导入 .json 文件内容到编辑器（Phase 5 R2）。
     */
    fun importFromJsonFile(uri: Uri) {
        viewModelScope.launch {
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    val text = input.bufferedReader().readText()
                    _editorJsonText.value = text
                    // 导入后自动校验
                    validateEditorJson()
                } ?: run {
                    _message.value = "无法打开文件"
                }
            } catch (e: Exception) {
                _message.value = "导入文件失败: ${e.message}"
            }
        }
    }

    /**
     * 从异常信息中提取可能的 JSON 错误行号。
     */
    private fun extractJsonErrorLine(e: Exception): String? {
        val msg = e.message ?: return null
        val lineRegex = Regex("at line (\\d+)", RegexOption.IGNORE_CASE)
        lineRegex.find(msg)?.let { match ->
            val lineNum = match.groupValues[1]
            return "第 $lineNum 行附近"
        }
        val charRegex = Regex("at character (\\d+)", RegexOption.IGNORE_CASE)
        charRegex.find(msg)?.let { match ->
            val charPos = match.groupValues[1].toIntOrNull()
            if (charPos != null) {
                val json = _editorJsonText.value
                var lineCount = 1
                for (i in 0 until charPos.coerceAtMost(json.length)) {
                    if (json[i] == '\n') lineCount++
                }
                return "第 $lineCount 行附近 (字符 $charPos)"
            }
        }
        return null
    }

    /** 保存编辑器中的 JSON 为插件 */
    fun saveFromEditor() {
        val json = _editorJsonText.value.trim()
        if (json.isBlank()) {
            _message.value = "JSON 不能为空"
            return
        }
        try {
            val manifest = jsonCodec.manifestFromJson(json)
            savePluginManifest(
                manifest.pluginId,
                manifest.name,
                json,
                manifest.pipelineStage,
            )
        } catch (e: Exception) {
            _message.value = "JSON 格式无效: ${e.message}"
        }
    }

    // ---- Plugin Management Actions ----

    /** 保存插件清单到数据库 */
    private fun savePluginManifest(pluginId: String, displayName: String, manifestJson: String, stage: String) {
        viewModelScope.launch {
            _saveState.value = SaveState.Saving
            try {
                val existing = pluginDao.getByPluginId(pluginId)
                val entity = PluginManifestEntity(
                    id = existing?.id ?: 0,
                    pluginId = pluginId,
                    manifestJson = manifestJson,
                    isEnabled = existing?.isEnabled ?: true,
                    pipelineStage = stage,
                    createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                    updatedAtMs = System.currentTimeMillis(),
                )
                pluginDao.insert(entity)
                _saveState.value = SaveState.Success("插件 \"$displayName\" 保存成功")
                _message.value = "插件 \"$displayName\" 保存成功"

                reloadPluginRegistry()
            } catch (e: Exception) {
                _saveState.value = SaveState.Error("保存失败: ${e.message}")
                _message.value = "保存失败: ${e.message}"
            }
        }
    }

    /** 删除插件 */
    fun deletePlugin(pluginId: String) {
        viewModelScope.launch {
            try {
                val entity = pluginDao.getByPluginId(pluginId)
                if (entity != null) {
                    val manifest = try {
                        PluginJsonCodec.manifestFromJson(entity.manifestJson)
                    } catch (_: Exception) { null }

                    pluginDao.delete(entity)

                    if (manifest != null && manifest.modelFilePath.isNotBlank()) {
                        val modelFileName = manifest.modelFilePath.substringAfterLast("/")
                        if (modelFileName.isNotBlank()) {
                            modelStorageManager?.deleteModel(modelFileName)
                        }
                    }

                    try { extensionRegistry.unload(pluginId) } catch (_: Exception) {}

                    modelStorageManager?.refresh()
                    reloadPluginRegistry()

                    val modelInfo = if (manifest != null) "「${manifest.name}」" else pluginId
                    _message.value = "插件 $modelInfo 已删除"
                }
            } catch (e: Exception) {
                _message.value = "删除失败: ${e.message}"
            }
        }
    }

    /** 切换插件启用状态 */
    fun togglePluginEnabled(pluginId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                pluginDao.setEnabled(pluginId, enabled)
                reloadPluginRegistry()
            } catch (e: Exception) {
                _message.value = "操作失败: ${e.message}"
            }
        }
    }

    /** 重新加载扩展插件注册表 */
    private fun reloadPluginRegistry() {
        viewModelScope.launch {
            try {
                val summary = extensionRegistry.loadAll()
                android.util.Log.i(
                    "PluginViewModel",
                    "插件注册中心已重新加载：${summary.successCount} 成功, ${summary.failedCount} 失败",
                )
                if (summary.failedCount > 0) {
                    _message.value = "插件加载完成：${summary.successCount} 成功, ${summary.failedCount} 失败"
                }
            } catch (e: Exception) {
                android.util.Log.e("PluginViewModel", "重新加载插件注册中心失败", e)
                _message.value = "插件加载失败: ${e.message}"
            }
        }
    }

    /**
     * 重新排序插件。
     */
    fun reorderPlugins(plugins: List<PluginManifestEntity>, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || fromIndex !in plugins.indices || toIndex !in plugins.indices) return

        viewModelScope.launch {
            try {
                val mutableList = plugins.toMutableList()
                val moved = mutableList.removeAt(fromIndex)
                mutableList.add(toIndex, moved)

                var failed = false
                val now = System.currentTimeMillis()
                for ((index, entity) in mutableList.withIndex()) {
                    try {
                        pluginDao.updateOrder(entity.pluginId, index, now)
                    } catch (e: Exception) {
                        android.util.Log.w("PluginViewModel", "更新排序失败: ${entity.pluginId}", e)
                        failed = true
                    }
                }
                if (failed) {
                    _message.value = "部分排序更新失败，请重试"
                }
            } catch (e: Exception) {
                _message.value = "排序更新失败: ${e.message}"
            }
        }
    }

    /** 清除消息 */
    fun clearMessage() {
        _message.value = null
    }

    /** 清除保存状态 */
    fun clearSaveState() {
        _saveState.value = SaveState.Idle
    }

    // ---- State Update Helpers (used by UI) ----

    fun updatePluginName(value: String) { _pluginName.value = value }
    fun updatePluginDescription(value: String) { _pluginDescription.value = value }
    fun updatePluginVersion(value: String) { _pluginVersion.value = value }
    fun updatePluginAuthor(value: String) { _pluginAuthor.value = value }
    fun updateEntryClass(value: String) { _entryClass.value = value }
    fun updateTaskType(value: PluginManifest.TaskType) { _taskType.value = value }
    fun updatePipelineStage(value: String) { _pipelineStage.value = value }
    fun updateEditorJsonText(value: String) { _editorJsonText.value = value }
    fun updateFeatureSchemaJson(value: String) { _featureSchemaJson.value = value }

    // ---- Capability Slot Delegates ----

    val slotMetadataList: StateFlow<List<SlotMetadata>> = capabilityRegistry.slotMetadataList

    val extPluginStates: StateFlow<List<ExtensionPluginRegistry.PluginState>> = extensionRegistry.pluginStates

    // ---- 核心功能状态看板（人脸聚类 / 地图聚类 / 语义识别 / 换脸）----

    /**
     * 单个核心功能的模型就绪状态。
     *
     * @param featureId 功能标识：face / geo / semantic / face_swap
     * @param displayName 显示名称
     * @param icon emoji 图标
     * @param description 功能描述
     * @param readiness 模型就绪状态
     * @param modelDetail 模型名称或状态说明
     * @param actionable 是否可进入使用页面（仅换脸为 true）
     */
    data class CoreFeatureStatus(
        val featureId: String,
        val displayName: String,
        val icon: String,
        val description: String,
        val readiness: ModelReadiness,
        val modelDetail: String,
        val actionable: Boolean,
    )

    /**
     * 四大核心功能的聚合状态流。
     *
     * - 人脸聚类：取 `face` 槽位激活 Provider 的 [ModelReadiness]
     * - 地图聚类：纯 DBSCAN 算法，恒为 [ModelReadiness.ALWAYS_READY]
     * - 语义识别：取 `semantic` 槽位激活 Provider（EVA02-CLIP，内置即用）的状态
     * - 换脸：InSwapper 插件就绪 且 `face` 槽位就绪 时为 [ModelReadiness.READY]
     */
    val coreFeatureStatuses: StateFlow<List<CoreFeatureStatus>> =
        combine(slotMetadataList, extPluginStates) { slots, extStates ->
            buildCoreFeatureStatuses(slots, extStates)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun buildCoreFeatureStatuses(
        slots: List<SlotMetadata>,
        extStates: List<ExtensionPluginRegistry.PluginState>,
    ): List<CoreFeatureStatus> {
        val faceSlot = slots.firstOrNull { it.slotId == "face" }
        val semanticSlot = slots.firstOrNull { it.slotId == "semantic" }

        val faceActive = faceSlot?.availableProviders
            ?.firstOrNull { it.providerId == faceSlot.activeProviderId }
        val faceReadiness = faceActive?.modelStatus ?: ModelReadiness.NOT_DOWNLOADED

        val semanticActive = semanticSlot?.availableProviders
            ?.firstOrNull { it.providerId == semanticSlot.activeProviderId }
        val semanticReadiness = semanticActive?.modelStatus ?: ModelReadiness.NOT_DOWNLOADED

        // 换脸：InSwapper 内置扩展插件就绪 + 人脸能力就绪
        val inswapperReady = extStates.any {
            it.pluginId == "plugin.inswapper" && it.isReady
        }
        val faceSwapReadiness = when {
            inswapperReady && faceReadiness == ModelReadiness.READY -> ModelReadiness.READY
            faceReadiness == ModelReadiness.ERROR -> ModelReadiness.ERROR
            else -> ModelReadiness.NOT_DOWNLOADED
        }

        return listOf(
            CoreFeatureStatus(
                featureId = "face",
                displayName = "人脸聚类",
                icon = "🧑",
                description = "检测人脸并提取特征向量用于聚类",
                readiness = faceReadiness,
                modelDetail = faceActive?.displayName ?: "未配置",
                actionable = false,
            ),
            CoreFeatureStatus(
                featureId = "semantic",
                displayName = "语义识别",
                icon = "🔍",
                description = "EVA02-CLIP 跨模态语义嵌入，支持自然语言搜索",
                readiness = semanticReadiness,
                modelDetail = if (semanticReadiness == ModelReadiness.READY)
                    "EVA02-CLIP 已就绪" else "语义模型加载中",
                actionable = false,
            ),
            CoreFeatureStatus(
                featureId = "face_swap",
                displayName = "换脸功能",
                icon = "🔄",
                description = "InSwapper 换脸，需人脸检测与换脸模型均就绪",
                readiness = faceSwapReadiness,
                modelDetail = "inswapper_128.onnx",
                actionable = true,
            ),
        )
    }

    /**
     * 语义模型是否为内置 EVA02-CLIP（随包打包，无需外部推送）。
     */
    fun isSemanticBundled(): Boolean {
        val provider = capabilityRegistry.getActiveProvider<SemanticEmbedProvider>("semantic")
        return provider is Eva02ClipProvider
    }

    // ---- 换脸执行 ----

    /** 换脸操作 UI 状态 */
    sealed class FaceSwapUiState {
        data object Idle : FaceSwapUiState()
        data object Loading : FaceSwapUiState()
        data class Success(val bitmap: Bitmap) : FaceSwapUiState()
        data class Error(val message: String) : FaceSwapUiState()
    }

    private val _faceSwapState = MutableStateFlow<FaceSwapUiState>(FaceSwapUiState.Idle)
    val faceSwapState: StateFlow<FaceSwapUiState> = _faceSwapState.asStateFlow()

    /**
     * 执行换脸：将 [sourceFile] 中最大的人脸换到 [targetFile] 中最大的人脸上。
     *
     * 内部通过 [ExtensionPluginRegistry.getPlugin] 获取内置 InSwapper 插件，
     * 构造 [PluginInput.MultiModalInput]（源图 + 目标图）后调用 [GenerativePlugin.execute]。
     */
    fun performFaceSwap(sourceFile: File, targetFile: File) {
        viewModelScope.launch {
            _faceSwapState.value = FaceSwapUiState.Loading
            try {
                val plugin = extensionRegistry.getPlugin("plugin.inswapper")
                if (plugin == null || !plugin.isReady()) {
                    _faceSwapState.value = FaceSwapUiState.Error("换脸插件未就绪，请确认模型已加载")
                    return@launch
                }
                // N4: 换脸硬性依赖 5 关键点做仿射对齐。若当前激活的人脸 Provider 不支持关键点
                // （如 ML Kit 几何方案，其 DetectedFace.landmarks 为 null），换脸会在检测后静默返回原图。
                // 入口提前校验嵌入维度与关键点能力倾向，给出区分提示，避免用户看到笼统的"未检测到人脸"。
                val activeFace = capabilityRegistry.getActiveProvider<com.renyxin.localalbum.core.plugin.capability.FaceProvider>("face")
                if (activeFace == null) {
                    _faceSwapState.value = FaceSwapUiState.Error("换脸失败：未激活人脸检测 Provider")
                    return@launch
                }
                if (activeFace.embeddingDim != 512) {
                    _faceSwapState.value = FaceSwapUiState.Error(
                        "换脸失败：当前人脸 Provider「${activeFace.displayName}」嵌入维度 ${activeFace.embeddingDim}≠512，请切换为 InsightFace/ArcFace 系 Provider"
                    )
                    return@launch
                }
                val input = PluginInput.MultiModalInput(
                    listOf(
                        PluginInput.ImageInput(file = sourceFile),
                        PluginInput.ImageInput(file = targetFile),
                    )
                )
                val output = plugin.execute(input)
                val bitmap = (output as? PluginOutput.ImageOutput)?.bitmap
                if (bitmap != null) {
                    _faceSwapState.value = FaceSwapUiState.Success(bitmap)
                } else {
                    _faceSwapState.value = FaceSwapUiState.Error("换脸失败：未检测到人脸或推理失败")
                }
            } catch (e: Exception) {
                _faceSwapState.value = FaceSwapUiState.Error("换脸失败: ${e.message}")
            }
        }
    }

    /** 重置换脸状态 */
    fun resetFaceSwapState() {
        _faceSwapState.value = FaceSwapUiState.Idle
    }

    // ---- 存储管理 (Phase 4) ----

    val storageStats: StateFlow<ModelStorageManager.StorageStats> =
        modelStorageManager?.storageStats
            ?: MutableStateFlow(ModelStorageManager.StorageStats()).asStateFlow()

    val modelStorageDetails: StateFlow<List<ModelStorageManager.ModelStorageInfo>> =
        modelStorageManager?.modelDetails
            ?: MutableStateFlow<List<ModelStorageManager.ModelStorageInfo>>(emptyList()).asStateFlow()

    fun refreshStorageStats() {
        modelStorageManager?.refresh()
    }

    // ---- 下载进度 (Phase 5b) ----

    /** 下载状态枚举 */
    enum class DownloadStatus { IDLE, DOWNLOADING, VERIFYING, INSTALLING, DONE, ERROR }

    /** 单个模型的下载进度 */
    data class DownloadProgress(
        val modelId: String,
        val status: DownloadStatus = DownloadStatus.IDLE,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val errorMessage: String? = null,
        val slotId: String? = null,
        val displayName: String? = null,
    ) {
        val progress: Float get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f
        val isActive: Boolean get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.VERIFYING || status == DownloadStatus.INSTALLING
        val percentText: String get() = if (totalBytes > 0) "${(progress * 100).toInt()}%" else ""
    }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val downloadStates: StateFlow<Map<String, DownloadProgress>> = _downloadStates.asStateFlow()

    /** 活跃下载 Job 映射（key 与 _downloadStates 一致），用于真正取消底层下载协程 */
    private val downloadJobs = mutableMapOf<String, Job>()

    /** 活跃下载数 */
    val activeDownloadCount: Int
        get() = _downloadStates.value.values.count { it.isActive }

    // ---- 模型目录浏览 (Phase 5a+) ----

    private val _modelCatalogList = MutableStateFlow<List<ModelInfo>>(emptyList())
    val modelCatalogList: StateFlow<List<ModelInfo>> = _modelCatalogList.asStateFlow()

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    /** 加载模型目录（本地 + 远程） */
    fun loadModelCatalog(query: String? = null) {
        viewModelScope.launch {
            _catalogLoading.value = true
            try {
                val catalog = modelCatalog ?: return@launch
                val models = if (query.isNullOrBlank()) catalog.listModels()
                else catalog.search(query)
                _modelCatalogList.value = models
            } catch (e: Exception) {
                _message.value = "加载模型目录失败: ${e.message}"
            } finally {
                _catalogLoading.value = false
            }
        }
    }

    /** 取消正在进行的下载 */
    fun cancelDownload(modelId: String) {
        // 真正取消底层下载协程，避免「取消」后下载仍在继续
        downloadJobs.remove(modelId)?.cancel()
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = (current[modelId] ?: DownloadProgress(modelId)).copy(
            status = DownloadStatus.ERROR,
            errorMessage = "用户取消",
        )
        _downloadStates.value = current
    }

    private fun updateDownloadState(modelId: String, status: DownloadStatus, downloaded: Long = 0, total: Long = 0, error: String? = null) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = DownloadProgress(modelId, status, downloaded, total, error)
        _downloadStates.value = current
    }

    /** 清理模型缓存 */
    fun clearModelCache() {
        modelStorageManager?.clearCache()
        _message.value = "模型缓存已清理"
    }

    /**
     * 从模型目录下载模型（Phase 5a+ 增强）。
     *
     * 使用 [ModelInfo] 中的元数据自动填充下载参数，
     * 并通过 ProgressCallback 实时更新 [downloadStates]。
     */
    fun downloadModel(model: ModelInfo) {
        val modelId = model.modelId
        val pluginId = "model:$modelId"
        val fileName = model.localFilePath?.substringAfterLast("/")
            ?: "${model.modelId}.${model.format.name.lowercase()}"
        val url = model.downloadUrl ?: run {
            _message.value = "模型无下载地址: ${model.displayName}"
            return
        }

        downloadJobs[modelId] = viewModelScope.launch {
            // 下载状态统一以 model.modelId 为键，与 ModelMarketTab 的查询/取消键一致
            updateDownloadState(modelId, DownloadStatus.DOWNLOADING, 0, model.fileSizeBytes)
            _message.value = "正在下载: ${model.displayName}..."

            try {
                val dm = modelDownloadManager
                if (dm == null) {
                    updateDownloadState(modelId, DownloadStatus.ERROR, error = "下载管理器未初始化")
                    return@launch
                }

                val modelFile = dm.ensureModel(
                    modelFileName = fileName,
                    url = url,
                    expectedSha256 = model.sha256,
                    progress = { downloaded, total ->
                        updateDownloadState(modelId, DownloadStatus.DOWNLOADING, downloaded, total)
                    },
                )

                if (modelFile == null) {
                    updateDownloadState(modelId, DownloadStatus.ERROR, error = "下载失败")
                    _message.value = "模型下载失败: ${model.displayName}"
                    return@launch
                }

                // 验证阶段
                updateDownloadState(modelId, DownloadStatus.VERIFYING,
                    model.fileSizeBytes.coerceAtLeast(0), model.fileSizeBytes)

                // 安装阶段
                updateDownloadState(modelId, DownloadStatus.INSTALLING)
                val manifestJson = PluginJsonCodec.manifestToJson(
                    PluginManifest(
                        pluginId = pluginId,
                        name = model.displayName,
                        version = model.version,
                        author = model.author,
                        taskType = PluginManifest.TaskType.FEATURE_EXTRACTION,
                        modelFormat = model.format,
                        modelFilePath = fileName,
                        entryClass = "",
                        inputTensors = emptyList(),
                        outputTensors = emptyList(),
                        description = model.description.ifBlank { null },
                    )
                )

                val existing = pluginDao.getByPluginId(pluginId)
                pluginDao.insert(
                    PluginManifestEntity(
                        id = existing?.id ?: 0,
                        pluginId = pluginId,
                        manifestJson = manifestJson,
                        isEnabled = true,
                        pipelineStage = "analysis",
                        createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                )

                modelStorageManager?.refresh()
                reloadPluginRegistry()
                updateDownloadState(modelId, DownloadStatus.DONE)
                _message.value = "模型安装成功: ${model.displayName}"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateDownloadState(modelId, DownloadStatus.ERROR, error = e.message)
                _message.value = "安装失败: ${e.message}"
                try { modelStorageManager?.deleteModel(fileName) } catch (_: Exception) {}
            }
        }
    }

    /**
     * 从 URL 下载并安装模型（Phase 4 增强 + 进度回调）。
     */
    fun installModelFromUrl(
        pluginId: String,
        displayName: String,
        downloadUrl: String,
        expectedSha256: String? = null,
        modelFileName: String = "$pluginId.tflite",
    ) {
        downloadJobs[pluginId] = viewModelScope.launch {
            _message.value = "正在下载模型: $displayName..."
            updateDownloadState(pluginId, DownloadStatus.DOWNLOADING, 0, -1)
            try {
                val dm = modelDownloadManager
                if (dm == null) {
                    _message.value = "下载管理器未初始化"
                    updateDownloadState(pluginId, DownloadStatus.ERROR, error = "下载管理器未初始化")
                    return@launch
                }

                val modelFile = dm.ensureModel(
                    modelFileName = modelFileName,
                    url = downloadUrl,
                    expectedSha256 = expectedSha256,
                    progress = { downloaded, total ->
                        updateDownloadState(pluginId, DownloadStatus.DOWNLOADING, downloaded, total)
                    },
                )

                if (modelFile == null) {
                    _message.value = "模型下载失败: $displayName"
                    updateDownloadState(pluginId, DownloadStatus.ERROR, error = "下载失败")
                    return@launch
                }

                updateDownloadState(pluginId, DownloadStatus.INSTALLING)

                val manifestJson = PluginJsonCodec.manifestToJson(
                    PluginManifest(
                        pluginId = pluginId,
                        name = displayName,
                        taskType = PluginManifest.TaskType.FEATURE_EXTRACTION,
                        modelFormat = PluginManifest.ModelFormat.TFLITE,
                        modelFilePath = modelFileName,
                        entryClass = "",
                        inputTensors = emptyList(),
                        outputTensors = emptyList(),
                        description = "从 $downloadUrl 下载",
                    )
                )

                val existing = pluginDao.getByPluginId(pluginId)
                pluginDao.insert(
                    PluginManifestEntity(
                        id = existing?.id ?: 0,
                        pluginId = pluginId,
                        manifestJson = manifestJson,
                        isEnabled = true,
                        pipelineStage = "analysis",
                        createdAtMs = existing?.createdAtMs ?: System.currentTimeMillis(),
                        updatedAtMs = System.currentTimeMillis(),
                    )
                )

                modelStorageManager?.refresh()
                reloadPluginRegistry()
                updateDownloadState(pluginId, DownloadStatus.DONE)
                _message.value = "模型安装成功: $displayName"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _message.value = "安装失败: ${e.message}"
                updateDownloadState(pluginId, DownloadStatus.ERROR, error = e.message)
                try { modelStorageManager?.deleteModel(modelFileName) } catch (_: Exception) {}
            }
        }
    }

    /** 通用的槽位 Provider 激活方法（增强版：自动触发模型下载）。
     *
     * 对于 MODEL 类型 Provider：
     * - 若模型已就绪 → 直接激活
     * - 若模型未下载/出错 → 先触发下载，下载成功后自动激活
     * - 若正在下载中 → 等待下载完成后自动激活
     *
     * 对于 BUILTIN/DEMO 类型 Provider → 直接激活。
     */
    fun activateProvider(slotId: String, providerId: String) {
        val providerType = capabilityRegistry.getProviderType(slotId, providerId)

        when (providerType) {
            com.renyxin.localalbum.core.plugin.capability.ProviderType.MODEL -> {
                val readiness = capabilityRegistry.getProviderModelStatus(slotId, providerId)
                when (readiness) {
                    com.renyxin.localalbum.core.plugin.capability.ModelReadiness.READY,
                    com.renyxin.localalbum.core.plugin.capability.ModelReadiness.ALWAYS_READY -> {
                        capabilityRegistry.activateProvider(slotId, providerId)
                        _message.value = "已激活: ${getProviderDisplayName(slotId, providerId)}"
                    }
                    com.renyxin.localalbum.core.plugin.capability.ModelReadiness.DOWNLOADING -> {
                        _message.value = "模型下载中，下载完成后将自动激活"
                    }
                    else -> {
                        _message.value = "正在准备模型，下载完成后将自动激活…"
                        triggerModelDownload(slotId, providerId, onReady = {
                            capabilityRegistry.activateProvider(slotId, providerId)
                            _message.value = "模型就绪并已激活: ${getProviderDisplayName(slotId, providerId)}"
                        })
                    }
                }
            }
            null -> {
                _message.value = "槽位或 Provider 不存在: $slotId/$providerId"
            }
            else -> {
                val success = capabilityRegistry.activateProvider(slotId, providerId)
                if (success) {
                    _message.value = "已激活: ${getProviderDisplayName(slotId, providerId)}"
                } else {
                    _message.value = "激活失败: Provider 不存在于槽位 $slotId"
                }
            }
        }
    }

    /** 获取 Provider 的显示名称（用于消息提示） */
    private fun getProviderDisplayName(slotId: String, providerId: String): String {
        val slotMeta = capabilityRegistry.slotMetadataList.value.find { it.slotId == slotId }
        return slotMeta?.availableProviders?.find { it.providerId == providerId }?.displayName
            ?: providerId
    }

    /**
     * 为指定 Provider 触发模型下载（供 UI 调用）。
     *
     * @param slotId 槽位 ID
     * @param providerId Provider ID（即 modelId）
     */
    fun downloadModelForProvider(slotId: String, providerId: String) {
        triggerModelDownload(slotId, providerId)
    }

    /**
     * 重试下载失败的模型。
     */
    fun retryModelDownload(slotId: String, providerId: String) {
        triggerModelDownload(slotId, providerId)
    }

    /**
     * 一键下载所有核心模型（跨所有槽位中 MODEL 类型且未下载的 Provider）。
     */
    fun downloadAllCoreModels() {
        viewModelScope.launch {
            val slotList = capabilityRegistry.slotMetadataList.value
            var started = 0
            for (meta in slotList) {
                for (provider in meta.availableProviders) {
                    if (provider.providerType == com.renyxin.localalbum.core.plugin.capability.ProviderType.MODEL &&
                        (provider.modelStatus == com.renyxin.localalbum.core.plugin.capability.ModelReadiness.NOT_DOWNLOADED ||
                            provider.modelStatus == com.renyxin.localalbum.core.plugin.capability.ModelReadiness.ERROR)
                    ) {
                        triggerModelDownload(meta.slotId, provider.providerId)
                        started++
                    }
                }
            }
            if (started == 0) {
                _message.value = "所有模型已就绪，无需下载"
            } else {
                _message.value = "已开始下载 $started 个模型，请在列表查看进度"
            }
        }
    }

    /**
     * 内部实现：通过 ModelManager 确保模型就绪，并同步状态。
     *
     * @param slotId 槽位 ID
     * @param providerId Provider ID
     * @param onReady 模型下载并加载成功后执行的回调（例如自动激活 Provider）
     */
    private fun triggerModelDownload(
        slotId: String,
        providerId: String,
        onReady: (() -> Unit)? = null,
    ) {
        downloadJobs[providerId] = viewModelScope.launch {
            val mm = modelManager
            if (mm == null) {
                _message.value = "模型管理器未初始化"
                return@launch
            }

            // 从 slotMetadataList 获取显示名称
            val slotMeta = capabilityRegistry.slotMetadataList.value.find { it.slotId == slotId }
            val displayName = slotMeta?.availableProviders
                ?.find { it.providerId == providerId }?.displayName ?: providerId

            capabilityRegistry.updateProviderModelStatus(
                slotId, providerId,
                com.renyxin.localalbum.core.plugin.capability.ModelReadiness.DOWNLOADING,
            )

            updateDownloadState(
                providerId,
                DownloadStatus.DOWNLOADING,
                slotId = slotId,
                displayName = displayName,
            )
            _message.value = "正在下载模型: $displayName..."

            try {
                // 获取 providerId 关联的所有底层 modelIds（支持多模型 Provider）
                val modelIds = capabilityRegistry.getProviderModelIds(providerId)
                var lastError: String? = null
                for (modelId in modelIds) {
                    val result = mm.ensureModelReady(modelId)
                    if (result.isFailure) {
                        lastError = result.exceptionOrNull()?.message ?: "下载失败"
                        break
                    }
                }
                if (lastError == null) {
                    capabilityRegistry.updateProviderModelStatus(
                        slotId, providerId,
                        com.renyxin.localalbum.core.plugin.capability.ModelReadiness.READY,
                    )
                    updateDownloadState(
                        providerId,
                        DownloadStatus.DONE,
                        slotId = slotId,
                        displayName = displayName,
                    )
                    _message.value = "模型就绪: $displayName"
                    // 执行下载成功回调（如自动激活 Provider）
                    onReady?.invoke()
                } else {
                    capabilityRegistry.updateProviderModelStatus(
                        slotId, providerId,
                        com.renyxin.localalbum.core.plugin.capability.ModelReadiness.ERROR,
                    )
                    updateDownloadState(
                        providerId,
                        DownloadStatus.ERROR,
                        slotId = slotId,
                        displayName = displayName,
                        error = lastError,
                    )
                    _message.value = "模型下载失败: $displayName — $lastError"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                capabilityRegistry.updateProviderModelStatus(
                    slotId, providerId,
                    com.renyxin.localalbum.core.plugin.capability.ModelReadiness.ERROR,
                )
                updateDownloadState(
                    providerId,
                    DownloadStatus.ERROR,
                    slotId = slotId,
                    displayName = displayName,
                    error = e.message,
                )
                _message.value = "模型下载异常: ${e.message}"
            }
        }
    }

    private fun updateDownloadState(
        modelId: String,
        status: DownloadStatus,
        slotId: String? = null,
        displayName: String? = null,
        downloaded: Long = 0,
        total: Long = 0,
        error: String? = null,
    ) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = DownloadProgress(modelId, status, downloaded, total, error, slotId, displayName)
        _downloadStates.value = current
    }

    // ---- Factory ----

    class Factory(
        private val appContext: Context,
        private val database: AppDatabase,
        private val capabilityRegistry: CapabilityRegistryV2,
        private val extensionRegistry: ExtensionPluginRegistry,
        private val modelManager: com.renyxin.localalbum.core.plugin.model.ModelManager? = null,
        private val modelStorageManager: ModelStorageManager? = null,
        private val modelDownloadManager: ModelDownloadManagerV2? = null,
        private val modelCatalog: ModelCatalog? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PluginViewModel::class.java)) {
                return PluginViewModel(
                    appContext, database, capabilityRegistry, extensionRegistry,
                    modelManager, modelStorageManager, modelDownloadManager, modelCatalog,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

// ---- State Classes ----

/** 张量解析状态 */
sealed class TensorParseState {
    object Idle : TensorParseState()
    object Loading : TensorParseState()
    data class Success(val inputTensors: List<TensorSpec>, val outputTensors: List<TensorSpec>) : TensorParseState()
    data class Error(val message: String) : TensorParseState()
}

/** JSON 校验状态 */
sealed class JsonValidationState {
    object Idle : JsonValidationState()
    data class Valid(val manifest: PluginManifest) : JsonValidationState()
    data class Error(val message: String) : JsonValidationState()
}

/** 保存操作状态 */
sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    data class Success(val message: String) : SaveState()
    data class Error(val message: String) : SaveState()
}
