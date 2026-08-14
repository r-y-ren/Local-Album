package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.pipeline.AnalysisPlanType
import com.renyxin.localalbum.core.pipeline.StageInclusionPolicy
import com.renyxin.localalbum.core.search.FtsQueryBuilder
import com.renyxin.localalbum.core.search.KeywordSearchProfile
import com.renyxin.localalbum.data.backup.BackupContract
import com.renyxin.localalbum.data.backup.DatabaseExporter
import com.renyxin.localalbum.data.backup.DatabaseImporter
import com.renyxin.localalbum.data.backup.PostRestoreTaskPolicy
import com.renyxin.localalbum.data.backup.PostRestoreTaskSeeder
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import com.renyxin.localalbum.edition.EditionConfiguration
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Real AppDatabase coverage for complete v3 self-restore and edition-inert Full AI snapshots. */
class CompleteBackupRoundTripTest {
    private lateinit var database: AppDatabase
    private lateinit var backup: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        backup = File(context.cacheDir, "complete-round-trip-${System.nanoTime()}.zip")
    }

    @After
    fun tearDown() {
        database.close()
        backup.delete()
    }

    @Test
    fun completeBackupSelfRestorePreservesInertAiDataAndDoesNotResumeMaintenance() = runBlocking {
        seedFullShapedSnapshot()
        val exporter = DatabaseExporter(
            database.mediaDao(),
            database.faceDao(),
            database.embeddingDao(),
            database,
        )

        val export = exporter.exportToFile(backup, "instrumentation")

        assertTrue(export.errorMessage.orEmpty(), export.success)
        ZipFile(backup).use { zip ->
            val manifest = JSONObject(zip.getInputStream(zip.getEntry("manifest.json")).bufferedReader().readText())
            val capabilities = manifest.getJSONArray("capabilities")
            assertEquals("complete", manifest.getString("profile"))
            assertEquals(32, manifest.getInt("roomSchemaVersion"))
            assertTrue((0 until capabilities.length()).any {
                capabilities.getString(it) == BackupContract.CAP_DELETION_INTENT
            })
            assertTrue(zip.getEntry("deletion_tombstones.ndjson") != null)
        }

        // This existing primary key used to collide with face_cluster_runs during same-DB restore.
        assertEquals(1, countWhere("maintenance_runs", "taskType='FACE_PROTOTYPES' AND generation=77"))
        val features = EditionConfiguration.features
        val importer = DatabaseImporter(
            mediaDao = database.mediaDao(),
            faceDao = database.faceDao(),
            embeddingDao = database.embeddingDao(),
            database = database,
            postRestoreTaskSeeder = PostRestoreTaskSeeder(
                PostRestoreTaskPolicy(
                    profileId = features.editionId,
                    pipelineScope = "test:restore|edition=${features.editionId}",
                    enqueueAutomaticAnalysis = StageInclusionPolicy(features.scanFeaturePolicy)
                        .includedStageIds(AnalysisPlanType.ENHANCEMENT)
                        .isNotEmpty(),
                ),
            ),
        )

        val import = importer.importFromFile(backup)

        assertTrue(import.errorMessage.orEmpty(), import.success)
        assertEquals(1, count("media_items"))
        assertEquals(1, count("faces"))
        assertEquals(1, count("media_embeddings"))
        assertEquals(1, count("media_items_fts"))
        database.mediaDao().getAllFtsEntries().single().let { restoredFts ->
            assertEquals(PARENT, restoredFts.parentPath)
            assertEquals(OCR_ONLY_TOKEN, restoredFts.ocrText)
        }
        assertEquals(1, count("feature_store"))
        assertEquals(1, count("plugin_manifest"))
        assertEquals(1, count("face_cluster_meta"))
        assertEquals(1, count("face_cluster_prototypes"))
        assertEquals(1, count("semantic_index_meta"))
        assertEquals(1, scalarInt("SELECT isActive FROM semantic_index_meta WHERE spaceId='space-1'"))
        assertEquals(1, count("semantic_cluster_generations"))
        assertEquals(
            1,
            scalarInt(
                "SELECT isActive FROM semantic_cluster_generations " +
                    "WHERE spaceId='space-1' AND generation=88",
            ),
        )
        assertEquals(1, count("semantic_cluster_meta"))
        assertEquals(1, count("semantic_cluster_members"))
        assertEquals(1, count("deletion_tombstones"))
        assertEquals(1, countWhere("maintenance_runs", "taskType='FACE_PROTOTYPES' AND generation=77"))
        assertEquals(0, countWhere("maintenance_runs", "taskType='DUPLICATE_EXACT'"))
        assertEquals(0, count("semantic_maintenance_runs"))
        assertEquals(0, count("analysis_tasks"))
        assertEquals(0, count("thumbnail_tasks"))
        assertEquals(
            if (features.keywordSearchProfile == KeywordSearchProfile.FULL) 1 else 0,
            database.mediaDao().countFts(
                FtsQueryBuilder.build(OCR_ONLY_TOKEN, features.keywordSearchProfile),
            ),
        )

        database.openHelper.readableDatabase.query(
            "SELECT enqueueAnalysis,enqueueThumbnail FROM enhancement_outbox",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            val expectedAnalysis = if (features.editionId == "full") 1 else 0
            assertEquals(expectedAnalysis, cursor.getInt(0))
            assertEquals(1, cursor.getInt(1))
            assertFalse(cursor.moveToNext())
        }

        val secondBackup = File(backup.parentFile, "complete-round-trip-second-${System.nanoTime()}.zip")
        try {
            val secondExport = exporter.exportToFile(secondBackup, "instrumentation-restored")
            assertTrue(secondExport.errorMessage.orEmpty(), secondExport.success)
            ZipFile(secondBackup).use { zip ->
                assertEquals(1, lineCount(zip, "faces.ndjson"))
                assertEquals(1, lineCount(zip, "embeddings.ndjson"))
                assertEquals(1, lineCount(zip, "feature_store.ndjson"))
                assertEquals(1, lineCount(zip, "face_cluster_runs.ndjson"))
                assertEquals(1, lineCount(zip, "semantic_cluster_members.ndjson"))
                assertEquals(1, lineCount(zip, "deletion_tombstones.ndjson"))
            }
        } finally {
            secondBackup.delete()
        }
    }

    private suspend fun seedFullShapedSnapshot() {
        database.mediaDao().insertAll(
            listOf(
                MediaEntity(
                    filePath = PATH,
                    fileName = "trip.jpg",
                    mediaType = MediaType.IMAGE,
                    capturedAtMs = 1,
                    modifiedAtMs = 2,
                    indexedAtMs = 3,
                    parentPath = PARENT,
                    fileSize = 4,
                    ocrText = OCR_ONLY_TOKEN,
                    faceClusterId = "person-1",
                ),
            ),
        )
        database.mediaDao().insertFtsAll(
            listOf(MediaFts(PATH, "trip.jpg", PARENT, OCR_ONLY_TOKEN, "Google", "Pixel 8")),
        )
        database.faceDao().insertFace(
            FaceEntity(
                faceId = 7,
                filePath = PATH,
                clusterId = "person-1",
                embedding = "0.1,0.2",
                boxLeft = 0f,
                boxTop = 0f,
                boxRight = 1f,
                boxBottom = 1f,
            ),
        )
        database.embeddingDao().insertEmbedding(
            MediaEmbedding(
                filePath = PATH,
                embedding = "0.3,0.4",
                modelVersion = 1,
                source = "full",
                embeddingBlob = byteArrayOf(1, 2, 3),
                providerId = "full-provider",
                modelId = "full-model",
                dimension = 2,
                spaceId = "space-1",
                generation = 88,
            ),
        )

        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO feature_store(id,filePath,pluginId,featureType,dataJson,schemaJson,modelVersion,generatedAtMs) VALUES(1,?,'full-plugin','embedding','[1,2]','{}',1,10)", arrayOf(PATH))
        db.execSQL("INSERT INTO plugin_manifest(id,pluginId,manifestJson,isEnabled,pipelineStage,orderIndex,createdAtMs,updatedAtMs) VALUES(1,'full-plugin','{}',1,'semantic',0,10,10)")
        db.execSQL("INSERT INTO maintenance_runs(taskType,generation,status,cursorPath,scannedCount,eligibleCount,failedCount,groupCount,memberCount,error,startedAt,updatedAt,completedAt,isActive) VALUES('FACE_PROTOTYPES',77,'COMPLETED',NULL,1,1,0,1,1,NULL,10,10,10,1)")
        db.execSQL("INSERT INTO maintenance_runs(taskType,generation,status,cursorPath,scannedCount,eligibleCount,failedCount,groupCount,memberCount,error,startedAt,updatedAt,completedAt,isActive) VALUES('DUPLICATE_EXACT',78,'RUNNING',NULL,0,0,0,0,0,NULL,10,10,0,1)")
        db.execSQL("INSERT INTO face_cluster_meta(generation,clusterId,personName,status,providerScope,modelScope,createdAt,updatedAt) VALUES(77,'person-1','Alice','ACTIVE','face-provider','face-model',10,10)")
        db.execSQL("INSERT INTO face_cluster_prototypes(generation,clusterId,prototypeIndex,embedding,sampleCount,providerScope,modelScope,updatedAt) VALUES(77,'person-1',0,'0.1,0.2',1,'face-provider','face-model',10)")
        db.execSQL("INSERT INTO semantic_index_meta(spaceId,providerId,modelId,modelVersion,dimension,codecId,formatVersion,activeGeneration,isActive,status,vectorCount,indexedCount,deletedCount,indexKind,builtAt,updatedAt) VALUES('space-1','full-provider','full-model',1,2,'raw',1,88,1,'READY',1,1,0,'EXACT',10,10)")
        db.execSQL("INSERT INTO semantic_maintenance_runs(taskType,generation,targetSpaceId,status,cursorPath,scannedCount,convertedCount,failedCount,totalCount,coverageCount,lastError,startedAt,updatedAt,completedAt,isActive) VALUES('SEMANTIC_CSV_BACKFILL',99,'space-1','RUNNING',NULL,0,0,0,1,0,NULL,10,10,0,1)")
        db.execSQL("INSERT INTO semantic_cluster_generations(spaceId,generation,status,isActive,sampleLimit,sampledCount,clusterCount,createdAt,completedAt) VALUES('space-1',88,'COMPLETED',1,10,1,1,10,10)")
        db.execSQL("INSERT INTO semantic_cluster_meta(spaceId,generation,clusterId,status,centroidBlob,memberCount,score,title,createdAt) VALUES('space-1',88,'topic-1','COMPLETED',?,1,0.9,'Trips',10)", arrayOf(byteArrayOf(4, 5)))
        db.execSQL("INSERT INTO semantic_cluster_members(spaceId,generation,clusterId,rank,filePath) VALUES('space-1',88,'topic-1',0,?)", arrayOf(PATH))
        db.execSQL("INSERT INTO deletion_tombstones(pathKey,filePath,operation,state,attemptCount,nextRetryAt,leaseUntil,leaseToken,lastErrorType,lastErrorCode,lastErrorMessage,sourceVersion,deletedAtMs,firstAttemptAt,lastAttemptAt,createdAt,updatedAt) VALUES('deadbeef','/missing.jpg','USER_PERMANENT','COMPLETED',1,0,0,NULL,NULL,NULL,NULL,'1:1',10,10,10,10,10)")
    }

    private fun count(table: String): Int = countWhere(table, "1=1")

    private fun scalarInt(query: String): Int =
        database.openHelper.readableDatabase.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun countWhere(table: String, where: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE $where").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun lineCount(zip: ZipFile, entry: String): Int =
        zip.getInputStream(zip.getEntry(entry)).bufferedReader().useLines { lines -> lines.count { it.isNotBlank() } }

    private companion object {
        const val PATH = "/storage/emulated/0/Pictures/Trips/trip.jpg"
        const val PARENT = "/storage/emulated/0/Pictures/Trips"
        const val OCR_ONLY_TOKEN = "ultravioletcipher"
    }
}
