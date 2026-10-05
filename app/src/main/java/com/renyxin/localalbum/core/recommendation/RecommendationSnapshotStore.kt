package com.renyxin.localalbum.core.recommendation

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 推荐批次快照的文件持久化（org.json，文件落 filesDir）。
 *
 * 推荐池与游标此前只存在于进程内存：冷启动推荐页空白、重启后刷新又从确定性
 * 排序的第一批重放（表现为"刷新永远推荐同样的内容"）。本类把"当前展示批次 +
 * 轮换游标"以紧凑 JSON 持久化——批次只存元数据与文件路径列表，MediaItem
 * 恢复时按路径回查数据库，避免把整库元数据快照到磁盘。
 *
 * 快照与推荐池是两个层次：池不持久化（重建确定性近似不变），游标持久化保证
 * 重启后的下一次刷新从上次断点继续轮换而不是重放第一批。
 */
class RecommendationSnapshotStore(private val file: File) {

    data class Entry(
        val albumId: String,
        val albumName: String,
        val directoryPath: String,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val reason: String,
        val score: Double,
        val category: RecommendationCategory,
        val paths: List<String>,
    )

    data class Snapshot(
        val cursor: Int,
        val savedAtMs: Long,
        val entries: List<Entry>,
    )

    /** 读取快照；文件缺失或损坏（版本不符/解析失败）返回 null 并清理残骸。 */
    fun load(): Snapshot? {
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optInt(KEY_VERSION) != VERSION) {
                file.delete()
                null
            } else {
                val entriesJson = json.optJSONArray(KEY_ENTRIES) ?: JSONArray()
                val entries = (0 until entriesJson.length()).mapNotNull { i ->
                    entriesJson.optJSONObject(i)?.let(::parseEntry)
                }
                Snapshot(
                    cursor = json.optInt(KEY_CURSOR, 0),
                    savedAtMs = json.optLong(KEY_SAVED_AT, 0L),
                    entries = entries,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "推荐快照读取失败，按缺失处理", e)
            file.delete()
            null
        }
    }

    /** 原子写入（临时文件 + rename），失败只告警不影响推荐流。 */
    fun save(cursor: Int, batch: List<Recommendation>) {
        try {
            val entries = JSONArray()
            batch.forEach { rec ->
                entries.put(
                    JSONObject()
                        .put(KEY_ALBUM_ID, rec.albumId)
                        .put(KEY_ALBUM_NAME, rec.albumName)
                        .put(KEY_DIRECTORY, rec.directoryPath)
                        .put(KEY_WINDOW_START, rec.windowStart.toEpochMilli())
                        .put(KEY_WINDOW_END, rec.windowEnd.toEpochMilli())
                        .put(KEY_REASON, rec.reason)
                        .put(KEY_SCORE, rec.score)
                        .put(KEY_CATEGORY, rec.category.name)
                        .put(KEY_PATHS, JSONArray(rec.mediaItems.map { it.filePath })),
                )
            }
            val json = JSONObject()
                .put(KEY_VERSION, VERSION)
                .put(KEY_SAVED_AT, System.currentTimeMillis())
                .put(KEY_CURSOR, cursor)
                .put(KEY_ENTRIES, entries)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // 跨文件系统或目标被占用时的回退：直接覆写目标文件
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "推荐快照写入失败", e)
        }
    }

    private fun parseEntry(o: JSONObject): Entry {
        val pathsJson = o.optJSONArray(KEY_PATHS) ?: JSONArray()
        return Entry(
            albumId = o.optString(KEY_ALBUM_ID),
            albumName = o.optString(KEY_ALBUM_NAME),
            directoryPath = o.optString(KEY_DIRECTORY),
            windowStartMs = o.optLong(KEY_WINDOW_START, 0L),
            windowEndMs = o.optLong(KEY_WINDOW_END, 0L),
            reason = o.optString(KEY_REASON),
            score = o.optDouble(KEY_SCORE, 0.0),
            category = runCatching { RecommendationCategory.valueOf(o.optString(KEY_CATEGORY)) }
                .getOrDefault(RecommendationCategory.WHOLE_ALBUM),
            paths = (0 until pathsJson.length()).map { pathsJson.optString(it) },
        )
    }

    private companion object {
        private const val TAG = "RecSnapshot"
        private const val VERSION = 1
        private const val KEY_VERSION = "version"
        private const val KEY_SAVED_AT = "savedAtMs"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_ENTRIES = "batch"
        private const val KEY_ALBUM_ID = "albumId"
        private const val KEY_ALBUM_NAME = "albumName"
        private const val KEY_DIRECTORY = "directoryPath"
        private const val KEY_WINDOW_START = "windowStartMs"
        private const val KEY_WINDOW_END = "windowEndMs"
        private const val KEY_REASON = "reason"
        private const val KEY_SCORE = "score"
        private const val KEY_CATEGORY = "category"
        private const val KEY_PATHS = "paths"
    }
}
