package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.renyxin.localalbum.data.db.dao.AnalysisStateDao
import com.renyxin.localalbum.data.db.dao.EmbeddingDao
import com.renyxin.localalbum.data.db.dao.FaceDao
import com.renyxin.localalbum.data.db.dao.FeatureStoreDao
import com.renyxin.localalbum.data.db.dao.MediaDao
import com.renyxin.localalbum.data.db.dao.PluginManifestDao
import com.renyxin.localalbum.data.db.entity.AnalysisStateEntity
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.FeatureStoreEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import com.renyxin.localalbum.data.db.entity.PluginManifestEntity

@Database(
    entities = [
        MediaEntity::class,
        MediaFts::class,
        FaceEntity::class,
        MediaEmbedding::class,
        FeatureStoreEntity::class,
        PluginManifestEntity::class,
        AnalysisStateEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun faceDao(): FaceDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun featureStoreDao(): FeatureStoreDao
    abstract fun pluginManifestDao(): PluginManifestDao
    abstract fun analysisStateDao(): AnalysisStateDao

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
                        geoClusterId TEXT,
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
                        perceptualHash, ocrText, geoClusterId, qualityScore, deletedAtMs,
                        isCorrupted, faceClusterId
                    )
                    SELECT
                        filePath, fileName, mediaType, capturedAtMs, modifiedAtMs, indexedAtMs,
                        parentPath, fileSize, isFavorite, isTrashed, width, height, mimeType,
                        durationMs, latitude, longitude, make, model, aperture, focalLength,
                        iso, exposureTime, orientation, sceneType, thumbnailPath, fingerprintHead,
                        perceptualHash, ocrText, geoClusterId, qualityScore, deletedAtMs,
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
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_geoClusterId ON media_items(geoClusterId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_fileSize ON media_items(fileSize)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_modifiedAtMs ON media_items(modifiedAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_isCorrupted ON media_items(isCorrupted)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_items_faceClusterId ON media_items(faceClusterId)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "local_album_db"
                )
                    .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    // 1.2: 仅在降级时销毁数据；升级缺失迁移时抛异常而非静默清库
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}