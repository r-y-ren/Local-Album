package com.renyxin.localalbum.core.plugin.model

import ai.onnxruntime.OrtSession
import com.renyxin.localalbum.core.plugin.PluginManifest
import kotlinx.coroutines.flow.StateFlow
import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * 统一的模型管理器接口（Phase 0/1 改进计划 + ONNX 扩展）。
 *
 * 集中管理所有 AI 模型的生命周期：注册 → 下载 → 加载 → 推理 → 卸载。
 * Provider 通过 [ensureModelReady] + [getInterpreter] / [getOnnxSession] 获取底层推理引擎。
 *
 * ## 运行时支持
 *
 * - **TFLite**: 通过 [getInterpreter] 获取 [Interpreter]
 * - **ONNX**: 通过 [getOnnxSession] 获取 [OrtSession]
 *
 * @see ModelManagerImpl 默认实现
 */
interface ModelManager {

    // ---- 运行时类型 ----

    /**
     * 模型推理运行时类型。
     */
    enum class ModelRuntime {
        /** TensorFlow Lite (.tflite) */
        TFLITE,
        /** ONNX Runtime (.onnx) */
        ONNX,
    }

    // ---- 模型描述 ----

    data class ModelDescriptor(
        val modelId: String,
        val displayName: String,
        val format: PluginManifest.ModelFormat,
        val fileUrl: String,
        val expectedSha256: String? = null,
        val inputShape: List<Int>,
        val outputShape: IntArray,
        val fileSizeBytes: Long,
        val memoryFootprintBytes: Long,
        /** 模型运行时类型，默认 TFLITE */
        val runtime: ModelManager.ModelRuntime = ModelManager.ModelRuntime.TFLITE,
        /** ONNX 模型子目录（相对于 assets/models/），用于多文件模型 */
        val assetSubDir: String? = null,
        /** assets/models/ 下的实际文件名（不含子目录路径）。
         *  例如 "inswapper_128.onnx"、"scrfd_person_2.5g.onnx"。
         *  若为空则尝试直接用 modelId 匹配。 */
        val assetFileName: String? = null,
    )

    // ---- 模型状态 ----

    data class ModelState(
        val modelId: String,
        val status: ModelStatus,
        val progress: Float = 0f,
        val loadedInMemory: Boolean = false,
        val consumerIds: Set<String> = emptySet(),
    )

    enum class ModelStatus {
        NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, LOADING, LOADED, ERROR
    }

    // ---- 注册 ----

    fun registerModel(descriptor: ModelDescriptor)

    // ---- 生命周期 ----

    suspend fun ensureModelReady(modelId: String): Result<File>

    /**
     * 获取已加载的 TFLite [Interpreter]。
     *
     * 调用方需先通过 [ensureModelReady] 确保模型就绪。
     * 返回 null 表示模型尚未加载。
     *
     * @deprecated TFLite [Interpreter] 实例非线程安全，直接并发调用 `run` 会崩溃。
     * 并发场景请改用 [withInterpreter]，它通过 Interpreter 池为每个并发 worker
     * 提供独立实例（共享同一只读 MappedByteBuffer），实现真正的多核并行。
     */
    @Deprecated(
        message = "Interpreter 非线程安全，并发推理请使用 withInterpreter",
        replaceWith = ReplaceWith("withInterpreter(modelId) { interpreter -> /* run */ }"),
    )
    fun getInterpreter(modelId: String): Interpreter?

    /**
     * 在 Interpreter 池中获取一个可用实例执行推理（线程安全）。
     *
     * TFLite [Interpreter] 非线程安全，同一实例并发 `run` 会崩溃。
     * 本方法维护一个由共享只读 [MappedByteBuffer] 创建的 Interpreter 池
     * （池大小 = [com.renyxin.localalbum.core.concurrent.InferenceDispatchers.inferenceConcurrency]），
     * 通过信号量并发控制，为每个并发调用提供独立实例，实现多核并行推理。
     *
     * 调用方需先通过 [ensureModelReady] 确保模型就绪。
     *
     * @param modelId 模型 ID
     * @param block 使用 Interpreter 的闭包，执行完毕后实例自动归还池
     * @return block 的返回值；模型未就绪时抛 [IllegalStateException]
     */
    suspend fun <T> withInterpreter(modelId: String, block: (Interpreter) -> T): T

