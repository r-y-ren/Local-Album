package com.renyxin.localalbum.core.album

import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.DirectoryNode
import com.renyxin.localalbum.data.db.dao.DirectorySummary
import java.util.UUID

class AlbumBuilder {

    /**
     * 仅取叶子目录构建相册平铺列表（向后兼容旧调用与测试）。
     * @param sortMode 排序模式: 0=名称, 1=日期, 2=大小, 3=数量
     */
    fun buildFromDirectoryTree(
        rootNodes: List<DirectoryNode>,
        sortMode: Int = 0,
    ): List<Album> {
        val leafNodes = mutableListOf<DirectoryNode>()
        rootNodes.forEach { collectLeafNodes(it, leafNodes) }

        return leafNodes
            .filter { it.mediaItems.isNotEmpty() }
            .map { node ->
                Album(
                    id = albumId(node.path),
                    name = node.name,
                    directoryPath = node.path,
                    mediaItems = node.mediaItems.sortedBy { it.capturedAt },
                    parentPath = node.parentPath,
                )
            }
            .sortedWith(albumComparator(sortMode))
    }

    /**
     * 构建保留目录层级的相册树。
     *
     * - 每个目录节点对应一个 [Album]，含 [Album.children] 子相册。
     * - 同时收集本目录直接包含的媒体到 [Album.mediaItems]。
     * - 若某节点自身无媒体且其后代也无任何媒体，则返回 null，避免空相册污染树。
     * @param sortMode 排序模式: 0=名称, 1=日期, 2=大小, 3=数量
     */
    fun buildAlbumTree(
        rootNodes: List<DirectoryNode>,
        sortMode: Int = 0,
    ): List<Album> {
        val comparator = albumComparator(sortMode)
        return rootNodes.mapNotNull { node ->
            buildAlbumNode(node, parentPath = null, depth = 0, comparator = comparator)
        }
    }

    private fun buildAlbumNode(
        node: DirectoryNode,
        parentPath: String?,
        depth: Int,
        comparator: Comparator<Album>,
    ): Album? {
        val childAlbums = node.children.mapNotNull { child ->
            buildAlbumNode(child, parentPath = node.path, depth = depth + 1, comparator = comparator)
        }.sortedWith(comparator)
        val media = node.mediaItems.sortedBy { it.capturedAt }

        if (media.isEmpty() && childAlbums.isEmpty()) return null

        return Album(
            id = albumId(node.path),
            name = node.name,
            directoryPath = node.path,
            mediaItems = media,
            parentPath = parentPath,
            depth = depth,
            children = childAlbums,
        )
    }

    private fun collectLeafNodes(node: DirectoryNode, out: MutableList<DirectoryNode>) {
        if (node.isLeafDirectory) {
            out += node
            return
        }
        node.children.forEach { collectLeafNodes(it, out) }
    }

    /**
     * 从数据库聚合摘要构建轻量目录树。节点只持有目录级元数据，不持有媒体实体。
     */
    fun buildFromDirectorySummaries(
        summaries: List<DirectorySummary>,
        roots: List<String>,
        sortMode: Int = 0,
    ): Pair<List<Album>, List<Album>> {
        if (summaries.isEmpty()) return emptyList<Album>() to emptyList()
        val nodes = linkedMapOf<String, SummaryNode>()
        summaries.forEach { summary ->
            nodes[summary.parentPath] = SummaryNode(summary = summary)
            var parent = summary.parentPath.substringBeforeLast('/', "")
            while (parent.isNotEmpty() && roots.any { isUnderRoot(parent, it) }) {
                nodes.putIfAbsent(parent, SummaryNode())
                parent = parent.substringBeforeLast('/', "")
            }
        }
        nodes.keys.forEach { path ->
            val parent = path.substringBeforeLast('/', "")
            if (parent in nodes) nodes.getValue(parent).childPaths += path
        }

        fun build(path: String, parentPath: String?, depth: Int): Album {
            val node = nodes.getValue(path)
            val summary = node.summary
            val children = node.childPaths.map { build(it, path, depth + 1) }
            val album = Album(
                id = albumId(path),
                name = path.substringAfterLast('/').ifEmpty { path },
                directoryPath = path,
                parentPath = parentPath,
                depth = depth,
                children = children,
                directMediaCount = summary?.mediaCount ?: 0,
                latestCapturedAtMs = summary?.latestCapturedAtMs ?: 0L,
                summaryCoverPaths = listOfNotNull(
                    summary?.coverThumbnailPath ?: summary?.coverFilePath,
                ),
            )
            return album.copy(children = children.sortedWith(albumComparator(sortMode)))
        }

        val childPaths = nodes.values.flatMap { it.childPaths }.toSet()
        val rootPaths = nodes.keys.filter { path ->
            path !in childPaths && (roots.isEmpty() || roots.any { isUnderRoot(path, it) })
        }
        val tree = rootPaths.map { build(it, null, 0) }.sortedWith(albumComparator(sortMode))
        val leaves = mutableListOf<Album>()
        fun collect(album: Album) {
            if (album.mediaCount > 0) leaves += album
            album.children.forEach(::collect)
        }
        tree.forEach(::collect)
        return tree to leaves.sortedWith(albumComparator(sortMode))
    }

    private data class SummaryNode(
        var summary: DirectorySummary? = null,
        val childPaths: MutableList<String> = mutableListOf(),
    )

    private fun isUnderRoot(path: String, root: String): Boolean =
        path == root || path.startsWith("$root/")

    private fun albumId(path: String): String =
        UUID.nameUUIDFromBytes(path.toByteArray()).toString()

    /**
     * 根据排序模式生成相册比较器。
     * - 0=名称（默认升序）
     * - 1=日期（最新优先，按 dateRange start 降序）
     * - 2=大小（总文件大小降序）
     * - 3=数量（媒体数量降序）
     */
    private fun albumComparator(sortMode: Int): Comparator<Album> = when (sortMode) {
        1 -> compareByDescending { it.latestCapturedAtMs }
        // 轻量摘要不持有文件大小总和；模式 2 使用媒体数作为稳定近似，避免全表读取。
        2 -> compareByDescending { it.mediaCount }
        3 -> compareByDescending { it.mediaCount }
        else -> compareBy { it.directoryPath }
    }
}