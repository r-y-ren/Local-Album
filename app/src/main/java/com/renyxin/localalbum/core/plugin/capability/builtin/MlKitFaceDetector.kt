package com.renyxin.localalbum.core.plugin.capability.builtin

import android.content.Context
import android.graphics.RectF
import com.renyxin.localalbum.core.analysis.FaceDetector
import com.renyxin.localalbum.core.plugin.capability.FaceDetector as IFaceDetector
import java.io.File

/**
 * 基于 ML Kit 的人脸检测器（仅检测边界框，不做嵌入提取）— Phase 3 拆分。
 *
 * 包裹项目现有的 [FaceDetector]，仅暴露 [IFaceDetector.detect] 接口。
 * 嵌入提取已拆分到 [MlKitFaceEmbedder]。
 *
 * @see MlKitFaceEmbedder ML Kit 人脸嵌入器
 * @see MlKitFaceProvider 组合式 FaceProvider（兼容旧接口）
 */
class MlKitFaceDetector(
    private val context: Context,
) : IFaceDetector {

    override val detectorId = "builtin:mlkit"
    override val displayName = "ML Kit 人脸检测"

    private val detector = FaceDetector(context)

    override suspend fun detect(file: File): List<RectF> {
        val result = detector.detect(file)
        return result.faces.map { face ->
            RectF(face.box)
        }
    }

    override suspend fun release() {
        detector.close()
    }
}
