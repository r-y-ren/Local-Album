package com.renyxin.localalbum.core.model

data class DirectoryNode(
    val path: String,
    val name: String,
    val parentPath: String?,
    val children: List<DirectoryNode>,
    val mediaItems: List<MediaItem>,
) {
    val isLeafDirectory: Boolean
        get() = children.isEmpty()

    /**
     * 该节点直接包含的媒体数量。
     */
    val directMediaCount: Int
        get() = mediaItems.size

    /**
     * 该节点及其所有后代节点包含的媒体总数。
     */
    val totalMediaCount: Int
        get() = mediaItems.size + children.sumOf { it.totalMediaCount }

    /**
     * 该节点下（含后代）的叶子目录数量，便于在树视图中显示"含 N 个子相册"。
     */
    val leafCount: Int
        get() = if (isLeafDirectory) {
            if (mediaItems.isEmpty()) 0 else 1
        } else {
            children.sumOf { it.leafCount }
        }
}