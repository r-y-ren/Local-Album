package com.renyxin.localalbum.core.concurrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnhancementResourceGateTest {
    @Test
    fun `core waits for running automatic section and blocks new automatic work`() = runBlocking {
        val automaticEntered = CompletableDeferred<Unit>()
        val releaseAutomatic = CompletableDeferred<Unit>()
        val automatic = async {
            EnhancementResourceGate.tryWithAutomaticEnhancement {
                automaticEntered.complete(Unit)
                releaseAutomatic.await()
                "automatic-done"
            }
        }
        automaticEntered.await()

        val coreEntered = CompletableDeferred<Unit>()
        val core = async {
            EnhancementResourceGate.withCoreScan {
                coreEntered.complete(Unit)
                "core-done"
            }
        }
        while (!EnhancementResourceGate.isCoreRequested) yield()

        assertNull(EnhancementResourceGate.tryWithAutomaticEnhancement { "must-not-run" })
        assertTrue(!coreEntered.isCompleted)
        releaseAutomatic.complete(Unit)

        assertEquals("automatic-done", automatic.await())
        assertEquals("core-done", core.await())
        assertTrue(coreEntered.isCompleted)
    }

    @Test
    fun `interactive thumbnail lane remains available while core owns automatic lane`() = runBlocking {
        val coreEntered = CompletableDeferred<Unit>()
        val releaseCore = CompletableDeferred<Unit>()
        val core = async {
            EnhancementResourceGate.withCoreScan {
                coreEntered.complete(Unit)
                releaseCore.await()
            }
        }
        coreEntered.await()

        assertEquals(
            "visible",
            EnhancementResourceGate.withInteractiveThumbnail { "visible" },
        )
        releaseCore.complete(Unit)
        core.await()
    }

    @Test
    fun `exclusive maintenance drains both lanes and closes new admission`() = runBlocking {
        val automaticEntered = CompletableDeferred<Unit>()
        val releaseAutomatic = CompletableDeferred<Unit>()
        val automatic = async {
            EnhancementResourceGate.tryWithAutomaticEnhancement {
                automaticEntered.complete(Unit)
                releaseAutomatic.await()
                "automatic-done"
            }
        }

        val visibleEntered = CompletableDeferred<Unit>()
        val releaseVisible = CompletableDeferred<Unit>()
        val visible = async {
            EnhancementResourceGate.withInteractiveThumbnail {
                visibleEntered.complete(Unit)
                releaseVisible.await()
                "visible-done"
            }
        }
        automaticEntered.await()
        visibleEntered.await()

        val maintenanceEntered = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val maintenance = async {
            EnhancementResourceGate.withExclusiveMaintenance {
                maintenanceEntered.complete(Unit)
                releaseMaintenance.await()
                "maintenance-done"
            }
        }
        while (!EnhancementResourceGate.isMaintenanceRequested) yield()

        assertNull(EnhancementResourceGate.tryWithAutomaticEnhancement { "must-not-run" })
        assertTrue(!maintenanceEntered.isCompleted)

        val queuedVisibleEntered = CompletableDeferred<Unit>()
        val queuedVisible = async {
            EnhancementResourceGate.withInteractiveThumbnail {
                queuedVisibleEntered.complete(Unit)
                "queued-visible"
            }
        }
        yield()
        assertTrue(!queuedVisibleEntered.isCompleted)

        releaseAutomatic.complete(Unit)
        assertEquals("automatic-done", automatic.await())
        assertTrue(!maintenanceEntered.isCompleted)

        releaseVisible.complete(Unit)
        assertEquals("visible-done", visible.await())
        maintenanceEntered.await()
        assertTrue(!queuedVisibleEntered.isCompleted)

        releaseMaintenance.complete(Unit)
        assertEquals("maintenance-done", maintenance.await())
        assertEquals("queued-visible", queuedVisible.await())
        assertTrue(!EnhancementResourceGate.isMaintenanceRequested)
    }

    @Test
    fun `interactive ai waiter blocks later automatic work while current batch drains`() = runBlocking {
        val automaticEntered = CompletableDeferred<Unit>()
        val releaseAutomatic = CompletableDeferred<Unit>()
        val automatic = async {
            EnhancementResourceGate.tryWithAutomaticEnhancement {
                automaticEntered.complete(Unit)
                releaseAutomatic.await()
                "automatic-done"
            }
        }
        automaticEntered.await()

        val aiEntered = CompletableDeferred<Unit>()
        val ai = async {
            EnhancementResourceGate.withInteractiveAi {
                aiEntered.complete(Unit)
                "ai-done"
            }
        }
        awaitCondition { EnhancementResourceGate.isInteractiveAiRequested }

        assertNull(EnhancementResourceGate.tryWithAutomaticEnhancement { "must-not-run" })
        assertTrue(!aiEntered.isCompleted)

        releaseAutomatic.complete(Unit)
        assertEquals("automatic-done", automatic.await())
        assertEquals("ai-done", ai.await())
        assertTrue(!EnhancementResourceGate.isInteractiveAiRequested)
    }

    @Test
    fun `core request overtakes interactive ai that has not started`() = runBlocking {
        val automaticEntered = CompletableDeferred<Unit>()
        val releaseAutomatic = CompletableDeferred<Unit>()
        val automatic = async {
            EnhancementResourceGate.tryWithAutomaticEnhancement {
                automaticEntered.complete(Unit)
                releaseAutomatic.await()
            }
        }
        automaticEntered.await()

        val aiEntered = CompletableDeferred<Unit>()
        val ai = async {
            EnhancementResourceGate.withInteractiveAi {
                aiEntered.complete(Unit)
                "ai-done"
            }
        }
        awaitCondition { EnhancementResourceGate.isInteractiveAiRequested }

        val coreEntered = CompletableDeferred<Unit>()
        val releaseCore = CompletableDeferred<Unit>()
        val core = async {
            EnhancementResourceGate.withCoreScan {
                coreEntered.complete(Unit)
                releaseCore.await()
                "core-done"
            }
        }
        awaitCondition { EnhancementResourceGate.isCoreRequested }

        releaseAutomatic.complete(Unit)
        automatic.await()
        withTimeout(TEST_TIMEOUT_MS) { coreEntered.await() }
        assertTrue(!aiEntered.isCompleted)

        releaseCore.complete(Unit)
        assertEquals("core-done", core.await())
        assertEquals("ai-done", ai.await())
    }

    @Test
    fun `core waits for an interactive ai section that already started`() = runBlocking {
        val aiEntered = CompletableDeferred<Unit>()
        val releaseAi = CompletableDeferred<Unit>()
        val ai = async {
            EnhancementResourceGate.withInteractiveAi {
                aiEntered.complete(Unit)
                releaseAi.await()
                "ai-done"
            }
        }
        aiEntered.await()

        val coreEntered = CompletableDeferred<Unit>()
        val core = async {
            EnhancementResourceGate.withCoreScan {
                coreEntered.complete(Unit)
                "core-done"
            }
        }
        awaitCondition { EnhancementResourceGate.isCoreRequested }
        assertTrue(!coreEntered.isCompleted)

        releaseAi.complete(Unit)
        assertEquals("ai-done", ai.await())
        assertEquals("core-done", core.await())
    }

    @Test
    fun `exclusive maintenance drains active ai and closes admission for queued ai`() = runBlocking {
        val activeAiEntered = CompletableDeferred<Unit>()
        val releaseActiveAi = CompletableDeferred<Unit>()
        val activeAi = async {
            EnhancementResourceGate.withInteractiveAi {
                activeAiEntered.complete(Unit)
                releaseActiveAi.await()
                "active-ai-done"
            }
        }
        activeAiEntered.await()

        val maintenanceEntered = CompletableDeferred<Unit>()
        val releaseMaintenance = CompletableDeferred<Unit>()
        val maintenance = async {
            EnhancementResourceGate.withExclusiveMaintenance {
                maintenanceEntered.complete(Unit)
                releaseMaintenance.await()
                "maintenance-done"
            }
        }
        awaitCondition { EnhancementResourceGate.isMaintenanceRequested }

        val queuedAiEntered = CompletableDeferred<Unit>()
        val queuedAi = async {
            EnhancementResourceGate.withInteractiveAi {
                queuedAiEntered.complete(Unit)
                "queued-ai-done"
            }
        }
        awaitCondition { EnhancementResourceGate.isInteractiveAiRequested }
        assertTrue(!maintenanceEntered.isCompleted)
        assertTrue(!queuedAiEntered.isCompleted)

        releaseActiveAi.complete(Unit)
        assertEquals("active-ai-done", activeAi.await())
        withTimeout(TEST_TIMEOUT_MS) { maintenanceEntered.await() }
        assertTrue(!queuedAiEntered.isCompleted)

        releaseMaintenance.complete(Unit)
        assertEquals("maintenance-done", maintenance.await())
        assertEquals("queued-ai-done", queuedAi.await())
    }

    @Test
    fun `cancelling queued interactive ai releases waiter state and locks`() = runBlocking {
        val automaticEntered = CompletableDeferred<Unit>()
        val releaseAutomatic = CompletableDeferred<Unit>()
        val automatic = async {
            EnhancementResourceGate.tryWithAutomaticEnhancement {
                automaticEntered.complete(Unit)
                releaseAutomatic.await()
            }
        }
        automaticEntered.await()

        val queuedAi = async {
            EnhancementResourceGate.withInteractiveAi { "must-not-run" }
        }
        awaitCondition { EnhancementResourceGate.isInteractiveAiRequested }
        queuedAi.cancelAndJoin()
        awaitCondition { !EnhancementResourceGate.isInteractiveAiRequested }

        releaseAutomatic.complete(Unit)
        automatic.await()
        assertEquals(
            "automatic-after-cancel",
            EnhancementResourceGate.tryWithAutomaticEnhancement { "automatic-after-cancel" },
        )
    }

    @Test
    fun `exclusive epoch invalidates automatic snapshots for core and maintenance requests`() =
        runBlocking {
            val beforeCore = EnhancementResourceGate.currentExclusiveEpoch
            EnhancementResourceGate.withCoreScan { Unit }
            val afterCore = EnhancementResourceGate.currentExclusiveEpoch
            assertTrue(afterCore > beforeCore)

            EnhancementResourceGate.withExclusiveMaintenance { Unit }
            assertTrue(EnhancementResourceGate.currentExclusiveEpoch > afterCore)
        }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(TEST_TIMEOUT_MS) {
            while (!condition()) yield()
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 5_000L
    }
}
