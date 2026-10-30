package dev.anydm.desktop

import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyCommandResult
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ProxyType
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.ProxyRemoval
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

class ProxyModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()

    private fun model() = ProxyModel(source, scope).also { it.load() }

    private val office = ProxyEntry(ProxyId(7), "Office", ProxyType.SOCKS5, "proxy.example", 1080, "ann", listOf("*.local"))

    @AfterTest
    fun close() = scope.cancel()

    private fun ProxyModel.fill(
        name: String = "Home",
        host: String = "proxy.example",
        port: String = "8080",
        change: (ProxyForm) -> ProxyForm = { it },
    ) {
        add()
        edit { change(it.copy(name = name, host = host, port = port)) }
    }

    @Test
    fun `it lists the saved proxies and the global choice`() {
        source.proxyList = listOf(office)
        source.proxyChosen = ProxyChoice.Named(office.id)
        val state = model().state.value
        assertTrue(state.loaded)
        assertEquals(listOf(office), state.proxies)
        assertEquals(ProxyChoice.Named(office.id), state.choice)
        assertNull(state.form)
    }

    @Test
    fun `a new proxy needs a name, a host and a port`() {
        val model = model()
        model.add()
        val errors = model.state.value.errors
        assertTrue(ProxyField.NAME in errors)
        assertTrue(ProxyField.HOST in errors)
        assertFalse(model.state.value.canSave)
        model.edit { it.copy(name = "Home", host = "h.example", port = "99999") }
        assertEquals(setOf(ProxyField.PORT), model.state.value.errors.keys)
        model.edit { it.copy(port = "8080") }
        assertTrue(model.state.value.canSave)
    }

    @Test
    fun `a name with a control character is refused on the form`() {
        val model = model()
        model.fill(name = "Ho\tme")
        assertTrue(ProxyField.NAME in model.state.value.errors)
        assertFalse(model.state.value.canSave)
    }

    @Test
    fun `the form never prints its password`() {
        assertFalse(ProxyForm(password = "hunter2").toString().contains("hunter2"))
    }

    @Test
    fun `a host is a bare host name`() {
        val model = model()
        model.fill(host = "http://h.example/")
        assertTrue(ProxyField.HOST in model.state.value.errors)
        model.edit { it.copy(host = "user@h.example") }
        assertTrue(ProxyField.HOST in model.state.value.errors)
        model.edit { it.copy(host = "h.example") }
        assertFalse(ProxyField.HOST in model.state.value.errors)
    }

    @Test
    fun `a password needs a username`() {
        val model = model()
        model.fill { it.copy(password = "secret") }
        assertTrue(ProxyField.USERNAME in model.state.value.errors)
        model.edit { it.copy(username = "ann") }
        assertTrue(
            model.state.value.errors
                .isEmpty(),
        )
    }

    @Test
    fun `a bypass pattern that is not one is refused`() {
        val model = model()
        model.fill { it.copy(bypass = "*.local, 10.0.0.0/99") }
        assertTrue(ProxyField.BYPASS in model.state.value.errors)
        model.edit { it.copy(bypass = "*.local, 10.0.0.0/8\n<local>") }
        assertTrue(
            model.state.value.errors
                .isEmpty(),
        )
    }

    @Test
    fun `saving a new proxy sends it with its password and lists it`() {
        val model = model()
        model.fill("Home", "h.example", "3128") {
            it.copy(type = ProxyType.HTTP, username = "ann", password = "secret", bypass = "*.local\n10.0.0.0/8")
        }

        model.save()

        val saved = source.proxyList.single()
        assertEquals("Home", saved.name)
        assertEquals(ProxyType.HTTP, saved.type)
        assertEquals(3128, saved.port)
        assertEquals(listOf("*.local", "10.0.0.0/8"), saved.bypass)
        assertEquals("secret", source.passwords[saved.id])
        assertEquals(listOf(saved), model.state.value.proxies)
        assertNull(model.state.value.form)
        assertEquals("Saved.", model.state.value.message)
    }

    @Test
    fun `an edit keeps the saved password unless one is typed`() {
        source.proxyList = listOf(office)
        source.passwords[office.id] = "old"
        val model = model()

        model.edit(office)
        model.edit { it.copy(host = "new.example") }
        model.save()
        assertEquals("new.example", source.proxyList.single().host)
        assertEquals("old", source.passwords[office.id])

        model.edit(source.proxyList.single())
        model.edit { it.copy(password = "newer") }
        model.save()
        assertEquals("newer", source.passwords[office.id])
    }

    @Test
    fun `a saved password can be removed`() {
        source.proxyList = listOf(office)
        source.passwords[office.id] = "old"
        val model = model()
        model.edit(office)
        model.edit { it.copy(clearPassword = true) }
        model.save()
        assertFalse(office.id in source.passwords)
    }

    @Test
    fun `a taken name stays on the form`() {
        source.proxyList = listOf(office)
        val model = model()
        model.fill("Office")
        model.save()
        assertEquals("Another proxy has this name.", model.state.value.errors[ProxyField.NAME])
        assertTrue(model.state.value.form != null)
    }

    @Test
    fun `a keychain that cannot be reached says so and keeps the form`() {
        source.proxyResult = ProxyCommandResult.KeychainFailed("locked")
        val model = model()
        model.fill { it.copy(username = "ann", password = "secret") }
        model.save()
        assertTrue(
            model.state.value.errors[ProxyField.PASSWORD]
                .orEmpty()
                .contains("locked"),
        )
        assertTrue(model.state.value.form != null)
    }

    @Test
    fun `cancelling drops the draft`() {
        val model = model()
        model.fill()
        model.cancel()
        assertNull(model.state.value.form)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `removing asks first and then removes`() {
        source.proxyList = listOf(office)
        val model = model()
        model.askRemove(office)
        assertEquals(office, model.state.value.removing)
        assertTrue(source.calls.isEmpty())

        model.confirmRemove()

        assertTrue(source.proxyList.isEmpty())
        assertNull(model.state.value.removing)
        assertTrue(
            model.state.value.proxies
                .isEmpty(),
        )
    }

    @Test
    fun `a proxy in use is kept and the screen says what uses it`() {
        source.proxyList = listOf(office)
        source.proxyUse = ProxyRemoval.InUse(downloads = 2, profiles = 1, global = true)
        val model = model()
        model.askRemove(office)
        model.confirmRemove()
        val message =
            model.state.value.message
                .orEmpty()
        assertTrue("2 downloads" in message, message)
        assertTrue("1 profile" in message, message)
        assertTrue("the global setting" in message, message)
        assertEquals(listOf(office), model.state.value.proxies)
    }

    @Test
    fun `the global choice goes to the engine and sticks`() {
        source.proxyList = listOf(office)
        val model = model()
        model.choose(ProxyChoice.Direct)
        assertEquals(ProxyChoice.Direct, source.proxyChosen)
        assertEquals(ProxyChoice.Direct, model.state.value.choice)
        model.choose(ProxyChoice.Named(office.id))
        assertEquals(ProxyChoice.Named(office.id), model.state.value.choice)
    }

    @Test
    fun `a refused choice leaves the old one and says why`() {
        source.results["setProxyChoice ${ProxyChoice.Direct}"] = CommandResult.Invalid("no")
        val model = model()
        model.choose(ProxyChoice.Direct)
        assertEquals(ProxyChoice.System, model.state.value.choice)
        assertEquals("no", model.state.value.message)
    }

    @Test
    fun `an https proxy and a torrent warning explain the limits`() {
        val model = model()
        model.fill { it.copy(type = ProxyType.HTTPS) }
        assertTrue(
            model.state.value.form!!
                .type.note
                .contains("FTP"),
        )
        model.edit { it.copy(type = ProxyType.SOCKS5) }
        assertTrue(
            model.state.value.form!!
                .type.note
                .contains("torrents"),
        )
    }

    @Test
    fun `a stopped engine is reported`() {
        val model = model()
        source.refuseAll = true
        model.choose(ProxyChoice.Direct)
        assertEquals("The download engine has stopped, so nothing was changed.", model.state.value.message)
    }
}
