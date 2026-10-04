package com.renyxin.localalbum.core.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * SAF 树授权删除：MediaStore 解析不到的文件（.nomedia 目录、未索引的自定义扫描根）
 * 在 Android 13+ 上唯一的删除途径。
 *
 * 用户通过 ACTION_OPEN_DOCUMENT_TREE 授权文件的某个祖先目录后，沿路径分段用
 * findFile 逐级下钻定位文档节点并删除。授权持久化（takePersistableUriPermission
 * 由调用方在回调里执行），同一目录后续删除不再弹选择器。
 */
object SafTreeDelete {

    data class Outcome(val deleted: List<String>, val notFound: List<String>)

    /** 授权树是否覆盖该文件（按主存储分段匹配，树根名须与文件首段一致）。 */
    fun covers(context: Context, treeUri: Uri, filePath: String): Boolean {
        val segments = primarySegments(filePath) ?: return false
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
        val rootName = root.name ?: return false
        return segments.isNotEmpty() && segments.first() == rootName
    }

    /**
     * 删除授权树下的一批文件。逐级 findFile 定位；找不到的文件归入
     * [Outcome.notFound]（可能已被其他途径删除，调用方可按 MISSING 清库）。
     */
    fun deleteUnderTree(context: Context, treeUri: Uri, paths: List<String>): Outcome {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return Outcome(emptyList(), paths)
        val deleted = mutableListOf<String>()
        val notFound = mutableListOf<String>()
        for (path in paths.distinct()) {
            val segments = primarySegments(path)
            if (segments == null || segments.isEmpty() ||
                segments.first() != (root.name ?: "")
            ) {
                notFound += path
                continue
            }
            var node: DocumentFile = root
            var resolved = true
            // 树根已匹配首段，从第二段开始下钻
            for (segment in segments.drop(1)) {
                val next = node.findFile(segment)
                if (next == null) {
                    resolved = false
                    break
                }
                node = next
            }
            if (resolved && node.isFile && node.delete()) {
                deleted += path
            } else {
                notFound += path
            }
        }
        return Outcome(deleted, notFound)
    }

    /** /storage/emulated/0/… → [DCIM, APictures, …, file.jpg]；其他卷返回 null。 */
    private fun primarySegments(filePath: String): List<String>? {
        val marker = "/storage/emulated/0/"
        if (!filePath.startsWith(marker)) return null
        return filePath.removePrefix(marker)
            .split('/')
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
    }

    /** 构造 ACTION_OPEN_DOCUMENT_TREE 的初始位置提示（尽力而为，失败不影响选择）。 */
    fun initialUriHint(): Uri? = runCatching {
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:",
        )
    }.getOrNull()
}
