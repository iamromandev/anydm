package dev.anydm.desktop

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DropTest {
    private fun torrent(name: String) = Path.of("/t/$name")

    private fun items(
        vararg files: String,
        text: String? = null,
    ) = DropItems(files.map(::torrent), text)

    @Test
    fun aTorrentFileIsClassifiedAsATorrent() {
        assertEquals(DropAction.Torrent(torrent("a.torrent"), false), classifyDrop(items("a.torrent")))
    }

    @Test
    fun theTorrentExtensionIsCaseInsensitive() {
        assertEquals(DropAction.Torrent(torrent("A.TORRENT"), false), classifyDrop(items("A.TORRENT")))
    }

    @Test
    fun aTorrentWinsOverLinkText() {
        assertEquals(
            DropAction.Torrent(torrent("a.torrent"), true),
            classifyDrop(items("a.torrent", text = "https://example.com/a.zip")),
        )
    }

    @Test
    fun severalTorrentsUseTheFirstAndFlagMore() {
        assertEquals(DropAction.Torrent(torrent("a.torrent"), true), classifyDrop(items("a.torrent", "b.torrent")))
    }

    @Test
    fun unusableFilesAroundATorrentDoNotCountAsMore() {
        assertEquals(DropAction.Torrent(torrent("b.torrent"), false), classifyDrop(items("a.png", "b.torrent")))
    }

    @Test
    fun httpHttpsAndMagnetTextAreLinks() {
        assertEquals(DropAction.Link("https://example.com/a.zip"), classifyDrop(items(text = "  https://example.com/a.zip \n")))
        assertEquals(DropAction.Link("HTTP://example.com"), classifyDrop(items(text = "HTTP://example.com")))
        assertEquals(DropAction.Link("magnet:?xt=urn:btih:abc"), classifyDrop(items(text = "magnet:?xt=urn:btih:abc")))
    }

    @Test
    fun severalLinkLinesUseTheFirstAndFlagMore() {
        assertEquals(
            DropAction.Link("https://a.example/x", more = true),
            classifyDrop(items(text = "https://a.example/x\nhttps://b.example/y")),
        )
    }

    @Test
    fun aLinkLineAfterNoiseIsStillUsedWithoutMore() {
        assertEquals(DropAction.Link("https://a.example/x"), classifyDrop(items(text = "see:\nhttps://a.example/x")))
    }

    @Test
    fun otherFilesAndPlainTextAreRejected() {
        assertEquals(DropAction.Rejected, classifyDrop(items("a.png")))
        assertEquals(DropAction.Rejected, classifyDrop(items("a.torrent.txt")))
        assertEquals(DropAction.Rejected, classifyDrop(items(text = "hello world")))
        assertEquals(DropAction.Rejected, classifyDrop(items(text = "ftp://example.com/a")))
        assertEquals(DropAction.Rejected, classifyDrop(items(text = "")))
        assertEquals(DropAction.Rejected, classifyDrop(DropItems(emptyList(), null)))
    }

    private class FileList(
        val files: List<File>,
    ) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor

        override fun getTransferData(flavor: DataFlavor): Any =
            if (isDataFlavorSupported(flavor)) files else throw UnsupportedFlavorException(flavor)
    }

    private class Throwing(
        val error: Exception,
    ) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)

        override fun isDataFlavorSupported(flavor: DataFlavor) = true

        override fun getTransferData(flavor: DataFlavor): Any = throw error
    }

    @Test
    fun textComesFromAStringSelection() {
        assertEquals(DropItems(emptyList(), "https://example.com"), dropItemsOf(StringSelection("https://example.com")))
    }

    @Test
    fun filesComeFromAFileListTransferable() {
        val r = dropItemsOf(FileList(listOf(File("/t/a.torrent"), File("/t/b.png"))))
        assertEquals(listOf(Path.of("/t/a.torrent"), Path.of("/t/b.png")), r.files)
        assertEquals(null, r.text)
    }

    @Test
    fun aTransferableThatThrowsGivesEmptyItems() {
        assertEquals(DropItems(emptyList(), null), dropItemsOf(Throwing(IOException("gone"))))
        assertEquals(DropItems(emptyList(), null), dropItemsOf(Throwing(UnsupportedFlavorException(DataFlavor.stringFlavor))))
        assertEquals(DropItems(emptyList(), null), dropItemsOf(Throwing(IllegalStateException("boom"))))
    }

    @Test
    fun hoverTogglesTheFlag() {
        val c = DropController()
        assertFalse(c.hovering)
        c.hover(true)
        assertTrue(c.hovering)
        c.hover(false)
        assertFalse(c.hovering)
    }

    @Test
    fun aDropClearsHoverAndIsEmitted() =
        runBlocking {
            val c = DropController()
            val got = mutableListOf<DropItems>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { c.drops.collect { got.add(it) } }
            c.hover(true)
            c.drop(items("a.torrent"))
            yield()
            assertFalse(c.hovering)
            assertEquals(listOf(items("a.torrent")), got)
            job.cancel()
        }
}
