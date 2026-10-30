package dev.anydm.desktop

import dev.anydm.engine.store.RuntimeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()

    private fun model() = DiagnosticsModel(source, scope).also { it.load() }

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun `it shows whether debug logging is on`() {
        source.runtime = RuntimeSettings(debugLogging = true)
        assertTrue(model().state.value.debugLogging)
    }

    @Test
    fun `debug logging switches through the engine`() {
        val model = model()
        model.setDebugLogging(true)
        assertEquals(listOf("setDebugLogging true"), source.calls)
        assertTrue(model.state.value.debugLogging)
        model.setDebugLogging(false)
        assertFalse(model.state.value.debugLogging)
    }

    @Test
    fun `a bundle is written where the user chose and the screen says where`() {
        val model = model()
        model.export("/home/ann/anydm-diagnostics.zip")
        assertEquals(listOf("/home/ann/anydm-diagnostics.zip"), source.bundles)
        assertEquals("Saved the diagnostics bundle to /home/ann/anydm-diagnostics.zip.", model.state.value.message)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `a bundle that cannot be written says why`() {
        source.diagnosticsError = "disk full"
        val model = model()
        model.export("/x.zip")
        assertEquals("The bundle couldn't be written: disk full", model.state.value.message)
    }

    @Test
    fun `a stopped engine is reported`() {
        val model = model()
        source.refuseAll = true
        model.setDebugLogging(true)
        assertEquals("The download engine has stopped, so nothing was changed.", model.state.value.message)
        assertFalse(model.state.value.debugLogging)
    }
}
