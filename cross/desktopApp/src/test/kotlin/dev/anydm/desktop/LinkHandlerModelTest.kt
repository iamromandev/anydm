package dev.anydm.desktop

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

internal class FakeLinkHandlers(
    var current: LinkHandlerStatus = LinkHandlerStatus(LinkSupport.NOT_REGISTERED, "Not registered."),
) : LinkHandlers {
    var registerResult: String? = null
    var unregisterResult: String? = null
    var calls = 0
    var registerThrows = false

    override suspend fun status() = current

    override suspend fun register(): String? {
        calls++
        if (registerThrows) error("boom")
        if (registerResult == null) current = LinkHandlerStatus(LinkSupport.REGISTERED, "Registered.")
        return registerResult
    }

    override suspend fun unregister(): String? {
        calls++
        if (unregisterResult == null) current = LinkHandlerStatus(LinkSupport.NOT_REGISTERED, "Not registered.")
        return unregisterResult
    }
}

internal class LinkHandlerModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val handlers = FakeLinkHandlers()

    private fun model() = LinkHandlerModel(handlers, scope, io = Dispatchers.Unconfined).also { it.load() }

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun `it shows what the system says`() {
        val state = model().state.value
        assertTrue(state.loaded)
        assertEquals(LinkSupport.NOT_REGISTERED, state.status.support)
    }

    @Test
    fun `registering reads the status again`() {
        val model = model()
        model.register()
        assertEquals(LinkSupport.REGISTERED, model.state.value.status.support)
        assertNull(model.state.value.message)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `a refused registration keeps the status and says why`() {
        handlers.registerResult = "Access is denied."
        val model = model()
        model.register()
        assertEquals(LinkSupport.NOT_REGISTERED, model.state.value.status.support)
        assertEquals("Access is denied.", model.state.value.message)
    }

    @Test
    fun `removing goes back to not registered`() {
        handlers.current = LinkHandlerStatus(LinkSupport.REGISTERED, "Registered.")
        val model = model()
        model.unregister()
        assertEquals(LinkSupport.NOT_REGISTERED, model.state.value.status.support)
    }

    @Test
    fun `it does not try when the system leaves it to the installer`() {
        handlers.current = LinkHandlerStatus(LinkSupport.BY_INSTALLER, "The installer did.")
        val model = model()
        model.register()
        assertEquals(0, handlers.calls)
    }

    @Test
    fun `a handler that throws does not leave the row busy`() {
        handlers.registerThrows = true
        val model = model()
        model.register()
        assertFalse(model.state.value.busy)
        assertTrue(
            model.state.value.message!!
                .contains("boom"),
        )
    }
}
