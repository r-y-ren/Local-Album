package com.renyxin.localalbum.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class Migration32To33Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun existingVisibleLibraryBecomesReadyStableBaselineWithVerifiedCurrentGridOnly() {
        val name = "migration-32-33-populated-${System.nanoTime()}"
        helper.createDatabase(name, 32).apply {
            insertMedia(
                db = this,
                path = READY_PATH,
                modifiedAtMs = 10L,
                fileSize = 20L,
                thumbnailPath = READY_GRID_PATH,
                scanGeneration = 4L,
            )
            insertThumbnailCache(
                db = this,
                path = READY_PATH,
                sourceVersion = "10:20",
                cachePath = READY_GRID_PATH,
            )
            insertMedia(
                db = this,
                path = STALE_PATH,
                modifiedAtMs = 11L,
                fileSize = 21L,
                thumbnailPath = STALE_GRID_PATH,
                scanGeneration = 9L,
            )
            insertThumbnailCache(
                db = this,
                path = STALE_PATH,
                sourceVersion = "10:21",
                cachePath = STALE_GRID_PATH,
            )
            insertMedia(
                db = this,
                path = PLACEHOLDER_PATH,
                modifiedAtMs = 12L,
                fileSize = 22L,
                thumbnailPath = null,
                scanGeneration = 7L,
            )
            insertMedia(
                db = this,
                path = TRASHED_PATH,
                modifiedAtMs = 13L,
                fileSize = 23L,
                thumbnailPath = TRASHED_GRID_PATH,
                scanGeneration = 99L,
                isTrashed = true,
            )
            insertThumbnailTask(this,READY_PATH,"10:20","DONE")
            insertThumbnailTask(this,STALE_PATH,"11:21","FAILED")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            name,
            33,
            true,
            AppDatabase.MIGRATION_32_33,
        )
        try {
            migrated.query(
                """SELECT stage,activeRunId,candidateGeneration,publishedGeneration,
                    hasPublishedBaseline,rebuildRequested,rebuildRequired,
                    progressCompleted,progressTotal,failureCount
                    FROM library_pipeline WHERE pipelineId = 'default'""".trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("READY", cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals(9L, cursor.getLong(2))
                assertEquals(9L, cursor.getLong(3))
                assertEquals(1, cursor.getInt(4))
                assertEquals(0, cursor.getInt(5))
                assertEquals(0, cursor.getInt(6))
                assertEquals(0, cursor.getInt(7))
                assertEquals(0, cursor.getInt(8))
                assertEquals(0, cursor.getInt(9))
                assertFalse(cursor.moveToNext())
            }

            migrated.query(
                """SELECT deliveryState,revision,dispatchToken,runToken,notBefore,nextDispatchAt
                    FROM thumbnail_lane_wake WHERE laneId = 'interactive'""".trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("QUIESCENT",cursor.getString(0))
                assertEquals(0L,cursor.getLong(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
                assertEquals(0L,cursor.getLong(4))
                assertEquals(0L,cursor.getLong(5))
                assertFalse(cursor.moveToNext())
            }
            migrated.query("SELECT COUNT(*) FROM thumbnail_cache_entries").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0,cursor.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM thumbnail_lane_wake").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2,cursor.getInt(0))
            }
            migrated.query("SELECT filePath,status FROM thumbnail_tasks ORDER BY filePath").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(STALE_PATH,cursor.getString(0))
                assertEquals("FAILED",cursor.getString(1))
                assertFalse(cursor.moveToNext())
            }

            migrated.query(
                """SELECT filePath,thumbnailPath,thumbnailState,publishedGeneration
                    FROM home_media_snapshot ORDER BY filePath""".trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(PLACEHOLDER_PATH, cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals("PLACEHOLDER", cursor.getString(2))
                assertEquals(9L, cursor.getLong(3))

                assertTrue(cursor.moveToNext())
                assertEquals(READY_PATH, cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals("PLACEHOLDER", cursor.getString(2))
                assertEquals(9L, cursor.getLong(3))

                assertTrue(cursor.moveToNext())
                assertEquals(STALE_PATH, cursor.getString(0))
                assertNull(cursor.getString(1))
                assertEquals("PLACEHOLDER", cursor.getString(2))
                assertEquals(9L, cursor.getLong(3))
                assertFalse(cursor.moveToNext())
            }
            migrated.query(
                "SELECT filePath,scanGeneration FROM media_items WHERE isTrashed = 0 ORDER BY filePath",
            ).use { cursor ->
                val generations = buildMap {
                    while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1))
                }
                assertEquals(7L, generations[PLACEHOLDER_PATH])
                assertEquals(4L, generations[READY_PATH])
                assertEquals(9L, generations[STALE_PATH])
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun nonEmptyLegacyLibraryWithOnlyZeroGenerationsGetsOneConsistentPositiveBaseline() {
        val name = "migration-32-33-zero-generation-${System.nanoTime()}"
        helper.createDatabase(name, 32).apply {
            insertMedia(
                db = this,
                path = ZERO_GENERATION_PATH,
                modifiedAtMs = 30L,
                fileSize = 40L,
                thumbnailPath = null,
                scanGeneration = 0L,
            )
            insertMedia(
                db = this,
                path = SECOND_ZERO_GENERATION_PATH,
                modifiedAtMs = 31L,
                fileSize = 41L,
                thumbnailPath = null,
                scanGeneration = 0L,
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            name,
            33,
            true,
            AppDatabase.MIGRATION_32_33,
        )
        try {
            migrated.query(
                """SELECT stage,candidateGeneration,publishedGeneration,hasPublishedBaseline
                    FROM library_pipeline WHERE pipelineId = 'default'""".trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("READY", cursor.getString(0))
                assertEquals(1L, cursor.getLong(1))
                assertEquals(1L, cursor.getLong(2))
                assertEquals(1, cursor.getInt(3))
            }
            migrated.query(
                "SELECT DISTINCT publishedGeneration FROM home_media_snapshot",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertFalse(cursor.moveToNext())
            }
            migrated.query(
                "SELECT DISTINCT scanGeneration FROM media_items WHERE isTrashed = 0",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertFalse(cursor.moveToNext())
            }
            migrated.query("SELECT COUNT(*) FROM home_media_snapshot").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    @Test
    fun emptyLegacyLibraryRemainsBehindInitialHomeGate() {
        val name = "migration-32-33-empty-${System.nanoTime()}"
        helper.createDatabase(name, 32).close()

        val migrated = helper.runMigrationsAndValidate(
            name,
            33,
            true,
            AppDatabase.MIGRATION_32_33,
        )
        try {
            migrated.query(
                """SELECT stage,candidateGeneration,publishedGeneration,hasPublishedBaseline
                    FROM library_pipeline WHERE pipelineId = 'default'""".trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("UNINITIALIZED", cursor.getString(0))
                assertEquals(0L, cursor.getLong(1))
                assertEquals(0L, cursor.getLong(2))
                assertEquals(0, cursor.getInt(3))
                assertFalse(cursor.moveToNext())
            }
            migrated.query("SELECT COUNT(*) FROM home_media_snapshot").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }

    private fun insertMedia(
        db: SupportSQLiteDatabase,
        path: String,
        modifiedAtMs: Long,
        fileSize: Long,
        thumbnailPath: String?,
        scanGeneration: Long,
        isTrashed: Boolean = false,
    ) {
        db.execSQL(
            """INSERT INTO media_items(
                filePath,fileName,mediaType,capturedAtMs,modifiedAtMs,indexedAtMs,parentPath,
                fileSize,isFavorite,isTrashed,width,height,mimeType,durationMs,orientation,
                thumbnailPath,perceptualHash,qualityScore,deletedAtMs,isCorrupted,scanGeneration
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf(
                path,
                path.substringAfterLast('/'),
                "IMAGE",
                modifiedAtMs,
                modifiedAtMs,
                modifiedAtMs,
                path.substringBeforeLast('/'),
                fileSize,
                0,
                if (isTrashed) 1 else 0,
                100,
                100,
                "image/jpeg",
                0L,
                0,
                thumbnailPath,
                0L,
                0.0,
                if (isTrashed) 1_000L else 0L,
                0,
                scanGeneration,
            ),
        )
    }

    private fun insertThumbnailTask(
        db: SupportSQLiteDatabase,
        path: String,
        sourceVersion: String,
        status: String,
    ) {
        db.execSQL(
            """INSERT INTO thumbnail_tasks(
                filePath,sizeClass,sourceVersion,mediaType,priority,status,attemptCount,nextRetryAt,
                leaseUntil,leaseToken,lastError,createdAt,updatedAt,scanId
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf(path,"grid",sourceVersion,"IMAGE",100,status,if (status == "FAILED") 4 else 1,
                0L,0L,null,if (status == "FAILED") "decode_failed" else null,1L,1L,null),
        )
    }

    private fun insertThumbnailCache(
        db: SupportSQLiteDatabase,
        path: String,
        sourceVersion: String,
        cachePath: String,
    ) {
        db.execSQL(
            """INSERT INTO thumbnail_cache_entries(
                filePath,sizeClass,sourceVersion,path,byteSize,lastAccessAt,createdAt,state,
                leaseUntil,deleteAttemptCount
            ) VALUES(?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf(path, "grid", sourceVersion, cachePath, 100L, 1L, 1L, "READY", 0L, 0),
        )
    }

    private companion object {
        const val READY_PATH = "/storage/emulated/0/DCIM/ready.jpg"
        const val STALE_PATH = "/storage/emulated/0/DCIM/stale.jpg"
        const val PLACEHOLDER_PATH = "/storage/emulated/0/DCIM/placeholder.jpg"
        const val TRASHED_PATH = "/storage/emulated/0/DCIM/trashed.jpg"
        const val READY_GRID_PATH = "/data/user/0/app/cache/ready.webp"
        const val STALE_GRID_PATH = "/data/user/0/app/cache/stale.webp"
        const val TRASHED_GRID_PATH = "/data/user/0/app/cache/trashed.webp"
        const val ZERO_GENERATION_PATH = "/storage/emulated/0/DCIM/zero-generation.jpg"
        const val SECOND_ZERO_GENERATION_PATH = "/storage/emulated/0/DCIM/zero-generation-2.jpg"
    }
}
