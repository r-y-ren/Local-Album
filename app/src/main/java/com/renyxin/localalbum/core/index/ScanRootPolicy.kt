package com.renyxin.localalbum.core.index

import java.io.File
import java.nio.file.Files

/** 扫描根目录与目录遍历的统一身份策略。 */
object ScanRootPolicy {
    /**
     * 将根目录解析为规范路径并按首次出现顺序去重。
     * 嵌套根不在此删除：遍历器使用共享 visited 集合，既保留显式根语义又只访问一次目录。
     */
    fun normalize(rootPaths: List<String>): List<String> {
        val roots = LinkedHashMap<String, String>()
        rootPaths.forEach { rawPath ->
            val raw = rawPath.trim()
            if (raw.isEmpty()) return@forEach
            val file = File(raw)
            val canonical = runCatching { file.canonicalFile }.getOrElse { file.absoluteFile }
            roots.putIfAbsent(canonical.path, canonical.path)
        }
        return roots.values.toList()
    }

    /** 返回稳定目录身份；无法解析时拒绝访问，避免同一异常路径不断重新入队。 */
    fun directoryKey(directory: File): String? =
        runCatching { directory.canonicalPath }.getOrNull()

    /** 子目录符号链接不跟随，阻断指回祖先或逃逸扫描根的循环。 */
    fun isSymbolicLink(directory: File): Boolean =
        runCatching { Files.isSymbolicLink(directory.toPath()) }.getOrDefault(false)
}
