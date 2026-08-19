package com.renyxin.localalbum.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.renyxin.localalbum.data.repo.SettingsRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private lateinit var preferencesFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SettingsStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        preferencesFile = File(
            context.filesDir,
            "settings-store-${System.nanoTime()}.preferences_pb",
        )
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { preferencesFile },
        )
        store = SettingsStore(dataStore)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        preferencesFile.delete()
    }

    @Test
    fun analysisProgressUiIsShownByDefault() = runBlocking {
        assertTrue(store.showAnalysisProgressUi.first())
    }

    @Test
    fun analysisProgressUiPreferenceIsPersisted() = runBlocking {
        store.setShowAnalysisProgressUi(false)

        assertFalse(store.showAnalysisProgressUi.first())

        store.setShowAnalysisProgressUi(true)

        assertTrue(store.showAnalysisProgressUi.first())
    }

    @Test
    fun scanScopeChangesEmitOneDurableAdvisoryPerRealChange() = runBlocking {
        val reasons = mutableListOf<String>()
        val repository = SettingsRepository(store) { reason -> reasons += reason }

        repository.addScanRoot("/storage/emulated/0/Pictures/")
        repository.addScanRoot("/storage/emulated/0/Pictures")
        repository.addIgnoreDir("Screenshots")
        repository.addIgnoreDir(" Screenshots ")
        repository.setShowNomediaDirectories(false)
        repository.setShowNomediaDirectories(true)
        repository.setShowNomediaDirectories(true)
        repository.removeIgnoreDir("Screenshots")
        repository.removeIgnoreDir("Screenshots")
        repository.removeScanRoot("/storage/emulated/0/Pictures/")
        repository.removeScanRoot("/storage/emulated/0/Pictures")

        assertEquals(
            listOf(
                "scan_root_added",
                "ignore_rule_added",
                "nomedia_policy_changed",
                "ignore_rule_removed",
                "scan_root_removed",
            ),
            reasons,
        )
        assertTrue(store.scanRoots.first().isEmpty())
        assertTrue(store.ignoreDirNames.first().isEmpty())
        assertTrue(store.showNomediaDirectories.first())
        assertEquals(null, store.pendingScanScopeChangeReason.first())
    }

    @Test
    fun interruptedScopeChangeIsReplayedBeforeItsMarkerIsAcknowledged() = runBlocking {
        val failedRepository = SettingsRepository(store) {
            throw IllegalStateException("simulated Room failure")
        }

        try {
            failedRepository.addScanRoot("/storage/emulated/0/DCIM")
            throw AssertionError("scope callback failure should propagate")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated Room failure", expected.message)
        }

        assertEquals("scan_root_added", store.pendingScanScopeChangeReason.first())
        val replayed = mutableListOf<String>()
        val recoveredRepository = SettingsRepository(store) { reason -> replayed += reason }

        recoveredRepository.replayPendingScanScopeChange()

        assertEquals(listOf("scan_root_added"), replayed)
        assertEquals(null, store.pendingScanScopeChangeReason.first())
    }

    @Test
    fun stableScanReservationDelaysALaterScopeMutation() = runBlocking {
        val committedReason = CompletableDeferred<String>()
        val repository = SettingsRepository(store) { reason -> committedReason.complete(reason) }
        val reservationEntered = CompletableDeferred<Unit>()
        val releaseReservation = CompletableDeferred<Unit>()

        val reservation = async(Dispatchers.Default) {
            repository.withStableScanSettings { settings ->
                reservationEntered.complete(Unit)
                releaseReservation.await()
                settings.scanRoots
            }
        }
        withTimeout(5_000L) { reservationEntered.await() }

        val mutation = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.addScanRoot("/storage/emulated/0/Pictures")
        }

        assertFalse("范围变化不应越过已进入的扫描预留边界", mutation.isCompleted)
        assertFalse("被互斥锁阻塞的范围变化不应提前提交 Room 回调", committedReason.isCompleted)
        releaseReservation.complete(Unit)

        val rootsUsedByReservation = withTimeout(5_000L) { reservation.await() }
        withTimeout(5_000L) { mutation.join() }

        assertTrue("当前预留必须使用变更前的稳定配置", rootsUsedByReservation.isEmpty())
        assertEquals("scan_root_added", committedReason.await())
        assertEquals(listOf("/storage/emulated/0/Pictures"), store.scanRoots.first())
        assertEquals(null, store.pendingScanScopeChangeReason.first())
    }

    @Test
    fun committedScopeMutationPrecedesALaterStableScanReservation() = runBlocking {
        val scopeCommitEntered = CompletableDeferred<Unit>()
        val releaseScopeCommit = CompletableDeferred<Unit>()
        val repository = SettingsRepository(store) {
            scopeCommitEntered.complete(Unit)
            releaseScopeCommit.await()
        }

        val mutation = async(Dispatchers.Default) {
            repository.addScanRoot("/storage/emulated/0/DCIM")
        }
        withTimeout(5_000L) { scopeCommitEntered.await() }

        val reservation = async(start = CoroutineStart.UNDISPATCHED) {
            repository.withStableScanSettings { settings -> settings.scanRoots }
        }

        assertFalse("后发扫描预留必须等待范围变化完成 Room 提交与 DataStore 确认", reservation.isCompleted)
        releaseScopeCommit.complete(Unit)

        withTimeout(5_000L) { mutation.await() }
        val rootsUsedByReservation = withTimeout(5_000L) { reservation.await() }

        assertEquals(listOf("/storage/emulated/0/DCIM"), rootsUsedByReservation)
        assertEquals(null, store.pendingScanScopeChangeReason.first())
    }
}
