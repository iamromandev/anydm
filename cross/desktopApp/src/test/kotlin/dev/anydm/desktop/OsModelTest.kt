package dev.anydm.desktop

import dev.anydm.engine.os.HotkeyStatus
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.RuntimeSettings
import dev.anydm.engine.store.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val chrome = Settings.CHROME_EXTENSION_ORIGIN

    private fun model() = OsModel(source, scope).also { it.load() }

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun `it shows what the engine has`() {
        source.runtime = RuntimeSettings(notifications = false)
        source.osSettings.value = source.osSettings.value.copy(clipboardWatch = true)
        val state = model().state.value
        assertTrue(state.loaded)
        assertFalse(state.notifications)
        assertTrue(state.clipboardWatch)
        assertFalse(state.globalHotkey)
        assertEquals(listOf(chrome), state.origins)
    }

    @Test
    fun `each toggle goes to the engine at once`() {
        val model = model()
        model.setNotifications(false)
        model.setClipboardWatch(true)
        model.setGlobalHotkey(true)
        assertEquals(listOf("setNotifications false", "setClipboardWatch true", "setGlobalHotkey true"), source.calls)
        val state = model.state.value
        assertFalse(state.notifications)
        assertTrue(state.clipboardWatch)
        assertTrue(state.globalHotkey)
    }

    @Test
    fun `the hotkey status follows the service`() {
        val model = model()
        source.hotkeyStatus.value = HotkeyStatus.NeedsPermission
        assertEquals(HotkeyStatus.NeedsPermission, model.state.value.hotkeyStatus)
        source.hotkeyStatus.value = HotkeyStatus.Active
        assertEquals(HotkeyStatus.Active, model.state.value.hotkeyStatus)
    }

    @Test
    fun `each status has words that say what to do`() {
        assertTrue(hotkeyHelp(HotkeyStatus.NeedsPermission, on = true).contains("Accessibility"))
        assertTrue(hotkeyHelp(HotkeyStatus.Unsupported("Wayland"), on = true).contains("Wayland"))
        assertTrue(hotkeyHelp(HotkeyStatus.Failed("no keyboard"), on = true).contains("no keyboard"))
        assertTrue(hotkeyHelp(HotkeyStatus.Active, on = true).contains("Shift+D"))
        assertTrue(hotkeyHelp(HotkeyStatus.Off, on = true).contains("starts"))
        assertTrue(hotkeyHelp(HotkeyStatus.Off, on = false).contains("Shift+D"))
    }

    @Test
    fun `an origin is added when it looks like an extension`() {
        val model = model()
        model.addOrigin(" moz-extension://abc-123 ")
        assertEquals(setOf(chrome, "moz-extension://abc-123"), source.runtime.bridgeOrigins)
        assertEquals(listOf(chrome, "moz-extension://abc-123").sorted(), model.state.value.origins)
        assertNull(model.state.value.originError)
    }

    @Test
    fun `an origin that is a web page or a duplicate is refused`() {
        val model = model()
        model.addOrigin("https://evil.example")
        assertTrue(
            model.state.value.originError
                .orEmpty()
                .contains("chrome-extension://"),
        )
        model.addOrigin(chrome)
        assertEquals("That origin is already allowed.", model.state.value.originError)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `an origin can be removed but not the last one`() {
        source.runtime = RuntimeSettings(bridgeOrigins = setOf(chrome, "moz-extension://abc"))
        val model = model()
        model.removeOrigin("moz-extension://abc")
        assertEquals(setOf(chrome), source.runtime.bridgeOrigins)
        model.removeOrigin(chrome)
        assertEquals(setOf(chrome), source.runtime.bridgeOrigins)
        assertTrue(
            model.state.value.message
                .orEmpty()
                .contains("at least one"),
        )
    }

    @Test
    fun `quick changes to the origins all land`() {
        source.runtime = RuntimeSettings(bridgeOrigins = setOf(chrome, "moz-extension://a", "moz-extension://b"))
        val model = model()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        source.gate = gate
        model.removeOrigin("moz-extension://a")
        model.removeOrigin("moz-extension://b")
        gate.complete(Unit)
        assertEquals(setOf(chrome), source.runtime.bridgeOrigins)
        assertEquals(listOf(chrome), model.state.value.origins)
    }

    @Test
    fun `a refused origin tells the panel to keep the text`() {
        val model = model()
        assertFalse(model.addOrigin("https://evil.example"))
        assertTrue(model.addOrigin("moz-extension://ok"))
    }

    @Test
    fun `the default origin comes back`() {
        source.runtime = RuntimeSettings(bridgeOrigins = setOf("moz-extension://abc"))
        val model = model()
        model.resetOrigins()
        assertEquals(setOf(chrome), source.runtime.bridgeOrigins)
        assertEquals(listOf(chrome), model.state.value.origins)
    }

    @Test
    fun `a refused change leaves the old value and says why`() {
        source.results["setBridgeOrigins ${listOf(chrome, "moz-extension://abc").sorted().joinToString(",")}"] = CommandResult.Invalid("no")
        val model = model()
        model.addOrigin("moz-extension://abc")
        assertEquals(listOf(chrome), model.state.value.origins)
        assertEquals("no", model.state.value.message)
    }

    @Test
    fun `a stopped engine is reported`() {
        val model = model()
        source.refuseAll = true
        model.setNotifications(false)
        assertEquals("The download engine has stopped, so nothing was changed.", model.state.value.message)
        assertTrue(model.state.value.notifications)
    }
}
