package com.renyxin.localalbum.core.album

import com.renyxin.localalbum.core.model.Album
import com.renyxin.localalbum.core.model.DirectoryNode
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
        1 -> compareByDescending { it.dateRange?.start ?: java.time.Instant.EPOCH }
        2 -> compareByDescending { it.mediaItems.sumOf { item -> item.fileSize } }
        3 -> compareByDescending { it.mediaCount }
        else -> compareBy { it.directoryPath }
    }
}