    /**
     * 获取已加载的 ONNX [OrtSession]。
     *
     * 调用方需先通过 [ensureModelReady] 确保模型就绪。
     * 返回 null 表示模型尚未加载或模型不是 ONNX 运行时。
     *
     * @deprecated 单个 [OrtSession] 的 intra-op 线程池为 session 级共享，
     * `setIntraOpNumThreads(1)` 后线程池仅 1 线程，多个并发 `session.run` 会被该线程池串行化，
     * 导致多核无法利用。并发推理请改用 [withOnnxSession]，它通过 session 池为每个并发 worker
     * 提供独立 session（独立线程池），实现真正的多核并行。
     */
    @Deprecated(
        message = "单 session 并发 run 受线程池串行化，并发推理请使用 withOnnxSession",
        replaceWith = ReplaceWith("withOnnxSession(modelId) { session -> /* run */ }"),
    )
    fun getOnnxSession(modelId: String): OrtSession?

    /**
     * 在 ONNX session 池中获取一个可用实例执行推理（多核并行）。
     *
     * 单个 [OrtSession] 的 intra-op 线程池为 session 级共享，`intra=1` 时仅 1 线程，
     * 多个并发 `run` 会被串行化。本方法维护一个由同一模型文件创建的独立 session 池
     * （池大小 = [com.renyxin.localalbum.core.concurrent.InferenceDispatchers.inferenceConcurrency]），
     * 每个 session 拥有独立的 intra-op 线程池，从而让 N 个并发推理真正跑在 N 个核心上。
     *
     * 调用方需先通过 [ensureModelReady] 确保模型就绪。
     *
     * @param modelId 模型 ID
     * @param block 使用 OrtSession 的闭包，执行完毕后 session 自动归还池
     * @return block 的返回值；模型未就绪时抛 [IllegalStateException]
     */
    suspend fun <T> withOnnxSession(modelId: String, block: (OrtSession) -> T): T

    // ---- 推理（便捷方法，适用于简单的单输入/单输出模型） ----

    suspend fun runInference(
        modelId: String,
        inputs: Map<String, Any>,
        outputNames: List<String> = listOf("output"),
    ): Map<String, Any>

    // ---- 查询 ----

    fun getModelState(modelId: String): StateFlow<ModelState>
    fun getAllModelStates(): StateFlow<List<ModelState>>

    // ---- 内存管理 ----

    fun evictModel(modelId: String)
    fun evictUnusedModels()
    fun getTotalMemoryFootprint(): Long

    // ---- 消费者管理 ----

    fun registerConsumer(modelId: String, consumerId: String)
    fun unregisterConsumer(modelId: String, consumerId: String)

    // ---- 查询所有已注册模型 ----

    /**
     * 获取所有已注册模型 ID 列表。
     * 用于批量操作（如首次启动自动下载所有核心模型）。
     */
    fun getRegisteredModelIds(): List<String>

    // ---- 内置模型 ----

    /**
     * 将内置模型从 assets/models/ 复制到 filesDir/models/。
     * 仅在首次启动时执行，后续通过 SharedPreferences 标记跳过。
     *
     * @return 成功复制的模型文件数量
     */
    suspend fun copyBundledModels(): Int

    /**
     * 准备内置模型：将所有带 [ModelDescriptor.assetFileName] 的已注册内置模型
     * 从 assets 解析到 filesDir，并将其状态从 `NOT_DOWNLOADED` 标记为 `DOWNLOADED`。
     *
     * 与 [copyBundledModels] 的区别：
     * - [copyBundledModels] 仅首次启动批量复制文件，不更新状态；
     * - 本方法**每次启动**调用，幂等地确认文件就位并更新状态，确保 UI 显示「已就绪」。
     *
     * 不加载模型到内存（避免 OOM），首次推理时由 [ensureModelReady] 真正加载。
     *
     * @return 成功标记为 DOWNLOADED 的内置模型数量
     */
    suspend fun prepareBundledModels(): Int
}
