package com.renyxin.localalbum.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupContractTest {
    @Test
    fun `v3 manifest contract includes important user state`() {
        val tables = BackupContract.tables.map { it.table }.toSet()
        assertTrue("media_items" in tables)
        assertTrue("faces" in tables)
        assertTrue("media_embeddings" in tables)
        assertTrue("feature_store" in tables)
        assertTrue("plugin_manifest" in tables)
        assertTrue("face_cluster_meta" in tables)
        assertTrue("face_cluster_prototypes" in tables)
        assertTrue("semantic_index_meta" in tables)
        assertTrue("deletion_tombstones" in tables)
        assertEquals(3, BackupContract.VERSION)
    }

    @Test
    fun `regenerable and transient tables are explicitly excluded`() {
        val included = BackupContract.tables.map { it.table }.toSet()
        listOf("thumbnail_cache_entries", "thumbnail_tasks", "analysis_tasks", "scan_runs",
            "scan_staging", "media_change_events", "media_store_references",
            "semantic_maintenance_runs", "duplicate_groups", "duplicate_members")
            .forEach { table ->
                assertFalse(table in included)
                assertTrue(table in BackupContract.excludedTables)
            }
    }

    @Test
    fun `privacy error never renders raw path or json`() {
        val path = "/storage/emulated/0/Private/secret.jpg"
        val error = BackupError("media.ndjson", 7, pathHash = BackupError.pathHash(path), type = BackupErrorType.FIELD_TYPE, messageCode = "bad_field")
        val message = error.friendlyMessage()
        assertFalse(message.contains(path))
        assertFalse(message.contains("secret.jpg"))
        assertTrue(message.contains("media.ndjson"))
        assertTrue(message.contains("第 7 行"))
    }
}
