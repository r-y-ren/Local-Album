package com.renyxin.localalbum.core.index

import android.util.Log
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * 扫描忽略规则（正则）匹配工具。
 *
 * 用户在设置中配置的"忽略规则"为正则表达式字符串列表，扫描时统一编译为
 * [Pattern] 并对**目录名**与**文件名**做 [Pattern.find]（部分匹配）。
 * 非法正则在编译阶段被跳过并记录日志，避免单条坏规则导致整个扫描流程崩溃。
 *
 * 匹配语义：[Pattern.find]（等价于 Kotlin `Regex.containsMatchIn`），
 * 即只要名称中存在匹配子串即命中。用户可用 `^` / `$` 自行锚定开头/结尾，
 * 例如 `^.trash` 命中所有以 `.trash` 开头的目录名/文件名。
 */
object IgnorePatternMatcher {

    private const val TAG = "IgnorePatternMatcher"

    /**
     * 将用户配置的正则字符串列表编译为 [Pattern] 列表。
     *
     * 空白规则会被忽略；非法正则会被跳过并记录 warning 日志，不影响其余规则。
     *
     * @param patterns 用户配置的正则字符串列表
     * @return 编译成功、可用的 [Pattern] 列表（可能为空）
     */
    fun compile(patterns: List<String>): List<Pattern> {
        if (patterns.isEmpty()) return emptyList()
        val compiled = ArrayList<Pattern>(patterns.size)
        for (raw in patterns) {
            val p = raw.trim()
            if (p.isEmpty()) continue
            try {
                compiled += Pattern.compile(p)
            } catch (e: PatternSyntaxException) {
                Log.w(TAG, "忽略规则正则非法，已跳过: \"$p\" (${e.message})")
            }
        }
        return compiled
    }

    /**
     * 判断 [name] 是否命中任一已编译规则。
     *
     * @param name 待检测的目录名或文件名
     * @param patterns 已编译的正则规则列表
     * @return true 表示命中，应跳过该目录/文件
     */
    fun matchesAny(name: String, patterns: List<Pattern>): Boolean {
        if (patterns.isEmpty()) return false
        for (p in patterns) {
            if (p.matcher(name).find()) return true
        }
        return false
    }
}
