package com.renyxin.localalbum.data.source

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.renyxin.localalbum.core.index.IgnorePatternMatcher
import com.renyxin.localalbum.core.index.MediaStoreIdentity
import com.renyxin.localalbum.core.index.ScanRootPolicy
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.core.model.DirectoryNode
import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.regex.Pattern
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 媒体元数据缓存接口，用于增量扫描时跳过未修改的文件解析。
 */
interface MediaMetadataCache {
    fun getCapturedAtMs(path: String, modifiedAtMs: Long): Long?
}

enum class MediaStoreLookupStatus {
    INCLUDED,
    EXCLUDED,
    UNRESOLVED,
}

data class ResolvedMediaStoreItem(
    val identity: MediaStoreIdentity,
    val contentUri: String,
    val status: MediaStoreLookupStatus,
    val filePath: String? = null,
    val mediaItem: MediaItem? = null,
)

/**
 * 增强版媒体扫描器。
 * - 基于 File API 遍历真实文件路径
 * - 提取完整 EXIF 元数据 (GPS, 相机型号, 光圈, ISO 等)
 * - 为视频提取时长和缩略图
 * - 支持协程化非阻塞扫描
 * - 生成缩略图并缓存到应用内部存储
 */
class MediaSource(
    private val context: Context? = null,
    private val ignoreDirNames: Set<String> = DefaultIgnoreDirs,
) {
    private val thumbDir: File? by lazy {
        context?.let { File(it.cacheDir, "thumbnails").also { it.mkdirs() } }
    }

    /**
     * 视频/图片元数据提取用的超时线程池（F3）。
     *
     * [MediaMetadataRetriever.setDataSource] 在遇到 DRM 保护、损坏、特定编码视频时会
     * **无限阻塞**（native 调用，runCatching 无法捕获，withTimeout 无法中断）。
     * 此处使用独立线程 + [java.util.concurrent.Future.get] 超时模式，确保单个问题文件
     * 不会卡死整个扫描流程。
     */
    private val mediaMetaExecutor = Executors.newFixedThreadPool(
        minOf(Runtime.getRuntime().availableProcessors(), 4),
    ) { r -> Thread(r, "media-meta").apply { isDaemon = true } }

    /**
     * 视频元数据聚合结果（F2：单次 MediaMetadataRetriever 提取全部字段）。
     */
    private data class VideoMeta(
        val width: String,
        val height: String,
        val durationMs: String,
        val mimeType: String,
        val capturedAt: Instant?,
    ) {
        fun toMap(): Map<String, String> = mapOf(
            "width" to width,
            "height" to height,
            "durationMs" to durationMs,
            "mimeType" to mimeType,
        )
    }

    /** Resolves only the requested stable MediaStore identities. */
    suspend fun resolveMediaStoreItems(
        identities: Collection<MediaStoreIdentity>,
        rootPaths: List<String>,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
        cache: MediaMetadataCache? = null,
    ): List<ResolvedMediaStoreItem> = withContext(Dispatchers.IO) {
        require(identities.size <= MAX_MEDIASTORE_RESOLVE_BATCH) {
            "MediaStore 单批解析超过 $MAX_MEDIASTORE_RESOLVE_BATCH"
        }
        if (identities.isEmpty()) return@withContext emptyList()
        val resolver = requireNotNull(context) { "MediaStore 解析需要 Android Context" }.contentResolver
        val roots = ScanRootPolicy.normalize(rootPaths)
        val compiledIgnore = IgnorePatternMatcher.compile(ignorePatterns)
        val resolved = ArrayList<ResolvedMediaStoreItem>(identities.size)

        identities.distinct().groupBy { it.volumeName to it.mediaType }.forEach { (group, groupItems) ->
            val (volumeName, mediaType) = group
            val collectionUri = mediaStoreCollectionUri(volumeName, mediaType)
            val projection = mediaStoreProjection(mediaType)
            val ids = groupItems.map { it.mediaStoreId }.distinct()
            val selection = "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",") { "?" }})"
            val cursor = resolver.query(
                collectionUri,
                projection,
                selection,
                ids.map(Long::toString).toTypedArray(),
                null,
            )
            if (cursor == null) {
                // ROM/provider visibility failure is not evidence of deletion. Keep every identity
                // explicit so the indexer schedules bounded reconciliation instead of purging it.
                groupItems.forEach { identity ->
                    resolved += ResolvedMediaStoreItem(
                        identity = identity,
                        contentUri = identity.canonicalContentUri(),
                        status = MediaStoreLookupStatus.UNRESOLVED,
                    )
                }
                return@forEach
            }
            cursor.use {
                val idColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val dataColumn = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val requestedById = groupItems.associateBy(MediaStoreIdentity::mediaStoreId)
                while (it.moveToNext()) {
                    val identity = requestedById[it.getLong(idColumn)] ?: continue
                    val contentUri = ContentUris.withAppendedId(
                        collectionUri,
                        identity.mediaStoreId,
                    ).toString()
                    val path = if (dataColumn >= 0 && !it.isNull(dataColumn)) it.getString(dataColumn) else null
                    if (path == null) {
                        resolved += ResolvedMediaStoreItem(
                            identity = identity,
                            contentUri = contentUri,
                            status = MediaStoreLookupStatus.UNRESOLVED,
                        )
                        continue
                    }
                    val file = File(path)
                    if (!file.exists() || !file.isFile) {
                        // A MediaStore row still exists, so transient storage/permission visibility
                        // cannot be interpreted as a confirmed delete or policy exclusion.
                        resolved += ResolvedMediaStoreItem(
                            identity = identity,
                            contentUri = contentUri,
                            status = MediaStoreLookupStatus.UNRESOLVED,
                            filePath = path,
                        )
                        continue
                    }
                    if (!isAllowedDeltaFile(file, roots, allowNomedia, compiledIgnore)) {
                        resolved += ResolvedMediaStoreItem(
                            identity = identity,
                            contentUri = contentUri,
                            status = MediaStoreLookupStatus.EXCLUDED,
                            filePath = path,
                        )
                        continue
                    }
                    resolved += ResolvedMediaStoreItem(
                        identity = identity,
                        contentUri = contentUri,
                        status = MediaStoreLookupStatus.INCLUDED,
                        filePath = path,
                        mediaItem = buildMediaItem(file, mediaType, cache),
                    )
                }
            }
        }
        resolved
    }

    private fun mediaStoreCollectionUri(volumeName: String, mediaType: MediaType): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return when (mediaType) {
                MediaType.IMAGE -> MediaStore.Images.Media.getContentUri(volumeName)
                MediaType.VIDEO -> MediaStore.Video.Media.getContentUri(volumeName)
            }
        }
        return when (mediaType) {
            MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun mediaStoreProjection(mediaType: MediaType): Array<String> = when (mediaType) {
        MediaType.IMAGE -> arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        MediaType.VIDEO -> arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA)
    }

    private fun isAllowedDeltaFile(
        file: File,
        roots: List<String>,
        allowNomedia: Boolean,
        ignorePatterns: List<Pattern>,
    ): Boolean {
        if (shouldIgnoreFile(file.name, ignorePatterns)) return false
        val canonicalPath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val matchingRoot = roots.firstOrNull { root ->
            canonicalPath == root || canonicalPath.startsWith("$root${File.separator}")
        } ?: return false
        var parent = file.parentFile
        while (parent != null) {
            val currentParent = parent
            val parentPath = runCatching { currentParent.canonicalPath }
                .getOrElse { currentParent.absolutePath }
            if (parentPath == matchingRoot) break
            if (shouldIgnoreDir(currentParent.name, ignorePatterns) ||
                (!allowNomedia && File(currentParent, ".nomedia").exists())
            ) {
                return false
            }
            parent = currentParent.parentFile
        }
        return true
    }

    /**
     * 为索引器提供不构造 DirectoryNode 树的有界批次枚举。
     * 单个目录的 listFiles 数组仍由平台 API 分配，但跨目录、跨图库不会累计媒体实体。
     */
    suspend fun enumerateMediaBatches(
        rootPaths: List<String>,
        cache: MediaMetadataCache? = null,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
        batchSize: Int = 250,
        emit: suspend (List<MediaItem>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(batchSize > 0)
        val compiled = IgnorePatternMatcher.compile(ignorePatterns)
        val pendingDirs = java.util.ArrayDeque<File>()
        ScanRootPolicy.normalize(rootPaths).forEach { rootPath ->
            val root = File(rootPath)
            require(root.exists() && root.isDirectory && root.canRead()) {
                "扫描根目录不可访问: ${root.name}"
            }
            pendingDirs.addLast(root)
        }
        val visitedDirs = HashSet<String>()
        val batch = ArrayList<MediaItem>(batchSize)
        while (pendingDirs.isNotEmpty()) {
            val dir = pendingDirs.removeLast()
            val directoryKey = ScanRootPolicy.directoryKey(dir) ?: continue
            if (!visitedDirs.add(directoryKey)) continue
            val files = dir.listFiles() ?: error("扫描目录遍历失败: ${dir.name}")
            for (file in files) {
                when {
                    file.isDirectory -> {
                        if (ScanRootPolicy.isSymbolicLink(file)) continue
                        if (shouldIgnoreDir(file.name, compiled) || hasNoMedia(file, allowNomedia)) continue
                        pendingDirs.addLast(file)
                    }
                    file.isFile -> {
                        if (shouldIgnoreFile(file.name, compiled)) continue
                        val type = detectMediaType(file.name) ?: continue
                        batch += buildMediaItem(file, type, cache)
                        if (batch.size == batchSize) {
                            emit(batch.toList())
                            batch.clear()
                        }
                    }
                }
            }
        }
        if (batch.isNotEmpty()) emit(batch.toList())
    }

    /**
     * 异步扫描多个根目录，返回每个根对应的 [DirectoryNode]。
     * @param allowNomedia 为 true 时不再跳过含 .nomedia 的目录
     * @param ignorePatterns 用户配置的忽略规则（正则字符串列表），同时匹配目录名与文件名。
     *        非法正则会在编译阶段被跳过（见 [IgnorePatternMatcher.compile]）。
     */
    suspend fun scanRoots(
        rootPaths: List<String>,
        cache: MediaMetadataCache? = null,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
        failOnUnreadable: Boolean = false,
    ): List<DirectoryNode> = withContext(Dispatchers.IO) {
        val compiled = IgnorePatternMatcher.compile(ignorePatterns)
        rootPaths.mapNotNull { rootPath ->
            val rootDir = File(rootPath)
            if (!rootDir.exists() || !rootDir.isDirectory || !rootDir.canRead()) {
                if (failOnUnreadable) error("扫描根目录不可访问: ${rootDir.name}")
                Log.w(TAG, "跳过不存在或不可读的根目录: $rootPath")
                return@mapNotNull null
            }
            buildNode(rootDir, parentPath = null, cache = cache, allowNomedia = allowNomedia, ignorePatterns = compiled, failOnUnreadable = failOnUnreadable)
        }
    }

    /**
     * 同步版本，在 IO 协程中调用。
     * @param allowNomedia 为 true 时不再跳过含 .nomedia 的目录
     * @param ignorePatterns 用户配置的忽略规则（正则字符串列表），同时匹配目录名与文件名。
     */
    fun scanRootsSync(
        rootPaths: List<String>,
        cache: MediaMetadataCache? = null,
        allowNomedia: Boolean = false,
        ignorePatterns: List<String> = emptyList(),
        failOnUnreadable: Boolean = false,
    ): List<DirectoryNode> = buildList {
        val compiled = IgnorePatternMatcher.compile(ignorePatterns)
        for (rootPath in rootPaths) {
            val rootDir = File(rootPath)
            if (!rootDir.exists() || !rootDir.isDirectory || !rootDir.canRead()) {
                if (failOnUnreadable) error("扫描根目录不可访问: ${rootDir.name}")
                Log.w(TAG, "跳过不存在或不可读的根目录: $rootPath")
                continue
            }
            add(buildNode(rootDir, parentPath = null, cache = cache, allowNomedia = allowNomedia, ignorePatterns = compiled, failOnUnreadable = failOnUnreadable))
        }
    }

    private fun buildNode(
        dir: File,
        parentPath: String?,
        cache: MediaMetadataCache?,
        allowNomedia: Boolean = false,
        ignorePatterns: List<Pattern> = emptyList(),
        failOnUnreadable: Boolean = false,
    ): DirectoryNode {
        val children = mutableListOf<DirectoryNode>()
        val mediaItems = mutableListOf<MediaItem>()

        val files = dir.listFiles()
        if (files == null && failOnUnreadable) {
            error("扫描目录遍历失败: ${dir.name}")
        }
        if (files != null) {
            for (file in files) {
                when {
                    file.isDirectory -> {
                        if (shouldIgnoreDir(file.name, ignorePatterns)) continue
                        if (hasNoMedia(file, allowNomedia)) continue
                        children += buildNode(file, parentPath = dir.absolutePath, cache = cache, allowNomedia = allowNomedia, ignorePatterns = ignorePatterns, failOnUnreadable = failOnUnreadable)
                    }
                    file.isFile -> {
                        // 用户配置的正则忽略规则对文件名生效（如 ^.trash 命中以 .trash 开头的文件）
                        if (shouldIgnoreFile(file.name, ignorePatterns)) continue
                        val mediaType = detectMediaType(file.name)
                        if (mediaType != null) {
                            mediaItems += buildMediaItem(file, mediaType, cache)
                        }
                    }
                }
            }
        }

        return DirectoryNode(
            path = dir.absolutePath,
            name = dir.name,
            parentPath = parentPath,
            children = children.sortedBy { it.name },
            mediaItems = mediaItems.sortedBy { it.capturedAt },
        )
    }

    /**
     * 判断目录是否应被忽略。
     *
     * 三层规则（任一命中即跳过）：
     * 1. 以 `.` 开头的隐藏目录（系统约定，始终跳过）；
     * 2. [DefaultIgnoreDirs] 中的精确目录名（大小写不敏感）；
     * 3. 用户配置的正则忽略规则（[IgnorePatternMatcher.matchesAny]）。
     */
    private fun shouldIgnoreDir(name: String, ignorePatterns: List<Pattern> = emptyList()): Boolean {
        if (name.startsWith('.')) return true
        if (ignoreDirNames.any { it.equals(name, ignoreCase = true) }) return true
        return IgnorePatternMatcher.matchesAny(name, ignorePatterns)
    }

    /**
     * 判断文件是否应被忽略（仅用户配置的正则忽略规则生效）。
     *
     * 注意：此处不自动跳过以 `.` 开头的隐藏文件——隐藏文件是否为媒体由 [detectMediaType]
     * 的扩展名判定决定；用户若需排除特定前缀文件，请配置正则规则（如 `^.trash`）。
     */
    private fun shouldIgnoreFile(name: String, ignorePatterns: List<Pattern>): Boolean {
        return IgnorePatternMatcher.matchesAny(name, ignorePatterns)
    }

    private fun hasNoMedia(dir: File, allowNomedia: Boolean = false): Boolean {
        if (allowNomedia) return false
        return File(dir, ".nomedia").exists()
    }

    private fun buildMediaItem(
        file: File,
        type: MediaType,
        cache: MediaMetadataCache?
    ): MediaItem {
        val modifiedMs = file.lastModified()

        // F1: 扫描阶段跳过损坏检测。
        // isFileCorrupted 对图片用 BitmapFactory.decodeFile、对视频用 MediaMetadataRetriever，
        // 是单文件最重操作。isCorrupted 字段无任何 WHERE 过滤查询（全局确认），
        // 损坏文件的缩略图生成会自然失败（ThumbnailWorker 跳过），不影响展示。
        val corrupted = false

        // 尝试从缓存获取拍摄时间
        val cachedCapturedAtMs = cache?.getCapturedAtMs(file.absolutePath, modifiedMs)

        // F2: 每文件只打开 1 次 ExifInterface / MediaMetadataRetriever，一次提取全部字段。
        // 原实现对同一图片开 2 次 ExifInterface、对同一视频开 3 次 MediaMetadataRetriever。
        val exifData: Map<String, String>
        val videoMeta: Map<String, String>
        val capturedAt: Instant

        when (type) {
            MediaType.IMAGE -> {
                // 单次 ExifInterface 读取拍摄时间 + 全部 EXIF 字段
                val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
                capturedAt = if (cachedCapturedAtMs != null) {
                    Instant.ofEpochMilli(cachedCapturedAtMs)
                } else {
                    exif?.let { readExifDateTimeFromInterface(it) } ?: Instant.ofEpochMilli(modifiedMs)
                }
                exifData = exif?.let { readAllExifData(it, file) } ?: emptyMap()
                videoMeta = emptyMap()
            }
            MediaType.VIDEO -> {
                // F2+F3: 单次 MediaMetadataRetriever 读取全部字段（带超时保护）
                val meta = readVideoMetaWithTimeout(file)
                capturedAt = if (cachedCapturedAtMs != null) {
                    Instant.ofEpochMilli(cachedCapturedAtMs)
                } else {
                    meta?.capturedAt ?: Instant.ofEpochMilli(modifiedMs)
                }
                exifData = emptyMap()
                videoMeta = meta?.toMap() ?: emptyMap()
            }
        }

        // 生成缩略图（仅返回已缓存路径，新文件交给 ThumbnailWorker 异步补齐）
        val thumbPath = generateThumbnail(file, type)

        return MediaItem(
            id = UUID.nameUUIDFromBytes(file.absolutePath.toByteArray()).toString(),
            filePath = file.absolutePath,
            fileName = file.name,
            type = type,
            capturedAt = capturedAt,
            modifiedAt = Instant.ofEpochMilli(modifiedMs),
            fileSize = file.length(),
            isFavorite = false,
            width = exifData["width"]?.toIntOrNull() ?: videoMeta["width"]?.toIntOrNull() ?: 0,
            height = exifData["height"]?.toIntOrNull() ?: videoMeta["height"]?.toIntOrNull() ?: 0,
            mimeType = exifData["mimeType"] ?: videoMeta["mimeType"] ?: "",
            durationMs = videoMeta["durationMs"]?.toLongOrNull() ?: 0L,
            latitude = exifData["latitude"]?.toDoubleOrNull(),
            longitude = exifData["longitude"]?.toDoubleOrNull(),
            make = exifData["make"],
            model = exifData["model"],
            aperture = exifData["aperture"],
            focalLength = exifData["focalLength"],
            iso = exifData["iso"],
            exposureTime = exifData["exposureTime"],
            orientation = exifData["orientation"]?.toIntOrNull() ?: 0,
            thumbnailPath = thumbPath,
            isCorrupted = corrupted,
        )
    }

    /**
     * 校验文件是否损坏。
     * - 图片：使用 [BitmapFactory] 的 inJustDecodeBounds 尝试解码边界，失败则视为损坏
     * - 视频：使用 [MediaMetadataRetriever] 尝试提取元数据，抛异常则视为损坏
     */
    fun isFileCorrupted(file: File, type: MediaType? = null): Boolean {
        if (!file.exists() || !file.isFile || file.length() == 0L) return true
        val detectedType = type ?: detectMediaType(file.name) ?: return false
        return when (detectedType) {
            MediaType.IMAGE -> {
                runCatching {
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
                    // 解码后 outWidth > 0 说明文件可读
                    options.outWidth > 0
                }.getOrElse { false }
            }
            MediaType.VIDEO -> {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) != null
                    } finally {
                        retriever.release()
                    }
                }.getOrElse { false }
            }
        }
    }

    // ---- EXIF 提取（F2: 接收已打开的 ExifInterface 实例，避免重复打开文件） ----

    /**
     * 从已打开的 [ExifInterface] 实例提取全部 EXIF 字段（F2）。
     * 原实现 [readExifData] 每次新建 ExifInterface，与 [readImageCapturedAt] 重复打开同一文件。
     */
    private fun readAllExifData(exif: ExifInterface, file: File): Map<String, String> {
        // ExifInterface.latLong 已完成 DMS 到十进制度数转换；坐标缺失时返回 null。
        val latLong = exif.latLong
        val latitudeStr = latLong?.get(0)?.toString().orEmpty()
        val longitudeStr = latLong?.get(1)?.toString().orEmpty()
        return mapOf(
            "width" to (exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).toString()),
            "height" to (exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).toString()),
            "make" to (exif.getAttribute(ExifInterface.TAG_MAKE) ?: ""),
            "model" to (exif.getAttribute(ExifInterface.TAG_MODEL) ?: ""),
            "aperture" to (exif.getAttribute(ExifInterface.TAG_F_NUMBER) ?: ""),
            "focalLength" to (exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH) ?: ""),
            "iso" to (exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY) ?: ""),
            "exposureTime" to (exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME) ?: ""),
            "orientation" to (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0).toString()),
            "latitude" to latitudeStr,
            "longitude" to longitudeStr,
            // 从扩展名推断 MIME 类型
            "mimeType" to mimeTypeForExtension(file.extension),
        )
    }

    /**
     * 从已打开的 [ExifInterface] 实例提取拍摄时间（F2）。
     * 原实现 [readImageCapturedAt] 每次新建 ExifInterface，与 [readExifData] 重复打开同一文件。
     */
    private fun readExifDateTimeFromInterface(exif: ExifInterface): Instant? {
        val tagKeys = listOf(
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_DIGITIZED,
        )
        for (key in tagKeys) {
            val value = exif.getAttribute(key) ?: continue
            val instant = parseExifDateTime(value) ?: continue
            return instant
        }
        return null
    }

    /**
     * 带超时保护的视频元数据提取（F2+F3）。
     *
     * 合并原 [readVideoCapturedAt] + [readVideoMeta] 为单次 [MediaMetadataRetriever] 调用（F2），
     * 并通过独立线程 + [java.util.concurrent.Future.get] 超时防止 native 阻塞挂起（F3）。
     *
     * 超时后返回 null，调用方用 file.lastModified() 兜底 capturedAt，width/height/duration
     * 由 MediaStore 数据补齐（mergeAndDeduplicate 中 MediaStore 优先）。
     *
     * @param timeoutMs 单文件元数据提取超时，默认 3 秒
     */
    private fun readVideoMetaWithTimeout(file: File, timeoutMs: Long = 3000L): VideoMeta? {
        val future = mediaMetaExecutor.submit<VideoMeta?> {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    val capturedAt = dateStr?.let { parseExifDateTime(it) }
                    VideoMeta(
                        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "0",
                        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "0",
                        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0",
                        mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "",
                        capturedAt = capturedAt,
                    )
                } finally {
                    retriever.release()
                }
            }.getOrNull()
        }
        return runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrElse {
                future.cancel(true)
                Log.w(TAG, "视频元数据提取超时(${timeoutMs}ms)，跳过: ${file.absolutePath}")
                null
            }
    }

    // ---- 缩略图生成 ----

    /**
     * 扫描主流程中调用的缩略图获取方法。
     * 仅返回已缓存的缩略图路径，新文件的缩略图交给 [ThumbnailWorker] 异步补齐，
     * 避免阻塞扫描主流程。
     */
    private fun generateThumbnail(file: File, @Suppress("UNUSED_PARAMETER") type: MediaType): String? {
        val dir = thumbDir ?: return null
        val webpThumb = File(dir, thumbnailCacheFileName(file, ThumbnailSpec.SIZE_GRID))
        // 兼容历史仅按路径命名的缓存；新缓存的 key 包含修改时间和文件大小，
        // 可在源文件被替换后自动失效。
        val legacyWebpThumb = File(dir, "${file.absolutePath.hashCode()}.webp")
        val legacyJpgThumb = File(dir, "${file.absolutePath.hashCode()}.jpg")
        return when {
            webpThumb.exists() -> webpThumb.absolutePath
            legacyWebpThumb.exists() -> legacyWebpThumb.absolutePath
            legacyJpgThumb.exists() -> legacyJpgThumb.absolutePath
            else -> null // 新文件不在此同步生成，交给 ThumbnailWorker
        }
    }

    /** 按 grid(256px)/preview(1280px) 生成，并以临时文件 + rename 原子发布。 */
    fun generateThumbnailSync(file: File, type: MediaType, sizeClass: String): String? {
        val dir = thumbDir ?: return null
        val targetPx = ThumbnailSpec.targetPx(sizeClass)
        val thumbFile = File(dir, thumbnailCacheFileName(file, sizeClass))
        if (thumbFile.exists()) return thumbFile.absolutePath
        val temporary = File(dir, ".${thumbFile.name}.${UUID.randomUUID()}.tmp")

        return runCatching {
            val bitmap = when (type) {
                MediaType.IMAGE -> decodeImageForTarget(file, targetPx)
                MediaType.VIDEO -> decodeVideoFrameForTarget(file, targetPx)
            } ?: error("decode_failed")
            encodeThumbnail(bitmap, temporary, sizeClass)
            check(temporary.renameTo(thumbFile) || (thumbFile.exists() && temporary.delete())) {
                "cache_publish_failed"
            }
            thumbFile.absolutePath
        }.onFailure { temporary.delete() }.getOrNull()
    }

    private fun decodeImageForTarget(file: File, targetPx: Int): Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
    }

    /** minSdk 29：优先 retriever 目标尺寸取帧，再用 ThumbnailUtils(Size)，最后才取普通帧。 */
    private fun decodeVideoFrameForTarget(file: File, targetPx: Int): Bitmap? {
        val scaled = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frameTimeUs = if (durationMs > 0) durationMs * 1000 / 3 else 0L
                retriever.getScaledFrameAtTime(
                    frameTimeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    targetPx,
                    targetPx,
                )
            } finally {
                retriever.release()
            }
        }.getOrNull()
        if (scaled != null) return scaled
        return runCatching { ThumbnailUtils.createVideoThumbnail(file, Size(targetPx, targetPx), null) }
            .getOrNull()
            ?: runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
    }

    internal fun encodeThumbnail(bitmap: Bitmap, targetFile: File, sizeClass: String): String {
        val (encodedWidth, encodedHeight) = ThumbnailSpec.encodedSize(
            bitmap.width,
            bitmap.height,
            sizeClass,
        )
        val encoded = if (sizeClass == ThumbnailSpec.SIZE_GRID) {
            // 网格单元需要统一方形尺寸，允许居中裁剪。
            ThumbnailUtils.extractThumbnail(bitmap, encodedWidth, encodedHeight)
        } else if (bitmap.width != encodedWidth || bitmap.height != encodedHeight) {
            // 查看器预览必须完整保留原图宽高比，不能复用方形裁剪逻辑。
            Bitmap.createScaledBitmap(bitmap, encodedWidth, encodedHeight, true)
        } else {
            bitmap
        }
        FileOutputStream(targetFile).use { out ->
            check(encoded.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out))
            out.fd.sync()
        }
        if (bitmap !== encoded) bitmap.recycle()
        encoded.recycle()
        return targetFile.absolutePath
    }

    /** 以源身份、尺寸档和格式版本构成缓存文件名。 */
    private fun thumbnailCacheFileName(file: File, sizeClass: String): String =
        "${file.absolutePath.hashCode()}_${file.lastModified()}_${file.length()}_${sizeClass}_v${ThumbnailSpec.CACHE_FORMAT_VERSION}.webp"

    private fun calculateInSampleSize(width: Int, height: Int, targetSize: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetSize && height / (sample * 2) >= targetSize) {
            sample *= 2
        }
        return sample
    }

    // ---- 工具方法 ----

    private fun parseExifDateTime(value: String): Instant? {
        // 尝试解析 "yyyy:MM:dd HH:mm:ss"
        val first = value.indexOf(':')
        val second = if (first >= 0) value.indexOf(':', first + 1) else -1
        val normalised = if (first in 4..4 && second in 7..7) {
            value.substring(0, first) + '-' +
                value.substring(first + 1, second) + '-' +
                value.substring(second + 1)
        } else {
            value
        }
        val pattern = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        return runCatching {
            val ldt = LocalDateTime.parse(normalised, pattern)
            val zdt = ZonedDateTime.of(ldt, ZoneId.systemDefault())
            zdt.toInstant()
        }.getOrNull()
    }

    private fun detectMediaType(fileName: String): MediaType? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in IMAGE_EXTS -> MediaType.IMAGE
            in VIDEO_EXTS -> MediaType.VIDEO
            else -> null
        }
    }

    private fun mimeTypeForExtension(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "dng" -> "image/x-adobe-dng"
        "raw" -> "image/x-raw"
        else -> "image/*"
    }

    internal companion object {
        const val MAX_MEDIASTORE_RESOLVE_BATCH = 500
        const val TAG = "MediaSource"

        val IMAGE_EXTS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "dng", "raw",
        )
        val VIDEO_EXTS = setOf(
            "mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "flv", "ts",
        )

        val DefaultIgnoreDirs = setOf(
            ".thumbnails", ".thumbnails2",
            "thumbnail", "thumbnails",
            ".git", ".svn",
        )
    }
}