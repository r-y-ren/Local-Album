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
class Migration29To30Test {
    @Test
    fun migrationCreatesJournalAndProfileScopedReferenceSchema() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-29-30-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(29) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit

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
            AppDatabase.MIGRATION_29_30.migrate(db)

            assertTrue(tableNames(db).contains("media_change_events"))
            assertTrue(tableNames(db).contains("media_store_references"))
            assertEquals(
                setOf(
                    "eventKey", "profileId", "eventType", "volumeName", "mediaType",
                    "mediaStoreId", "contentUri", "flags", "firstObservedAtMs", "observedAtMs",
                    "status", "attemptCount", "nextAttemptAt", "leaseUntil", "leaseToken",
                    "lastError", "createdAtMs", "updatedAtMs",
                ),
                columnNames(db, "media_change_events"),
            )
            assertEquals(
                setOf(
                    "stableKey", "profileId", "volumeName", "mediaType", "mediaStoreId",
                    "contentUri", "filePath", "sourceVersion", "observedAtMs", "scanGeneration",
                ),
                columnNames(db, "media_store_references"),
            )

            assertTrue(
                indexNames(db, "media_change_events").contains(
                    "index_media_change_events_profileId_status_observedAtMs",
                ),
            )
            assertTrue(
                indexNames(db, "media_change_events").contains("index_media_change_events_leaseToken"),
            )
            assertTrue(
                indexNames(db, "media_change_events").contains("index_media_change_events_eventType_status"),
            )
            assertTrue(
                indexNames(db, "media_store_references").contains(
                    "index_media_store_references_profileId_volumeName_mediaType_mediaStoreId",
                ),
            )
            assertTrue(
                indexNames(db, "media_store_references").contains(
                    "index_media_store_references_profileId_filePath",
                ),
            )
            assertEquals(
                listOf("profileId", "filePath"),
                indexColumns(
                    db,
                    "media_store_references",
                    "index_media_store_references_profileId_filePath",
                ),
            )
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> {
        val result = linkedSetOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
        ).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getString(0)
        }
        return result
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val result = linkedSetOf<String>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) result += cursor.getString(nameColumn)
        }
        return result
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val result = linkedSetOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) result += cursor.getString(nameColumn)
        }
        return result
    }

    private fun indexColumns(
        db: SupportSQLiteDatabase,
        table: String,
        index: String,
    ): List<String> {
        val result = mutableListOf<String>()
        db.query("PRAGMA index_info(`$index`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) result += cursor.getString(nameColumn)
        }
        return result
    }
}
