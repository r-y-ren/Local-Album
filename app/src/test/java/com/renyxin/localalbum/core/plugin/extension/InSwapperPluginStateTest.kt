package com.renyxin.localalbum.core.plugin.extension

import android.content.Context
import com.renyxin.localalbum.core.plugin.PluginContext
import com.renyxin.localalbum.core.plugin.PluginInput
import com.renyxin.localalbum.core.plugin.ProgressReporter
import com.renyxin.localalbum.core.plugin.capability.FaceProvider
import com.renyxin.localalbum.core.plugin.model.ModelManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class InSwapperPluginStateTest {

    @Test
    fun `incompatible provider failure is retryable and does not touch models`() = runBlocking {
        val modelManager = mock(ModelManager::class.java)
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val plugin = InSwapperPlugin(
            modelManager = modelManager,
            faceProviderFactory = { EmbeddingOnlyFaceProvider },
            faceModelIdsFactory = {
                error("Model IDs must not be resolved for an incompatible provider")
            },
        )
        plugin.initialize(
            context,
            PluginContext(
                coroutineScope = this,
                progressReporter = NoOpProgressReporter,
                pluginId = plugin.getId(),
            ),
        )
        clearInvocations(modelManager)

        var failure: OnDemandExecutionException? = null
        try {
            plugin.execute(
                PluginInput.ImageInput(
                    file = File("not-read-for-incompatible-provider.jpg"),
                ),
            )
        } catch (error: OnDemandExecutionException) {
            failure = error
        }

        assertNotNull("Provider incompatibility must be actionable", failure)
        assertEquals(
            OnDemandExecutionFailureKind.PROVIDER_INCOMPATIBLE,
            failure?.kind,
        )
        assertTrue(failure?.message.orEmpty().contains("五点关键点"))
        assertEquals(
            OnDemandRuntimeState.AVAILABLE_NEEDS_LOAD,
            plugin.runtimeState.value,
        )
        verifyNoInteractions(modelManager)
    }

    private object EmbeddingOnlyFaceProvider : FaceProvider {
        override val providerId = "test:embedding-only"
        override val displayName = "Embedding-only provider"
        override val embeddingDim = 512

        override suspend fun detectFaces(file: File): List<FaceProvider.DetectedFace> =
            error("Detection must not run for an incompatible provider")

        override suspend fun release() = Unit
    }

    private object NoOpProgressReporter : ProgressReporter {
        override fun reportProgress(
            currentIndex: Int,
            totalCount: Int,
            message: String?,
        ) = Unit

        override fun reportStatus(status: ProgressReporter.TaskStatus) = Unit
    }
}
