package com.renyxin.localalbum.ui.components

import com.renyxin.localalbum.data.repo.AlbumSyncState
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumSyncStatusBannerTest {
    @Test
    fun `cached failure makes stale data explicit`() {
        assertEquals(
            "更新失败，当前显示上次结果",
            albumSyncStatusText(AlbumSyncState.Failed("io", hasCachedAlbums = true)),
        )
    }

    @Test
    fun `updating shows determinate progress when available`() {
        assertEquals(
            "正在更新相册 · 30/100",
            albumSyncStatusText(
                AlbumSyncState.Updating(
                    hasCachedAlbums = true,
                    processed = 30,
                    total = 100,
                )
            ),
        )
    }

    @Test
    fun `checking and success have concise copy`() {
        assertEquals(
            "正在检查新照片…",
            albumSyncStatusText(AlbumSyncState.Checking(hasCachedAlbums = true)),
        )
        assertEquals(
            "相册已更新",
            albumSyncStatusText(AlbumSyncState.UpToDate(updatedAtMs = 1L)),
        )
    }

    @Test
    fun `paused scan says cached snapshot remains visible`() {
        assertEquals(
            "更新已暂停，当前显示上次结果",
            albumSyncStatusText(AlbumSyncState.Paused(hasCachedAlbums = true)),
        )
    }
}
