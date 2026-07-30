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
class Migration25To26Test {
    @Test fun migrationCreatesDeletionTombstonesAndRetryIndex() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-25-26-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(25) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_25_26.migrate(db)
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='deletion_tombstones'").use {
                assertTrue(it.moveToFirst())
            }
            db.query("PRAGMA index_list(deletion_tombstones)").use { cursor ->
                val indexes = buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                assertTrue(indexes.contains("index_deletion_tombstones_state_nextRetryAt_leaseUntil"))
                assertTrue(indexes.contains("index_deletion_tombstones_filePath"))
            }
            db.query("PRAGMA table_info(deletion_tombstones)").use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count++
                assertEquals(17, count)
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
