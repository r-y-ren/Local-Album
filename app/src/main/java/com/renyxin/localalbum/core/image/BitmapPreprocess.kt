package com.renyxin.localalbum.core.image

import android.graphics.Bitmap

/**
 * 分类/嵌入类模型的通用预处理缩放：短边等比缩放到 [size]，再中心裁剪 [size]×[size]。
 *
 * 直接把任意长宽比硬拉伸到正方形会破坏纵横比（全景图/长截图被压扁），正是
 * OCR 错字与场景误判的同类根源；CLIP/MobileNet 官方预处理均为短边缩放+中心裁剪。
 */
fun centerCropSquare(src: Bitmap, size: Int): Bitmap {
    if (src.width == size && src.height == size) return src
    val shortSide = minOf(src.width, src.height)
    val scale = size.toFloat() / shortSide
    val sw = (src.width * scale).toInt().coerceAtLeast(1)
    val sh = (src.height * scale).toInt().coerceAtLeast(1)
    val scaled = if (sw != src.width || sh != src.height) {
        Bitmap.createScaledBitmap(src, sw, sh, true)
    } else {
        src
    }
    if (scaled.width == size && scaled.height == size) return scaled
    val x = (scaled.width - size) / 2
    val y = (scaled.height - size) / 2
    return Bitmap.createBitmap(scaled, x, y, size, size)
}
