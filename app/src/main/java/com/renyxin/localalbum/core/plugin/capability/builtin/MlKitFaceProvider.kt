package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import com.renyxin.localalbum.core.plugin.capability.ComposedFaceProvider
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import java.io.File

/**
 * 基于 ML Kit 的内置默认人脸 Provider（Phase 3 重构）。
 *
 * 现在内部组合 [MlKitFaceDetector] + [MlKitFaceEmbedder]，
 * 通过 [ComposedFaceProvider] 适配为 [FaceProvider] 接口。
 * 保持与旧版 [FaceProvider] 的完全兼容。
 */
class MlKitFaceProvider(
    private val context: Context,
) : FaceProvider {

    private val delegate: ComposedFaceProvider by lazy {
        ComposedFaceProvider(
            detector = MlKitFaceDetector(context),
            embedder = MlKitFaceEmbedder(context),
        )
    }

    override val providerId = "builtin:mlkit"
    override val displayName = "ML Kit 人脸检测 (默认)"
    override val embeddingDim = delegate.embeddingDim

    override suspend fun detectFaces(file: File): List<FaceProvider.DetectedFace> {
        return delegate.detectFaces(file)
    }

    override suspend fun release() {
        delegate.release()
    }
}
