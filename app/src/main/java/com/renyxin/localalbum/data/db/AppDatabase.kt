package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.renyxin.localalbum.data.db.dao.AlbumSnapshotDao
import com.renyxin.localalbum.data.db.dao.AnalysisStateDao
import com.renyxin.localalbum.data.db.dao.BackupStagingDao
import com.renyxin.localalbum.data.db.dao.AnalysisTaskDao
import com.renyxin.localalbum.data.db.dao.DeletionTombstoneDao
import com.renyxin.localalbum.data.db.dao.DuplicateMaintenanceDao
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.EnhancementOutboxDao
import com.renyxin.localalbum.data.db.dao.FaceClusterDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.FeatureStoreDao
import com.renyxin.localalbum.data.db.dao.ImportStagingDao
import com.renyxin.localalbum.data.db.dao.MediaChangeDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.PluginManifestDao
import com.renyxin.localalbum.data.db.dao.ScanRunDao
import com.renyxin.localalbum.data.db.dao.ScanStagingDao
import com.renyxin.localalbum.data.db.dao.SemanticDao
import com.renyxin.localalbum.data.db.dao.ThumbnailCacheDao
import com.renyxin.localalbum.data.db.dao.ThumbnailTaskDao
import com.renyxin.localalbum.data.db.entity.AlbumSnapshotDirectoryEntity
import com.renyxin.localalbum.data.db.entity.AlbumSnapshotMetaEntity
import com.renyxin.localalbum.data.db.entity.AnalysisStateEntity
import com.renyxin.localalbum.data.db.entity.AnalysisTaskEntity
import com.renyxin.localalbum.data.db.entity.DeletionTombstoneEntity
import com.renyxin.localalbum.data.db.entity.DuplicateGroupEntity
import com.renyxin.localalbum.data.db.entity.DuplicateHashStagingEntity
import com.renyxin.localalbum.data.db.entity.DuplicateMemberEntity
import com.renyxin.localalbum.data.db.entity.EnhancementOutboxEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity
import com.renyxin.localalbum.data.db.entity.FaceClusterMetaEntity
import com.renyxin.localalbum.data.db.entity.FaceClusterPrototypeEntity
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.FeatureStoreEntity
import com.renyxin.localalbum.data.db.entity.ImportEmbeddingStagingEntity
import com.renyxin.localalbum.data.db.entity.ImportFaceStagingEntity
import com.renyxin.localalbum.data.db.entity.ImportFtsStagingEntity
import com.renyxin.localalbum.data.db.entity.ImportMediaStagingEntity
import com.renyxin.localalbum.data.db.entity.MediaChangeEventEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaStoreReferenceEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import com.renyxin.localalbum.data.db.entity.PluginManifestEntity
import com.renyxin.localalbum.data.db.entity.SemanticClusterGenerationEntity
import com.renyxin.localalbum.data.db.entity.SemanticClusterMemberEntity
import com.renyxin.localalbum.data.db.entity.SemanticClusterMetaEntity
import com.renyxin.localalbum.data.db.entity.SemanticIndexMetaEntity
import com.renyxin.localalbum.data.db.entity.SemanticMaintenanceRunEntity
import com.renyxin.localalbum.data.db.entity.ScanRunEntity
import com.renyxin.localalbum.data.db.entity.ScanStagingEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailCacheEntryEntity
import com.renyxin.localalbum.data.db.entity.ThumbnailTaskEntity

