package com.renyxin.localalbum.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import java.io.File
import java.io.RandomAccessFile

/**
 * 图像解码的统一回退链：BitmapFactory 优先，返回 null 时回退 ImageDecoder。
 *
 * BitmapFactory 在部分设备/系统版本上无法解码 16-bit PNG（ComfyUI 等 AI 生成器的
 * 常见输出），返回 null；ImageDecoder 原生支持这类编码并按目标采样规整化。
 * 两条链都失败才视为文件不可解码（此时调用方可判定文件损坏）。
 *
 * 回退链保证产出 ARGB_8888 软件位图：后续像素读取（getPixels）与再缩放
 * 都要求该配置，ImageDecoder 在宽色域源上可能给出 RGBA_F16。
 */
object RobustImageDecode {

    /** 路径解码（语义分析等）：先按 maxDim 采样解码，null 再走 ImageDecoder。 */
    fun decodeFile(file: File, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = powerOfTwoSample(bounds.outWidth, bounds.outHeight, maxDim)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)?.let { return it }
        return decodeViaImageDecoder(ImageDecoder.createSource(file), maxDim)
    }

    /**
     * 已校验 fd 解码（缩略图生成）：BitmapFactory 主链沿用单 fd 语义（每次解码前 seek(0)）。
     * ImageDecoder 没有 FileDescriptor 重载，兜底改按 [canonicalPath] 打开；
     * 调用方（generateThumbnailSync）在解码后仍做 before/after/path 三重签名校验，
     * 兜底期间文件被替换的情况依旧会被拒绝发布。
     */
    fun decodeFd(source: RandomAccessFile, canonicalPath: String, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        source.seek(0L)
        BitmapFactory.decodeFileDescriptor(source.fd, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = powerOfTwoSample(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        source.seek(0L)
        BitmapFactory.decodeFileDescriptor(source.fd, null, opts)?.let { return it }
        val decoded = runCatching {
            decodeViaImageDecoder(ImageDecoder.createSource(File(canonicalPath)), targetPx)
        }.getOrNull()
        source.seek(0L)
        return decoded
    }

    /** 16-bit PNG 等冷门编码：BitmapFactory 拒绝，ImageDecoder 兜底。 */
    private fun decodeViaImageDecoder(imageSource: ImageDecoder.Source, maxDim: Int): Bitmap? =
        runCatching {
            val decoded = ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(
                    powerOfTwoSample(info.size.width, info.size.height, maxDim),
                )
            }
            decoded.asArgb8888()
        }.getOrNull()

    private fun Bitmap.asArgb8888(): Bitmap =
        if (config == Bitmap.Config.ARGB_8888) this
        else copy(Bitmap.Config.ARGB_8888, false)

    private fun powerOfTwoSample(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) sample *= 2
        return sample
    }
}
