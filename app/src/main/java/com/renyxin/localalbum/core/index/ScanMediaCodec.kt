package com.renyxin.localalbum.core.index

import com.renyxin.localalbum.core.model.MediaItem
import com.renyxin.localalbum.core.model.MediaType
import org.json.JSONObject
import java.time.Instant

/** scan_staging 的紧凑 JSON 编解码与双来源字段合并。 */
internal object ScanMediaCodec {
    fun encode(item: MediaItem): String = JSONObject().apply {
        put("p", item.filePath)
        put("n", item.fileName)
        put("t", item.type.name)
        put("c", item.capturedAt.toEpochMilli())
        put("m", item.modifiedAt.toEpochMilli())
        put("s", item.fileSize)
        put("w", item.width)
        put("h", item.height)
        put("mime", item.mimeType)
        put("d", item.durationMs)
        putOpt("lat", item.latitude)
        putOpt("lon", item.longitude)
        putOpt("make", item.make)
        putOpt("model", item.model)
        putOpt("a", item.aperture)
        putOpt("f", item.focalLength)
        putOpt("iso", item.iso)
        putOpt("e", item.exposureTime)
        put("o", item.orientation)
        putOpt("thumb", item.thumbnailPath)
    }.toString()

    fun decode(json: String): MediaItem {
        val value = JSONObject(json)
        val path = value.getString("p")
        return MediaItem(
            id = java.util.UUID.nameUUIDFromBytes(path.toByteArray()).toString(),
            filePath = path,
            fileName = value.getString("n"),
            type = MediaType.valueOf(value.getString("t")),
            capturedAt = Instant.ofEpochMilli(value.getLong("c")),
            modifiedAt = Instant.ofEpochMilli(value.getLong("m")),
            fileSize = value.optLong("s"),
            width = value.optInt("w"),
            height = value.optInt("h"),
            mimeType = value.optString("mime"),
            durationMs = value.optLong("d"),
            latitude = value.nullableDouble("lat"),
            longitude = value.nullableDouble("lon"),
            make = value.nullableString("make"),
            model = value.nullableString("model"),
            aperture = value.nullableString("a"),
            focalLength = value.nullableString("f"),
            iso = value.nullableString("iso"),
            exposureTime = value.nullableString("e"),
            orientation = value.optInt("o"),
            thumbnailPath = value.nullableString("thumb"),
        )
    }

    fun merge(mediaStoreJson: String?, fileSystemJson: String?): MediaItem {
        val mediaStore = mediaStoreJson?.let(::decode)
        val fileSystem = fileSystemJson?.let(::decode)
        if (mediaStore == null) return requireNotNull(fileSystem)
        if (fileSystem == null) return mediaStore
        return mediaStore.copy(
            // File enumeration is the high-resolution physical identity source. MediaStore-only
            // rows keep their provider metadata because they have no filesystem counterpart here.
            modifiedAt = fileSystem.modifiedAt,
            fileSize = fileSystem.fileSize,
            latitude = mediaStore.latitude ?: fileSystem.latitude,
            longitude = mediaStore.longitude ?: fileSystem.longitude,
            make = mediaStore.make?.ifBlank { fileSystem.make } ?: fileSystem.make,
            model = mediaStore.model?.ifBlank { fileSystem.model } ?: fileSystem.model,
            aperture = mediaStore.aperture ?: fileSystem.aperture,
            focalLength = mediaStore.focalLength ?: fileSystem.focalLength,
            iso = mediaStore.iso ?: fileSystem.iso,
            exposureTime = mediaStore.exposureTime ?: fileSystem.exposureTime,
            orientation = if (mediaStore.orientation == 0) fileSystem.orientation else mediaStore.orientation,
            thumbnailPath = mediaStore.thumbnailPath ?: fileSystem.thumbnailPath,
        )
    }

    private fun JSONObject.nullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else getString(key)

    private fun JSONObject.nullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)
}
