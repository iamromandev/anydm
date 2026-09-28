package dev.anydm.api

import kotlin.test.Test
import kotlin.test.assertEquals

class ServerConfigTest {
    @Test
    fun `a trailing slash and surrounding space go`() {
        val config = ServerConfig(" http://nas.local:8030/ ", "k").normalized()
        assertEquals("http://nas.local:8030", config.baseUrl)
    }

    @Test
    fun `a blank key means no key`() {
        assertEquals(null, ServerConfig("http://a", "  ").normalized().apiKey)
    }
}
