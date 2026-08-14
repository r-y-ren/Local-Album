package com.renyxin.localalbum.edition

import android.content.Context
import com.renyxin.localalbum.core.pipeline.FullScanFeaturePolicy
import com.renyxin.localalbum.data.worker.FaceClusterMaintenanceWorker
import com.renyxin.localalbum.core.plugin.capability.CapabilityRegistryV2
import com.renyxin.localalbum.core.plugin.capability.CapabilitySlot
import com.renyxin.localalbum.core.plugin.capability.OcrProvider
import com.renyxin.localalbum.core.plugin.capability.ProviderType
import com.renyxin.localalbum.core.plugin.capability.builtin.MlKitOcrProvider
import com.renyxin.localalbum.core.plugin.capability.builtin.PaddleOCRProvider
import com.renyxin.localalbum.core.plugin.model.GLMOcrProvider
import com.renyxin.localalbum.core.plugin.model.ModelManager
import com.renyxin.localalbum.core.search.KeywordSearchProfile

object EditionConfiguration {
    val features = EditionFeatures(
        editionId = "full",
        scanFeaturePolicy = FullScanFeaturePolicy,
        registeredCapabilitySlots = EditionFeatures.ALL_CAPABILITY_SLOTS,
        keywordSearchProfile = KeywordSearchProfile.FULL,
        showPeopleAlbums = true,
        enableSemanticSearch = true,
        showAiAnalysisPreferences = true,
        showPluginManager = true,
        showFaceSwap = true,
        allowFaceClusterMaintenance = true,
    )

    fun registerEditionCapabilityProviders(
        registry: CapabilityRegistryV2,
        modelManager: ModelManager,
        appContext: Context,
    ) = with(registry) {
        registerSlot(
            CapabilitySlot(
                slotId = "ocr",
                displayName = "文字识别 (OCR)",
                description = "识别图像中的文字内容",
                interfaceClass = OcrProvider::class,
                defaultProviderId = "model:paddleocr",
                icon = "📝",
                required = false,
            ),
        )
        registerProvider(
            slotId = "ocr",
            providerId = "model:paddleocr",
            provider = PaddleOCRProvider(modelManager, appContext),
            isDefault = true,
            providerType = ProviderType.MODEL,
        )
        registerProvider(
            slotId = "ocr",
            providerId = "model:glm_ocr",
            provider = GLMOcrProvider(modelManager, appContext),
            providerType = ProviderType.MODEL,
        )
        registerProvider(
            slotId = "ocr",
            providerId = "builtin:mlkit_ocr",
            provider = MlKitOcrProvider(appContext),
            providerType = ProviderType.BUILTIN,
        )
        registerProviderModelIds(
            slotId = "ocr",
            providerId = "model:paddleocr",
            modelIds = listOf("model:paddleocr_det", "model:paddleocr_rec"),
        )
        registerProviderModelIds(
            slotId = "ocr",
            providerId = "model:glm_ocr",
            modelIds = listOf("model:glm_ocr_encoder", "model:glm_ocr_decoder"),
        )
    }

    fun enqueueFaceClusterMaintenance(context: Context) {
        FaceClusterMaintenanceWorker.enqueue(context)
    }
}
