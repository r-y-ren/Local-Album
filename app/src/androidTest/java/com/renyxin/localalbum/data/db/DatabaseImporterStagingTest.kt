package com.renyxin.localalbum.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.backup.DatabaseExporter
import com.renyxin.localalbum.data.backup.DatabaseImporter
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.HomeMediaSnapshotEntity
import com.renyxin.localalbum.data.db.entity.LibraryPipelineStage
import com.renyxin.localalbum.data.db.entity.MediaChangeEventEntity
import com.renyxin.localalbum.data.db.entity.MediaEmbedding
import com.renyxin.localalbum.data.db.entity.MediaEntity
import com.renyxin.localalbum.data.db.entity.MediaFts
import com.renyxin.localalbum.data.db.entity.MediaStoreReferenceEntity
import com.renyxin.localalbum.data.db.entity.toImportStaging
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Room 真实事务测试：导入 staging 是生产四表的失败隔离边界。 */
class DatabaseImporterStagingTest {
    private lateinit var database: AppDatabase
    private lateinit var importer: DatabaseImporter

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        importer = DatabaseImporter(
            mediaDao = database.mediaDao(),
            faceDao = database.faceDao(),
            embeddingDao = database.embeddingDao(),
            database = database,
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun stagingWriteFailureDoesNotChangeProductionTables() = runBlocking {
        seedProduction("/old.jpg")
        seedLocalMediaChangeState()
        val duplicate = mediaJson("/new.jpg")
        val root = backupJson(JSONArray().put(duplicate).put(duplicate), JSONArray(), JSONArray(), JSONArray())

        val result = importer.importFromJson(root.toString())

        assertFalse(result.success)
        assertEquals(listOf("/old.jpg"), database.mediaDao().getPaged(100, 0).map { it.filePath })
        assertEquals(1, database.faceDao().getCount())
        assertEquals(1, database.embeddingDao().getCount())
        assertEquals(1, database.mediaDao().getFtsCount())
        assertTrue(database.mediaChangeDao().hasOutstandingMediaChanges(LOCAL_PROFILE))
        assertEquals(1, tableCount("media_store_references"))
        assertEquals(0, database.importStagingDao().mediaCount("unused"))
    }

    @Test
    fun successfulImportAtomicallySwitchesAllFourProductionTables() = runBlocking {
        seedProduction("/old.jpg")
        seedLocalMediaChangeState()
        val path = "/new.jpg"
        val root = backupJson(
            JSONArray().put(mediaJson(path)),
            JSONArray().put(JSONObject().apply {
                put("faceId", 42); put("filePath", path); put("embedding", "0.1,0.2")
                put("boxLeft", 0.1); put("boxTop", 0.2); put("boxRight", 0.8); put("boxBottom", 0.9)
            }),
            JSONArray().put(JSONObject().apply {
                put("filePath", path); put("embedding", "0.3,0.4"); put("modelVersion", 2)
            }),
            JSONArray().put(JSONObject().apply {
                put("filePath", path); put("fileName", "new.jpg"); put("ocrText", "new text")
            }),
        )

        val result = importer.importFromJson(root.toString())

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertEquals(listOf(path), database.mediaDao().getPaged(100, 0).map { it.filePath })
        assertEquals(listOf(path), database.faceDao().getPaged(100, 0).map { it.filePath })
        assertEquals(listOf(path), database.embeddingDao().getPaged(100, 0).map { it.filePath })
        assertEquals(listOf(path), database.mediaDao().getAllFtsEntries().map { it.filePath })
        assertEquals(0, tableCount("analysis_tasks"))
        assertEquals(0, tableCount("thumbnail_tasks"))
        assertEquals(0, tableCount("enhancement_outbox"))
        assertEquals(0, tableCount("scan_runs"))
        assertFalse(database.mediaChangeDao().hasOutstandingMediaChanges(LOCAL_PROFILE))
        assertFalse(database.mediaChangeDao().hasReconciliationHint(LOCAL_PROFILE))
        assertEquals(0, tableCount("media_change_events"))
        assertEquals(0, tableCount("media_store_references"))

        val snapshot = requireNotNull(database.homeMediaSnapshotDao().getByPath(path))
        assertEquals(HomeMediaSnapshotEntity.THUMBNAIL_PLACEHOLDER, snapshot.thumbnailState)
        assertEquals(null, snapshot.thumbnailPath)
        val pipeline = requireNotNull(database.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.NEEDS_REBUILD.name, pipeline.stage)
        assertTrue(pipeline.hasPublishedBaseline)
        assertTrue(pipeline.rebuildRequired)
        assertFalse(pipeline.rebuildRequested)
        assertEquals("backup_import_requires_validation", pipeline.rebuildReason)
        assertEquals(snapshot.publishedGeneration, pipeline.publishedGeneration)
    }

    @Test
    fun emptyImportPublishesAnEmptyBaselineWithoutStartingAFullScan() = runBlocking {
        seedProduction("/old.jpg")
        val empty = backupJson(JSONArray(), JSONArray(), JSONArray(), JSONArray())

        val result = importer.importFromJson(empty.toString())

        assertTrue(result.errorMessage.orEmpty(), result.success)
        assertEquals(0, database.mediaDao().getCount())
        assertEquals(0, database.homeMediaSnapshotDao().count())
        val pipeline = requireNotNull(database.libraryPipelineDao().get())
        assertEquals(LibraryPipelineStage.NEEDS_REBUILD.name, pipeline.stage)
        assertTrue(pipeline.hasPublishedBaseline)
        assertTrue(pipeline.publishedGeneration > 0L)
        assertTrue(pipeline.rebuildRequired)
        assertFalse(pipeline.rebuildRequested)
        assertEquals(0, tableCount("scan_runs"))
        assertEquals(0, tableCount("enhancement_outbox"))
    }

    @Test
    fun staleStagingCanBeCleanedWithoutTouchingProduction() = runBlocking {
        seedProduction("/old.jpg")
        val generation = "crashed-import"
        database.importStagingDao().insertMedia(listOf(media("/stale.jpg").toImportStaging(generation)))
        assertEquals(1, database.importStagingDao().mediaCount(generation))

        importer.cleanupStaleStaging()

        assertEquals(0, database.importStagingDao().mediaCount(generation))
        assertEquals(listOf("/old.jpg"), database.mediaDao().getPaged(100, 0).map { it.filePath })
    }

    private suspend fun seedProduction(path: String) {
        database.mediaDao().insertAll(listOf(media(path)))
        database.faceDao().insertFaces(listOf(FaceEntity(filePath = path, embedding = "old", boxLeft = 0f, boxTop = 0f, boxRight = 1f, boxBottom = 1f)))
        database.embeddingDao().insertEmbedding(MediaEmbedding(path, "old", 1))
        database.mediaDao().insertFtsAll(
            listOf(
                MediaFts(
                    filePath = path,
                    fileName = path.substringAfterLast('/'),
                    parentPath = path.substringBeforeLast('/', ""),
                    ocrText = "old",
                    make = null,
                    model = null,
                ),
            ),
        )
    }

    private suspend fun seedLocalMediaChangeState() {
        database.mediaChangeDao().record(
            MediaChangeEventEntity(
                eventKey = "$LOCAL_PROFILE:external:IMAGE:9",
                profileId = LOCAL_PROFILE,
                eventType = MediaChangeEventEntity.TYPE_MEDIA,
                volumeName = "external",
                mediaType = MediaType.IMAGE.name,
                mediaStoreId = 9L,
                contentUri = "content://media/external/images/media/9",
                firstObservedAtMs = 1L,
                observedAtMs = 2L,
            ),
        )
        database.mediaChangeDao().upsertReferences(
            listOf(
                MediaStoreReferenceEntity(
                    stableKey = "$LOCAL_PROFILE:external:IMAGE:9",
                    profileId = LOCAL_PROFILE,
                    volumeName = "external",
                    mediaType = MediaType.IMAGE.name,
                    mediaStoreId = 9L,
                    contentUri = "content://media/external/images/media/9",
                    filePath = "/device-only.jpg",
                    sourceVersion = "1:1",
                    observedAtMs = 2L,
                ),
            ),
        )
    }

    private fun tableCount(table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun media(path: String) = MediaEntity(
        filePath = path, fileName = path.substringAfterLast('/'), mediaType = MediaType.IMAGE,
        capturedAtMs = 1, modifiedAtMs = 2, indexedAtMs = 3, parentPath = "/",
    )

    private fun mediaJson(path: String) = JSONObject().apply {
        put("filePath", path); put("fileName", path.substringAfterLast('/')); put("mediaType", "IMAGE")
        put("capturedAtMs", 1); put("modifiedAtMs", 2); put("indexedAtMs", 3); put("parentPath", "/")
    }

    private fun backupJson(media: JSONArray, faces: JSONArray, embeddings: JSONArray, fts: JSONArray) =
        JSONObject().apply {
            put("version", DatabaseExporter.EXPORT_FORMAT_VERSION)
            put("mediaItems", media); put("faces", faces); put("embeddings", embeddings); put("ftsEntries", fts)
        }

    private companion object {
        const val LOCAL_PROFILE = "lite"
    }
}
