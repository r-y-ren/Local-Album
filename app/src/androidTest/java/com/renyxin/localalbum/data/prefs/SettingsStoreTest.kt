package com.renyxin.localalbum.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
}
