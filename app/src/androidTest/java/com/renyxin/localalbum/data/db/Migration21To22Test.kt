package com.renyxin.localalbum.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration21To22Test {
    @Test
    fun migration创建维护任务与generation结果表() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-21-22-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        try {
            val db = helper.writableDatabase
            AppDatabase.MIGRATION_21_22.migrate(db)
            val expected = setOf(
                "maintenance_runs",
                "duplicate_hash_staging",
                "duplicate_groups",
                "duplicate_members",
            )
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('maintenance_runs','duplicate_hash_staging','duplicate_groups','duplicate_members')",
            ).use { cursor ->
                val actual = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertEquals(expected, actual)
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}
