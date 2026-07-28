package com.renyxin.localalbum.core.plugin.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.renyxin.localalbum.core.concurrent.InferenceDispatchers
import com.renyxin.localalbum.core.plugin.PluginManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.zip.ZipInputStream

/**
 * 模型管理器默认实现（Phase 0 改进计划 + ONNX 扩展）。
 *
 * 支持两种运行时：
 * - TFLite: 通过 [Interpreter] 推理
 * - ONNX: 通过 [OrtSession] 推理
 *
 * 内部组件：
 * - [ModelDownloadManagerV2] — 下载与缓存
 * - TFLite [Interpreter] 池 — 加载/卸载
 * - ONNX [OrtSession] 池 — 加载/卸载
 * - 状态追踪 — [StateFlow] 驱动
 */
class ModelManagerImpl(
    private val context: Context,
) : ModelManager {

    companion object {
        private const val TAG = "ModelManager"
    }

    /** Phase 6: 推理调度器（优先级队列 + 并发控制） */
    val inferenceScheduler = InferenceScheduler(maxConcurrentModels = 2)

    /** Phase 6: 模型版本追踪器（持久化版本号，检测升级触发重建） */
    val versionTracker = ModelVersionTracker(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // ---- 内部存储 ----

    // 修复：改用 ConcurrentHashMap，避免 evictModel/getInterpreter 等无锁访问与
    // ensureModelReady/runInference 锁内访问并发修改导致 ConcurrentModificationException。
    private val descriptors = java.util.concurrent.ConcurrentHashMap<String, ModelManager.ModelDescriptor>()
    private val interpreters = java.util.concurrent.ConcurrentHashMap<String, Interpreter>()
    private val onnxSessions = java.util.concurrent.ConcurrentHashMap<String, OrtSession>()
    private val stateFlows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<ModelManager.ModelState>>()
    private val allStatesFlow = MutableStateFlow<List<ModelManager.ModelState>>(emptyList())

    /**
     * 修复：按 modelId 的推理锁。TFLite Interpreter / ONNX Session 非线程安全，
     * 同一模型的并发推理需串行；不同模型可并发，避免原全局 mutex 把所有推理串行化。
     */
    private val inferenceLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private fun inferenceLockFor(modelId: String): Mutex =
        inferenceLocks.computeIfAbsent(modelId) { Mutex() }

    // ---- TFLite Interpreter 池（多核并行优化）----
    //
    // TFLite Interpreter 实例非线程安全，同一实例并发 run 会崩溃。
    // 为支持文件级并行推理，维护一个由共享只读 MappedByteBuffer 创建的
    // Interpreter 池：每个并发 worker 通过信号量获取独立实例，实现真正的多核并行。
    // 模型权重通过 mmap 共享，多实例仅额外占用各自的中间张量内存。

    /** 每个 modelId 的共享只读模型缓冲区（mmap），供池中多个 Interpreter 复用。 */
    private val tfliteBuffers = java.util.concurrent.ConcurrentHashMap<String, MappedByteBuffer>()

    /** 每个 modelId 的空闲 Interpreter 队列（线程安全）。 */
    private val tfliteFreePools = java.util.concurrent.ConcurrentHashMap<String, ConcurrentLinkedDeque<Interpreter>>()

    /** 每个 modelId 的信号量，限制并发推理数 = 池大小。 */
    private val tfliteSemaphores = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()

    /** 池大小，等于推理并发度。 */
    private val tflitePoolSize: Int = InferenceDispatchers.inferenceConcurrency

    /** 池操作互斥锁（仅保护「取出或创建」的检查-创建原子性，run 不持锁）。 */
    private val tflitePoolMutex = Mutex()

    // ---- ONNX Session 池（多核并行优化）----
    //
    // 单个 OrtSession 的 intra-op 线程池为 session 级共享，setIntraOpNumThreads(1) 后
    // 线程池仅 1 线程，多个并发 session.run 会被该线程池串行化（单核瓶颈根因）。
    // 本池为每个并发 worker 提供独立 session（各自独立线程池），让 N 个并发推理跑在 N 核上。
    // 多个 session 共享同一模型文件的 mmap 只读页，内存代价仅为各 session 的内部张量缓冲。

    /** 每个 modelId 的模型文件路径，用于按需创建新 session。 */
    private val onnxModelPaths = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 每个 modelId 的空闲 OrtSession 队列。 */
    private val onnxFreePools = java.util.concurrent.ConcurrentHashMap<String, ConcurrentLinkedDeque<OrtSession>>()

    /** 每个 modelId 的信号量，限制并发推理数 = 池大小。 */
    private val onnxSemaphores = java.util.concurrent.ConcurrentHashMap<String, Semaphore>()

    /** ONNX 池操作互斥锁。 */
    private val onnxPoolMutex = Mutex()

    // ---- ONNX 环境 ----

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * 创建配置好的 ONNX SessionOptions：intra/inter op 线程设为 1（配合文件级并行，
     * 避免 oversubscription），启用 XNNPACK（ARM NEON 优化）。
     *
     * 例外：InsightFace SCRFD det_10g.onnx 的 createSession 在 XNNPACK EP 下会
     * abort（SIGABRT / libc++abi terminating），对该系列模型禁用 XNNPACK，
     * 其他模型（含 EVA02-CLIP）保留 XNNPACK 加速。
     */
    private fun createOnnxSessionOptions(modelId: String = ""): OrtSession.SessionOptions =
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            if (modelId != "model:insightface_det") {
                runCatching { addXnnpack(emptyMap()) }
                    .onFailure { Log.w(TAG, "XNNPACK 启用失败，回退默认 EP: ${it.message}") }
            } else {
                Log.i(TAG, "模型 $modelId 禁用 XNNPACK（SCRFD det_10g createSession 兼容性）")
            }
        }

    /**
     * 创建配置好的 TFLite Interpreter.Options：单线程（配合文件级并行）+ XNNPACK。
     */
    private fun createTfliteOptions(): Interpreter.Options = Interpreter.Options().apply {
        setNumThreads(1)
        setUseXNNPACK(true)
    }

    // ---- 下载管理 ----

    private val downloadManager = ModelDownloadManagerV2(context)

    // ---- 已加载模型的总内存估算 ----

    private var totalMemoryBytes: Long = 0L

    // ---- 内置模型复制标记 (SharedPreferences key) ----

    private val prefs = context.getSharedPreferences("model_manager_prefs", Context.MODE_PRIVATE)
    private val bundledCopiedKey = "bundled_models_copied_v2"

    // ===================== 注册 =====================

    override fun registerModel(descriptor: ModelManager.ModelDescriptor) {
        descriptors[descriptor.modelId] = descriptor
        val state = ModelManager.ModelState(
            modelId = descriptor.modelId,
            status = ModelManager.ModelStatus.NOT_DOWNLOADED,
        )
        stateFlows[descriptor.modelId] = MutableStateFlow(state)
        emitAllStates()
        Log.d(TAG, "模型已注册: ${descriptor.modelId} (${descriptor.runtime})")
    }

    // ===================== 生命周期 =====================

    override suspend fun ensureModelReady(modelId: String): Result<File> {
        val descriptor = descriptors[modelId]
            ?: return Result.failure(IllegalStateException("模型未注册: $modelId"))

        return mutex.withLock {
            val current = stateFlows[modelId]?.value ?: return@withLock Result.failure(
                IllegalStateException("模型状态不存在: $modelId")
            )

            // 已加载 (TFLite 或 ONNX)
            if (current.status == ModelManager.ModelStatus.LOADED) {
                if (descriptor.runtime == ModelManager.ModelRuntime.TFLITE && interpreters.containsKey(modelId))
                    return@withLock Result.success(getModelFile(modelId, descriptor))
                if (descriptor.runtime == ModelManager.ModelRuntime.ONNX && onnxSessions.containsKey(modelId))
                    return@withLock Result.success(getModelFile(modelId, descriptor))
            }

            // 尝试从 assetFileName 自动重命名资产文件到 modelId 命名格式
            resolveAssetFile(modelId, descriptor)

            // 下载 (仅当 fileUrl 非空且模型未内置时)
            if (current.status == ModelManager.ModelStatus.NOT_DOWNLOADED ||
                current.status == ModelManager.ModelStatus.ERROR
            ) {
                val modelFile = getModelFile(modelId, descriptor)
                // 如果内置模型已复制到本地，跳过下载
                if (!modelFile.exists() && descriptor.fileUrl.isNotBlank()) {
                    updateState(modelId, ModelManager.ModelStatus.DOWNLOADING, progress = 0f)

                    val ext = if (descriptor.runtime == ModelManager.ModelRuntime.ONNX) ".onnx" else ".tflite"
                    val result = downloadManager.ensureModel(
                        modelFileName = "$modelId$ext",
                        url = descriptor.fileUrl,
                        expectedSha256 = descriptor.expectedSha256,
                        progress = { downloaded, total ->
                            val p = if (total > 0) downloaded.toFloat() / total else 0f
                            updateState(modelId, ModelManager.ModelStatus.DOWNLOADING, progress = p)
                        },
                    )

                    if (result == null) {
                        updateState(modelId, ModelManager.ModelStatus.ERROR)
                        return@withLock Result.failure(RuntimeException("模型下载失败: $modelId"))
                    }
                }
                updateState(modelId, ModelManager.ModelStatus.DOWNLOADED, progress = 1f)
            }

            // 加载到内存
            val modelFile = getModelFile(modelId, descriptor)
            if (!modelFile.exists()) {
                updateState(modelId, ModelManager.ModelStatus.ERROR)
                return@withLock Result.failure(RuntimeException("模型文件不存在: $modelId -> ${modelFile.absolutePath}"))
            }

            updateState(modelId, ModelManager.ModelStatus.LOADING)
            try {
                when (descriptor.runtime) {
                    ModelManager.ModelRuntime.TFLITE -> {
                        val tfliteBuffer: MappedByteBuffer = FileInputStream(modelFile).channel.map(
                            FileChannel.MapMode.READ_ONLY, 0, modelFile.length()
                        )
                        // 缓存共享只读 buffer，供 Interpreter 池创建多实例
                        tfliteBuffers[modelId] = tfliteBuffer
                        tfliteFreePools[modelId] = ConcurrentLinkedDeque()
                        tfliteSemaphores[modelId] = Semaphore(tflitePoolSize)
                        // 首个实例放入池（单线程 + XNNPACK，配合文件级并行）
                        val interpreter = Interpreter(tfliteBuffer, createTfliteOptions())
                        interpreters[modelId] = interpreter
                        tfliteFreePools[modelId]!!.add(interpreter)
                    }
                    ModelManager.ModelRuntime.ONNX -> {
                        // intra/inter op = 1 + XNNPACK，配合文件级并行避免 oversubscription
                        Log.i("CrashDebug", ">> ensureModelReady createSession: $modelId (文件=${modelFile.name}, ${modelFile.length()}B, 线程=${Thread.currentThread().name})")
                        val session = ortEnv.createSession(modelFile.absolutePath, createOnnxSessionOptions(modelId))
                        Log.i("CrashDebug", "<< ensureModelReady createSession 完成: $modelId")
                        onnxSessions[modelId] = session
                        // 初始化 session 池：缓存路径 + 首个实例入池 + 信号量
                        onnxModelPaths[modelId] = modelFile.absolutePath
                        onnxFreePools[modelId] = ConcurrentLinkedDeque<OrtSession>().apply { add(session) }
                        onnxSemaphores[modelId] = Semaphore(tflitePoolSize)
                    }
                }
                totalMemoryBytes += descriptor.memoryFootprintBytes

                updateState(modelId, ModelManager.ModelStatus.LOADED, loadedInMemory = true)
                Log.i(TAG, "模型加载成功: $modelId (${descriptor.runtime}, ${modelFile.length() / 1024}KB)")
                Result.success(modelFile)
            } catch (e: Exception) {
                Log.e(TAG, "模型加载失败: $modelId", e)
                updateState(modelId, ModelManager.ModelStatus.ERROR)
                Result.failure(e)
            }
        }
    }

    // ===================== Interpreter / Session 访问 =====================

    @Deprecated("Interpreter 非线程安全，并发推理请使用 withInterpreter", level = DeprecationLevel.WARNING)
    override fun getInterpreter(modelId: String): Interpreter? {
        return interpreters[modelId]
    }

    @Deprecated("单 session 并发 run 受线程池串行化，并发推理请使用 withOnnxSession", level = DeprecationLevel.WARNING)
    override fun getOnnxSession(modelId: String): OrtSession? {
        return onnxSessions[modelId]
    }

    override suspend fun <T> withOnnxSession(modelId: String, block: (OrtSession) -> T): T {
        val semaphore = onnxSemaphores[modelId]
        val path = onnxModelPaths[modelId]
        if (semaphore == null || path == null) {
            // 模型未通过池加载，回退到单实例 + 锁
            val single = onnxSessions[modelId]
                ?: throw IllegalStateException("ONNX 模型未加载: $modelId")
            return inferenceLockFor(modelId).withLock { block(single) }
        }
        val freePool = onnxFreePools[modelId]!!

        return semaphore.withPermit {
            // 优先取空闲 session；无则创建新 session（独立 intra-op 线程池）
            val session = freePool.poll() ?: onnxPoolMutex.withLock {
                freePool.poll() ?: {
                    Log.i("CrashDebug", ">> withOnnxSession 池扩容 createSession: $modelId (线程=${Thread.currentThread().name})")
                    val s = ortEnv.createSession(path, createOnnxSessionOptions(modelId))
                    Log.i("CrashDebug", "<< withOnnxSession 池扩容 createSession 完成: $modelId")
                    s
                }()
            }
            try {
                block(session)
            } finally {
                freePool.add(session)
            }
        }
    }

    override suspend fun <T> withInterpreter(modelId: String, block: (Interpreter) -> T): T {
        val semaphore = tfliteSemaphores[modelId]
        val buffer = tfliteBuffers[modelId]
        if (semaphore == null || buffer == null) {
            // 模型未通过池加载（可能是 ONNX 或未就绪），回退到单实例 + 锁
            val single = interpreters[modelId]
                ?: throw IllegalStateException("TFLite 模型未加载: $modelId")
            return inferenceLockFor(modelId).withLock { block(single) }
        }
        val freePool = tfliteFreePools[modelId]!!

        return semaphore.withPermit {
            // 优先取空闲实例；无则从共享 buffer 创建新实例（池未满时）
            val interpreter = freePool.poll() ?: tflitePoolMutex.withLock {
                freePool.poll() ?: Interpreter(buffer, createTfliteOptions())
            }
            try {
                block(interpreter)
            } finally {
                freePool.add(interpreter)
            }
        }
    }

    // ===================== 推理 =====================

    override suspend fun runInference(
        modelId: String,
        inputs: Map<String, Any>,
        outputNames: List<String>,
    ): Map<String, Any> {
        val descriptor = descriptors[modelId]
            ?: throw IllegalStateException("模型未注册: $modelId")

        // 修复：使用按 modelId 的推理锁替代全局 mutex，允许不同模型并发推理，
        // 同时保证同一模型的推理串行（Interpreter/Session 非线程安全）。
        return inferenceLockFor(modelId).withLock {
            when (descriptor.runtime) {
                ModelManager.ModelRuntime.TFLITE -> {
                    val interpreter = interpreters[modelId]
                        ?: throw IllegalStateException("TFLite 模型未加载: $modelId")

                    val inputArray = descriptor.inputShape.mapIndexed { i, _ ->
                        val key = "input_$i"
                        inputs[key] ?: inputs.values.firstOrNull()
                            ?: throw IllegalArgumentException("缺少输入张量: $key")
                    }.toTypedArray()

                    // descriptor.outputShape 描述单个输出的形状；
                    // 需先按形状预分配输出容器并以输出索引为 key 放入 outputMap，
                    // 否则 runForMultipleInputsOutputs 会因空 map 抛 IllegalArgumentException。
                    val outputTotal = descriptor.outputShape.fold(1) { acc, d -> acc * d }
                    val outputContainer = Array(1) { FloatArray(outputTotal) }
                    val outputMap = mutableMapOf<Int, Any>(0 to outputContainer)
                    interpreter.runForMultipleInputsOutputs(inputArray, outputMap)

                    val result = mutableMapOf<String, Any>()
                    val name = outputNames.firstOrNull() ?: "output_0"
                    result[name] = outputContainer[0]
                    result
                }
                ModelManager.ModelRuntime.ONNX -> {
                    val session = onnxSessions[modelId]
                        ?: throw IllegalStateException("ONNX 模型未加载: $modelId")

                    val env = OrtEnvironment.getEnvironment()
                    val inputTensors = mutableMapOf<String, OnnxTensor>()
                    try {
                        // 构建输入张量
                        val inputKeys = if (inputs.size == 1 && inputs.containsKey("input")) {
                            // 单输入模型: 使用 "input" 键
                            mapOf("input" to inputs["input"]!!)
                        } else {
                            inputs
                        }

                        for ((name, data) in inputKeys) {
                            when (data) {
                                is FloatArray -> {
                                    inputTensors[name] = OnnxTensor.createTensor(
                                        env, java.nio.FloatBuffer.wrap(data), longArrayOf(1, data.size.toLong())
                                    )
                                }
                                is Array<*> -> {
                                    @Suppress("UNCHECKED_CAST")
                                    val arr = data as Array<FloatArray>
                                    val flatSize = arr.size * arr[0].size
                                    val flat = FloatArray(flatSize)
                                    for (i in arr.indices) {
                                        arr[i].copyInto(flat, i * arr[0].size)
                                    }
                                    inputTensors[name] = OnnxTensor.createTensor(
                                        env, java.nio.FloatBuffer.wrap(flat),
                                        longArrayOf(arr.size.toLong(), arr[0].size.toLong())
                                    )
                                }
                                else -> throw IllegalArgumentException(
                                    "不支持的输入类型: ${data::class.simpleName} (模型: $modelId)"
                                )
                            }
                        }

                        val result = session.run(inputTensors)
                        val outputMap = mutableMapOf<String, Any>()
                        for (entry in result) {
                            outputMap[entry.key] = entry.value.value
                        }
                        result.close()
                        outputMap
                    } finally {
                        inputTensors.values.forEach { it.close() }
                    }
                }
            }
        }
    }

    // ===================== 查询 =====================

    override fun getModelState(modelId: String): StateFlow<ModelManager.ModelState> {
        return stateFlows[modelId] ?: MutableStateFlow(
            ModelManager.ModelState(modelId, ModelManager.ModelStatus.ERROR)
        )
    }

    override fun getAllModelStates(): StateFlow<List<ModelManager.ModelState>> {
        return allStatesFlow.asStateFlow()
    }

    // ===================== 内存管理 =====================

    override fun evictModel(modelId: String) {
        // TFLite：池中包含所有 Interpreter 实例（含 interpreters 中的首个），统一由池关闭。
        // interpreters[modelId] 与池中首个实例为同一对象，避免重复 close。
        interpreters.remove(modelId)
        tfliteFreePools.remove(modelId)?.forEach { runCatching { it.close() } }
        tfliteBuffers.remove(modelId)
        tfliteSemaphores.remove(modelId)
        // ONNX：池中包含所有 session 实例（含 onnxSessions 中的首个），统一由池关闭。
        onnxSessions.remove(modelId)
        onnxFreePools.remove(modelId)?.forEach { runCatching { it.close() } }
        onnxModelPaths.remove(modelId)
        onnxSemaphores.remove(modelId)

        val descriptor = descriptors[modelId]
        if (descriptor != null) {
            totalMemoryBytes -= descriptor.memoryFootprintBytes
        }

        updateState(modelId, ModelManager.ModelStatus.DOWNLOADED, loadedInMemory = false)
        Log.i(TAG, "模型已卸载: $modelId")
    }

    override fun evictUnusedModels() {
        val tfliteToEvict = interpreters.keys.filter { modelId ->
            val state = stateFlows[modelId]?.value
            state != null && state.consumerIds.isEmpty()
        }
        val onnxToEvict = onnxSessions.keys.filter { modelId ->
            val state = stateFlows[modelId]?.value
            state != null && state.consumerIds.isEmpty()
        }
        val toEvict = tfliteToEvict + onnxToEvict
        if (toEvict.isNotEmpty()) {
            val freedBytes = toEvict.sumOf { descriptors[it]?.memoryFootprintBytes ?: 0L }
            Log.i(TAG, "淘汰未使用模型: ${toEvict.joinToString()}, 释放约 ${freedBytes / 1024 / 1024}MB")
        }
        toEvict.forEach { evictModel(it) }
    }

    fun getConsumerCount(modelId: String): Int {
        return stateFlows[modelId]?.value?.consumerIds?.size ?: 0
    }

    override fun getTotalMemoryFootprint(): Long = totalMemoryBytes

    // ===================== 消费者管理 =====================

    override fun registerConsumer(modelId: String, consumerId: String) {
        stateFlows[modelId]?.update { state ->
            state.copy(consumerIds = state.consumerIds + consumerId)
        }
        Log.d(TAG, "消费者注册: $consumerId → $modelId (count=${getConsumerCount(modelId)})")
    }

    override fun unregisterConsumer(modelId: String, consumerId: String) {
        stateFlows[modelId]?.update { state ->
            state.copy(consumerIds = state.consumerIds - consumerId)
        }
        Log.d(TAG, "消费者注销: $consumerId → $modelId (count=${getConsumerCount(modelId)})")
    }

    // ===================== 内置模型管理 =====================

    override fun getRegisteredModelIds(): List<String> = descriptors.keys.toList()

    /**
     * 准备内置模型：遍历所有带 [ModelManager.ModelDescriptor.assetFileName] 的已注册模型，
     * 调用 [resolveAssetFile]（幂等）确认文件就位，然后将状态从 `NOT_DOWNLOADED` 标记为 `DOWNLOADED`。
     *
     * 每次启动调用，幂等。不加载到内存，首次推理时由 [ensureModelReady] 真正加载。
     */
    override suspend fun prepareBundledModels(): Int = withContext(Dispatchers.IO) {
        var prepared = 0
        for ((modelId, descriptor) in descriptors) {
            // 仅处理带 assetFileName 的内置模型
            if (descriptor.assetFileName.isNullOrEmpty()) continue
            // 已 LOADED 的跳过（如 InSwapper 在 initialize 中已加载）
            val current = stateFlows[modelId]?.value?.status
            if (current == ModelManager.ModelStatus.LOADED) continue

            try {
                resolveAssetFile(modelId, descriptor)
                val modelFile = getModelFile(modelId, descriptor)
                if (modelFile.exists() && modelFile.length() > 0) {
                    updateState(modelId, ModelManager.ModelStatus.DOWNLOADED, progress = 1f)
                    prepared++
                    Log.i(TAG, "内置模型已就绪: $modelId (${modelFile.name})")
                } else {
                    Log.w(TAG, "内置模型文件未就位: $modelId (asset=${descriptor.assetFileName})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "准备内置模型失败: $modelId", e)
            }
        }
        Log.i(TAG, "内置模型准备完成: $prepared 个已标记为 DOWNLOADED")
        prepared
    }

    override suspend fun copyBundledModels(): Int = withContext(Dispatchers.IO) {
        if (prefs.getBoolean(bundledCopiedKey, false)) {
            Log.d(TAG, "内置模型已复制过，跳过")
            return@withContext 0
        }

        val modelDir = downloadManager.getModelDir()
        var copied = 0

        try {
            val assetModelDir = "models"
            val assetsList = context.assets.list(assetModelDir)
            if (assetsList.isNullOrEmpty()) {
                Log.d(TAG, "assets/models/ 为空，无内置模型可复制")
                return@withContext 0
            }

            for (name in assetsList) {
                val assetPath = "$assetModelDir/$name"
                val destFile = File(modelDir, name)

                try {
                    // 检查是否为子目录 (assets 不支持 list 递归，需判断)
                    val subList = context.assets.list(assetPath)
                    if (subList != null && subList.isNotEmpty()) {
                        // 子目录: 递归复制
                        copied += copyAssetDir(assetPath, File(modelDir, name))
                        continue
                    }

                    // 单个文件
                    if (destFile.exists() && destFile.length() > 0) {
                        Log.d(TAG, "模型已存在，跳过: $name")
                        copied++
                        continue
                    }

                    copyAssetFile(assetPath, destFile)
                    Log.i(TAG, "内置模型复制成功: $name (${destFile.length() / 1024}KB)")

                    // 处理 zip 文件解压
                    if (name.endsWith(".zip")) {
                        copied += extractZip(destFile, modelDir)
                    } else {
                        copied++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "内置模型处理失败: $name", e)
                    destFile.delete()
                }
            }

            prefs.edit().putBoolean(bundledCopiedKey, true).apply()
            Log.i(TAG, "内置模型复制完成: $copied 个文件")
        } catch (e: Exception) {
            Log.w(TAG, "读取 assets/models/ 失败", e)
        }

        copied
    }

    /** 递归复制 assets 子目录 */
    private fun copyAssetDir(assetDir: String, destDir: File): Int {
        var count = 0
        if (!destDir.exists()) destDir.mkdirs()

        val entries = context.assets.list(assetDir) ?: return 0
        for (entry in entries) {
            val assetPath = "$assetDir/$entry"
            val destFile = File(destDir, entry)

            val subList = context.assets.list(assetPath)
            if (subList != null && subList.isNotEmpty()) {
                count += copyAssetDir(assetPath, destFile)
            } else {
                if (!destFile.exists() || destFile.length() == 0L) {
                    copyAssetFile(assetPath, destFile)
                    Log.d(TAG, "复制: $assetPath → ${destFile.absolutePath}")
                }
                count++
            }
        }
        return count
    }

    /** 单个 assets 文件复制 */
    private fun copyAssetFile(assetPath: String, destFile: File) {
        context.assets.open(assetPath).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    /** 解压 zip 文件到目标目录 */
    private fun extractZip(zipFile: File, destDir: File): Int {
        var count = 0
        val buffer = ByteArray(8192)
        try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryFile = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        entryFile.outputStream().use { output ->
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                output.write(buffer, 0, len)
                            }
                        }
                        count++
                        Log.i(TAG, "解压: ${entry.name} (${entryFile.length() / 1024}KB)")
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            Log.i(TAG, "zip 解压完成: ${zipFile.name} → $count 个文件")
        } catch (e: Exception) {
            Log.e(TAG, "zip 解压失败: ${zipFile.name}", e)
        }
        return count
    }

    // ===================== 内部 =====================

    /**
     * 将内置资产模型解析为 modelId 格式的文件名（如 "model:retinaface.onnx"）。
     *
     * 采用「直接从 assets 复制」而非「重命名 modelDir 中已复制文件」的方式，原因：
     * 1. 不依赖 [copyBundledModels] 的后台延迟复制，消除首秒竞态；
     * 2. 复制而非移动，避免多 Provider 共享同一资产文件（如 w600k_r50.onnx）时冲突；
     * 3. 对 buffalo_l.zip 内的成员按需解压单个 entry，无需预先解压整个 zip。
     */
    private fun resolveAssetFile(modelId: String, descriptor: ModelManager.ModelDescriptor) {
        val targetFile = getModelFile(modelId, descriptor)
        if (targetFile.exists() && targetFile.length() > 0) return

        targetFile.parentFile?.mkdirs()
        val assetName = descriptor.assetFileName

        // 1. 直接从 assets 复制单文件模型（retinaface/scrfd/inswapper/sam2/PP-OCR/Moondream2）
        if (assetName != null) {
            val assetPath = if (descriptor.assetSubDir != null) {
                "models/${descriptor.assetSubDir}/$assetName"
            } else {
                "models/$assetName"
            }
            if (copyAssetTo(assetPath, targetFile)) {
                Log.i(TAG, "从 assets 复制模型: $assetPath → ${targetFile.name}")
                return
            }

            // 2. buffalo_l.zip 内的成员（det_10g.onnx / w600k_r50.onnx 等）按需解压
            if (extractFromZipAsset("models/buffalo_l.zip", assetName, targetFile)) {
                Log.i(TAG, "从 buffalo_l.zip 解压模型: $assetName → ${targetFile.name}")
                return
            }
        }

        // 3. 兼容：copyBundledModels 已将资产复制到 modelDir（含子目录），从那里复制
        val modelDir = downloadManager.getModelDir()
        if (assetName != null) {
            val copied = if (descriptor.assetSubDir != null) {
                File(modelDir, "${descriptor.assetSubDir}/$assetName")
            } else {
                File(modelDir, assetName)
            }
            if (copied.exists() && copied.length() > 0 && copyFile(copied, targetFile)) {
                Log.i(TAG, "从 modelDir 复制资产: ${copied.name} → ${targetFile.name}")
                return
            }
        } else if (descriptor.assetSubDir != null) {
            val subDir = File(modelDir, descriptor.assetSubDir)
            subDir.listFiles()?.firstOrNull { it.isFile && it.length() > 0 }?.let { f ->
                if (copyFile(f, targetFile)) {
                    Log.i(TAG, "从 modelDir 子目录复制: ${descriptor.assetSubDir}/${f.name} → ${targetFile.name}")
                    return
                }
            }
        }

        // 4. 兜底：扫描 modelDir 下以 modelId 简化名开头的文件
        val simpleId = modelId.substringAfterLast(":").replace("_", "-")
        val candidates = modelDir.listFiles { f ->
            f.isFile && f.name.contains(simpleId, ignoreCase = true)
        }
        if (candidates != null && candidates.isNotEmpty()) {
            copyFile(candidates.first(), targetFile)
            Log.i(TAG, "模糊匹配复制: ${candidates.first().name} → ${targetFile.name}")
        }
    }

    /** 从 assets 复制单个文件到目标文件，成功返回 true */
    private fun copyAssetTo(assetPath: String, targetFile: File): Boolean {
        return try {
            context.assets.open(assetPath).use { input ->
                targetFile.outputStream().use { output -> input.copyTo(output) }
            }
            targetFile.exists() && targetFile.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    /** 从 assets 中的 zip 包解压指定 entry 到目标文件，成功返回 true */
    private fun extractFromZipAsset(zipAssetPath: String, entryName: String, targetFile: File): Boolean {
        return try {
            context.assets.open(zipAssetPath).use { stream ->
                ZipInputStream(stream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == entryName) {
                            targetFile.outputStream().use { output -> zis.copyTo(output) }
                            return targetFile.exists() && targetFile.length() > 0
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /** 复制本地文件到目标文件，成功返回 true */
    private fun copyFile(src: File, dst: File): Boolean {
        return try {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            dst.exists() && dst.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun getModelFile(modelId: String, descriptor: ModelManager.ModelDescriptor? = null): File {
        val d = descriptor ?: descriptors[modelId]
        val ext = if (d?.runtime == ModelManager.ModelRuntime.ONNX) ".onnx" else ".tflite"
        return File(downloadManager.getModelDir(), "$modelId$ext")
    }

    private fun updateState(
        modelId: String,
        status: ModelManager.ModelStatus,
        progress: Float? = null,
        loadedInMemory: Boolean? = null,
    ) {
        stateFlows[modelId]?.update { current ->
            current.copy(
                status = status,
                progress = progress ?: current.progress,
                loadedInMemory = loadedInMemory ?: current.loadedInMemory,
            )
        }
        emitAllStates()
    }

    private fun emitAllStates() {
        allStatesFlow.value = stateFlows.values.map { it.value }
    }
}
