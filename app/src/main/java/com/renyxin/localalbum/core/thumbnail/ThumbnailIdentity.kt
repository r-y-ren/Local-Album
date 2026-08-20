package com.renyxin.localalbum.core.thumbnail

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 缩略图从扫描事实到任务、缓存、物理文件与发布 CAS 的完整身份。
 *
 * [sourceVersion] 使用可判定的版本化 codec。`f=-` 明确表示扫描数据尚未取得指纹；它不会与
 * 任意真实指纹相等，因此后续取得指纹时会形成新身份并安全 supersede。媒体类型和格式版本
 * 是独立身份维度，绝不依赖 sourceVersion 的偶然编码来隔离。
 */
data class ThumbnailIdentity(
    val canonicalPath: String,
    val mediaType: String,
    val sourceVersion: String,
    val sizeClass: String,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
) {
    init {
        require(mediaType == MEDIA_IMAGE || mediaType == MEDIA_VIDEO) { "Unsupported media type: $mediaType" }
        require(sizeClass == SIZE_GRID || sizeClass == SIZE_PREVIEW) { "Unsupported size class: $sizeClass" }
        require(formatVersion > 0) { "formatVersion must be positive" }
        require(SourceVersionCodec.decode(sourceVersion) != null) { "Invalid thumbnail source version" }
    }

    /** 不含隐私明文路径、可用于 lease-private 文件名的稳定摘要。 */
    fun sha256(): String = sha256Hex(
        listOf(canonicalPath, mediaType, sourceVersion, sizeClass, formatVersion.toString())
            .joinToString("\u0000"),
    )

    companion object {
        const val MEDIA_IMAGE = "IMAGE"
        const val MEDIA_VIDEO = "VIDEO"
        const val SIZE_GRID = "grid"
        const val SIZE_PREVIEW = "preview"
        const val CURRENT_FORMAT_VERSION = 5

        /** 全缩略图子系统唯一允许的路径规范化入口。 */
        fun canonicalizePath(path: String): String {
            val file = File(path.trim())
            return runCatching { file.canonicalFile.path }.getOrElse { file.absoluteFile.normalize().path }
        }

        fun create(
            path: String,
            mediaType: String,
            modifiedAtMs: Long,
            fileSize: Long,
            fingerprintHead: String?,
            sizeClass: String,
            formatVersion: Int = CURRENT_FORMAT_VERSION,
        ): ThumbnailIdentity = ThumbnailIdentity(
            canonicalPath = canonicalizePath(path),
            mediaType = mediaType,
            sourceVersion = SourceVersionCodec.encode(modifiedAtMs, fileSize, fingerprintHead),
            sizeClass = sizeClass,
            formatVersion = formatVersion,
        )

        internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

data class ThumbnailSourceVersion(
    val modifiedAtMs: Long,
    val fileSize: Long,
    val fingerprintHead: String?,
    val degraded: Boolean,
)

/** 稳定、可迁移且严格可解析的缩略图源版本 codec。 */
object SourceVersionCodec {
    private const val PREFIX = "sv1"
    private const val ABSENT_FINGERPRINT = "-"
    private const val MILLIS_PER_SECOND = 1_000L
    private val FINGERPRINT = Regex("[0-9a-fA-F]+")

    fun encode(modifiedAtMs: Long, fileSize: Long, fingerprintHead: String?): String {
        require(modifiedAtMs >= 0L) { "modifiedAtMs must not be negative" }
        require(fileSize >= 0L) { "fileSize must not be negative" }
        val normalizedFingerprint = fingerprintHead
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotEmpty)
        require(normalizedFingerprint == null || FINGERPRINT.matches(normalizedFingerprint)) {
            "fingerprintHead must be hexadecimal"
        }
        return "$PREFIX|m=$modifiedAtMs|s=$fileSize|f=${normalizedFingerprint ?: ABSENT_FINGERPRINT}"
    }

    /**
     * v33 运行时仅发布 sv1；旧 `mtime:size` 只在迁移/兼容读取中可解析为显式 degraded，
     * 新任务构造不会再产生旧编码。
     */
    fun decode(encoded: String): ThumbnailSourceVersion? {
        if (!encoded.startsWith("$PREFIX|")) {
            val legacy = encoded.split(':')
            if (legacy.size != 2) return null
            val modified = legacy[0].toLongOrNull() ?: return null
            val size = legacy[1].toLongOrNull() ?: return null
            if (modified < 0L || size < 0L) return null
            return ThumbnailSourceVersion(modified, size, null, degraded = true)
        }
        val parts = encoded.split('|')
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val modified = parts[1].removePrefix("m=").takeIf { parts[1].startsWith("m=") }?.toLongOrNull()
            ?: return null
        val size = parts[2].removePrefix("s=").takeIf { parts[2].startsWith("s=") }?.toLongOrNull()
            ?: return null
        val fingerprintToken = parts[3].removePrefix("f=").takeIf { parts[3].startsWith("f=") }
            ?: return null
        if (modified < 0L || size < 0L) return null
        val fingerprint = when {
            fingerprintToken == ABSENT_FINGERPRINT -> null
            FINGERPRINT.matches(fingerprintToken) -> fingerprintToken.lowercase()
            else -> return null
        }
        return ThumbnailSourceVersion(modified, size, fingerprint, degraded = fingerprint == null)
    }

    fun matchesPhysical(
        encoded: String,
        modifiedAtMs: Long,
        fileSize: Long,
        fingerprintHead: String? = null,
    ): Boolean {
        val decoded = decode(encoded) ?: return false
        if (modifiedAtMs < 0L || fileSize < 0L) return false
        // Existing MediaStore identities are second-precision; only that persisted shape gets
        // same-second compatibility. New millisecond identities remain exact, with no wide tolerance.
        val modifiedAtMatches = if (decoded.modifiedAtMs % MILLIS_PER_SECOND == 0L) {
            decoded.modifiedAtMs / MILLIS_PER_SECOND == modifiedAtMs / MILLIS_PER_SECOND
        } else {
            decoded.modifiedAtMs == modifiedAtMs
        }
        if (!modifiedAtMatches || decoded.fileSize != fileSize) return false
        return decoded.fingerprintHead == null ||
            decoded.fingerprintHead == fingerprintHead?.trim()?.lowercase()
    }
}
