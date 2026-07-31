package com.renyxin.localalbum.core.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * 插件配置 JSON 序列化/反序列化工具（Phase 1.4）。
 *
 * 提供 [PluginManifest] 和 [FeatureSchema] 与 JSON 之间的双向转换，
 * 用于：
 * 1. 可视化向导模式：解析 model_manifest.json → [PluginManifest]
 * 2. 高级 JSON 模式：用户编写的 JSON → 校验 → [PluginManifest]
 * 3. 异构存储：[FeatureSchema] ↔ JSON Blob 持久化到 feature_store 表
 *
 * 使用 org.json（Android 内置 + 测试依赖已有），无需引入额外序列化库。
 */
object PluginJsonCodec {

    // ---- PluginManifest ----

    /**
     * 将 [PluginManifest] 序列化为 JSON 字符串。
     */
    fun manifestToJson(manifest: PluginManifest): String {
        return manifestToJsonObj(manifest).toString(2)
    }

    /**
     * 将 [PluginManifest] 序列化为 [JSONObject]（内部复用）。
     */
    fun manifestToJsonObj(manifest: PluginManifest): JSONObject {
        return JSONObject().apply {
            put("pluginId", manifest.pluginId)
            put("name", manifest.name)
            put("version", manifest.version)
            put("author", manifest.author)
            put("taskType", manifest.taskType.name)
            put("modelFormat", manifest.modelFormat.name)
            put("modelFilePath", manifest.modelFilePath)
            put("entryClass", manifest.entryClass)
            put("inputTensors", JSONArray(manifest.inputTensors.map { tensorSpecToJson(it) }))
            put("outputTensors", JSONArray(manifest.outputTensors.map { tensorSpecToJson(it) }))
            put("preprocessing", preprocessingToJson(manifest.preprocessing))
            put("postprocessing", postprocessingToJson(manifest.postprocessing))
            manifest.description?.let { put("description", it) }
            put("enabled", manifest.enabled)
            put("pipelineStage", manifest.pipelineStage)
            if (manifest.dependsOn.isNotEmpty()) {
                put("dependsOn", JSONArray(manifest.dependsOn))
            }
            manifest.authorizedCertificateFingerprint?.let {
                put("authorizedCertificateFingerprint", it)
            }
        }
    }

