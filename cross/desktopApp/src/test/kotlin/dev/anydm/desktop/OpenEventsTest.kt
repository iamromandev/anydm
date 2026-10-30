package dev.anydm.desktop

import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

internal class OpenEventsTest {
    @Test
    fun `what arrives before the app is ready is delivered once it is`() {
        val events = OpenEvents()
        events.deliver(listOf("magnet:?xt=urn:btih:a"))
        events.deliver(listOf("magnet:?xt=urn:btih:b"))
        val got = mutableListOf<List<String>>()
        events.attach { got += it }
        assertEquals(listOf(listOf("magnet:?xt=urn:btih:a"), listOf("magnet:?xt=urn:btih:b")), got)
    }

    @Test
    fun `what arrives later goes straight through`() {
        val events = OpenEvents()
        val got = mutableListOf<List<String>>()
        events.attach { got += it }
        events.deliver(listOf("anydm://add?url=https%3A%2F%2Fexample.com%2Fa.zip"))
        assertEquals(1, got.size)
    }

    @Test
    fun `a link and a file become launch arguments`() {
        assertEquals("magnet:?xt=urn:btih:a", openArgument(URI("magnet:?xt=urn:btih:a")))
        assertEquals(File("/tmp/a b.torrent").path, openArgument(File("/tmp/a b.torrent")))
    }
}
