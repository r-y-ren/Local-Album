package com.renyxin.localalbum.data.source

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * MediaStore 双通道索引源。
 * 在 API 29+ (Android 10+) 上，使用 MediaStore ContentProvider 作为主索引通道，
 * 同时保留 File API 作为回退通道用于访问外部存储 (SD 卡 / USB OTG)。
 *
 * 优势：
 * - 不需要遍历文件系统，速度极快
 * - 自动获取缩略图 URI (无需自行生成)
 * - 与系统图库保持同步
 * - 支持 Android 13+ 细粒度权限 (READ_MEDIA_IMAGES / READ_MEDIA_VIDEO)
 */
class MediaStoreIndexer(private val context: Context) {

    /**
     * 通过 MediaStore 查询所有媒体文件，返回 [MediaItem] 列表。
     *
     * @param blacklistPaths 已索引路径集合，用于增量跳过
     * @param progress 进度回调 (current, total)
     */
    suspend fun queryAll(
        blacklistPaths: Set<String> = emptySet(),
        progress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()

        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.LATITUDE,
            MediaStore.Images.Media.LONGITUDE,
            MediaStore.Images.Media.ORIENTATION,
        )

        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.LATITUDE,
            MediaStore.Video.Media.LONGITUDE,
        )

        // 查询图片
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStore(
                uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection = imageProjection,
                mediaType = MediaType.IMAGE,
            ) { cursor ->
                val dataPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                if (dataPath != null && dataPath !in blacklistPaths) {
                    items.add(cursorToImageItem(cursor, dataPath))
                }
            }
        }

        // 查询视频
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStore(
                uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection = videoProjection,
                mediaType = MediaType.VIDEO,
            ) { cursor ->
                val dataPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                if (dataPath != null && dataPath !in blacklistPaths) {
                    items.add(cursorToVideoItem(cursor, dataPath))
                }
            }
        }

        // 回退到 File API 扫描外部存储 (SD 卡等)
        val fileItems = scanExternalStorage(blacklistPaths)
        items.addAll(fileItems)

        progress(items.size, items.size)
        Log.d(TAG, "MediaStore 索引完成: ${items.size} 个媒体项")
        items
    }

    /**
     * 仅查询自 [lastModifiedSince] 之后修改的媒体文件 (增量查询)。
     */
    suspend fun querySince(
        lastModifiedSince: Long,
        progress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()

        val selection = "${MediaStore.Images.Media.DATE_MODIFIED} > ?"
        val selectionArgs = arrayOf((lastModifiedSince / 1000).toString())

        val imageProjection = getImageProjection()
        val videoProjection = getVideoProjection()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMediaStore(
                uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection = imageProjection,
                mediaType = MediaType.IMAGE,
                selection = selection,
                selectionArgs = selectionArgs,
            ) { cursor ->
                val dataPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA))
                if (dataPath != null) {
                    items.add(cursorToImageItem(cursor, dataPath))
                }
            }

            queryMediaStore(
                uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
                projection = videoProjection,
                mediaType = MediaType.VIDEO,
                selection = selection,
                selectionArgs = selectionArgs,
            ) { cursor ->
                val dataPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                if (dataPath != null) {
                    items.add(cursorToVideoItem(cursor, dataPath))
                }
            }
        }

        progress(items.size, items.size)
        items
    }

    /**
     * 获取文件的缩略图 URI。
     */
    fun getThumbnailUri(filePath: String, mediaType: MediaType): Uri? {
        val file = File(filePath)
        if (!file.exists()) return null
        val fileSize = file.length()

        return when (mediaType) {
            MediaType.IMAGE -> {
                val imageId = queryIdForPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    filePath
                )
                if (imageId != null) {
                    ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        imageId
                    )
                } else null
            }
            MediaType.VIDEO -> {
                val videoId = queryIdForPath(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    filePath
                )
                if (videoId != null) {
                    ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        videoId
                    )
                } else null
            }
        }
    }

    // ---- 私有方法 ----

    private fun getImageProjection() = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.LATITUDE,
        MediaStore.Images.Media.LONGITUDE,
        MediaStore.Images.Media.ORIENTATION,
    )

    private fun getVideoProjection() = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATA,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.MIME_TYPE,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.LATITUDE,
        MediaStore.Video.Media.LONGITUDE,
    )

    private fun queryMediaStore(
        uri: Uri,
        projection: Array<String>,
        mediaType: MediaType,
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        onItem: (android.database.Cursor) -> Unit,
    ) {
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            sortOrder,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                onItem(cursor)
            }
        }
    }

    private fun cursorToImageItem(cursor: android.database.Cursor, dataPath: String): MediaItem {
        val capturedMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
        val modifiedSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED))
        val fileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: ""

        return MediaItem(
            id = UUID.nameUUIDFromBytes(dataPath.toByteArray()).toString(),
            filePath = dataPath,
            fileName = fileName,
            type = MediaType.IMAGE,
            capturedAt = if (capturedMs > 0) Instant.ofEpochMilli(capturedMs) else Instant.ofEpochMilli(modifiedSec * 1000),
            modifiedAt = Instant.ofEpochMilli(modifiedSec * 1000),
            fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)),
            isFavorite = false,
            width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)),
            height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)) ?: "",
            latitude = safeGetDouble(cursor, MediaStore.Images.Media.LATITUDE),
            longitude = safeGetDouble(cursor, MediaStore.Images.Media.LONGITUDE),
            orientation = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)),
        )
    }

    private fun cursorToVideoItem(cursor: android.database.Cursor, dataPath: String): MediaItem {
        val capturedMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN))
        val modifiedSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED))
        val fileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: ""

        return MediaItem(
            id = UUID.nameUUIDFromBytes(dataPath.toByteArray()).toString(),
            filePath = dataPath,
            fileName = fileName,
            type = MediaType.VIDEO,
            capturedAt = if (capturedMs > 0) Instant.ofEpochMilli(capturedMs) else Instant.ofEpochMilli(modifiedSec * 1000),
            modifiedAt = Instant.ofEpochMilli(modifiedSec * 1000),
            fileSize = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)),
            isFavorite = false,
            width = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)),
            height = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)) ?: "",
            durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)),
            latitude = safeGetDouble(cursor, MediaStore.Video.Media.LATITUDE),
            longitude = safeGetDouble(cursor, MediaStore.Video.Media.LONGITUDE),
        )
    }

    private fun safeGetDouble(cursor: android.database.Cursor, columnName: String): Double? {
        val idx = cursor.getColumnIndex(columnName)
        if (idx < 0) return null
        val value = cursor.getDouble(idx)
        return if (value == 0.0) null else value
    }

    private fun queryIdForPath(uri: Uri, filePath: String): Long? {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DATA} = ?"
        return context.contentResolver.query(
            uri,
            projection,
            selection,
            arrayOf(filePath),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getLong(0)
            } else null
        }
    }

    /**
     * 回退扫描: 通过 File API 扫描外部存储 (SD 卡、USB OTG 等)。
     * 仅扫描 /storage 下的非内部存储路径。
     */
    private suspend fun scanExternalStorage(blacklistPaths: Set<String>): List<MediaItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<MediaItem>()
        val internalStorage = Environment.getExternalStorageDirectory().absolutePath

        val storageDir = File("/storage")
        if (!storageDir.exists() || !storageDir.isDirectory) return@withContext items

        storageDir.listFiles()?.forEach { dir ->
            if (dir.absolutePath == internalStorage) return@forEach // 内部存储已由 MediaStore 覆盖
            if (dir.absolutePath.startsWith("/storage/emulated")) return@forEach
            collectMediaFiles(dir, blacklistPaths, items)
        }

        items
    }

    private fun collectMediaFiles(dir: File, blacklistPaths: Set<String>, items: MutableList<MediaItem>) {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory && !file.name.startsWith(".")) {
                collectMediaFiles(file, blacklistPaths, items)
            } else if (file.isFile && file.absolutePath !in blacklistPaths) {
                val ext = file.extension.lowercase()
                val mediaType = when (ext) {
                    in MediaSource.Companion.IMAGE_EXTS -> MediaType.IMAGE
                    in MediaSource.Companion.VIDEO_EXTS -> MediaType.VIDEO
                    else -> null
                }
                if (mediaType != null) {
                    items.add(
                        MediaItem(
                            id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString(),
                            filePath = file.absolutePath,
                            fileName = file.name,
                            type = mediaType,
                            capturedAt = Instant.ofEpochMilli(file.lastModified()),
                            modifiedAt = Instant.ofEpochMilli(file.lastModified()),
                            fileSize = file.length(),
                        )
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "MediaStoreIndexer"
    }
}