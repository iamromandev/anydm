package dev.anydm.desktop

import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class OsHelpersTest {
    @Test
    fun `free space of a folder that does not exist yet is that of its nearest parent`() {
        val dir = Files.createTempDirectory("anydm-free")
        try {
            val free = freeDiskSpace(dir.resolve("a/b/c"))
            assertNotNull(free)
            assertTrue(free > 0)
        } finally {
            Files.deleteIfExists(dir)
        }
    }

    @Test
    fun `a relative path has no free space answer`() {
        assertNull(
            freeDiskSpace(
                java.nio.file.Path
                    .of("relative/folder"),
            ),
        )
    }

    @Test
    fun `file actions on a missing file say it is gone`() {
        val gone = Files.createTempDirectory("anydm-gone").also { Files.delete(it) }
        val actions = DesktopFileActions()
        for (result in listOf(actions.open(gone), actions.reveal(gone))) {
            assertTrue(result!!.endsWith("is no longer there."), result)
        }
    }

    @Test
    fun `only web addresses are opened in the browser`() {
        val actions = DesktopFileActions()
        for (url in listOf("file:///etc/passwd", "javascript:alert(1)", "not a url", "", "ftp://example.com/x", "https:///nohost")) {
            assertNotNull(actions.openUrl(url), url)
        }
    }

    private fun Clipboard.text(): String? =
        if (isDataFlavorAvailable(DataFlavor.stringFlavor)) getData(DataFlavor.stringFlavor) as String else null

    @Test
    fun `the clipboard is emptied when it still holds the copied text`() {
        val board = Clipboard("test")
        board.setContents(StringSelection("ABCD-EFGH"), null)
        assertTrue(clearIfStill(board, "ABCD-EFGH"))
        assertEquals("", board.text())
    }

    @Test
    fun `something copied since is left alone`() {
        val board = Clipboard("test")
        board.setContents(StringSelection("something else"), null)
        assertFalse(clearIfStill(board, "ABCD-EFGH"))
        assertEquals("something else", board.text())
    }

    @Test
    fun `an empty clipboard is left alone`() {
        val board = Clipboard("test")
        assertFalse(clearIfStill(board, "ABCD-EFGH"))
        assertNull(board.text())
    }

    @Test
    fun `a clipboard that cannot be read is left alone and nothing is thrown`() {
        val board =
            object : Clipboard("broken") {
                var cleared = false

                override fun getContents(requestor: Any?): Transferable = throw IllegalStateException("clipboard unavailable")

                override fun setContents(
                    contents: Transferable?,
                    owner: java.awt.datatransfer.ClipboardOwner?,
                ) {
                    cleared = true
                }
            }
        assertFalse(clearIfStill(board, "ABCD-EFGH"))
        assertFalse(board.cleared)
    }

    @Test
    fun `work given to runOffThread runs on another thread and is done when the call returns`() {
        val caller = Thread.currentThread()
        var ranOn: Thread? = null
        runOffThread(5_000) { ranOn = Thread.currentThread() }
        assertNotNull(ranOn)
        assertTrue(ranOn !== caller)
    }

    @Test
    fun `work that hangs does not hold the caller beyond the wait`() {
        val release = java.util.concurrent.CountDownLatch(1)
        val began = System.nanoTime()
        runOffThread(100) { release.await() }
        val tookMs = (System.nanoTime() - began) / 1_000_000
        release.countDown()
        assertTrue(tookMs < 3_000, "took $tookMs ms")
    }

    @Test
    fun `work that fails does not fail the caller`() {
        runOffThread(5_000) { error("no clipboard") }
    }
}
