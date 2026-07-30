package com.renyxin.localalbum.data.db

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.renyxin.localalbum.core.model.DirectoryMediaQuery
import com.renyxin.localalbum.core.model.DirectoryMediaQueryMapper
import com.renyxin.localalbum.core.model.DirectoryMediaSort
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.data.db.entity.MediaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DirectoryMediaPagingTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        database.mediaDao().insertAll(
            listOf(
                media("/album/c.jpg", "c.jpg", MediaType.IMAGE, captured = 30, modified = 10, size = 100),
                media("/album/A.jpg", "A.jpg", MediaType.IMAGE, captured = 10, modified = 30, size = 300),
                media("/album/b.mp4", "b.mp4", MediaType.VIDEO, captured = 20, modified = 20, size = 200),
                media("/other/z.jpg", "z.jpg", MediaType.IMAGE, captured = 99, modified = 99, size = 999),
                media("/album/trashed.jpg", "trashed.jpg", MediaType.IMAGE, captured = 40, modified = 40, size = 400, trashed = true),
            )
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun dateAscendingAndDescendingAreCompleteAndStable() = runBlocking {
        assertEquals(
            listOf("/album/c.jpg", "/album/b.mp4", "/album/A.jpg"),
            load(DirectoryMediaQuery("/album", sort = DirectoryMediaSort.DATE_DESC)),
        )
        assertEquals(
            listOf("/album/A.jpg", "/album/b.mp4", "/album/c.jpg"),
            load(DirectoryMediaQuery("/album", sort = DirectoryMediaSort.DATE_ASC)),
        )
    }

    @Test
    fun nameAndSizeOrderingDoNotDuplicateOrLeakDirectories() = runBlocking {
        assertEquals(
            listOf("/album/A.jpg", "/album/b.mp4", "/album/c.jpg"),
            load(DirectoryMediaQuery("/album", sort = DirectoryMediaSort.NAME_ASC)),
        )
        assertEquals(
            listOf("/album/A.jpg", "/album/b.mp4", "/album/c.jpg"),
            load(DirectoryMediaQuery("/album", sort = DirectoryMediaSort.SIZE_DESC)),
        )
    }

    @Test
    fun imageAndVideoFiltersAreAppliedBeforePaging() = runBlocking {
        assertEquals(
            listOf("/album/c.jpg", "/album/A.jpg"),
            load(DirectoryMediaQuery("/album", MediaType.IMAGE, DirectoryMediaSort.DATE_DESC)),
        )
        assertEquals(
            listOf("/album/b.mp4"),
            load(DirectoryMediaQuery("/album", MediaType.VIDEO, DirectoryMediaSort.NAME_ASC)),
        )
    }

    private suspend fun load(query: DirectoryMediaQuery): List<String> {
        val spec = DirectoryMediaQueryMapper.paging(query)
        val source = database.mediaDao().directoryPagingSource(SimpleSQLiteQuery(spec.sql, spec.args.toTypedArray()))
        val result = source.load(PagingSource.LoadParams.Refresh(key = 0, loadSize = 100, placeholdersEnabled = false))
        return (result as PagingSource.LoadResult.Page).data.map { it.filePath }
    }

    private fun media(
        path: String,
        name: String,
        type: MediaType,
        captured: Long,
        modified: Long,
        size: Long,
        trashed: Boolean = false,
    ) = MediaEntity(
        filePath = path,
        fileName = name,
        mediaType = type,
        capturedAtMs = captured,
        modifiedAtMs = modified,
        parentPath = path.substringBeforeLast('/'),
        fileSize = size,
        isTrashed = trashed,
    )
}
