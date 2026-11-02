package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class LinkPasteTest {
    @Test
    fun `a pasted web link, magnet or ftp address is taken`() {
        assertEquals("https://example.com/a.zip", pastedLink("  https://example.com/a.zip \n"))
        assertEquals(
            "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a",
            pastedLink("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a"),
        )
        assertEquals("ftp://host/file.iso", pastedLink("ftp://host/file.iso"))
    }

    @Test
    fun `the first line that is a link wins`() {
        assertEquals("https://a.example/x.bin", pastedLink("see this:\nhttps://a.example/x.bin\nhttps://b.example/y.bin"))
    }

    @Test
    fun `text that is no link and an empty clipboard give nothing`() {
        assertNull(pastedLink("just some words"))
        assertNull(pastedLink(""))
        assertNull(pastedLink(null))
    }

    @Test
    fun `only torrent files are taken from a drop`() {
        assertEquals(
            listOf("/home/me/a.torrent", "/home/me/B.TORRENT"),
            droppedTorrents(listOf("/home/me/a.torrent", "/home/me/notes.txt", "/home/me/B.TORRENT")),
        )
        assertEquals(emptyList(), droppedTorrents(listOf("/home/me/pic.png")))
    }

    @Test
    fun `only the first 64 KB of a large clipboard is scanned`() {
        val filler = "words ".repeat(20_000)
        assertNull(pastedLink(filler + "\nhttps://example.com/late.zip"))
        assertEquals("https://example.com/early.zip", pastedLink("https://example.com/early.zip\n$filler"))
    }

    @Test
    fun `a line over 8 KB is ignored even when it is a link`() {
        val long = "https://example.com/" + "a".repeat(9_000)
        assertEquals("https://example.com/ok.zip", pastedLink("$long\nhttps://example.com/ok.zip"))
        assertNull(pastedLink(long))
    }

    @Test
    fun `a clipboard is read off the caller's thread`() =
        kotlinx.coroutines.test.runTest {
            val caller = Thread.currentThread()
            var readOn: Thread? = null
            val link =
                clipboardLink {
                    readOn = Thread.currentThread()
                    "https://example.com/a.zip"
                }
            assertEquals("https://example.com/a.zip", link)
            assertTrue(readOn !== caller)
        }

    @Test
    fun `a clipboard that can't be read gives no link`() = kotlinx.coroutines.test.runTest { assertNull(clipboardLink { error("locked") }) }
}
