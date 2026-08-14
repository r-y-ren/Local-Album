package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration30To31Test {
    @Test
    fun migrationCreatesPostCoreOutboxAndThumbnailOwnership() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-30-31-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(30) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE thumbnail_tasks (
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
                            )""".trimIndent(),
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )

        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_30_31.migrate(db)

            assertTrue(tableNames(db).contains("enhancement_outbox"))
            assertTrue(columnNames(db, "thumbnail_tasks").contains("scanId"))
            assertEquals(
                setOf(
                    "outboxId", "scanId", "profileId", "filePath", "sourceVersion",
                    "mediaType", "pipelineScope", "enqueueAnalysis", "enqueueThumbnail",
                    "parentPath", "status", "attemptCount", "nextRetryAt", "leaseUntil",
                    "leaseToken", "lastError", "createdAt", "updatedAt",
                ),
                columnNames(db, "enhancement_outbox"),
            )
            val outboxIndexes = indexNames(db, "enhancement_outbox")
            assertTrue(outboxIndexes.contains("index_enhancement_outbox_scanId_status_createdAt"))
            assertTrue(outboxIndexes.contains("index_enhancement_outbox_status_nextRetryAt_createdAt"))
            assertTrue(outboxIndexes.contains("index_enhancement_outbox_leaseUntil"))
            assertTrue(outboxIndexes.contains("index_enhancement_outbox_leaseToken"))
            assertTrue(
                outboxIndexes.contains(
                    "index_enhancement_outbox_profileId_filePath_sourceVersion_pipelineScope",
                ),
            )
            assertTrue(indexNames(db, "thumbnail_tasks").contains("index_thumbnail_tasks_scanId_status"))
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> = buildSet {
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
            .use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> = buildSet {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(name))
        }
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> = buildSet {
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(name))
        }
    }
}
