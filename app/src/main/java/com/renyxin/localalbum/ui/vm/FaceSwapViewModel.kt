package com.renyxin.localalbum.ui.vm

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.PluginOutput
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.extension.ExtensionPluginRegistry
import com.renyxin.localalbum.core.plugin.extension.OnDemandGenerativePlugin
import com.renyxin.localalbum.core.plugin.extension.OnDemandRuntimeState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Lightweight host for the direct face-swap entry; it does not expose plugin-management state. */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceSwapViewModel(
    private val capabilityRegistry: CapabilityRegistryV2,
    private val extensionRegistry: ExtensionPluginRegistry,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data class Success(val bitmap: Bitmap) : UiState
        data class Error(val message: String) : UiState
    }

    /**
     * Availability is independent from native residency. A descriptor-registered plugin remains
     * actionable while its models are absent from memory; execution performs targeted preparation.
     */
    val readiness: StateFlow<OnDemandRuntimeState> =
        extensionRegistry.pluginStates.flatMapLatest { plugins ->
            val listed = plugins.any { it.pluginId == IN_SWAPPER_ID && it.isReady }
            val plugin = extensionRegistry.getPlugin(IN_SWAPPER_ID) as? OnDemandGenerativePlugin
            if (!listed || plugin == null) {
                flowOf(OnDemandRuntimeState.ERROR)
            } else {
                combine(capabilityRegistry.slotMetadataList, plugin.runtimeState) { slots, runtime ->
                    val faceSlot = slots.firstOrNull { it.slotId == FACE_SLOT }
                    val activeFace = capabilityRegistry.getActiveProvider<FaceProvider>(FACE_SLOT)
                    if (
                        faceSlot == null ||
                        activeFace == null ||
                        activeFace.embeddingDim != REQUIRED_EMBEDDING_DIM ||
                        !activeFace.supportsFivePointLandmarks
                    ) {
                        OnDemandRuntimeState.ERROR
                    } else {
                        runtime
                    }
                }
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            OnDemandRuntimeState.ERROR,
        )

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var performJob: Job? = null

    fun perform(sourceFile: File, targetFile: File) {
        // A cancelled job may still be executing NonCancellable model cleanup. Do not admit a new
        // request until that job is fully complete, otherwise its finally block could evict sessions
        // registered by the new request.
        if (performJob?.isCompleted == false) return
        performJob = viewModelScope.launch {
            updateState(UiState.Loading)
            try {
                val plugin = extensionRegistry.getPlugin(IN_SWAPPER_ID)
                    as? OnDemandGenerativePlugin
                if (plugin == null || !plugin.isReady()) {
                    updateState(UiState.Error("换脸插件不可用，请稍后重试"))
                    return@launch
                }
                val activeFace = capabilityRegistry.getActiveProvider<FaceProvider>(FACE_SLOT)
                if (activeFace == null) {
                    updateState(UiState.Error("换脸失败：未激活人脸检测 Provider"))
                    return@launch
                }
                if (activeFace.embeddingDim != REQUIRED_EMBEDDING_DIM) {
                    updateState(
                        UiState.Error(
                            "换脸失败：当前人脸 Provider「${activeFace.displayName}」嵌入维度 " +
                                "${activeFace.embeddingDim}≠$REQUIRED_EMBEDDING_DIM，请切换为 InsightFace Provider",
                        ),
                    )
                    return@launch
                }
                if (!activeFace.supportsFivePointLandmarks) {
                    updateState(
                        UiState.Error(
                            "换脸失败：当前人脸 Provider「${activeFace.displayName}」不提供五点关键点，请切换为 InsightFace Provider",
                        ),
                    )
                    return@launch
                }
                val output = withTimeout(FACE_SWAP_TIMEOUT_MS) {
                    plugin.execute(
                        PluginInput.MultiModalInput(
                            listOf(
                                PluginInput.ImageInput(file = sourceFile),
                                PluginInput.ImageInput(file = targetFile),
                            ),
                        ),
                    )
                }
                val bitmap = (output as? PluginOutput.ImageOutput)?.bitmap
                updateState(
                    if (bitmap != null) {
                        UiState.Success(bitmap)
                    } else {
                        UiState.Error("换脸失败：未检测到人脸或推理失败")
                    },
                )
            } catch (_: TimeoutCancellationException) {
                updateState(UiState.Error("换脸超时，模型资源已释放，请重试"))
            } catch (cancelled: CancellationException) {
                updateState(UiState.Idle)
                throw cancelled
            } catch (error: Throwable) {
                updateState(UiState.Error("换脸失败: ${error.message ?: error.javaClass.simpleName}"))
            } finally {
                val finishingJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                if (performJob === finishingJob) performJob = null
            }
        }
    }

    fun cancel() {
        performJob?.cancel()
    }

    /** Called by the route's dispose hook; cancellation triggers the plugin's NonCancellable cleanup. */
    fun onPageExit() {
        cancel()
        recycleResult()
        _state.value = UiState.Idle
        viewModelScope.launch {
            (extensionRegistry.getPlugin(IN_SWAPPER_ID) as? OnDemandGenerativePlugin)
                ?.releaseRuntimeResources()
        }
    }

    fun reset() {
        recycleResult()
        updateState(UiState.Idle)
    }

    override fun onCleared() {
        performJob?.cancel()
        recycleResult()
        super.onCleared()
    }

    private fun updateState(newState: UiState) {
        val previous = _state.value
        if (previous is UiState.Success && previous.bitmap !== (newState as? UiState.Success)?.bitmap) {
            if (!previous.bitmap.isRecycled) previous.bitmap.recycle()
        }
        _state.value = newState
    }

    private fun recycleResult() {
        val bitmap = (_state.value as? UiState.Success)?.bitmap ?: return
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    class Factory(
        private val capabilityRegistry: CapabilityRegistryV2,
        private val extensionRegistry: ExtensionPluginRegistry,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FaceSwapViewModel::class.java))
            return FaceSwapViewModel(capabilityRegistry, extensionRegistry) as T
        }
    }

    private companion object {
        const val FACE_SLOT = "face"
        const val IN_SWAPPER_ID = "plugin.inswapper"
        const val REQUIRED_EMBEDDING_DIM = 512
        const val FACE_SWAP_TIMEOUT_MS = 120_000L
    }
}
