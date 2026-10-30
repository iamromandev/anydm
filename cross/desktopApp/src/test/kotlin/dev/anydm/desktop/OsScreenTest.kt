package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import dev.anydm.engine.os.HotkeyStatus
import dev.anydm.engine.store.RuntimeSettings
import dev.anydm.engine.store.Settings
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
internal class OsScreenTest : PanelScreenTest(Section.SYSTEM) {
    @Test
    fun `it shows the saved switches`() {
        source.runtime = RuntimeSettings(notifications = false)
        source.osSettings.value = source.osSettings.value.copy(clipboardWatch = true)
        show()
        rule.onNodeWithTag("os-notifications").assertIsOff()
        rule.onNodeWithTag("os-clipboard").assertIsOn()
        rule.onNodeWithTag("os-hotkey").assertIsOff()
    }

    @Test
    fun `the window choices show and change the preferences`() {
        prefs.closeToTray = true
        prefs.startHidden = false
        show()
        rule.onNodeWithTag("window-close-to-tray").assertIsOn()
        rule.onNodeWithTag("window-start-hidden").assertIsOff()
        click("window-close-to-tray")
        click("window-start-hidden")
        rule.waitForIdle()
        assertEquals(false, prefs.closeToTray)
        assertEquals(true, prefs.startHidden)
    }

    @Test
    fun `without a tray the window choices are off and say why`() {
        tray = false
        show()
        rule.onNodeWithTag("window-close-to-tray").assertIsNotEnabled()
        rule.onNodeWithTag("window-start-hidden").assertIsNotEnabled()
        tag("window-no-tray").assertTextContains("tray", substring = true)
    }

    @Test
    fun `a switch reaches the engine`() {
        show()
        click("os-notifications")
        click("os-clipboard")
        click("os-hotkey")
        rule.waitForIdle()
        assertEquals(listOf("setNotifications false", "setClipboardWatch true", "setGlobalHotkey true"), source.calls)
        rule.onNodeWithTag("os-hotkey").assertIsOn()
    }

    @Test
    fun `the hotkey says what it needs`() {
        source.osSettings.value = source.osSettings.value.copy(globalHotkey = true)
        source.hotkeyStatus.value = HotkeyStatus.NeedsPermission
        show()
        tag("os-hotkey-help").assertTextContains("Accessibility", substring = true)
        source.hotkeyStatus.value = HotkeyStatus.Active
        rule.waitForIdle()
        tag("os-hotkey-help").assertTextContains("Shift+D", substring = true)
    }

    @Test
    fun `the chrome extension is listed and a firefox one can be added`() {
        show()
        tag("origin-text-${Settings.CHROME_EXTENSION_ORIGIN}").assertTextContains("anydm for Chrome", substring = true)
        type("origin-new", "moz-extension://abc-123")
        click("origin-add")
        rule.waitForIdle()
        assertEquals(setOf(Settings.CHROME_EXTENSION_ORIGIN, "moz-extension://abc-123"), source.runtime.bridgeOrigins)
        tag("origin-row-moz-extension://abc-123").assertExists()
    }

    @Test
    fun `no origins at all is flagged as answering any extension`() {
        source.runtime = RuntimeSettings(bridgeOrigins = emptySet())
        show()
        tag("origin-any-warning").assertTextContains("any extension", substring = true)
    }

    @Test
    fun `a refused origin keeps what was typed`() {
        show()
        type("origin-new", "https://evil.example")
        click("origin-add")
        rule.waitForIdle()
        rule.onNodeWithTag("origin-new").assertTextContains("https://evil.example")
    }

    @Test
    fun `a web address is refused and nothing is sent`() {
        show()
        type("origin-new", "https://evil.example")
        click("origin-add")
        rule.waitForIdle()
        tag("origin-new-error").assertTextContains("chrome-extension://", substring = true)
        assertEquals(emptyList(), source.calls)
    }

    @Test
    fun `an origin is removed, and the last one stays`() {
        source.runtime = RuntimeSettings(bridgeOrigins = setOf(Settings.CHROME_EXTENSION_ORIGIN, "moz-extension://abc"))
        show()
        click("origin-remove-moz-extension://abc")
        rule.waitForIdle()
        assertEquals(setOf(Settings.CHROME_EXTENSION_ORIGIN), source.runtime.bridgeOrigins)
        click("origin-remove-${Settings.CHROME_EXTENSION_ORIGIN}")
        rule.waitForIdle()
        tag("os-message").assertTextContains("at least one", substring = true)
    }

    @Test
    fun `links that do not open in anydm yet can be registered and then removed`() {
        show()
        tag("links-status").assertTextContains("Not registered", substring = true)
        click("links-register")
        rule.waitForIdle()
        tag("links-status").assertTextContains("Registered", substring = true)
        rule.onNodeWithTag("links-register").assertDoesNotExist()
        click("links-remove")
        rule.waitForIdle()
        tag("links-status").assertTextContains("Not registered", substring = true)
    }

    @Test
    fun `a refused registration says why`() {
        links.registerResult = "Access is denied."
        show()
        click("links-register")
        rule.waitForIdle()
        tag("links-message").assertTextContains("Access is denied.", substring = true)
    }

    @Test
    fun `when the installer does it the row only explains`() {
        links.current = LinkHandlerStatus(LinkSupport.BY_INSTALLER, "The installed bundle registers them.")
        show()
        tag("links-status").assertTextContains("installed bundle", substring = true)
        rule.onNodeWithTag("links-register").assertDoesNotExist()
        rule.onNodeWithTag("links-remove").assertDoesNotExist()
    }
}
