package dev.anydm.desktop

import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class MemoryPrefs(
    override var closeToTray: Boolean = true,
    override var startHidden: Boolean = false,
    override var theme: ThemeChoice = ThemeChoice.SYSTEM,
    override var stripCollapsed: Boolean = false,
) : AppPrefs

class WindowModelTest {
    @Test
    fun `the window starts shown`() {
        assertTrue(WindowModel(MemoryPrefs(), trayAvailable = true).state.value.visible)
    }

    @Test
    fun `closing hides to the tray when that is chosen and there is a tray`() {
        val window = WindowModel(MemoryPrefs(closeToTray = true), trayAvailable = true)
        assertEquals(CloseAction.HIDE, window.close())
        assertFalse(window.state.value.visible)
    }

    @Test
    fun `closing quits without a tray or when close to tray is off`() {
        assertEquals(CloseAction.QUIT, WindowModel(MemoryPrefs(closeToTray = true), trayAvailable = false).close())
        val off = WindowModel(MemoryPrefs(closeToTray = false), trayAvailable = true)
        assertEquals(CloseAction.QUIT, off.close())
        assertTrue(off.state.value.visible)
    }

    @Test
    fun `show, hide and toggle follow each other`() {
        val window = WindowModel(MemoryPrefs(), trayAvailable = true)
        window.hide()
        assertFalse(window.state.value.visible)
        window.toggle()
        assertTrue(window.state.value.visible)
        window.toggle()
        assertFalse(window.state.value.visible)
        window.show()
        assertTrue(window.state.value.visible)
    }

    @Test
    fun `the networks strip being collapsed is kept in the preferences`() {
        val prefs = MemoryPrefs()
        val window = WindowModel(prefs, trayAvailable = true)
        assertFalse(window.state.value.stripCollapsed)
        window.setStripCollapsed(true)
        assertTrue(prefs.stripCollapsed)
        assertTrue(window.state.value.stripCollapsed)
        assertTrue(WindowModel(prefs, trayAvailable = true).state.value.stripCollapsed)
    }

    @Test
    fun `the choices are kept in the preferences`() {
        val prefs = MemoryPrefs()
        val window = WindowModel(prefs, trayAvailable = true)
        window.setCloseToTray(false)
        window.setStartHidden(true)
        assertFalse(prefs.closeToTray)
        assertTrue(prefs.startHidden)
        assertFalse(window.state.value.closeToTray)
        assertTrue(window.state.value.startHidden)
    }

    @Test
    fun `a first launch starts hidden only with a tray, and a link or a plain launch shows it`() {
        val hidden = WindowModel(MemoryPrefs(startHidden = true), trayAvailable = true)
        hidden.startUp(hiddenRequested = false, hasLinks = false)
        assertFalse(hidden.state.value.visible)

        val flag = WindowModel(MemoryPrefs(), trayAvailable = true)
        flag.startUp(hiddenRequested = true, hasLinks = false)
        assertFalse(flag.state.value.visible)

        val withLink = WindowModel(MemoryPrefs(startHidden = true), trayAvailable = true)
        withLink.startUp(hiddenRequested = true, hasLinks = true)
        assertTrue(withLink.state.value.visible)

        val noTray = WindowModel(MemoryPrefs(startHidden = true), trayAvailable = false)
        noTray.startUp(hiddenRequested = true, hasLinks = false)
        assertTrue(noTray.state.value.visible)

        val plain = WindowModel(MemoryPrefs(), trayAvailable = true)
        plain.startUp(hiddenRequested = false, hasLinks = false)
        assertTrue(plain.state.value.visible)
    }

    @Test
    fun `the theme starts from the preference and a choice is kept and emitted`() {
        val prefs = MemoryPrefs(theme = ThemeChoice.DARK)
        val window = WindowModel(prefs, trayAvailable = true)
        assertEquals(ThemeChoice.DARK, window.state.value.theme)
        window.setTheme(ThemeChoice.LIGHT)
        assertEquals(ThemeChoice.LIGHT, window.state.value.theme)
        assertEquals(ThemeChoice.LIGHT, prefs.theme)
    }

    @Test
    fun `the theme round trips through the preferences store`() {
        val node = Preferences.userRoot().node("dev/anydm/desktop-test-${System.nanoTime()}")
        try {
            val prefs = PreferencesAppPrefs(node)
            assertEquals(ThemeChoice.SYSTEM, prefs.theme)
            prefs.theme = ThemeChoice.DARK
            assertEquals(ThemeChoice.DARK, PreferencesAppPrefs(node).theme)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `the collapsed strip round trips through the preferences store`() {
        val node = Preferences.userRoot().node("dev/anydm/desktop-test-${System.nanoTime()}")
        try {
            val prefs = PreferencesAppPrefs(node)
            assertFalse(prefs.stripCollapsed)
            prefs.stripCollapsed = true
            assertTrue(PreferencesAppPrefs(node).stripCollapsed)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun `an unreadable theme falls back to system`() {
        val node = Preferences.userRoot().node("dev/anydm/desktop-test-${System.nanoTime()}")
        try {
            node.put("theme", "PURPLE")
            assertEquals(ThemeChoice.SYSTEM, PreferencesAppPrefs(node).theme)
        } finally {
            node.removeNode()
        }
    }
}
