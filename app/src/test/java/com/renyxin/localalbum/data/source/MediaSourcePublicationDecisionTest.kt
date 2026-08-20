package com.renyxin.localalbum.data.source

import com.renyxin.localalbum.core.index.HybridIndexer
import com.renyxin.localalbum.core.model.MediaType
import com.renyxin.localalbum.core.thumbnail.HeadFingerprint
import com.renyxin.localalbum.core.thumbnail.SourceVersionCodec
import com.renyxin.localalbum.core.thumbnail.ThumbnailIdentity
import com.renyxin.localalbum.core.thumbnail.ThumbnailSpec
import com.renyxin.localalbum.data.db.dao.MediaDao
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class MediaSourcePublicationDecisionTest {
    private val source = MediaSource()
    private val path = "/media/source.jpg"
    private val fingerprint = "abc123"
    private val identity = ThumbnailIdentity(
        canonicalPath = path,
        mediaType = MediaType.IMAGE.name,
        sourceVersion = SourceVersionCodec.encode(10L,100L,fingerprint),
        sizeClass = ThumbnailSpec.SIZE_GRID,
        formatVersion = ThumbnailIdentity.CURRENT_FORMAT_VERSION,
    )

    @Test
    fun `stable fd and current path permit publication`() {
        val stable = signature()
        assertTrue(source.sourceSnapshotsAllowPublication(stable,stable,stable,identity))
    }

    @Test
    fun `path replacement after open is rejected even when logical identity cycles back`() {
        val openedV1 = signature(fileKey="1:10")
        val pathV1Rebound = signature(fileKey="1:30")
        assertFalse(source.sourceSnapshotsAllowPublication(openedV1,openedV1,pathV1Rebound,identity))
    }

    @Test
    fun `decode time mutation and type change are rejected`() {
        val before = signature()
        assertFalse(source.sourceSnapshotsAllowPublication(
            before,signature(fingerprint="changed"),before,identity,
        ))
        assertFalse(source.sourceSnapshotsAllowPublication(
            before,before,signature(mediaType=MediaType.VIDEO.name),identity,
        ))
    }

    @Test
    fun `missing file key fallback requires all observable facts`() {
        val before = signature(fileKey=null)
        assertTrue(source.sourceSnapshotsAllowPublication(before,before,before,identity))
        assertFalse(source.sourceSnapshotsAllowPublication(
            before,before,signature(fileKey=null,modifiedAtMs=11L),identity,
        ))
        assertFalse(source.sourceSnapshotsAllowPublication(
            before,before,signature(fileKey=null,fileSize=101L),identity,
        ))
    }

    @Test
    fun `scanner fingerprint identity matches physical validation for files larger than head window`() {
        val file = File.createTempFile("thumbnail-fingerprint", ".jpg")
        try {
            val head = ByteArray(HeadFingerprint.WINDOW_BYTES) { (it % 251).toByte() }
            file.writeBytes(head + ByteArray(HeadFingerprint.WINDOW_BYTES * 2) { 0x5a })
            val indexer = HybridIndexer(
                context=mock(android.content.Context::class.java),
                profileId="test",
                mediaDao=mock(MediaDao::class.java),
            )
            val scanFingerprint = requireNotNull(indexer.computeFingerprintHead(file.absolutePath))
            val physicalFingerprint = requireNotNull(source.fingerprintHead(file))
            val sourceVersion = SourceVersionCodec.encode(
                file.lastModified(),file.length(),scanFingerprint,
            )

            assertEquals(scanFingerprint,physicalFingerprint)
            assertTrue(SourceVersionCodec.matchesPhysical(
                sourceVersion,file.lastModified(),file.length(),physicalFingerprint,
            ))

            file.writeBytes(head + ByteArray(HeadFingerprint.WINDOW_BYTES * 2) { 0x33 })
            assertEquals(scanFingerprint,source.fingerprintHead(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `random access fingerprint starts at head and restores caller position`() {
        val file = File.createTempFile("thumbnail-fingerprint-fd", ".jpg")
        try {
            file.writeBytes(ByteArray(HeadFingerprint.WINDOW_BYTES * 2) { (it % 251).toByte() })
            val expected = requireNotNull(source.fingerprintHead(file))

            RandomAccessFile(file,"r").use { randomAccess ->
                val callerPosition = 317L
                randomAccess.seek(callerPosition)

                assertEquals(expected,source.fingerprintHead(randomAccess))
                assertEquals(callerPosition,randomAccess.filePointer)
            }
        } finally {
            file.delete()
        }
    }

    private fun signature(
        mediaType: String = MediaType.IMAGE.name,
        modifiedAtMs: Long = 10L,
        fileSize: Long = 100L,
        fileKey: String? = "1:10",
        fingerprint: String = this.fingerprint,
    ) = MediaSource.PhysicalSourceSignature(
        canonicalPath=path,
        mediaType=mediaType,
        modifiedAtMs=modifiedAtMs,
        fileSize=fileSize,
        fileKey=fileKey,
        fingerprintHead=fingerprint,
    )
}
