package com.renyxin.localalbum.core.saf

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore

/** 为 Android 11+ 共享媒体构造系统永久删除授权请求。 */
object MediaStoreDeleteRequest {

    /** 系统删除请求及其未能解析成媒体 URI 的路径（继续走应用内删除链路并如实上报）。 */
    data class DeleteRequest(
        val intentSender: android.content.IntentSender,
        val unresolvedPaths: List<String>,
    )

    /**
     * 只处理 MediaStore 中能按绝对路径解析到的媒体；解析不到的路径通过
     * [DeleteRequest.unresolvedPaths] 交还给调用方，不再静默丢弃——否则用户在
     * 系统弹窗里确认"删除"后，这部分文件会永远留在回收站且无任何提示。
     */
    fun create(context: Context, paths: Collection<String>): DeleteRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || paths.isEmpty()) return null
        val resolver = context.contentResolver
        val uris = mutableListOf<Uri>()
        val unresolved = mutableListOf<String>()
        paths.distinct().forEach { path ->
            val uri = resolveUri(resolver, path)
            if (uri != null) uris += uri else unresolved += path
        }
        if (uris.isEmpty()) return null
        val pendingIntent = runCatching {
            MediaStore.createDeleteRequest(resolver, uris)
        }.getOrNull() ?: return null
        return DeleteRequest(pendingIntent.intentSender, unresolved)
    }

    private fun resolveUri(resolver: ContentResolver, path: String): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val queryArgs = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DATA} = ?")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(path),
            )
            // 默认查询会排除已被系统移入回收站（IS_TRASHED=1）的行；不包含它们会让
            // 这些文件解析失败并落入注定失败的 File.delete() 兜底。
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(collection, projection, queryArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        return null
    }
}
