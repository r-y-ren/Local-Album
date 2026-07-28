package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.renyxin.localalbum.core.plugin.PluginManifest
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.model.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RetinaFace 人脸检测 Provider (retinaface-resnet50.onnx)。
 * 仅提供人脸检测框，不提取嵌入向量（嵌入维=0表示仅检测无嵌入）。
 */
class RetinaFaceProvider(
    private val modelManager: ModelManager,
    private val context: Context,
) : FaceProvider {

    companion object {
        private const val TAG = "RetinaFace"
        const val MODEL_ID = "model:retinaface"
        private const val INPUT_SIZE = 640
        private const val CONF_THRESHOLD = 0.5f
    }

    override val providerId = "model:retinaface"
    override val displayName = "RetinaFace 人脸检测 (ResNet50)"
    override val embeddingDim = 0

    init {
        modelManager.registerModel(
            ModelManager.ModelDescriptor(
                modelId = MODEL_ID,
                displayName = "RetinaFace ResNet50",
                format = PluginManifest.ModelFormat.ONNX,
                fileUrl = "",
                inputShape = listOf(1, 3, INPUT_SIZE, INPUT_SIZE),
                outputShape = intArrayOf(1, 0),
                fileSizeBytes = 109_000_000,
                memoryFootprintBytes = 100_000_000,
                runtime = ModelManager.ModelRuntime.ONNX,
                assetFileName = "retinaface-resnet50.onnx",
            )
        )
    }

    override suspend fun detectFaces(file: File): List<FaceProvider.DetectedFace> = withContext(Dispatchers.IO) {
        val bitmap = decodeBitmap(file) ?: return@withContext emptyList()
        val result = modelManager.ensureModelReady(MODEL_ID)
        if (result.isFailure) return@withContext emptyList()
        try {
            val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
            val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
            resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
            // ⚠️ N12 修正：声明的张量形状 [1,3,INPUT,INPUT] 是 NCHW 平面布局，必须先填完整个 R 平面、
            // 再 G、再 B。原先逐像素写 R,G,B,R,G,B… 是 HWC 交错布局，与 NCHW 形状不匹配，
            // 会导致喂入模型的通道-空间排布完全错乱（该 bug 因下方 emptyList() 空实现而休眠）。
            // 与 InsightFaceProvider.preprocessDetection 的 3 平面循环写法保持一致。
            // NOTE: RetinaFace 标准归一化为 (x-127.5)/128（mean=127.5, std=128），
            // 当前使用 /255（mean=0, std=255）。待输出解析实现时需根据
            // retinaface-resnet50.onnx 实际输入约定确认归一化参数。
            for (p in pixels) buf.put((p shr 16 and 0xFF) / 255f)  // R plane
            for (p in pixels) buf.put((p shr 8 and 0xFF) / 255f)   // G plane
            for (p in pixels) buf.put((p and 0xFF) / 255f)         // B plane
            buf.rewind()
            if (resized != bitmap) resized.recycle()

            // 通过 session 池获取独立 session（独立 intra-op 线程池），实现多核并行推理
            modelManager.withOnnxSession(MODEL_ID) { session ->
                val env = OrtEnvironment.getEnvironment()
                val input = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
                val output = session.run(mapOf(session.inputNames.iterator().next() to input))
                input.close()
                output.close()
            }

            emptyList<FaceProvider.DetectedFace>()  // 需根据不同模型输出格式解析
        } catch (e: Exception) {
            Log.w(TAG, "RetinaFace 检测失败", e)
            emptyList()
        }
    }

    override suspend fun release() {
        modelManager.unregisterConsumer(MODEL_ID, "RetinaFace")
    }

    private fun decodeBitmap(file: File): Bitmap? {
        return try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
    }
}
