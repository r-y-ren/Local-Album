package com.renyxin.localalbum.data.repo

import java.io.File

/**
 * 物理文件删除的可测试策略。
 *
 * 只有删除成功或源文件原本已不存在的路径才能进入数据库 purge；删除失败与异常路径必须保留。
 */
internal object PhysicalFileDeletion {
    enum class Outcome { DELETED, MISSING, FAILED }

    data class ItemResult(
        val path: String,
        val outcome: Outcome,
        val errorType: String? = null,
        val errorCode: String? = null,
        /** 不包含路径或异常原始 message，适合持久化及诊断 UI。 */
        val safeErrorMessage: String? = null,
    )

    data class Result(val items: List<ItemResult>) {
        val deletedOrMissing: List<String>
            get() = items.filter { it.outcome != Outcome.FAILED }.map { it.path }
        val failed: List<String>
            get() = items.filter { it.outcome == Outcome.FAILED }.map { it.path }
    }

    fun delete(
        paths: List<String>,
        deleteExisting: (File) -> Boolean = File::delete,
    ): Result = Result(paths.distinct().map { path ->
        val file = File(path)
        try {
            when {
                !file.exists() -> ItemResult(path, Outcome.MISSING)
                !file.isFile -> ItemResult(path, Outcome.FAILED, "NotRegularFile", "NOT_FILE", "目标不是普通文件")
                deleteExisting(file) -> ItemResult(path, Outcome.DELETED)
                else -> ItemResult(path, Outcome.FAILED, "DeleteRejected", "DELETE_FALSE", "系统拒绝删除或权限不足")
            }
        } catch (error: Exception) {
            ItemResult(
                path = path,
                outcome = Outcome.FAILED,
                errorType = error.javaClass.simpleName.take(80),
                errorCode = "EXCEPTION",
                safeErrorMessage = "文件删除异常（详情已脱敏）",
            )
        }
    })
}
