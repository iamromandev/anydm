package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.store.ProxyRemoval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
internal class ProxiesScreenTest : PanelScreenTest(Section.PROXIES) {
    private val office = ProxyEntry(ProxyId(7), "Office", ProxyType.SOCKS5, "proxy.example", 1080, "ann", emptyList())

    @Test
    fun `it shows the saved proxies and the global choice`() {
        source.proxyList = listOf(office)
        source.proxyChosen = ProxyChoice.Named(office.id)
        show()
        tag("proxy-name-7").assertTextContains("Office")
        tag("proxy-detail-7").assertTextContains("proxy.example:1080", substring = true)
        rule.onNodeWithTag("proxy-choice-7").assertIsSelected()
    }

    @Test
    fun `an empty list says so`() {
        show()
        tag("proxy-empty").assertTextContains("No proxies", substring = true)
    }

    @Test
    fun `a proxy is added with its password and the form closes`() {
        show()
        click("proxy-add")
        rule.onNodeWithTag("proxy-save").assertIsNotEnabled()
        type("proxy-name", "Home")
        click("proxy-type-http")
        type("proxy-host", "h.example")
        type("proxy-port", "3128")
        type("proxy-username", "ann")
        type("proxy-password", "secret")
        rule.onNodeWithTag("proxy-save").assertIsEnabled()
        click("proxy-save")
        rule.waitForIdle()

        val saved = source.proxyList.single()
        assertEquals("Home", saved.name)
        assertEquals(3128, saved.port)
        assertEquals("secret", source.passwords[saved.id])
        tag("proxy-message").assertTextContains("Saved.")
        tag("proxy-detail-${saved.id.value}").assertTextContains("h.example:3128", substring = true)
    }

    @Test
    fun `a bad port is flagged and keeps save off`() {
        show()
        click("proxy-add")
        type("proxy-name", "Home")
        type("proxy-host", "h.example")
        type("proxy-port", "70000")
        tag("proxy-port-error").assertTextContains("1 to 65535", substring = true)
        rule.onNodeWithTag("proxy-save").assertIsNotEnabled()
    }

    @Test
    fun `the form explains what each proxy type carries`() {
        show()
        click("proxy-add")
        click("proxy-type-https")
        tag("proxy-type-note").assertTextContains("FTP", substring = true)
        click("proxy-type-socks5")
        tag("proxy-type-note").assertTextContains("torrents", substring = true)
    }

    @Test
    fun `a taken name is flagged on the form`() {
        source.proxyList = listOf(office)
        show()
        click("proxy-add")
        type("proxy-name", "Office")
        type("proxy-host", "h.example")
        type("proxy-port", "80")
        click("proxy-save")
        rule.waitForIdle()
        tag("proxy-name-error").assertTextContains("Another proxy has this name.")
    }

    @Test
    fun `an edit changes the proxy and a typed password replaces the saved one`() {
        source.proxyList = listOf(office)
        source.passwords[office.id] = "old"
        show()
        click("proxy-edit-7")
        type("proxy-host", "new.example")
        type("proxy-password", "newer")
        click("proxy-save")
        rule.waitForIdle()
        assertEquals("new.example", source.proxyList.single().host)
        assertEquals("newer", source.passwords[office.id])
    }

    @Test
    fun `choosing the global proxy reaches the engine`() {
        source.proxyList = listOf(office)
        show()
        click("proxy-choice-direct")
        rule.waitForIdle()
        assertEquals(ProxyChoice.Direct, source.proxyChosen)
        rule.onNodeWithTag("proxy-choice-direct").assertIsSelected()
        click("proxy-choice-7")
        rule.waitForIdle()
        assertEquals(ProxyChoice.Named(office.id), source.proxyChosen)
    }

    @Test
    fun `removing asks first`() {
        source.proxyList = listOf(office)
        show()
        click("proxy-remove-7")
        rule.onNodeWithTag("proxy-remove-dialog").assertExists()
        assertFalse(source.calls.any { it.startsWith("removeProxy") })
        click("proxy-remove-confirm")
        rule.waitForIdle()
        assertEquals(emptyList(), source.proxyList)
        tag("proxy-message").assertTextContains("Removed Office.")
    }

    @Test
    fun `a proxy in use stays and the screen says what uses it`() {
        source.proxyList = listOf(office)
        source.proxyUse = ProxyRemoval.InUse(downloads = 3, profiles = 0, global = false)
        show()
        click("proxy-remove-7")
        click("proxy-remove-confirm")
        rule.waitForIdle()
        tag("proxy-message").assertTextContains("3 downloads", substring = true)
        tag("proxy-row-7").assertExists()
    }

    @Test
    fun `a stopped engine is reported`() {
        show()
        source.refuseAll = true
        click("proxy-choice-direct")
        rule.waitForIdle()
        tag("proxy-message").assertTextContains("engine has stopped", substring = true)
    }
}
