package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration28To29Test {
    @Test
    fun migrationAddsLifecycleOwnershipAndMapsLegacyRuns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-28-29-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(28) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE scan_runs (
                                scanId TEXT NOT NULL PRIMARY KEY,
                                generation INTEGER NOT NULL,
                                scanType TEXT NOT NULL,
                                status TEXT NOT NULL,
                                mediaStoreCompleted INTEGER NOT NULL,
                                fileSystemCompleted INTEGER NOT NULL,
                                startedAt INTEGER NOT NULL,
                                completedAt INTEGER NOT NULL,
                                errorType TEXT
                            )""",
                        )
                        db.execSQL(
                            """CREATE TABLE analysis_tasks (
                                taskId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                filePath TEXT NOT NULL,
                                sourceVersion TEXT NOT NULL,
                                pipelineScope TEXT NOT NULL,
                                priority INTEGER NOT NULL,
                                status TEXT NOT NULL,
                                attemptCount INTEGER NOT NULL,
                                nextRetryAt INTEGER NOT NULL,
                                leaseUntil INTEGER NOT NULL,
                                leaseToken TEXT,
                                lastError TEXT,
                                createdAt INTEGER NOT NULL,
                                updatedAt INTEGER NOT NULL
                            )""",
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }).build(),
        )

        try {
            val db = helper.writableDatabase
            insertRun(db, "completed", 1L, "COMPLETED", 10L, 20L)
            insertRun(db, "failed", 2L, "FAILED", 11L, 21L)
            insertRun(db, "aborted", 3L, "ABORTED", 12L, 22L)
            insertRun(db, "running", 4L, "RUNNING", 13L, 0L)
            db.execSQL(
                """INSERT INTO analysis_tasks (
                    filePath, sourceVersion, pipelineScope, priority, status,
                    attemptCount, nextRetryAt, leaseUntil, leaseToken, lastError,
                    createdAt, updatedAt
                ) VALUES ('/legacy.jpg', 'v1', 'core:v1', 0, 'PENDING', 0, 0, 0, NULL, NULL, 1, 1)""",
            )

            AppDatabase.MIGRATION_28_29.migrate(db)

            db.query(
                """SELECT indexAvailability, coreScanState, enhancementState,
                    indexAvailableAt, coreCompletedAt
                    FROM scan_runs WHERE scanId = 'completed'""",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PUBLISHED", cursor.getString(0))
                assertEquals("COMPLETED", cursor.getString(1))
                assertEquals("NOT_SCHEDULED", cursor.getString(2))
                assertEquals(20L, cursor.getLong(3))
                assertEquals(20L, cursor.getLong(4))
            }
            assertRunState(db, "failed", "FAILED", "FAILED", null)
            assertRunState(db, "aborted", "ABORTED", "CANCELLED", null)
            assertRunState(db, "running", "ABORTED", "PAUSED", "process_restarted")

            db.query("SELECT scanId FROM analysis_tasks WHERE filePath = '/legacy.jpg'").use {
                assertTrue(it.moveToFirst())
                assertNull(it.getString(0))
            }
            assertTrue(indexNames(db, "scan_runs").contains("index_scan_runs_coreScanState_startedAt"))
            assertTrue(indexNames(db, "scan_runs").contains("index_scan_runs_enhancementState_startedAt"))
            assertTrue(indexNames(db, "analysis_tasks").contains("index_analysis_tasks_scanId_status"))
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    private fun insertRun(
        db: SupportSQLiteDatabase,
        scanId: String,
        generation: Long,
        status: String,
        startedAt: Long,
        completedAt: Long,
    ) {
        db.execSQL(
            """INSERT INTO scan_runs (
                scanId, generation, scanType, status, mediaStoreCompleted,
                fileSystemCompleted, startedAt, completedAt, errorType
            ) VALUES ('$scanId', $generation, 'FULL', '$status', 1, 1,
                $startedAt, $completedAt, NULL)""",
        )
    }

    private fun assertRunState(
        db: SupportSQLiteDatabase,
        scanId: String,
        expectedStatus: String,
        expectedCoreState: String,
        expectedError: String?,
    ) {
        db.query(
            "SELECT status, coreScanState, errorType FROM scan_runs WHERE scanId = '$scanId'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expectedStatus, cursor.getString(0))
            assertEquals(expectedCoreState, cursor.getString(1))
            if (expectedError == null) {
                assertNull(cursor.getString(2))
            } else {
                assertEquals(expectedError, cursor.getString(2))
            }
        }
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val names = linkedSetOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                names += cursor.getString(nameColumn)
            }
        }
        return names
    }
}
