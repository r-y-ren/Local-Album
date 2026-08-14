package com.renyxin.localalbum.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class Migration31To32Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesFtsRowsAndBackfillsParentPathWithoutDestructiveFallback() {
        val name = "migration-31-32-${System.nanoTime()}"
        helper.createDatabase(name, 31).apply {
            insertMedia(this, PATH, PARENT_PATH)
            execSQL(
                "INSERT INTO media_items_fts(filePath,fileName,ocrText,make,model) VALUES(?,?,?,?,?)",
                arrayOf(PATH, "trip.jpg", "imported secret", "Google", "Pixel 8"),
            )
            execSQL(
                """INSERT INTO import_fts_staging(
                    generation,filePath,fileName,ocrText,make,model
                ) VALUES(?,?,?,?,?,?)""".trimIndent(),
                arrayOf("restore-generation", PATH, "trip.jpg", "staged secret", "Google", "Pixel 8"),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            name,
            32,
            true,
            AppDatabase.MIGRATION_31_32,
        )
        try {
            assertEquals(
                listOf("filePath", "fileName", "parentPath", "ocrText", "make", "model"),
                columnNames(migrated, "media_items_fts"),
            )
            migrated.query(
                "SELECT filePath,fileName,parentPath,ocrText,make,model FROM media_items_fts",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals(PATH, cursor.getString(0))
                assertEquals("trip.jpg", cursor.getString(1))
                assertEquals(PARENT_PATH, cursor.getString(2))
                assertEquals("imported secret", cursor.getString(3))
                assertEquals("Google", cursor.getString(4))
                assertEquals("Pixel 8", cursor.getString(5))
                assertEquals(false, cursor.moveToNext())
            }

            assertEquals(
                listOf(
                    "stagingId", "generation", "filePath", "fileName", "parentPath",
                    "ocrText", "make", "model",
                ),
                columnNames(migrated, "import_fts_staging"),
            )
            migrated.query(
                "SELECT generation,filePath,parentPath,ocrText FROM import_fts_staging",
            ).use { cursor ->
                assertEquals(true, cursor.moveToFirst())
                assertEquals("restore-generation", cursor.getString(0))
                assertEquals(PATH, cursor.getString(1))
                assertEquals(PARENT_PATH, cursor.getString(2))
                assertEquals("staged secret", cursor.getString(3))
                assertEquals(false, cursor.moveToNext())
            }
        } finally {
            migrated.close()
        }
    }

    private fun insertMedia(db: SupportSQLiteDatabase, path: String, parentPath: String) {
        db.execSQL(
            """INSERT INTO media_items(
                filePath,fileName,mediaType,capturedAtMs,modifiedAtMs,indexedAtMs,parentPath,
                fileSize,isFavorite,isTrashed,width,height,mimeType,durationMs,orientation,
                perceptualHash,qualityScore,deletedAtMs,isCorrupted,scanGeneration
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
            arrayOf(
                path, "trip.jpg", "IMAGE", 1L, 2L, 3L, parentPath,
                100L, 0, 0, 100, 100, "image/jpeg", 0L, 0,
                0L, 0.0, 0L, 0, 7L,
            ),
        )
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): List<String> = buildList {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(name))
        }
    }

    private companion object {
        const val PATH = "/storage/emulated/0/DCIM/Trips/trip.jpg"
        const val PARENT_PATH = "/storage/emulated/0/DCIM/Trips"
    }
}
