package com.renyxin.localalbum.data.backup

import java.security.MessageDigest

/**
 * 备份格式契约独立于 Room schema 版本。
 *
 * v3 只包含用户不可再生或重建成本高的重要状态。瞬态任务、租约、扫描/import staging、
 * 缩略图缓存与重复检测结果均明确排除，并在导入最终切换时清理后重新 seed。
 */
object BackupContract {
    const val FORMAT = "localalbum-ndjson-zip"
    const val VERSION = 3
    const val ROOM_SCHEMA_AT_INTRODUCTION = 27

    const val CAP_CORE = "backup.contract.core.v3"
    const val CAP_EMBEDDING_SPACES = "embedding.spaces.v1"
    const val CAP_PLUGIN_STATE = "plugin.state.v1"
    const val CAP_FACE_CLUSTERS = "face.clusters.v1"
    const val CAP_SEMANTIC_CLUSTERS = "semantic.clusters.v1"
    const val CAP_DELETION_INTENT = "deletion.intent.v1"

    // core + space identity 是读取任何 v3 的最低能力；生产完整 profile 另由 manifest.requiredProfile
    // 强制 deletion intent 等完整表。Fake DAO 仅允许生成测试用 core profile，不用于 App UI。
    val requiredCapabilities = setOf(CAP_CORE, CAP_EMBEDDING_SPACES)
    val knownCapabilities = requiredCapabilities + setOf(CAP_PLUGIN_STATE, CAP_FACE_CLUSTERS, CAP_SEMANTIC_CLUSTERS)

    data class Table(
        val entry: String,
        val table: String,
        val required: Boolean,
        val capability: String,
        val blobColumns: Set<String> = emptySet(),
        val whereClause: String? = null,
    )

    val tables = listOf(
        Table("media.ndjson", "media_items", true, CAP_CORE),
        Table("faces.ndjson", "faces", true, CAP_CORE),
        Table("embeddings.ndjson", "media_embeddings", true, CAP_EMBEDDING_SPACES, setOf("embeddingBlob")),
        Table("fts.ndjson", "media_items_fts", false, CAP_CORE),
        Table("analysis_state.ndjson", "analysis_state", false, CAP_CORE),
        Table("feature_store.ndjson", "feature_store", false, CAP_PLUGIN_STATE),
        Table("plugin_manifest.ndjson", "plugin_manifest", false, CAP_PLUGIN_STATE),
        Table("face_cluster_runs.ndjson", "maintenance_runs", false, CAP_FACE_CLUSTERS, whereClause = "taskType = 'FACE_PROTOTYPES'"),
        Table("face_cluster_meta.ndjson", "face_cluster_meta", false, CAP_FACE_CLUSTERS),
        Table("face_cluster_prototypes.ndjson", "face_cluster_prototypes", false, CAP_FACE_CLUSTERS),
        Table("semantic_index_meta.ndjson", "semantic_index_meta", false, CAP_SEMANTIC_CLUSTERS),
        Table("semantic_cluster_generations.ndjson", "semantic_cluster_generations", false, CAP_SEMANTIC_CLUSTERS),
        Table("semantic_cluster_meta.ndjson", "semantic_cluster_meta", false, CAP_SEMANTIC_CLUSTERS, setOf("centroidBlob")),
        Table("semantic_cluster_members.ndjson", "semantic_cluster_members", false, CAP_SEMANTIC_CLUSTERS),
        Table("deletion_tombstones.ndjson", "deletion_tombstones", true, CAP_DELETION_INTENT),
    )

    val byEntry = tables.associateBy(Table::entry)

    /** 明确写入 manifest，审计时不依赖代码中的隐式约定。 */
    val excludedTables = listOf(
        "thumbnail_cache_entries", "thumbnail_tasks", "analysis_tasks", "scan_runs", "scan_staging",
        "import_media_staging", "import_face_staging", "import_embedding_staging", "import_fts_staging",
        "semantic_maintenance_runs", "duplicate_hash_staging", "duplicate_groups", "duplicate_members",
        "maintenance_runs:DUPLICATE_EXACT",
    )
}

enum class BackupErrorType {
    IO, FORMAT, VERSION, CAPABILITY, MISSING_ENTRY, UNKNOWN_ENTRY, DUPLICATE_ENTRY,
    ZIP_SLIP, ZIP_BOMB, DISK_SPACE, HASH_MISMATCH, LINE_TOO_LONG, JSON_SYNTAX,
    FIELD_TYPE, ENUM_VALUE, BASE64, STAGING, SWITCH,
}

/** 不包含完整路径或原始 JSON 行的可安全展示错误。 */
data class BackupError(
    val entry: String? = null,
    val line: Long? = null,
    val safeKey: String? = null,
    val pathHash: String? = null,
    val type: BackupErrorType,
    val messageCode: String,
) {
    fun friendlyMessage(): String {
        val location = buildString {
            entry?.let { append("条目 $it") }
            line?.let { if (isNotEmpty()) append("，"); append("第 ${it} 行") }
        }
        val detail = when (type) {
            BackupErrorType.IO -> "无法读写备份文件"
            BackupErrorType.VERSION -> "备份版本不受支持"
            BackupErrorType.CAPABILITY -> "备份包含当前版本不支持的能力"
            BackupErrorType.MISSING_ENTRY -> "备份缺少必需数据"
            BackupErrorType.UNKNOWN_ENTRY -> "备份包含未声明的数据"
            BackupErrorType.DUPLICATE_ENTRY -> "ZIP 中存在重复条目"
            BackupErrorType.ZIP_SLIP -> "ZIP 条目路径不安全"
            BackupErrorType.ZIP_BOMB -> "备份解压规模或压缩比超过安全上限"
            BackupErrorType.DISK_SPACE -> "可用磁盘空间不足"
            BackupErrorType.HASH_MISMATCH -> "备份完整性校验失败"
            BackupErrorType.LINE_TOO_LONG -> "单条记录超过大小上限"
            BackupErrorType.JSON_SYNTAX -> "记录不是有效 JSON"
            BackupErrorType.FIELD_TYPE -> "记录字段类型错误"
            BackupErrorType.ENUM_VALUE -> "记录包含未知枚举值"
            BackupErrorType.BASE64 -> "记录包含损坏的 Base64 数据"
            BackupErrorType.STAGING -> "备份暂存失败，原数据未改变"
            BackupErrorType.SWITCH -> "恢复切换失败，原数据未改变"
            BackupErrorType.FORMAT -> "备份格式无效"
        }
        return if (location.isEmpty()) detail else "$location：$detail"
    }

    companion object {
        fun pathHash(path: String): String = MessageDigest.getInstance("SHA-256")
            .digest(path.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }
}

class BackupException(val backupError: BackupError, cause: Throwable? = null) : Exception(backupError.messageCode, cause)
