package com.renyxin.localalbum.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PluginJsonCodec] 单元测试（Phase 1.5）。
 *
 * 测试 [PluginManifest] 和 [FeatureSchema] 的 JSON 序列化/反序列化往返（round-trip）、
 * 字段校验、异常处理等纯逻辑。
 */
class PluginJsonCodecTest {

    // ---- PluginManifest 序列化/反序列化 ----

    @Test
    fun `manifest round-trip preserves all fields`() {
        val original = PluginManifest(
            pluginId = "face_swap_v1",
            name = "人脸换脸插件",
            version = "2.1.0",
            author = "TestAuthor",
            taskType = PluginManifest.TaskType.GENERATIVE,
            modelFormat = PluginManifest.ModelFormat.ONNX,
            modelFilePath = "models/face_swap.onnx",
            entryClass = "com.example.FaceSwapPlugin",
            inputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "input_image",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 256, 256, 3),
                ),
                PluginManifest.TensorSpec(
                    name = "face_embedding",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 512),
                ),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "output_image",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 256, 256, 3),
                ),
            ),
            preprocessing = PluginManifest.PreprocessingConfig(
                resizeWidth = 256,
                resizeHeight = 256,
                normalizeMean = listOf(0.5f, 0.5f, 0.5f),
                normalizeStd = listOf(0.5f, 0.5f, 0.5f),
                bgrToRgb = true,
                cropToSquare = true,
            ),
            postprocessing = PluginManifest.PostprocessingConfig(
                nmsThreshold = 0.4f,
                confidenceThreshold = 0.5f,
                labelMapPath = "labels/labels.txt",
                topK = 5,
            ),
            description = "AIGC 换脸生成插件",
            enabled = true,
            pipelineStage = "generative",
            dependsOn = listOf("face_detection_v1", "face_embedding_v1"),
        )

        val json = PluginJsonCodec.manifestToJson(original)
        val restored = PluginJsonCodec.manifestFromJson(json)

        assertEquals(original.pluginId, restored.pluginId)
        assertEquals(original.name, restored.name)
        assertEquals(original.version, restored.version)
        assertEquals(original.author, restored.author)
        assertEquals(original.taskType, restored.taskType)
        assertEquals(original.modelFormat, restored.modelFormat)
        assertEquals(original.modelFilePath, restored.modelFilePath)
        assertEquals(original.entryClass, restored.entryClass)
        assertEquals(original.inputTensors.size, restored.inputTensors.size)
        assertEquals(original.inputTensors[0].name, restored.inputTensors[0].name)
        assertEquals(original.inputTensors[0].dataType, restored.inputTensors[0].dataType)
        assertEquals(original.inputTensors[0].shape, restored.inputTensors[0].shape)
        assertEquals(original.inputTensors[1].name, restored.inputTensors[1].name)
        assertEquals(original.inputTensors[1].shape, restored.inputTensors[1].shape)
        assertEquals(original.outputTensors.size, restored.outputTensors.size)
        assertEquals(original.outputTensors[0].name, restored.outputTensors[0].name)
        assertEquals(original.preprocessing.resizeWidth, restored.preprocessing.resizeWidth)
        assertEquals(original.preprocessing.resizeHeight, restored.preprocessing.resizeHeight)
        assertEquals(original.preprocessing.normalizeMean, restored.preprocessing.normalizeMean)
        assertEquals(original.preprocessing.normalizeStd, restored.preprocessing.normalizeStd)
        assertEquals(original.preprocessing.bgrToRgb, restored.preprocessing.bgrToRgb)
        assertEquals(original.preprocessing.cropToSquare, restored.preprocessing.cropToSquare)
        assertEquals(original.postprocessing.nmsThreshold, restored.postprocessing.nmsThreshold, 0.001f)
        assertEquals(original.postprocessing.confidenceThreshold, restored.postprocessing.confidenceThreshold, 0.001f)
        assertEquals(original.postprocessing.labelMapPath, restored.postprocessing.labelMapPath)
        assertEquals(original.postprocessing.topK, restored.postprocessing.topK)
        assertEquals(original.description, restored.description)
        assertEquals(original.enabled, restored.enabled)
        assertEquals(original.pipelineStage, restored.pipelineStage)
        assertEquals(original.dependsOn, restored.dependsOn)
    }

    @Test
    fun `manifest with quantization params round-trip preserves quantization`() {
        val original = PluginManifest(
            pluginId = "quant_model",
            name = "量化模型",
            taskType = PluginManifest.TaskType.CLASSIFICATION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "model.tflite",
            entryClass = "com.example.Plugin",
            inputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "input",
                    dataType = PluginManifest.TensorDataType.UINT8,
                    shape = listOf(1, 224, 224, 3),
                    quantizationParams = PluginManifest.QuantizationParams(
                        scale = 0.0078125f,
                        zeroPoint = 128L,
                    ),
                ),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "output",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 1000),
                ),
            ),
        )

        val json = PluginJsonCodec.manifestToJson(original)
        val restored = PluginJsonCodec.manifestFromJson(json)

        val qp = restored.inputTensors[0].quantizationParams
        assertNotNull("量化参数不应为 null", qp)
        assertEquals(0.0078125f, qp!!.scale, 0.0001f)
        assertEquals(128L, qp.zeroPoint)
    }

    @Test
    fun `manifest with default values round-trip preserves defaults`() {
        val original = PluginManifest(
            pluginId = "simple_plugin",
            name = "简单插件",
            taskType = PluginManifest.TaskType.FEATURE_EXTRACTION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "model.tflite",
            entryClass = "com.example.Plugin",
            inputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "input",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 128, 128, 3),
                ),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "output",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 256),
                ),
            ),
        )

        val json = PluginJsonCodec.manifestToJson(original)
        val restored = PluginJsonCodec.manifestFromJson(json)

        assertEquals("1.0.0", restored.version)
        assertEquals("", restored.author)
        assertEquals(false, restored.preprocessing.bgrToRgb)
        assertEquals(false, restored.preprocessing.cropToSquare)
        assertEquals(0, restored.preprocessing.resizeWidth)
        assertEquals(0, restored.postprocessing.topK)
        assertEquals(true, restored.enabled)
        assertEquals("extraction", restored.pipelineStage)
        assertTrue("dependsOn 应为空", restored.dependsOn.isEmpty())
    }

    // ---- 校验 ----

    @Test
    fun `validate manifest with all required fields passes`() {
        val manifest = createValidManifest()
        assertNull("有效配置应通过校验", PluginJsonCodec.validateManifest(manifest))
    }

    @Test
    fun `validate manifest with blank pluginId fails`() {
        val manifest = createValidManifest().copy(pluginId = "")
        val error = PluginJsonCodec.validateManifest(manifest)
        assertNotNull("空 pluginId 应校验失败", error)
        assertTrue("错误信息应包含 pluginId", error!!.contains("pluginId"))
    }

    @Test
    fun `validate manifest with blank modelFilePath fails`() {
        val manifest = createValidManifest().copy(modelFilePath = "")
        val error = PluginJsonCodec.validateManifest(manifest)
        assertNotNull("空 modelFilePath 应校验失败", error)
    }

    @Test
    fun `validate manifest with empty inputTensors fails`() {
        val manifest = createValidManifest().copy(inputTensors = emptyList())
        val error = PluginJsonCodec.validateManifest(manifest)
        assertNotNull("空 inputTensors 应校验失败", error)
    }

    @Test
    fun `validate manifest with empty outputTensors fails`() {
        val manifest = createValidManifest().copy(outputTensors = emptyList())
        val error = PluginJsonCodec.validateManifest(manifest)
        assertNotNull("空 outputTensors 应校验失败", error)
    }

    @Test
    fun `validate manifest json string with invalid json returns error`() {
        val error = PluginJsonCodec.validateManifest("{ invalid json }")
        assertNotNull("无效 JSON 应返回错误", error)
    }

    @Test
    fun `validate manifest json string with unknown taskType returns error`() {
        val json = """
            {
                "pluginId": "test",
                "name": "Test",
                "taskType": "UNKNOWN_TYPE",
                "modelFormat": "TFLITE",
                "modelFilePath": "model.tflite",
                "entryClass": "com.example.Plugin",
                "inputTensors": [{"name": "in", "dataType": "FLOAT32", "shape": [1, 224, 224, 3]}],
                "outputTensors": [{"name": "out", "dataType": "FLOAT32", "shape": [1, 10]}]
            }
        """.trimIndent()
        val error = PluginJsonCodec.validateManifest(json)
        assertNotNull("未知 taskType 应返回错误", error)
        assertTrue("错误信息应包含 taskType", error!!.contains("taskType"))
    }

    @Test
    fun `manifest from json with missing required field throws exception`() {
        val json = """{"name": "Test"}"""
        try {
            PluginJsonCodec.manifestFromJson(json)
            assert(false) { "应抛出 PluginConfigException" }
        } catch (e: PluginConfigException) {
            assertTrue("异常信息应包含 pluginId", e.message!!.contains("pluginId"))
        }
    }

    // ---- FeatureSchema 序列化/反序列化 ----

    @Test
    fun `schema round-trip preserves all fields`() {
        val original = FeatureSchema(
            pluginId = "clip_embedder",
            featureType = "embedding",
            fields = listOf(
                FeatureSchema.FieldSpec(
                    name = "embedding",
                    dataType = FeatureSchema.DataType.FLOAT_ARRAY,
                    dims = listOf(512),
                    description = "CLIP 512维语义嵌入向量",
                ),
            ),
            modelVersion = 2,
        )

        val json = PluginJsonCodec.schemaToJson(original)
        val restored = PluginJsonCodec.schemaFromJson(json)

        assertEquals(original.pluginId, restored.pluginId)
        assertEquals(original.featureType, restored.featureType)
        assertEquals(original.modelVersion, restored.modelVersion)
        assertEquals(original.fields.size, restored.fields.size)
        assertEquals(original.fields[0].name, restored.fields[0].name)
        assertEquals(original.fields[0].dataType, restored.fields[0].dataType)
        assertEquals(original.fields[0].dims, restored.fields[0].dims)
        assertEquals(original.fields[0].description, restored.fields[0].description)
    }

    @Test
    fun `schema with multiple fields round-trip preserves order`() {
        val original = FeatureSchema(
            pluginId = "detector",
            featureType = "detection_box",
            fields = listOf(
                FeatureSchema.FieldSpec("box_left", FeatureSchema.DataType.FLOAT, description = "左边界"),
                FeatureSchema.FieldSpec("box_top", FeatureSchema.DataType.FLOAT, description = "上边界"),
                FeatureSchema.FieldSpec("box_right", FeatureSchema.DataType.FLOAT, description = "右边界"),
                FeatureSchema.FieldSpec("box_bottom", FeatureSchema.DataType.FLOAT, description = "下边界"),
                FeatureSchema.FieldSpec("label", FeatureSchema.DataType.STRING),
                FeatureSchema.FieldSpec("confidence", FeatureSchema.DataType.FLOAT),
            ),
        )

        val json = PluginJsonCodec.schemaToJson(original)
        val restored = PluginJsonCodec.schemaFromJson(json)

        assertEquals(original.fields.size, restored.fields.size)
        for (i in original.fields.indices) {
            assertEquals(original.fields[i].name, restored.fields[i].name)
            assertEquals(original.fields[i].dataType, restored.fields[i].dataType)
        }
    }

    @Test
    fun `schema isVectorType returns true for single float array field`() {
        val schema = FeatureSchema(
            pluginId = "embedder",
            featureType = "embedding",
            fields = listOf(
                FeatureSchema.FieldSpec("embedding", FeatureSchema.DataType.FLOAT_ARRAY, dims = listOf(256)),
            ),
        )
        assertTrue("单 FLOAT_ARRAY 字段应为向量类型", schema.isVectorType)
        assertEquals(256, schema.vectorDim)
    }

    @Test
    fun `schema isVectorType returns false for multi-field schema`() {
        val schema = FeatureSchema(
            pluginId = "detector",
            featureType = "detection",
            fields = listOf(
                FeatureSchema.FieldSpec("box", FeatureSchema.DataType.FLOAT_ARRAY, dims = listOf(4)),
                FeatureSchema.FieldSpec("label", FeatureSchema.DataType.STRING),
            ),
        )
        assertFalse("多字段 schema 不应为向量类型", schema.isVectorType)
        assertNull(schema.vectorDim)
    }

    @Test
    fun `fieldSpec elementCount for scalar returns 1`() {
        val field = FeatureSchema.FieldSpec("score", FeatureSchema.DataType.FLOAT)
        assertEquals(1, field.elementCount)
    }

    @Test
    fun `fieldSpec elementCount for vector returns dim product`() {
        val field = FeatureSchema.FieldSpec("matrix", FeatureSchema.DataType.FLOAT_ARRAY, dims = listOf(3, 4, 5))
        assertEquals(60, field.elementCount)
    }

    // ---- Feature data 序列化/反序列化 ----

    @Test
    fun `feature data round-trip preserves vector and metadata`() {
        val originalVector = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val originalMetadata = mapOf("source" to "clip", "version" to "2")

        val json = PluginJsonCodec.featureDataToJson(originalVector, originalMetadata)
        val (restoredVector, restoredMetadata) = PluginJsonCodec.featureDataFromJson(json)

        assertEquals(originalVector.size, restoredVector.size)
        for (i in originalVector.indices) {
            assertEquals(originalVector[i], restoredVector[i], 0.0001f)
        }
        assertEquals(originalMetadata, restoredMetadata)
    }

    @Test
    fun `feature data round-trip without metadata preserves vector`() {
        val originalVector = floatArrayOf(1f, 2f, 3f)

        val json = PluginJsonCodec.featureDataToJson(originalVector)
        val (restoredVector, restoredMetadata) = PluginJsonCodec.featureDataFromJson(json)

        assertEquals(originalVector.size, restoredVector.size)
        assertTrue("无 metadata 时应为空 map", restoredMetadata.isEmpty())
    }

    @Test
    fun `feature data round-trip with empty vector preserves empty`() {
        val originalVector = FloatArray(0)

        val json = PluginJsonCodec.featureDataToJson(originalVector)
        val (restoredVector, _) = PluginJsonCodec.featureDataFromJson(json)

        assertEquals(0, restoredVector.size)
    }

    // ---- 辅助方法 ----

    // ---- PluginOutput 序列化/反序列化 ----

    @Test
    fun `classificationOutput round-trip preserves labels and confidence`() {
        val original = PluginOutput.ClassificationOutput(
            labels = listOf(
                PluginOutput.LabelResult("cat", 0.95f),
                PluginOutput.LabelResult("dog", 0.03f),
                PluginOutput.LabelResult("bird", 0.02f),
            ),
        )

        val json = PluginJsonCodec.pluginOutputToJson(original)
        val restored = PluginJsonCodec.pluginOutputFromJson(json)

        assertTrue("反序列化结果应为 ClassificationOutput", restored is PluginOutput.ClassificationOutput)
        val r = restored as PluginOutput.ClassificationOutput
        assertEquals(original.labels.size, r.labels.size)
        assertEquals(original.topLabel, r.topLabel)
        assertEquals(original.topConfidence, r.topConfidence, 0.0001f)
        for (i in original.labels.indices) {
            assertEquals(original.labels[i].label, r.labels[i].label)
            assertEquals(original.labels[i].confidence, r.labels[i].confidence, 0.0001f)
        }
    }

    @Test
    fun `featureOutput round-trip preserves vector and metadata`() {
        val original = PluginOutput.FeatureOutput(
            vector = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f),
            schema = FeatureSchema(
                pluginId = "clip_v1",
                featureType = "embedding",
                fields = listOf(
                    FeatureSchema.FieldSpec(
                        name = "embedding",
                        dataType = FeatureSchema.DataType.FLOAT_ARRAY,
                        dims = listOf(5),
                    ),
                ),
                modelVersion = 2,
            ),
            metadata = mapOf("source" to "clip", "normalized" to "true"),
        )

        val json = PluginJsonCodec.pluginOutputToJson(original)
        val restored = PluginJsonCodec.pluginOutputFromJson(json)

        assertTrue("反序列化结果应为 FeatureOutput", restored is PluginOutput.FeatureOutput)
        val r = restored as PluginOutput.FeatureOutput
        assertEquals(original.vector.size, r.vector.size)
        for (i in original.vector.indices) {
            assertEquals(original.vector[i], r.vector[i], 0.0001f)
        }
        assertEquals(original.metadata, r.metadata)
    }

    @Test
    fun `detectionOutput round-trip preserves boxes with embedding`() {
        val original = PluginOutput.DetectionOutput(
            imagePath = "/photos/img1.jpg",
            boxes = listOf(
                PluginOutput.DetectedBox(
                    box = android.graphics.RectF(0.1f, 0.2f, 0.3f, 0.4f),
                    label = "face",
                    confidence = 0.98f,
                    embedding = floatArrayOf(0.5f, 0.6f, 0.7f),
                ),
                PluginOutput.DetectedBox(
                    box = android.graphics.RectF(0.5f, 0.6f, 0.9f, 0.95f),
                    label = "person",
                    confidence = 0.85f,
                    embedding = null,
                ),
            ),
        )

        val json = PluginJsonCodec.pluginOutputToJson(original)
        val restored = PluginJsonCodec.pluginOutputFromJson(json)

        assertTrue("反序列化结果应为 DetectionOutput", restored is PluginOutput.DetectionOutput)
        val r = restored as PluginOutput.DetectionOutput
        assertEquals(original.imagePath, r.imagePath)
        assertEquals(original.boxes.size, r.boxes.size)

        // 检查带 embedding 的检测框
        assertEquals(original.boxes[0].label, r.boxes[0].label)
        assertEquals(original.boxes[0].confidence, r.boxes[0].confidence, 0.0001f)
        assertEquals(original.boxes[0].box.left, r.boxes[0].box.left, 0.0001f)
        assertEquals(original.boxes[0].box.top, r.boxes[0].box.top, 0.0001f)
        assertEquals(original.boxes[0].box.right, r.boxes[0].box.right, 0.0001f)
        assertEquals(original.boxes[0].box.bottom, r.boxes[0].box.bottom, 0.0001f)
        assertNotNull("embedding 不应为 null", r.boxes[0].embedding)
        val originalEmb = original.boxes[0].embedding!!
        val restoredEmb = r.boxes[0].embedding!!
        for (i in originalEmb.indices) {
            assertEquals(originalEmb[i], restoredEmb[i], 0.0001f)
        }

        // 检查无 embedding 的检测框
        assertEquals(original.boxes[1].label, r.boxes[1].label)
        assertEquals(original.boxes[1].confidence, r.boxes[1].confidence, 0.0001f)
        assertNull("无 embedding 的框反序列化后也应为 null", r.boxes[1].embedding)
    }

    @Test
    fun `imageOutput round-trip preserves paths`() {
        val original = PluginOutput.ImageOutput(
            bitmap = null,
            outputPath = "/output/result.jpg",
            sourcePath = "/photos/src.jpg",
        )

        val json = PluginJsonCodec.pluginOutputToJson(original)
        val restored = PluginJsonCodec.pluginOutputFromJson(json)

        assertTrue("反序列化结果应为 ImageOutput", restored is PluginOutput.ImageOutput)
        val r = restored as PluginOutput.ImageOutput
        assertEquals(original.sourcePath, r.sourcePath)
        assertEquals(original.outputPath, r.outputPath)
        assertNull("bitmap 不可序列化，反序列化后应为 null", r.bitmap)
    }

    private fun createValidManifest(): PluginManifest {
        return PluginManifest(
            pluginId = "test_plugin",
            name = "Test Plugin",
            taskType = PluginManifest.TaskType.CLASSIFICATION,
            modelFormat = PluginManifest.ModelFormat.TFLITE,
            modelFilePath = "models/test.tflite",
            entryClass = "com.example.TestPlugin",
            inputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "input",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 224, 224, 3),
                ),
            ),
            outputTensors = listOf(
                PluginManifest.TensorSpec(
                    name = "output",
                    dataType = PluginManifest.TensorDataType.FLOAT32,
                    shape = listOf(1, 1000),
                ),
            ),
        )
    }
}