    /**
     * 从 JSON 字符串反序列化为 [PluginManifest]。
     *
     * @throws PluginConfigException 当 JSON 格式错误或必填字段缺失时抛出
     */
    fun manifestFromJson(json: String): PluginManifest {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw PluginConfigException("JSON 解析失败: ${e.message}", e)
        }
        return manifestFromJsonObj(obj)
    }

    /**
     * 从 [JSONObject] 反序列化为 [PluginManifest]。
     */
    fun manifestFromJsonObj(obj: JSONObject): PluginManifest {
        val pluginId = obj.requireString("pluginId")
        val name = obj.requireString("name")
        val taskTypeStr = obj.requireString("taskType")
        val taskType = try {
            PluginManifest.TaskType.valueOf(taskTypeStr)
        } catch (e: IllegalArgumentException) {
            throw PluginConfigException("未知的 taskType: $taskTypeStr，支持值: ${PluginManifest.TaskType.values().joinToString()}")
        }
        val modelFormatStr = obj.requireString("modelFormat")
        val modelFormat = try {
            PluginManifest.ModelFormat.valueOf(modelFormatStr)
        } catch (e: IllegalArgumentException) {
            throw PluginConfigException("未知的 modelFormat: $modelFormatStr，支持值: ${PluginManifest.ModelFormat.values().joinToString()}")
        }

        val inputTensors = obj.optJsonArray("inputTensors")?.mapNotNull {
            tensorSpecFromJson(it as JSONObject)
        } ?: emptyList()

        val outputTensors = obj.optJsonArray("outputTensors")?.mapNotNull {
            tensorSpecFromJson(it as JSONObject)
        } ?: emptyList()

        val preprocessing = obj.optJSONObject("preprocessing")?.let { preprocessingFromJson(it) }
            ?: PluginManifest.PreprocessingConfig()

        val postprocessing = obj.optJSONObject("postprocessing")?.let { postprocessingFromJson(it) }
            ?: PluginManifest.PostprocessingConfig()

        val dependsOn = obj.optJsonArray("dependsOn")?.map { it.toString() } ?: emptyList()

        return PluginManifest(
            pluginId = pluginId,
            name = name,
            version = obj.optString("version", "1.0.0"),
            author = obj.optString("author", ""),
            taskType = taskType,
            modelFormat = modelFormat,
            modelFilePath = obj.requireString("modelFilePath"),
            entryClass = obj.requireString("entryClass"),
            inputTensors = inputTensors,
            outputTensors = outputTensors,
            preprocessing = preprocessing,
            postprocessing = postprocessing,
            description = obj.optNullableString("description"),
            enabled = obj.optBoolean("enabled", true),
            pipelineStage = obj.optString("pipelineStage", taskType.stageName),
            dependsOn = dependsOn,
            authorizedCertificateFingerprint = obj.optNullableString("authorizedCertificateFingerprint"),
        )
    }

    // ---- TensorSpec ----

    private fun tensorSpecToJson(spec: PluginManifest.TensorSpec): JSONObject {
        return JSONObject().apply {
            put("name", spec.name)
            put("dataType", spec.dataType.name)
            put("shape", JSONArray(spec.shape))
            spec.quantizationParams?.let { qp ->
                put("quantizationParams", JSONObject().apply {
                    put("scale", qp.scale)
                    put("zeroPoint", qp.zeroPoint)
                })
            }
        }
    }

    private fun tensorSpecFromJson(obj: JSONObject): PluginManifest.TensorSpec {
        val dataTypeStr = obj.requireString("dataType")
        val dataType = try {
            PluginManifest.TensorDataType.valueOf(dataTypeStr)
        } catch (e: IllegalArgumentException) {
            throw PluginConfigException("未知的 dataType: $dataTypeStr")
        }
        val shapeList = obj.optJsonArray("shape")?.map { (it as Number).toInt() } ?: emptyList()
        val qpObj = obj.optJSONObject("quantizationParams")
        val qp = qpObj?.let {
            PluginManifest.QuantizationParams(
                scale = it.getDouble("scale").toFloat(),
                zeroPoint = it.getLong("zeroPoint"),
            )
        }
        return PluginManifest.TensorSpec(
            name = obj.requireString("name"),
            dataType = dataType,
            shape = shapeList,
            quantizationParams = qp,
        )
    }

    // ---- Preprocessing / Postprocessing ----

    private fun preprocessingToJson(config: PluginManifest.PreprocessingConfig): JSONObject {
        return JSONObject().apply {
            put("resizeWidth", config.resizeWidth)
            put("resizeHeight", config.resizeHeight)
            put("normalizeMean", JSONArray(config.normalizeMean))
            put("normalizeStd", JSONArray(config.normalizeStd))
            put("bgrToRgb", config.bgrToRgb)
            put("cropToSquare", config.cropToSquare)
        }
    }

    private fun preprocessingFromJson(obj: JSONObject): PluginManifest.PreprocessingConfig {
        return PluginManifest.PreprocessingConfig(
            resizeWidth = obj.optInt("resizeWidth", 0),
            resizeHeight = obj.optInt("resizeHeight", 0),
            normalizeMean = obj.optJsonArray("normalizeMean")?.map { (it as Number).toFloat() }
                ?: listOf(0f, 0f, 0f),
            normalizeStd = obj.optJsonArray("normalizeStd")?.map { (it as Number).toFloat() }
                ?: listOf(1f, 1f, 1f),
            bgrToRgb = obj.optBoolean("bgrToRgb", false),
            cropToSquare = obj.optBoolean("cropToSquare", false),
        )
    }

    private fun postprocessingToJson(config: PluginManifest.PostprocessingConfig): JSONObject {
        return JSONObject().apply {
            put("nmsThreshold", config.nmsThreshold)
            put("confidenceThreshold", config.confidenceThreshold)
            config.labelMapPath?.let { put("labelMapPath", it) }
            put("topK", config.topK)
        }
    }

    private fun postprocessingFromJson(obj: JSONObject): PluginManifest.PostprocessingConfig {
        return PluginManifest.PostprocessingConfig(
            nmsThreshold = obj.optDouble("nmsThreshold", 0.0).toFloat(),
            confidenceThreshold = obj.optDouble("confidenceThreshold", 0.0).toFloat(),
            labelMapPath = obj.optNullableString("labelMapPath"),
            topK = obj.optInt("topK", 0),
        )
    }

    // ---- FeatureSchema ----

    /**
     * 将 [FeatureSchema] 序列化为 JSON 字符串（用于 feature_store.schemaJson）。
     */
    fun schemaToJson(schema: FeatureSchema): String {
        return schemaToJsonObj(schema).toString()
    }

    /**
     * 将 [FeatureSchema] 序列化为 [JSONObject]。
     */
    fun schemaToJsonObj(schema: FeatureSchema): JSONObject {
        return JSONObject().apply {
            put("pluginId", schema.pluginId)
            put("featureType", schema.featureType)
            put("modelVersion", schema.modelVersion)
            put("fields", JSONArray(schema.fields.map { fieldSpecToJson(it) }))
        }
    }

    /**
     * 从 JSON 字符串反序列化为 [FeatureSchema]。
     */
    fun schemaFromJson(json: String): FeatureSchema {
        val obj = JSONObject(json)
        return schemaFromJsonObj(obj)
    }

    /**
     * 从 [JSONObject] 反序列化为 [FeatureSchema]。
     */
    fun schemaFromJsonObj(obj: JSONObject): FeatureSchema {
        val fields = obj.optJsonArray("fields")?.mapNotNull {
            fieldSpecFromJson(it as JSONObject)
        } ?: emptyList()

        return FeatureSchema(
            pluginId = obj.requireString("pluginId"),
            featureType = obj.requireString("featureType"),
            fields = fields,
            modelVersion = obj.optInt("modelVersion", 1),
        )
    }

    private fun fieldSpecToJson(spec: FeatureSchema.FieldSpec): JSONObject {
        return JSONObject().apply {
            put("name", spec.name)
            put("dataType", spec.dataType.name)
            spec.dims?.let { put("dims", JSONArray(it)) }
            spec.description?.let { put("description", it) }
        }
    }

    private fun fieldSpecFromJson(obj: JSONObject): FeatureSchema.FieldSpec {
        val dataTypeStr = obj.requireString("dataType")
        val dataType = try {
            FeatureSchema.DataType.valueOf(dataTypeStr)
        } catch (e: IllegalArgumentException) {
            throw PluginConfigException("未知的 dataType: $dataTypeStr")
        }
        val dims = obj.optJsonArray("dims")?.map { (it as Number).toInt() }
        return FeatureSchema.FieldSpec(
            name = obj.requireString("name"),
            dataType = dataType,
            dims = dims,
            description = obj.optNullableString("description"),
        )
    }

    // ---- Feature data (FloatArray) ----

    /**
     * 将浮点向量序列化为 JSON 字符串（用于 feature_store.dataJson）。
     *
     * 格式：{"vector": [0.1, 0.2, ...], "metadata": {"key": "value"}}
     */
    fun featureDataToJson(vector: FloatArray, metadata: Map<String, String> = emptyMap()): String {
        return JSONObject().apply {
            put("vector", JSONArray(vector.map { it.toDouble() }))
            if (metadata.isNotEmpty()) {
                put("metadata", JSONObject(metadata))
            }
        }.toString()
    }

    /**
     * 从 JSON 字符串反序列化浮点向量。
     *
     * @return Pair(vector, metadata)
     */
    fun featureDataFromJson(json: String): Pair<FloatArray, Map<String, String>> {
        val obj = JSONObject(json)
        val vectorArr = obj.optJsonArray("vector")
        val vector = vectorArr?.map { (it as Number).toFloat() }?.toFloatArray() ?: FloatArray(0)
        val metaObj = obj.optJSONObject("metadata")
        val metadata = if (metaObj != null) {
            metaObj.keys().asSequence().associateWith { metaObj.getString(it) }
        } else emptyMap()
        return Pair(vector, metadata)
    }

    // ---- PluginOutput ----

    /**
     * 将 [PluginOutput] 序列化为通用 JSON Blob 字符串。
     *
     * 不同类型输出采用不同 JSON 结构：
     * - ClassificationOutput → {"type":"classification","labels":[...]}
     * - FeatureOutput → {"type":"feature","vector":[...],"dim":N,"metadata":{...}}
     * - DetectionOutput → {"type":"detection","boxes":[...],"imagePath":"..."}
     * - ImageOutput → {"type":"image","outputPath":"...","sourcePath":"..."}
     */
    fun pluginOutputToJson(output: PluginOutput): String {
        return when (output) {
            is PluginOutput.ClassificationOutput -> JSONObject().apply {
                put("type", "classification")
                put("labels", JSONArray(output.labels.map { label ->
                    JSONObject().apply {
                        put("label", label.label)
                        put("confidence", label.confidence.toDouble())
                    }
                }))
            }.toString()

            is PluginOutput.FeatureOutput -> JSONObject().apply {
                put("type", "feature")
                put("vector", JSONArray(output.vector.toList()))
                put("metadata", JSONObject(output.metadata))
            }.toString()

            is PluginOutput.DetectionOutput -> JSONObject().apply {
                put("type", "detection")
                put("imagePath", output.imagePath)
                put("boxes", JSONArray(output.boxes.map { box ->
                    JSONObject().apply {
                        put("label", box.label)
                        put("confidence", box.confidence.toDouble())
                        put("left", box.box.left.toDouble())
                        put("top", box.box.top.toDouble())
                        put("right", box.box.right.toDouble())
                        put("bottom", box.box.bottom.toDouble())
                        box.embedding?.let { emb ->
                            put("embedding", JSONArray(emb.toList()))
                        }
                    }
                }))
            }.toString()

            is PluginOutput.ImageOutput -> JSONObject().apply {
                put("type", "image")
                output.outputPath?.let { put("outputPath", it) }
                output.sourcePath.let { put("sourcePath", it) }
            }.toString()
        }
    }

    /**
     * 从 JSON 字符串反序列化为 [PluginOutput]。
     *
     * @throws PluginConfigException 当 JSON 格式错误或类型字段缺失时抛出
     */
    fun pluginOutputFromJson(json: String): PluginOutput {
        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw PluginConfigException("PluginOutput JSON 解析失败: ${e.message}", e)
        }
        val type = obj.requireString("type")
        return when (type) {
            "classification" -> {
                val labels = obj.optJsonArray("labels")?.map { labelObj ->
                    val l = labelObj as JSONObject
                    PluginOutput.LabelResult(
                        label = l.requireString("label"),
                        confidence = l.getDouble("confidence").toFloat(),
                    )
                } ?: emptyList()
                PluginOutput.ClassificationOutput(labels = labels)
            }
            "feature" -> {
                val vector = obj.optJsonArray("vector")?.map {
                    (it as Number).toFloat()
                }?.toFloatArray() ?: FloatArray(0)
                val metaObj = obj.optJSONObject("metadata")
                val metadata = if (metaObj != null) {
                    metaObj.keys().asSequence().associateWith { metaObj.getString(it) }
                } else emptyMap()
                PluginOutput.FeatureOutput(
                    vector = vector,
                    schema = FeatureSchema(pluginId = "", featureType = "", fields = emptyList()),
                    metadata = metadata,
                )
            }
            "detection" -> {
                val imagePath = obj.requireString("imagePath")
                val boxes = obj.optJsonArray("boxes")?.map { boxObj ->
                    val b = boxObj as JSONObject
                    val embeddingArr = b.optJsonArray("embedding")
                    val embedding = embeddingArr?.map { (it as Number).toFloat() }?.toFloatArray()
                    PluginOutput.DetectedBox(
                        box = android.graphics.RectF(
                            b.getDouble("left").toFloat(),
                            b.getDouble("top").toFloat(),
                            b.getDouble("right").toFloat(),
                            b.getDouble("bottom").toFloat(),
                        ),
                        label = b.requireString("label"),
                        confidence = b.getDouble("confidence").toFloat(),
                        embedding = embedding,
                    )
                } ?: emptyList()
                PluginOutput.DetectionOutput(boxes = boxes, imagePath = imagePath)
            }
            "image" -> {
                PluginOutput.ImageOutput(
                    bitmap = null,
                    outputPath = obj.optNullableString("outputPath"),
                    sourcePath = obj.requireString("sourcePath"),
                )
            }
            else -> throw PluginConfigException("未知的 PluginOutput type: $type")
        }
    }

    // ---- 校验 ----

    /**
     * 校验 model_manifest.json 字符串的格式完整性。
     *
     * @return null 表示校验通过，否则返回错误描述
     */
    fun validateManifest(json: String): String? {
        return try {
            val manifest = manifestFromJson(json)
            validateManifest(manifest)
        } catch (e: PluginConfigException) {
            e.message
        } catch (e: Exception) {
            "未知错误: ${e.message}"
        }
    }

    /**
     * 校验 [PluginManifest] 对象的完整性。
     *
     * @return null 表示校验通过，否则返回错误描述
     */
    fun validateManifest(manifest: PluginManifest): String? {
        if (manifest.pluginId.isBlank()) return "pluginId 不能为空"
        if (manifest.name.isBlank()) return "name 不能为空"
        if (manifest.modelFilePath.isBlank()) return "modelFilePath 不能为空"
        if (manifest.entryClass.isBlank()) return "entryClass 不能为空"
        if (manifest.inputTensors.isEmpty()) return "inputTensors 不能为空"
        if (manifest.outputTensors.isEmpty()) return "outputTensors 不能为空"
        for (tensor in manifest.inputTensors) {
            if (tensor.shape.isEmpty()) return "输入张量 ${tensor.name} 的 shape 不能为空"
        }
        for (tensor in manifest.outputTensors) {
            if (tensor.shape.isEmpty()) return "输出张量 ${tensor.name} 的 shape 不能为空"
        }
        return null
    }

    // ---- 辅助扩展 ----

    /** 获取必填 String 字段，缺失时抛出 [PluginConfigException]。 */
    private fun JSONObject.requireString(key: String): String {
        if (!has(key) || isNull(key)) {
            throw PluginConfigException("缺少必填字段: $key")
        }
        return getString(key)
    }

    /** 安全获取可空 String 字段，字段缺失或 JSON null 时返回 null。 */
    private fun JSONObject.optNullableString(key: String): String? {
        return if (has(key) && !isNull(key)) getString(key) else null
    }

    /** 安全获取 JSONArray，不存在或类型不符时返回 null。 */
    private fun JSONObject.optJsonArray(key: String): JSONArray? {
        return if (has(key) && !isNull(key)) optJSONArray(key) else null
    }

    /** JSONArray 的 map 扩展（org.json 原生不支持）。 */
    private fun <T> JSONArray.map(transform: (Any) -> T): List<T> {
        return (0 until length()).map { transform(get(it)) }
    }

    /** JSONArray 的 mapNotNull 扩展（org.json 原生不支持）。 */
    private fun <T : Any> JSONArray.mapNotNull(transform: (Any) -> T?): List<T> {
        return (0 until length()).mapNotNull { transform(get(it)) }
    }
}

/**
 * 插件配置异常。
 *
 * 在 JSON 解析、字段校验失败时抛出，携带人类可读的错误描述。
 */
class PluginConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)
