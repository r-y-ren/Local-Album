package com.renyxin.localalbum.core.index

import com.renyxin.localalbum.core.thumbnail.HeadFingerprint
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 单次扫描运行内共享的文件级读取缓存，随扫描结束整体丢弃。
 *
 * - 头指纹（前 4096 字节 SHA-256）会在 MediaStore 引用 sourceVersion、缩略图缓存
 *   探测与入库转换中被同一文件重复需要；同一扫描内只读一次盘，后续直接复用。
 * - 视频元数据超时黑名单：native 提取不可中断且超时线程无法真正回收，同一扫描
 *   内对已超时的文件直接跳过，避免坏文件在多源枚举/重试中反复吃满 3 秒。
 *
 * 必须只在一个 scan run 生命周期内使用：跨扫描复用会在文件被替换后返回陈旧指纹。
 * 读取失败（文件缺失等）返回 null 且不缓存，下次调用照常重试。
 */
class ScanRunCache {
    private val fingerprintHeads = ConcurrentHashMap<String, String>()
    private val timedOutVideoPaths: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun fingerprintHead(path: String): String? {
        fingerprintHeads[path]?.let { return it }
        val computed = runCatching { HeadFingerprint.compute(File(path)) }.getOrNull()
        if (computed != null) fingerprintHeads[path] = computed
        return computed
    }

    fun isTimedOutVideo(path: String): Boolean = path in timedOutVideoPaths

    fun markTimedOutVideo(path: String) {
        timedOutVideoPaths.add(path)
    }
}
