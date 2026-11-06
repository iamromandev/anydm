package dev.anydm.desktop

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class UpdateIndicatorModelTest {
    private val v = Version.parse("1.2.0")!!
    private val page = "https://github.com/iamromandev/anydm/releases/tag/v1.2.0"

    @Test
    fun `idle with automatic updates on is an up arrow without text`() {
        val button = UpdateIndicatorModel.button(UpdateState.Idle, autoUpdate = true)
        assertEquals(UpdateIcon.UP, button.icon)
        assertNull(button.text)
        assertEquals("anydm updates automatically", button.label)
    }

    @Test
    fun `idle with automatic updates off says so`() {
        assertEquals("Automatic updates are off", UpdateIndicatorModel.button(UpdateState.Idle, autoUpdate = false).label)
    }

    @Test
    fun `checking shows a spinner`() {
        val button = UpdateIndicatorModel.button(UpdateState.Checking, autoUpdate = true)
        assertEquals(UpdateIcon.SPINNER, button.icon)
        assertNull(button.text)
        assertEquals("Checking for updates…", button.label)
    }

    @Test
    fun `available reads Update`() {
        val button = UpdateIndicatorModel.button(UpdateState.Available(v, page), autoUpdate = false)
        assertEquals("Update", button.text)
        assertEquals("anydm 1.2.0 is available", button.label)
    }

    @Test
    fun `downloading shows the percent`() {
        val button = UpdateIndicatorModel.button(UpdateState.Downloading(v, 42), autoUpdate = true)
        assertEquals("42%", button.text)
        assertEquals("Downloading anydm 1.2.0… 42%", button.label)
    }

    @Test
    fun `ready reads Restart to update`() {
        val button = UpdateIndicatorModel.button(UpdateState.Ready(v, Path.of("x.dmg")), autoUpdate = true)
        assertEquals("Restart to update", button.text)
        assertEquals("anydm 1.2.0 is ready — restart to update", button.label)
    }

    @Test
    fun `error shows an alert with the version that failed`() {
        val button = UpdateIndicatorModel.button(UpdateState.Error(v, "bad", page), autoUpdate = true)
        assertEquals(UpdateIcon.ALERT, button.icon)
        assertNull(button.text)
        assertEquals("Couldn't install anydm 1.2.0", button.label)
    }

    @Test
    fun `error without a version names none`() {
        assertEquals("Couldn't install anydm", UpdateIndicatorModel.button(UpdateState.Error(null, "bad", null), true).label)
    }

    private fun items(state: UpdateState) = UpdateIndicatorModel.items(state).map { Triple(it.action, it.text, it.enabled) }

    @Test
    fun `idle offers only the check`() {
        assertEquals(listOf(Triple(UpdateAction.CHECK, "Check for Updates…", true)), items(UpdateState.Idle))
    }

    @Test
    fun `checking disables the check`() {
        assertEquals(listOf(Triple(UpdateAction.CHECK, "Check for Updates…", false)), items(UpdateState.Checking))
    }

    @Test
    fun `available offers the download first`() {
        assertEquals(
            listOf(
                Triple(UpdateAction.DOWNLOAD, "Download anydm 1.2.0", true),
                Triple(UpdateAction.CHECK, "Check for Updates…", true),
            ),
            items(UpdateState.Available(v, page)),
        )
    }

    @Test
    fun `downloading shows a disabled line and disables the check`() {
        assertEquals(
            listOf(
                Triple(UpdateAction.DOWNLOAD, "Downloading 1.2.0…", false),
                Triple(UpdateAction.CHECK, "Check for Updates…", false),
            ),
            items(UpdateState.Downloading(v, 10)),
        )
    }

    @Test
    fun `ready offers the restart`() {
        assertEquals(
            listOf(
                Triple(UpdateAction.RESTART, "Restart to Update to 1.2.0", true),
                Triple(UpdateAction.CHECK, "Check for Updates…", true),
            ),
            items(UpdateState.Ready(v, Path.of("x.dmg"))),
        )
    }

    @Test
    fun `error offers the website`() {
        assertEquals(
            listOf(
                Triple(UpdateAction.WEBSITE, "Get anydm 1.2.0 from the website…", true),
                Triple(UpdateAction.CHECK, "Check for Updates…", true),
            ),
            items(UpdateState.Error(v, "bad", page)),
        )
    }

    @Test
    fun `error without a page offers no website`() {
        assertEquals(listOf(UpdateAction.CHECK), UpdateIndicatorModel.items(UpdateState.Error(v, "bad", null)).map { it.action })
    }

    @Test
    fun `tags follow the action`() {
        assertEquals("update-menu-restart", UpdateAction.RESTART.tag)
        assertEquals("update-menu-download", UpdateAction.DOWNLOAD.tag)
        assertEquals("update-menu-website", UpdateAction.WEBSITE.tag)
        assertEquals("update-menu-check", UpdateAction.CHECK.tag)
    }
}
