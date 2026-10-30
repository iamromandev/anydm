package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkRouterTest {
    private val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a&dn=x"

    @Test
    fun `web, ftp, sftp, magnet and torrent links are routed in the form the engine takes`() {
        val route = LinkRouter.route(listOf("https://example.com/a.zip", "ftp://h/f.bin", "sftp://h/f.bin", magnet, "/tmp/x.torrent"))
        assertEquals(listOf("https://example.com/a.zip", "ftp://h/f.bin", "sftp://h/f.bin", magnet, "file:///tmp/x.torrent"), route.links)
    }

    @Test
    fun `an anydm add link carries the link to add`() {
        val route = LinkRouter.route(listOf("anydm://add?url=https%3A%2F%2Fexample.com%2Fa.zip"))
        assertEquals(listOf("https://example.com/a.zip"), route.links)
    }

    @Test
    fun `a stray argument or a link of another kind is dropped`() {
        val route = LinkRouter.route(listOf("--verbose", "javascript:alert(1)", "file:///etc/passwd", "", "https://example.com/a"))
        assertEquals(listOf("https://example.com/a"), route.links)
    }

    @Test
    fun `the same link twice is added once`() {
        val route = LinkRouter.route(listOf("https://example.com/a", "https://example.com/a", " https://example.com/a "))
        assertEquals(listOf("https://example.com/a"), route.links)
    }

    @Test
    fun `hidden is a flag and is not a link`() {
        assertTrue(LinkRouter.route(listOf("--hidden")).hidden)
        assertFalse(LinkRouter.route(listOf("https://example.com/a")).hidden)
        assertEquals(emptyList(), LinkRouter.route(listOf("--hidden")).links)
    }

    @Test
    fun `no arguments route nothing`() {
        val route = LinkRouter.route(emptyList())
        assertEquals(emptyList(), route.links)
        assertFalse(route.hidden)
    }
}
