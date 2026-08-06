package com.renyxin.localalbum.core.index

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScanRootPolicyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `normalize collapses duplicate canonical roots`() {
        val root = tempFolder.newFolder("root")
        val aliases = listOf(
            root.absolutePath,
            File(root, ".").absolutePath,
            root.canonicalPath,
        )

        assertEquals(listOf(root.canonicalPath), ScanRootPolicy.normalize(aliases))
    }

    @Test
    fun `directory key is stable for equivalent paths`() {
        val root = tempFolder.newFolder("stable")

        assertEquals(
            ScanRootPolicy.directoryKey(root),
            ScanRootPolicy.directoryKey(File(root, ".")),
        )
    }

    @Test
    fun `ordinary directories are not symbolic links`() {
        assertFalse(ScanRootPolicy.isSymbolicLink(tempFolder.newFolder("ordinary")))
    }
}
