package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.core.analysis.IncrementalFaceClusterAssigner
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.entity.FaceClusterMetaEntity
import com.renyxin.localalbum.data.db.entity.FaceClusterPrototypeEntity
import com.renyxin.localalbum.data.db.entity.FaceEntity
import com.renyxin.localalbum.data.db.entity.MaintenanceRunEntity
import com.renyxin.localalbum.data.db.entity.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceClusterV23Test {
    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun close() = database.close()

    @Test
    fun migration保留旧人脸命名与媒体聚类并创建原型表() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-22-23-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE faces(faceId INTEGER PRIMARY KEY, clusterId TEXT, personName TEXT)")
            db.execSQL("CREATE TABLE media_items(filePath TEXT PRIMARY KEY, faceClusterId TEXT)")
            db.execSQL("INSERT INTO faces VALUES(1, 'c1', '小明')")
            db.execSQL("INSERT INTO media_items VALUES('/a.jpg', 'c1')")
            AppDatabase.MIGRATION_22_23.migrate(db)
            db.query("SELECT clusterId, personName FROM faces WHERE faceId = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("c1", it.getString(0))
                assertEquals("小明", it.getString(1))
            }
            db.query("SELECT faceClusterId FROM media_items WHERE filePath = '/a.jpg'").use {
                assertTrue(it.moveToFirst())
                assertEquals("c1", it.getString(0))
            }
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'face_cluster_%'").use {
                val names = mutableSetOf<String>()
                while (it.moveToNext()) names += it.getString(0)
                assertEquals(setOf("face_cluster_meta", "face_cluster_prototypes"), names)
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun activeGeneration隔离名称保留且cursor可恢复() = runBlocking {
        val dao = database.faceClusterDao()
        val task = MaintenanceRunEntity.TASK_FACE_PROTOTYPES
        dao.insertRun(MaintenanceRunEntity(taskType = task, generation = 1))
        dao.upsertMeta(FaceClusterMetaEntity(1, "c1", "小明", providerScope = "p", modelScope = "m"))
        dao.upsertPrototype(FaceClusterPrototypeEntity(1, "c1", 0, "1,0", 2, "p", "m"))
        dao.markFinalizing(1, 10)
        assertTrue(dao.publish(1, 11))

        dao.insertRun(MaintenanceRunEntity(taskType = task, generation = 2))
        dao.upsertMeta(FaceClusterMetaEntity(2, "c1", dao.latestPersonName("c1"), providerScope = "p", modelScope = "m"))
        dao.upsertPrototype(FaceClusterPrototypeEntity(2, "c1", 0, "0,1", 1, "p", "m"))
        assertEquals(1, dao.advanceCursor(2, "128", 128, 120, 8, 20))
        assertEquals("128", dao.resumableRun()?.cursorPath)

        val beforePublish = dao.activePrototypesAfter("p", "m", "", -1, 10)
        assertEquals(1L, beforePublish.single().generation)
        assertNull(dao.activeRun()?.takeIf { it.generation == 2L })

        dao.setPersonName("c1", "新名字", 21)
        assertEquals("新名字", dao.getMeta(1, "c1")?.personName)
        assertEquals("新名字", dao.getMeta(2, "c1")?.personName)
        dao.markFinalizing(2, 22)
        assertTrue(dao.publish(2, 23))
        val afterPublish = dao.activePrototypesAfter("p", "m", "", -1, 10)
        assertEquals(listOf(2L), afterPublish.map { it.generation }.distinct())
    }

    @Test
    fun 同一图片的多张人脸只归入一个人物分类() = runBlocking {
        val path = "/album/group.jpg"
        database.mediaDao().insertAll(
            listOf(
                MediaEntity(
                    filePath = path,
                    fileName = "group.jpg",
                    mediaType = MediaType.IMAGE,
                    capturedAtMs = 1L,
                    modifiedAtMs = 1L,
                    parentPath = "/album",
                ),
            ),
        )
        database.faceDao().insertFaces(
            listOf(
                FaceEntity(
                    filePath = path,
                    embedding = "1,0",
                    boxLeft = 0f,
                    boxTop = 0f,
                    boxRight = 0.8f,
                    boxBottom = 0.8f,
                ),
                FaceEntity(
                    filePath = path,
                    embedding = "0,1",
                    boxLeft = 0f,
                    boxTop = 0f,
                    boxRight = 0.2f,
                    boxBottom = 0.2f,
                ),
            ),
        )

        IncrementalFaceClusterAssigner(
            mediaDao = database.mediaDao(),
            faceDao = database.faceDao(),
            faceClusterDao = database.faceClusterDao(),
            providerScope = "builtin",
            modelScope = "test",
        ).assign(listOf(path))

        val faces = database.faceDao().getByFilePath(path)
        assertEquals(1, faces.mapNotNull { it.clusterId }.distinct().size)
        assertEquals(1, database.faceDao().getClusterSummaries().size)
    }

    @Test
    fun active原型未命中的相似人脸仍会形成同一新人物簇() = runBlocking {
        val clusterDao = database.faceClusterDao()
        clusterDao.insertRun(
            MaintenanceRunEntity(
                taskType = MaintenanceRunEntity.TASK_FACE_PROTOTYPES,
                generation = 1,
            ),
        )
        clusterDao.upsertPrototype(
            FaceClusterPrototypeEntity(1, "old", 0, "0,1", 1, "builtin", "test"),
        )
        clusterDao.markFinalizing(1, 1)
        assertTrue(clusterDao.publish(1, 2))

        val paths = listOf("/album/a.jpg", "/album/b.jpg")
        database.mediaDao().insertAll(paths.mapIndexed { index, path ->
            MediaEntity(
                filePath = path,
                fileName = path.substringAfterLast('/'),
                mediaType = MediaType.IMAGE,
                capturedAtMs = index.toLong(),
                modifiedAtMs = index.toLong(),
                parentPath = "/album",
            )
        })
        database.faceDao().insertFaces(paths.mapIndexed { index, path ->
            FaceEntity(
                filePath = path,
                embedding = if (index == 0) "1,0" else "0.999,0.001",
                boxLeft = 0f,
                boxTop = 0f,
                boxRight = 1f,
                boxBottom = 1f,
            )
        })

        IncrementalFaceClusterAssigner(
            mediaDao = database.mediaDao(),
            faceDao = database.faceDao(),
            faceClusterDao = clusterDao,
            providerScope = "builtin",
            modelScope = "test",
        ).assign(paths)

        val assigned = paths.flatMap { database.faceDao().getByFilePath(it) }
        assertEquals(1, assigned.mapNotNull { it.clusterId }.distinct().size)
        assertEquals(1, database.faceDao().getClusterSummaries().count { it.clusterId != "old" })
    }
}