@Database(
    entities = [
        MediaEntity::class,
        MediaFts::class,
        FaceEntity::class,
        MediaEmbedding::class,
        FeatureStoreEntity::class,
        PluginManifestEntity::class,
        AnalysisStateEntity::class,
        ThumbnailTaskEntity::class,
        ThumbnailCacheEntryEntity::class,
        AnalysisTaskEntity::class,
        ScanRunEntity::class,
        ScanStagingEntity::class,
        ImportMediaStagingEntity::class,
        ImportFaceStagingEntity::class,
        ImportEmbeddingStagingEntity::class,
        ImportFtsStagingEntity::class,
        MaintenanceRunEntity::class,
        DuplicateHashStagingEntity::class,
        DuplicateGroupEntity::class,
        DuplicateMemberEntity::class,
        FaceClusterMetaEntity::class,
        FaceClusterPrototypeEntity::class,
        SemanticIndexMetaEntity::class,
        SemanticMaintenanceRunEntity::class,
        SemanticClusterGenerationEntity::class,
        SemanticClusterMetaEntity::class,
        SemanticClusterMemberEntity::class,
        DeletionTombstoneEntity::class,
        com.renyxin.localalbum.data.db.entity.BackupImportStagingEntity::class,
        AlbumSnapshotDirectoryEntity::class,
        AlbumSnapshotMetaEntity::class,
        MediaChangeEventEntity::class,
        MediaStoreReferenceEntity::class,
        EnhancementOutboxEntity::class,
    ],
    version = 32,
    exportSchema = true,
)
/**
 * 应用主数据库（Room，schema version 32，exportSchema = true）。
 *
 * 单例入口见 [getDatabase]；全部 DAO 通过 abstract 访问器暴露。
 *
 * ## 迁移规则（新增 schema 变更的固定流程）
 * 1. 新增一个 `MIGRATION_N_N+1`（object : Migration(N, N+1)）并附 KDoc 说明变更内容；
 * 2. **必须**同步追加到 [getDatabase] 的 `addMigrations(...)` 数组——遗漏会导致升级路径
 *    抛异常（本项目已配置升级缺失迁移时抛异常而非静默清库，仅降级允许销毁重建）；
 * 3. bump `@Database(version = ...)` 并重新编译生成 `app/schemas/.../{N+1}.json`；
 * 4. 验证导出的 schema JSON 与预期 diff 一致（历史版本 31/32.json 为参照）。
 *
 * companion object 中 MIGRATION_8_9 … MIGRATION_31_32 共 24 个迁移约占本文件 85%，
 * 为刻意保留：Room Migration 数组需静态引用，拆文件收益低且有 exportSchema 回归风险。
 */
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun faceDao(): FaceDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun featureStoreDao(): FeatureStoreDao
    abstract fun pluginManifestDao(): PluginManifestDao
    abstract fun analysisStateDao(): AnalysisStateDao
    abstract fun thumbnailTaskDao(): ThumbnailTaskDao
    abstract fun thumbnailCacheDao(): ThumbnailCacheDao
    abstract fun analysisTaskDao(): AnalysisTaskDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun scanStagingDao(): ScanStagingDao
    abstract fun mediaChangeDao(): MediaChangeDao
    abstract fun enhancementOutboxDao(): EnhancementOutboxDao
    abstract fun albumSnapshotDao(): AlbumSnapshotDao
    abstract fun importStagingDao(): ImportStagingDao
    abstract fun backupStagingDao(): BackupStagingDao
    abstract fun duplicateMaintenanceDao(): DuplicateMaintenanceDao
    abstract fun faceClusterDao(): FaceClusterDao
    abstract fun semanticDao(): SemanticDao
    abstract fun deletionTombstoneDao(): DeletionTombstoneDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration 8 → 9：新增 feature_store 通用异构存储表（Phase 1.3）。
         *
         * 该表用于持久化由外部未知模型插件生成的任意维度特征向量、
         * 自定义分类标签、特殊坐标等异构数据，消除对固定表结构的依赖。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS feature_store (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        filePath TEXT NOT NULL,
                        pluginId TEXT NOT NULL,
                        featureType TEXT NOT NULL,
                        dataJson TEXT NOT NULL,
                        schemaJson TEXT NOT NULL,
                        modelVersion INTEGER NOT NULL DEFAULT 1,
                        generatedAtMs INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_feature_store_filePath_pluginId
                    ON feature_store(filePath, pluginId)
                """.trimIndent())

                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_feature_store_pluginId
                    ON feature_store(pluginId)
                """.trimIndent())

                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_feature_store_featureType
                    ON feature_store(featureType)
                """.trimIndent())

                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_feature_store_modelVersion
                    ON feature_store(modelVersion)
                """.trimIndent())
            }
        }

        /**
         * Migration 9 → 10：新增 plugin_manifest 表（Phase 4.2）。
         *
         * 持久化用户通过可视化向导或 JSON 编辑器导入的第三方 AI 插件配置。
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS plugin_manifest (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pluginId TEXT NOT NULL,
                        manifestJson TEXT NOT NULL,
                        isEnabled INTEGER NOT NULL DEFAULT 1,
                        pipelineStage TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL DEFAULT 0,
                        updatedAtMs INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE UNIQUE INDEX IF NOT EXISTS index_plugin_manifest_pluginId
                    ON plugin_manifest(pluginId)
                """.trimIndent())

                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_plugin_manifest_isEnabled
                    ON plugin_manifest(isEnabled)
                """.trimIndent())

                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_plugin_manifest_pipelineStage
                    ON plugin_manifest(pipelineStage)
                """.trimIndent())
            }
        }

        /**
         * Migration 10 → 11：plugin_manifest 表新增 orderIndex 列（Phase 5 R1）。
         *
         * 支持同一 pipelineStage 内多个插件的执行顺序调整。
         * 现有行默认 orderIndex = 0。
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    ALTER TABLE plugin_manifest ADD COLUMN orderIndex INTEGER NOT NULL DEFAULT 0
                """.trimIndent())
            }
        }

        /**
         * Migration 11 → 12：新增 analysis_state 表（Phase 1 断点续跑）。
         *
         * 持久化 (filePath, stageId) 粒度的分析阶段完成状态，进程重启后据此跳过已完成阶段。
         */
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS analysis_state (
                        filePath TEXT NOT NULL,
                        stageId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        modelVersion INTEGER NOT NULL DEFAULT 1,
                        updatedAtMs INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(filePath, stageId)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_analysis_state_stageId
                    ON analysis_state(stageId)
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_analysis_state_status
                    ON analysis_state(status)
                """.trimIndent())
                db.execSQL("""
                    CREATE INDEX IF NOT EXISTS index_analysis_state_modelVersion
                    ON analysis_state(modelVersion)
                """.trimIndent())
            }
        }

        /**
         * Migration 12 → 13：移除 media_items.similarGroupId 列。
         *
         * 相似照片功能已移除，仅保留基于 SHA-256 的完全重复检测（运行时计算，不持久化）。
         * SQLite 不支持 ALTER TABLE DROP COLUMN（Android API < 35），需重建表。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 创建不含 similarGroupId 的新表
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS media_items_new (
                        filePath TEXT NOT NULL PRIMARY KEY,
                        fileName TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        capturedAtMs INTEGER NOT NULL,
                        modifiedAtMs INTEGER NOT NULL,
                        indexedAtMs INTEGER NOT NULL,
                        parentPath TEXT NOT NULL,
                        fileSize INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        isTrashed INTEGER NOT NULL DEFAULT 0,
                        width INTEGER NOT NULL,
                        height INTEGER NOT NULL,
                        mimeType TEXT NOT NULL,
                        durationMs INTEGER NOT NULL,
                        latitude REAL,
                        longitude REAL,
                        make TEXT,
                        model TEXT,
                        aperture TEXT,
                        focalLength TEXT,
                        iso TEXT,
                        exposureTime TEXT,
                        orientation INTEGER NOT NULL,
                        sceneType TEXT,
                        thumbnailPath TEXT,
                        fingerprintHead TEXT,
                        perceptualHash INTEGER NOT NULL DEFAULT 0,
                        ocrText TEXT,
                        qualityScore REAL NOT NULL DEFAULT 0.0,
                        deletedAtMs INTEGER NOT NULL DEFAULT 0,
                        isCorrupted INTEGER NOT NULL DEFAULT 0,
                        faceClusterId TEXT
                    )
                """.trimIndent())
                // 2. 复制数据（排除 similarGroupId）
                db.execSQL("""
                    INSERT INTO media_items_new (
                        filePath, fileName, mediaType, capturedAtMs, modifiedAtMs, indexedAtMs,
                        parentPath, fileSize, isFavorite, isTrashed, width, height, mimeType,
                        durationMs, latitude, longitude, make, model, aperture, focalLength,
                        iso, exposureTime, orientation, sceneType, thumbnailPath, fingerprintHead,
                        perceptualHash, ocrText, qualityScore, deletedAtMs,
                        isCorrupted, faceClusterId
                    )
                    SELECT
                        filePath, fileName, mediaType, capturedAtMs, modifiedAtMs, indexedAtMs,
                        parentPath, fileSize, isFavorite, isTrashed, width, height, mimeType,
                        durationMs, latitude, longitude, make, model, aperture, focalLength,
                        iso, exposureTime, orientation, sceneType, thumbnailPath, fingerprintHead,
                        perceptualHash, ocrText, qualityScore, deletedAtMs,
                        isCorrupted, faceClusterId
                    FROM media_items
                """.trimIndent())
                // 3. 删除旧表
                db.execSQL("DROP TABLE media_items")
                // 4. 重命名新表
                db.execSQL("ALTER TABLE media_items_new RENAME TO media_items")
                // 5. 重建索引
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_capturedAtMs ON media_items(capturedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_parentPath ON media_items(parentPath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isFavorite ON media_items(isFavorite)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isTrashed ON media_items(isTrashed)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_mediaType ON media_items(mediaType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_sceneType ON media_items(sceneType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_fileSize ON media_items(fileSize)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_modifiedAtMs ON media_items(modifiedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isCorrupted ON media_items(isCorrupted)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_faceClusterId ON media_items(faceClusterId)")
            }
        }

        /**
         * Migration 13 → 14：为大图库的分页时间线、收藏、目录和场景筛选补充复合索引。
         * 仅创建索引，不改变媒体内容，因此升级不会触发重扫或丢失用户分析结果。
         */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isTrashed_capturedAtMs ON media_items(isTrashed, capturedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isFavorite_isTrashed_capturedAtMs ON media_items(isFavorite, isTrashed, capturedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_parentPath_isTrashed_capturedAtMs ON media_items(parentPath, isTrashed, capturedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_mediaType_isTrashed_capturedAtMs ON media_items(mediaType, isTrashed, capturedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_sceneType_isTrashed_capturedAtMs ON media_items(sceneType, isTrashed, capturedAtMs)")
            }
        }

        /**
         * Migration 14 → 15：为语义向量添加 Float32 BLOB 存储。
         * 旧 CSV 字段保留，查询端对未迁移记录回退解析，不阻塞升级后的立即搜索。
         */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_embeddings ADD COLUMN embeddingBlob BLOB")
            }
        }

        /** Migration 15 → 16：新增可领取、可退避、可收敛的持久化缩略图任务表。 */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS thumbnail_tasks (
                        taskId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        filePath TEXT NOT NULL,
                        sizeClass TEXT NOT NULL,
                        sourceVersion TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextRetryAt INTEGER NOT NULL,
                        leaseUntil INTEGER NOT NULL,
                        leaseToken TEXT,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_thumbnail_tasks_filePath_sizeClass_sourceVersion ON thumbnail_tasks(filePath, sizeClass, sourceVersion)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thumbnail_tasks_status_nextRetryAt_priority ON thumbnail_tasks(status, nextRetryAt, priority)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thumbnail_tasks_leaseUntil ON thumbnail_tasks(leaseUntil)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thumbnail_tasks_leaseToken ON thumbnail_tasks(leaseToken)")
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT OR IGNORE INTO thumbnail_tasks
                        (filePath, sizeClass, sourceVersion, mediaType, priority, status,
                         attemptCount, nextRetryAt, leaseUntil, leaseToken, lastError, createdAt, updatedAt)
                    SELECT filePath, 'grid', modifiedAtMs || ':' || fileSize, mediaType, 0, 'PENDING',
                           0, 0, 0, NULL, NULL, $now, $now
                    FROM media_items WHERE thumbnailPath IS NULL AND isTrashed = 0
                """.trimIndent())
            }
        }

        /** Migration 16 → 17：扫描代次与可恢复分析任务基础设施。 */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_items ADD COLUMN scanGeneration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_scanGeneration ON media_items(scanGeneration)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS analysis_tasks (
                        taskId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        filePath TEXT NOT NULL,
                        sourceVersion TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextRetryAt INTEGER NOT NULL,
                        leaseUntil INTEGER NOT NULL,
                        leaseToken TEXT,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_analysis_tasks_filePath_sourceVersion ON analysis_tasks(filePath, sourceVersion)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_tasks_status_nextRetryAt_priority ON analysis_tasks(status, nextRetryAt, priority)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_tasks_leaseUntil ON analysis_tasks(leaseUntil)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_tasks_leaseToken ON analysis_tasks(leaseToken)")
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT OR IGNORE INTO analysis_tasks
                        (filePath, sourceVersion, priority, status, attemptCount, nextRetryAt,
                         leaseUntil, leaseToken, lastError, createdAt, updatedAt)
                    SELECT filePath, modifiedAtMs || ':' || fileSize, 0, 'PENDING', 0, 0,
                           0, NULL, NULL, $now, $now
                    FROM media_items WHERE mediaType = 'IMAGE' AND isTrashed = 0
                """.trimIndent())
            }
        }

        /** Migration 17 → 18：新增扫描运行状态机，作为孤儿删除的完整性门禁。 */
        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scan_runs (
                        scanId TEXT NOT NULL PRIMARY KEY,
                        generation INTEGER NOT NULL,
                        scanType TEXT NOT NULL,
                        status TEXT NOT NULL,
                        mediaStoreCompleted INTEGER NOT NULL,
                        fileSystemCompleted INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        completedAt INTEGER NOT NULL,
                        errorType TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_runs_status_startedAt ON scan_runs(status, startedAt)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_scan_runs_generation ON scan_runs(generation)")
            }
        }

        /** Migration 18 → 19：新增扫描 staging，双来源去重不再依赖全量内存 Map。 */
        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS scan_staging (
                        scanId TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        sourceMask INTEGER NOT NULL,
                        mediaStoreJson TEXT,
                        fileSystemJson TEXT,
                        PRIMARY KEY(scanId, filePath)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_staging_scanId_filePath ON scan_staging(scanId, filePath)")
            }
        }

        /** Migration 19 → 20：分析任务加入 pipeline scope 与旧 sourceVersion 淘汰语义。 */
        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN pipelineScope TEXT NOT NULL DEFAULT 'core:v1'")
                db.execSQL("DROP INDEX IF EXISTS index_analysis_tasks_filePath_sourceVersion")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_analysis_tasks_filePath_sourceVersion_pipelineScope ON analysis_tasks(filePath, sourceVersion, pipelineScope)")
            }
        }

        /** Migration 20 → 21：新增四张 generation 隔离的覆盖式导入 staging 表。 */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS import_media_staging (
                        generation TEXT NOT NULL, filePath TEXT NOT NULL, fileName TEXT NOT NULL,
                        mediaType TEXT NOT NULL, capturedAtMs INTEGER NOT NULL, modifiedAtMs INTEGER NOT NULL,
                        indexedAtMs INTEGER NOT NULL, parentPath TEXT NOT NULL, fileSize INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL, isTrashed INTEGER NOT NULL, width INTEGER NOT NULL,
                        height INTEGER NOT NULL, mimeType TEXT NOT NULL, durationMs INTEGER NOT NULL,
                        latitude REAL, longitude REAL, make TEXT, model TEXT, aperture TEXT, focalLength TEXT,
                        iso TEXT, exposureTime TEXT, orientation INTEGER NOT NULL, sceneType TEXT,
                        thumbnailPath TEXT, fingerprintHead TEXT, perceptualHash INTEGER NOT NULL,
                        ocrText TEXT, qualityScore REAL NOT NULL, deletedAtMs INTEGER NOT NULL,
                        isCorrupted INTEGER NOT NULL, faceClusterId TEXT, scanGeneration INTEGER NOT NULL,
                        PRIMARY KEY(generation, filePath)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_media_staging_generation ON import_media_staging(generation)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS import_face_staging (
                        stagingId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, generation TEXT NOT NULL,
                        faceId INTEGER NOT NULL, filePath TEXT NOT NULL, clusterId TEXT, personName TEXT,
                        embedding TEXT NOT NULL, boxLeft REAL NOT NULL, boxTop REAL NOT NULL,
                        boxRight REAL NOT NULL, boxBottom REAL NOT NULL, thumbnailPath TEXT,
                        detectedAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_face_staging_generation ON import_face_staging(generation)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS import_embedding_staging (
                        generation TEXT NOT NULL, filePath TEXT NOT NULL, embedding TEXT NOT NULL,
                        modelVersion INTEGER NOT NULL, generatedAtMs INTEGER NOT NULL, source TEXT NOT NULL,
                        embeddingBlob BLOB, PRIMARY KEY(generation, filePath)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_embedding_staging_generation ON import_embedding_staging(generation)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS import_fts_staging (
                        stagingId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, generation TEXT NOT NULL,
                        filePath TEXT NOT NULL, fileName TEXT NOT NULL, ocrText TEXT, make TEXT, model TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_import_fts_staging_generation ON import_fts_staging(generation)")
            }
        }

        /** Migration 21 → 22：新增 generation 隔离、可恢复的完全重复离线维护任务与结果。 */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS maintenance_runs (
                        taskType TEXT NOT NULL, generation INTEGER NOT NULL, status TEXT NOT NULL,
                        cursorPath TEXT, scannedCount INTEGER NOT NULL, eligibleCount INTEGER NOT NULL,
                        failedCount INTEGER NOT NULL, groupCount INTEGER NOT NULL, memberCount INTEGER NOT NULL,
                        error TEXT, startedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                        completedAt INTEGER NOT NULL, isActive INTEGER NOT NULL,
                        PRIMARY KEY(taskType, generation)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_maintenance_runs_taskType_status_startedAt ON maintenance_runs(taskType, status, startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_maintenance_runs_taskType_isActive ON maintenance_runs(taskType, isActive)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS duplicate_hash_staging (
                        generation INTEGER NOT NULL, filePath TEXT NOT NULL, fileSize INTEGER NOT NULL,
                        exactHash TEXT NOT NULL, qualityScore REAL NOT NULL, modifiedAtMs INTEGER NOT NULL,
                        PRIMARY KEY(generation, filePath)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_hash_staging_generation_fileSize_exactHash ON duplicate_hash_staging(generation, fileSize, exactHash)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_hash_staging_generation_filePath ON duplicate_hash_staging(generation, filePath)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS duplicate_groups (
                        generation INTEGER NOT NULL, groupId TEXT NOT NULL, exactHash TEXT NOT NULL,
                        memberCount INTEGER NOT NULL, totalBytes INTEGER NOT NULL, wastedBytes INTEGER NOT NULL,
                        recommendedKeepPath TEXT NOT NULL, invalidated INTEGER NOT NULL, createdAt INTEGER NOT NULL,
                        PRIMARY KEY(generation, groupId)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_groups_generation_invalidated_wastedBytes ON duplicate_groups(generation, invalidated, wastedBytes)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_groups_generation_exactHash ON duplicate_groups(generation, exactHash)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS duplicate_members (
                        generation INTEGER NOT NULL, groupId TEXT NOT NULL, filePath TEXT NOT NULL,
                        fileSize INTEGER NOT NULL, qualityScore REAL NOT NULL, modifiedAtMs INTEGER NOT NULL,
                        isRecommendedKeep INTEGER NOT NULL,
                        PRIMARY KEY(generation, groupId, filePath)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_members_generation_groupId_filePath ON duplicate_members(generation, groupId, filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_duplicate_members_filePath ON duplicate_members(filePath)")
            }
        }

        /**
         * Migration 22 → 23：人物簇 metadata、多代表原型与 FACE_PROTOTYPES maintenance generation。
         * 不改写 faces/personName/media_items.faceClusterId；旧聚类由 Worker 按 faceId keyset 有界 bootstrap。
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS face_cluster_meta (
                        generation INTEGER NOT NULL, clusterId TEXT NOT NULL, personName TEXT,
                        status TEXT NOT NULL, providerScope TEXT NOT NULL, modelScope TEXT NOT NULL,
                        createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(generation, clusterId)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_face_cluster_meta_generation_status_clusterId ON face_cluster_meta(generation, status, clusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_face_cluster_meta_clusterId ON face_cluster_meta(clusterId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS face_cluster_prototypes (
                        generation INTEGER NOT NULL, clusterId TEXT NOT NULL, prototypeIndex INTEGER NOT NULL,
                        embedding TEXT NOT NULL, sampleCount INTEGER NOT NULL,
                        providerScope TEXT NOT NULL, modelScope TEXT NOT NULL, updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(generation, clusterId, prototypeIndex)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_face_cluster_prototypes_generation_providerScope_modelScope_clusterId ON face_cluster_prototypes(generation, providerScope, modelScope, clusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_face_cluster_prototypes_generation_clusterId ON face_cluster_prototypes(generation, clusterId)")
            }
        }

        /** Migration 23 → 24：独立缩略图缓存元数据；保留任务表和 media thumbnailPath。 */
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS thumbnail_cache_entries (
                        filePath TEXT NOT NULL,
                        sizeClass TEXT NOT NULL,
                        sourceVersion TEXT NOT NULL,
                        path TEXT NOT NULL,
                        byteSize INTEGER NOT NULL,
                        lastAccessAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        state TEXT NOT NULL,
                        leaseUntil INTEGER NOT NULL,
                        deleteAttemptCount INTEGER NOT NULL,
                        PRIMARY KEY(filePath, sizeClass, sourceVersion)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thumbnail_cache_entries_state_lastAccessAt_filePath_sizeClass_sourceVersion ON thumbnail_cache_entries(state, lastAccessAt, filePath, sizeClass, sourceVersion)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thumbnail_cache_entries_filePath_sizeClass_sourceVersion_state ON thumbnail_cache_entries(filePath, sizeClass, sourceVersion, state)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_thumbnail_cache_entries_leaseUntil ON thumbnail_cache_entries(leaseUntil)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_thumbnail_cache_entries_path ON thumbnail_cache_entries(path)")
                // 旧 media.thumbnailPath 保持原样；SQL migration 无法可靠读取派生文件 byteSize，
                // 因此不写入零字节伪 metadata。v24 Worker/UI 命中路径会以真实文件长度惰性建档。
            }
        }

        /**
         * Migration 24 → 25：语义向量空间、可恢复回填、Exact/ANN metadata 与持久主题簇。
         * 旧 CSV/BLOB 原样保留并隔离到 legacy space，不猜测 Provider 身份。
         */
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media_embeddings ADD COLUMN providerId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE media_embeddings ADD COLUMN modelId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE media_embeddings ADD COLUMN dimension INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_embeddings ADD COLUMN spaceId TEXT NOT NULL DEFAULT 'semantic:legacy:v24'")
                db.execSQL("ALTER TABLE media_embeddings ADD COLUMN generation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media_embeddings ADD COLUMN codecId TEXT NOT NULL DEFAULT 'float32-le'")
                db.execSQL("ALTER TABLE media_embeddings ADD COLUMN formatVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_embeddings_spaceId_filePath ON media_embeddings(spaceId, filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_embeddings_providerId_modelId_modelVersion ON media_embeddings(providerId, modelId, modelVersion)")
                db.execSQL("ALTER TABLE import_embedding_staging ADD COLUMN providerId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE import_embedding_staging ADD COLUMN modelId TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE import_embedding_staging ADD COLUMN dimension INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_embedding_staging ADD COLUMN spaceId TEXT NOT NULL DEFAULT 'semantic:legacy:v24'")
                db.execSQL("ALTER TABLE import_embedding_staging ADD COLUMN vectorGeneration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE import_embedding_staging ADD COLUMN codecId TEXT NOT NULL DEFAULT 'float32-le'")
                db.execSQL("ALTER TABLE import_embedding_staging ADD COLUMN formatVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_index_meta (spaceId TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, modelId TEXT NOT NULL, modelVersion INTEGER NOT NULL, dimension INTEGER NOT NULL, codecId TEXT NOT NULL, formatVersion INTEGER NOT NULL, activeGeneration INTEGER NOT NULL, isActive INTEGER NOT NULL, status TEXT NOT NULL, vectorCount INTEGER NOT NULL, indexedCount INTEGER NOT NULL, deletedCount INTEGER NOT NULL, indexKind TEXT NOT NULL, builtAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_index_meta_isActive ON semantic_index_meta(isActive)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_index_meta_providerId_modelId_modelVersion_dimension ON semantic_index_meta(providerId,modelId,modelVersion,dimension)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_maintenance_runs (taskType TEXT NOT NULL, generation INTEGER NOT NULL, targetSpaceId TEXT NOT NULL, status TEXT NOT NULL, cursorPath TEXT, scannedCount INTEGER NOT NULL, convertedCount INTEGER NOT NULL, failedCount INTEGER NOT NULL, totalCount INTEGER NOT NULL, coverageCount INTEGER NOT NULL, lastError TEXT, startedAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, completedAt INTEGER NOT NULL, isActive INTEGER NOT NULL, PRIMARY KEY(taskType,generation))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_maintenance_runs_taskType_status ON semantic_maintenance_runs(taskType,status)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_maintenance_runs_taskType_isActive ON semantic_maintenance_runs(taskType,isActive)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_cluster_generations (spaceId TEXT NOT NULL, generation INTEGER NOT NULL, status TEXT NOT NULL, isActive INTEGER NOT NULL, sampleLimit INTEGER NOT NULL, sampledCount INTEGER NOT NULL, clusterCount INTEGER NOT NULL, createdAt INTEGER NOT NULL, completedAt INTEGER NOT NULL, PRIMARY KEY(spaceId,generation))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_cluster_generations_spaceId_isActive ON semantic_cluster_generations(spaceId,isActive)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_cluster_meta (spaceId TEXT NOT NULL, generation INTEGER NOT NULL, clusterId TEXT NOT NULL, status TEXT NOT NULL, centroidBlob BLOB NOT NULL, memberCount INTEGER NOT NULL, score REAL NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(spaceId,generation,clusterId))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_cluster_meta_spaceId_generation_status ON semantic_cluster_meta(spaceId,generation,status)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_cluster_members (spaceId TEXT NOT NULL, generation INTEGER NOT NULL, clusterId TEXT NOT NULL, rank INTEGER NOT NULL, filePath TEXT NOT NULL, PRIMARY KEY(spaceId,generation,clusterId,rank))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_cluster_members_spaceId_generation_clusterId ON semantic_cluster_members(spaceId,generation,clusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_semantic_cluster_members_filePath ON semantic_cluster_members(filePath)")
            }
        }

        /** Migration 25 → 26：持久化用户删除意图、脱敏失败与租约重试控制面。 */
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS deletion_tombstones (
                        pathKey TEXT NOT NULL PRIMARY KEY,
                        filePath TEXT NOT NULL,
                        operation TEXT NOT NULL,
                        state TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextRetryAt INTEGER NOT NULL,
                        leaseUntil INTEGER NOT NULL,
                        leaseToken TEXT,
                        lastErrorType TEXT,
                        lastErrorCode TEXT,
                        lastErrorMessage TEXT,
                        sourceVersion TEXT NOT NULL,
                        deletedAtMs INTEGER NOT NULL,
                        firstAttemptAt INTEGER NOT NULL,
                        lastAttemptAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_deletion_tombstones_filePath ON deletion_tombstones(filePath)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deletion_tombstones_state_nextRetryAt_leaseUntil ON deletion_tombstones(state,nextRetryAt,leaseUntil)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deletion_tombstones_leaseToken ON deletion_tombstones(leaseToken)")
            }
        }

        /** Migration 26 → 27：N6 通用 generation 备份 staging；备份格式版本仍独立为 streaming v3。 */
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS backup_import_staging (
                        generation TEXT NOT NULL,
                        entryName TEXT NOT NULL,
                        lineNumber INTEGER NOT NULL,
                        stableKey TEXT NOT NULL,
                        payload TEXT NOT NULL,
                        PRIMARY KEY(generation, entryName, lineNumber)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_backup_import_staging_generation_entryName_lineNumber ON backup_import_staging(generation, entryName, lineNumber)")
            }
        }

        /** Migration 27 → 28：增加原子发布的目录级相册快照，供冷启动直接恢复。 */
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS album_snapshot_directories (
                        directoryPath TEXT NOT NULL PRIMARY KEY,
                        mediaCount INTEGER NOT NULL,
                        latestCapturedAtMs INTEGER NOT NULL,
                        coverThumbnailPath TEXT,
                        coverFilePath TEXT,
                        snapshotVersion INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_album_snapshot_directories_snapshotVersion ON album_snapshot_directories(snapshotVersion)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS album_snapshot_meta (
                        id INTEGER NOT NULL PRIMARY KEY,
                        activeVersion INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )
                """.trimIndent())
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT INTO album_snapshot_directories
                        (directoryPath, mediaCount, latestCapturedAtMs, coverThumbnailPath, coverFilePath, snapshotVersion)
                    SELECT parentPath, COUNT(*), MAX(capturedAtMs),
                           (SELECT m2.thumbnailPath FROM media_items m2
                            WHERE m2.parentPath = m1.parentPath AND m2.isTrashed = 0
                            ORDER BY m2.capturedAtMs DESC, m2.filePath ASC LIMIT 1),
                           (SELECT m2.filePath FROM media_items m2
                            WHERE m2.parentPath = m1.parentPath AND m2.isTrashed = 0
                            ORDER BY m2.capturedAtMs DESC, m2.filePath ASC LIMIT 1),
                           $now
                    FROM media_items m1 WHERE isTrashed = 0 GROUP BY parentPath
                """.trimIndent())
                db.execSQL("INSERT OR REPLACE INTO album_snapshot_meta (id, activeVersion, updatedAtMs) VALUES (1, $now, $now)")
            }
        }

        /**
         * Migration 28 → 29：持久化核心/增强扫描生命周期及分析任务归属。
         *
         * 所有新增列都有与实体默认值一致的 SQL 默认值，旧运行记录继续作为
         * legacy status 使用；NULL scanId 保留旧导入和用户手动任务的无归属语义。
         */
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_runs ADD COLUMN indexAvailability TEXT NOT NULL DEFAULT 'EMPTY'")
                db.execSQL("ALTER TABLE scan_runs ADD COLUMN coreScanState TEXT NOT NULL DEFAULT 'IDLE'")
                db.execSQL("ALTER TABLE scan_runs ADD COLUMN enhancementState TEXT NOT NULL DEFAULT 'NOT_SCHEDULED'")
                db.execSQL("ALTER TABLE scan_runs ADD COLUMN firstBatchCommittedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scan_runs ADD COLUMN indexAvailableAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scan_runs ADD COLUMN coreCompletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scan_runs ADD COLUMN enhancementStartedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE scan_runs ADD COLUMN enhancementCompletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_runs_coreScanState_startedAt ON scan_runs(coreScanState, startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scan_runs_enhancementState_startedAt ON scan_runs(enhancementState, startedAt)")

                // Preserve the meaning of legacy terminal runs instead of exposing the
                // newly added default IDLE/EMPTY values after an upgrade.
                db.execSQL(
                    "UPDATE scan_runs SET indexAvailability = 'PUBLISHED', " +
                        "coreScanState = 'COMPLETED', " +
                        "indexAvailableAt = CASE WHEN completedAt = 0 THEN startedAt ELSE completedAt END, " +
                        "coreCompletedAt = CASE WHEN completedAt = 0 THEN startedAt ELSE completedAt END " +
                        "WHERE status = 'COMPLETED'",
                )
                db.execSQL(
                    "UPDATE scan_runs SET coreScanState = 'FAILED' " +
                        "WHERE status = 'FAILED'",
                )
                db.execSQL(
                    "UPDATE scan_runs SET coreScanState = 'CANCELLED' " +
                        "WHERE status = 'ABORTED'",
                )
                db.execSQL(
                    "UPDATE scan_runs SET status = 'ABORTED', " +
                        "completedAt = CASE WHEN completedAt = 0 THEN startedAt ELSE completedAt END, " +
                        "coreScanState = 'PAUSED', errorType = 'process_restarted' " +
                        "WHERE status = 'RUNNING'",
                )

                db.execSQL("ALTER TABLE analysis_tasks ADD COLUMN scanId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_analysis_tasks_scanId_status ON analysis_tasks(scanId, status)")
            }
        }

        /** Migration 29 -> 30: durable changed-set journal and stable MediaStore identity map. */
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS media_change_events (
                        eventKey TEXT NOT NULL PRIMARY KEY,
                        profileId TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        volumeName TEXT,
                        mediaType TEXT,
                        mediaStoreId INTEGER,
                        contentUri TEXT,
                        flags INTEGER NOT NULL,
                        firstObservedAtMs INTEGER NOT NULL,
                        observedAtMs INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextAttemptAt INTEGER NOT NULL,
                        leaseUntil INTEGER NOT NULL,
                        leaseToken TEXT,
                        lastError TEXT,
                        createdAtMs INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_change_events_profileId_status_observedAtMs " +
                        "ON media_change_events(profileId, status, observedAtMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_change_events_leaseToken " +
                        "ON media_change_events(leaseToken)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_media_change_events_eventType_status " +
                        "ON media_change_events(eventType, status)",
                )

                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS media_store_references (
                        stableKey TEXT NOT NULL PRIMARY KEY,
                        profileId TEXT NOT NULL,
                        volumeName TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        mediaStoreId INTEGER NOT NULL,
                        contentUri TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        sourceVersion TEXT NOT NULL,
                        observedAtMs INTEGER NOT NULL,
                        scanGeneration INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_media_store_references_profileId_volumeName_mediaType_mediaStoreId " +
                        "ON media_store_references(profileId, volumeName, mediaType, mediaStoreId)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_media_store_references_profileId_filePath " +
                        "ON media_store_references(profileId, filePath)",
                )
            }
        }

        /** Migration 30 -> 31: post-core enhancement handoff outbox and thumbnail ownership gate. */
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE thumbnail_tasks ADD COLUMN scanId TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_thumbnail_tasks_scanId_status " +
                        "ON thumbnail_tasks(scanId, status)",
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS enhancement_outbox (
                        outboxId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        scanId TEXT NOT NULL,
                        profileId TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        sourceVersion TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        pipelineScope TEXT NOT NULL,
                        enqueueAnalysis INTEGER NOT NULL,
                        enqueueThumbnail INTEGER NOT NULL,
                        parentPath TEXT NOT NULL,
                        status TEXT NOT NULL,
                        attemptCount INTEGER NOT NULL,
                        nextRetryAt INTEGER NOT NULL,
                        leaseUntil INTEGER NOT NULL,
                        leaseToken TEXT,
                        lastError TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )""".trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_enhancement_outbox_scanId_status_createdAt " +
                        "ON enhancement_outbox(scanId, status, createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_enhancement_outbox_status_nextRetryAt_createdAt " +
                        "ON enhancement_outbox(status, nextRetryAt, createdAt)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_enhancement_outbox_leaseUntil " +
                        "ON enhancement_outbox(leaseUntil)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_enhancement_outbox_leaseToken " +
                        "ON enhancement_outbox(leaseToken)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_enhancement_outbox_profileId_filePath_sourceVersion_pipelineScope " +
                        "ON enhancement_outbox(profileId, filePath, sourceVersion, pipelineScope)",
                )
            }
        }

        /**
         * Migration 31 -> 32: add parentPath to FTS and typed import staging without dropping data.
         *
         * SQLite cannot ALTER an FTS4 virtual table to add a column, so the old rows are copied to
         * an ordinary migration table, the virtual table is recreated, and parentPath is backfilled
         * from the canonical media row. Typed staging is rebuilt to match Room's exact no-default
         * schema while preserving any crash-recovery generation that existed during an upgrade.
         */
        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE media_items_fts_v31_backup (
                        filePath TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        ocrText TEXT,
                        make TEXT,
                        model TEXT
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO media_items_fts_v31_backup(filePath,fileName,ocrText,make,model)
                        SELECT filePath,fileName,ocrText,make,model FROM media_items_fts""".trimIndent(),
                )
                db.execSQL("DROP TABLE media_items_fts")
                db.execSQL(
                    """CREATE VIRTUAL TABLE IF NOT EXISTS media_items_fts USING FTS4(
                        filePath TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        parentPath TEXT NOT NULL,
                        ocrText TEXT,
                        make TEXT,
                        model TEXT,
                        tokenize=unicode61
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO media_items_fts(filePath,fileName,parentPath,ocrText,make,model)
                        SELECT f.filePath,f.fileName,COALESCE(m.parentPath,''),f.ocrText,f.make,f.model
                        FROM media_items_fts_v31_backup f
                        LEFT JOIN media_items m ON m.filePath = f.filePath""".trimIndent(),
                )
                db.execSQL("DROP TABLE media_items_fts_v31_backup")

                db.execSQL(
                    """CREATE TABLE import_fts_staging_v32 (
                        stagingId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        generation TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        fileName TEXT NOT NULL,
                        parentPath TEXT NOT NULL,
                        ocrText TEXT,
                        make TEXT,
                        model TEXT
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO import_fts_staging_v32(
                        stagingId,generation,filePath,fileName,parentPath,ocrText,make,model
                    ) SELECT s.stagingId,s.generation,s.filePath,s.fileName,
                        COALESCE(
                            (SELECT m.parentPath FROM import_media_staging m
                             WHERE m.generation = s.generation AND m.filePath = s.filePath LIMIT 1),
                            (SELECT m.parentPath FROM media_items m
                             WHERE m.filePath = s.filePath LIMIT 1),
                            ''
                        ),s.ocrText,s.make,s.model
                    FROM import_fts_staging s""".trimIndent(),
                )
                db.execSQL("DROP TABLE import_fts_staging")
                db.execSQL("ALTER TABLE import_fts_staging_v32 RENAME TO import_fts_staging")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_import_fts_staging_generation " +
                        "ON import_fts_staging(generation)",
                )
            }
        }

        /**
         * 数据库单例入口（double-checked locking，进程内唯一实例，库名 `local_album_db`）。
         *
         * 注册了 MIGRATION_8_9 至 MIGRATION_31_32 的完整迁移链；升级缺失迁移时抛异常，
         * 仅降级时允许销毁重建（fallbackToDestructiveMigrationOnDowngrade）。
         *
         * @param context 任意 Context，内部取 applicationContext，持有进程级单例安全
         * @return 进程唯一的 [AppDatabase] 实例
         */
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "local_album_db"
                )
                    .addMigrations(
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                        MIGRATION_23_24,
                        MIGRATION_24_25,
                        MIGRATION_25_26,
                        MIGRATION_26_27,
                        MIGRATION_27_28,
                        MIGRATION_28_29,
                        MIGRATION_29_30,
                        MIGRATION_30_31,
                        MIGRATION_31_32,
                    )
                    // 1.2: 仅在降级时销毁数据；升级缺失迁移时抛异常而非静默清库
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}