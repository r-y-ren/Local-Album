package com.renyxin.localalbum.core.plugin.extension

/** Stable categories for actionable failures raised by the on-demand face-swap path. */
enum class OnDemandExecutionFailureKind {
    DESCRIPTOR_UNAVAILABLE,
    PROVIDER_INCOMPATIBLE,
    MODEL_ASSET_MISSING,
    NATIVE_RUNTIME,
    INFERENCE,
}

/**
 * An actionable interactive-execution failure.
 *
 * Expected detection misses continue to use an empty image output. Configuration, model asset, and
 * native runtime failures use this exception so callers can distinguish them without changing the
 * shared PluginOutput.ImageOutput constructor ABI.
 */
class OnDemandExecutionException(
    val kind: OnDemandExecutionFailureKind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** Pure policy shared by the plugin and both UI entry points. */
internal object FaceSwapExecutionPolicy {
    const val REQUIRED_EMBEDDING_DIM = 512

    fun descriptorFailure(): OnDemandExecutionException =
        OnDemandExecutionException(
            kind = OnDemandExecutionFailureKind.DESCRIPTOR_UNAVAILABLE,
            message = "换脸插件描述符尚未注册，请稍后重试",
        )

    fun compatibilityFailure(
        providerDisplayName: String?,
        embeddingDim: Int?,
        supportsFivePointLandmarks: Boolean,
    ): OnDemandExecutionException? = when {
        providerDisplayName == null || embeddingDim == null ->
            OnDemandExecutionException(
                kind = OnDemandExecutionFailureKind.PROVIDER_INCOMPATIBLE,
                message = "未激活人脸检测 Provider",
            )

        embeddingDim != REQUIRED_EMBEDDING_DIM ->
            OnDemandExecutionException(
                kind = OnDemandExecutionFailureKind.PROVIDER_INCOMPATIBLE,
                message =
                    "当前人脸 Provider「$providerDisplayName」嵌入维度 " +
                        "$embeddingDim≠$REQUIRED_EMBEDDING_DIM，请切换为 InsightFace Provider",
            )

        !supportsFivePointLandmarks ->
            OnDemandExecutionException(
                kind = OnDemandExecutionFailureKind.PROVIDER_INCOMPATIBLE,
                message =
                    "当前人脸 Provider「$providerDisplayName」不提供五点关键点，" +
                        "请切换为 InsightFace Provider",
            )

        else -> null
    }

    fun operationalFailure(error: Throwable): OnDemandExecutionException {
        if (error is OnDemandExecutionException) return error

        val causes = generateSequence(error) { it.cause }.toList()
        val searchable = causes.joinToString("\n") { cause ->
            "${cause.javaClass.name}: ${cause.message.orEmpty()}"
        }
        val detail = causes
            .firstNotNullOfOrNull { cause -> cause.message?.takeIf(String::isNotBlank) }
            ?: error.javaClass.simpleName

        return when {
            causes.any { it is UnsatisfiedLinkError } ||
                NATIVE_RUNTIME_MARKERS.any { searchable.contains(it, ignoreCase = true) } ->
                OnDemandExecutionException(
                    kind = OnDemandExecutionFailureKind.NATIVE_RUNTIME,
                    message =
                        "本机 AI 运行库初始化失败，请确认安装包架构完整后重试（$detail）",
                    cause = error,
                )

            MODEL_ASSET_MARKERS.any { searchable.contains(it, ignoreCase = true) } ->
                OnDemandExecutionException(
                    kind = OnDemandExecutionFailureKind.MODEL_ASSET_MISSING,
                    message =
                        "所需换脸模型资产缺失，请安装 InsightFace 与 InSwapper 模型后重试" +
                            "（$detail）",
                    cause = error,
                )

            else ->
                OnDemandExecutionException(
                    kind = OnDemandExecutionFailureKind.INFERENCE,
                    message = "按需加载或推理失败：$detail",
                    cause = error,
                )
        }
    }

    fun idleState(descriptorReady: Boolean): OnDemandRuntimeState =
        if (descriptorReady) {
            OnDemandRuntimeState.AVAILABLE_NEEDS_LOAD
        } else {
            OnDemandRuntimeState.ERROR
        }

    private val MODEL_ASSET_MARKERS = listOf(
        "模型文件不存在",
        "模型未注册",
        "model file",
        "asset is missing",
        "no such file",
        "emap_512.bin",
        "buffalo_l.zip",
    )

    private val NATIVE_RUNTIME_MARKERS = listOf(
        "emutls",
        "opencv native runtime initialization",
        "dlopen failed",
        "couldn't find dso",
        "native library",
    )
}
