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
class Migration27To28Test {
    @Test
    fun migrationBuildsCommittedAlbumSnapshotFromExistingMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-27-28-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE media_items (
                                filePath TEXT NOT NULL PRIMARY KEY,
                                parentPath TEXT NOT NULL,
                                capturedAtMs INTEGER NOT NULL,
                                thumbnailPath TEXT,
                                isTrashed INTEGER NOT NULL
                            )"""
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO media_items VALUES ('/DCIM/a.jpg','/DCIM',10,NULL,0)")
            db.execSQL("INSERT INTO media_items VALUES ('/DCIM/b.jpg','/DCIM',20,'/thumb/b',0)")
            db.execSQL("INSERT INTO media_items VALUES ('/hidden/c.jpg','/hidden',30,NULL,1)")

            AppDatabase.MIGRATION_27_28.migrate(db)

            db.query("SELECT directoryPath, mediaCount, coverFilePath FROM album_snapshot_directories").use {
                assertTrue(it.moveToFirst())
                assertEquals("/DCIM", it.getString(0))
                assertEquals(2, it.getInt(1))
                assertEquals("/DCIM/b.jpg", it.getString(2))
                assertTrue(!it.moveToNext())
            }
            db.query("SELECT activeVersion, updatedAtMs FROM album_snapshot_meta WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getLong(0) > 0L)
                assertEquals(it.getLong(0), it.getLong(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
