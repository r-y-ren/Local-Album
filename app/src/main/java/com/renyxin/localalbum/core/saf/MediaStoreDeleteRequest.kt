package com.renyxin.localalbum.core.saf

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** 为 Android 11+ 共享媒体构造系统永久删除授权请求。 */
object MediaStoreDeleteRequest {
    /**
     * 只返回 MediaStore 中能按绝对路径解析到的媒体 URI。
     * 无法解析的路径继续交给普通文件删除链路处理并报告失败。
     */
    fun create(context: Context, paths: Collection<String>): PendingIntent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || paths.isEmpty()) return null
        val resolver = context.contentResolver
        val uris = paths.distinct().mapNotNull { path -> resolveUri(context, path) }
        if (uris.isEmpty()) return null
        return MediaStore.createDeleteRequest(resolver, uris)
    }

    private fun resolveUri(context: Context, path: String): Uri? {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        return null
    }
}
