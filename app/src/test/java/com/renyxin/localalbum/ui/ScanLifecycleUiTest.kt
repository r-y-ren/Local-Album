package com.renyxin.localalbum.ui

import com.renyxin.localalbum.data.db.entity.CoreScanState
import com.renyxin.localalbum.data.db.entity.EnhancementState
import com.renyxin.localalbum.data.db.entity.IndexAvailability
import com.renyxin.localalbum.data.repo.ScanState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLifecycleUiTest {
    @Test
    fun `core completion and running enhancement stay visibly distinct`() {
        assertEquals(
            "核心扫描完成",
            coreScanStatusText(IndexAvailability.PUBLISHED, CoreScanState.COMPLETED),
        )
        assertEquals(
            "后台增强进行中",
            enhancementStatusText(EnhancementState.RUNNING),
        )
    }

    @Test
    fun `durable publishing state keeps scan controls disabled after ui recreation`() {
        assertTrue(isCoreScanActive(ScanState.Done, CoreScanState.PUBLISHING))
        assertFalse(isCoreScanActive(ScanState.Done, CoreScanState.COMPLETED))
    }

    @Test
    fun `partial index is explicit before core completion`() {
        assertEquals(
            "索引已部分可用，正在应用变更",
            coreScanStatusText(IndexAvailability.PARTIAL, CoreScanState.APPLYING_CHANGES),
        )
    }

    @Test
    fun `paused enhancement is not described as completed`() {
        assertEquals("后台增强已暂停", enhancementStatusText(EnhancementState.PAUSED))
    }

    @Test
    fun `core preemption is visibly distinct from a user pause`() {
        assertEquals(
            "核心扫描优先，后台增强稍后恢复",
            enhancementStatusText(EnhancementState.PREEMPTED),
        )
    }
}
