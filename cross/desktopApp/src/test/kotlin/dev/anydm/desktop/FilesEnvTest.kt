package dev.anydm.desktop

import dev.anydm.engine.torrent.MAX_TORRENT_BYTES
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentResult
import kotlinx.coroutines.runBlocking
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FilesEnvTest {
    private val home = "/Users/x"

    @Test
    fun homeIsShortenedToTilde() {
        assertEquals("~/Movies", displayFolder("/Users/x/Movies", home))
        assertEquals("~/Movies/Shows", displayFolder("/Users/x/Movies/Shows", home))
        assertEquals("~", displayFolder("/Users/x", home))
    }

    @Test
    fun homeWithTrailingSeparatorStillMatches() {
        assertEquals("~/Movies", displayFolder("/Users/x/Movies", "/Users/x/"))
        assertEquals("~", displayFolder("/Users/x/", "/Users/x/"))
    }

    @Test
    fun onlyAWholeLeadingHomeFolderIsShortened() {
        assertEquals("/Users/xavier/Movies", displayFolder("/Users/xavier/Movies", home))
        assertEquals("/mnt/Users/x/Movies", displayFolder("/mnt/Users/x/Movies", home))
        assertEquals("/Volumes/Media", displayFolder("/Volumes/Media", home))
    }

    @Test
    fun anEmptyOrRootHomeShortensNothing() {
        assertEquals("/Movies", displayFolder("/Movies", ""))
        assertEquals("/Movies", displayFolder("/Movies", "/"))
    }

    @Test
    fun windowsStyleHomeIsShortenedToo() {
        assertEquals("~\\Movies", displayFolder("C:\\Users\\x\\Movies", "C:\\Users\\x"))
    }

    @Test
    fun defaultFolderIsDownloadsUnderHome() {
        assertEquals("/Users/x/Downloads", defaultFolder("/Users/x"))
        assertEquals("/Users/x/Downloads", defaultFolder("/Users/x/"))
        assertEquals("C:\\Users\\x\\Downloads", defaultFolder("C:\\Users\\x"))
        assertEquals("~/Downloads", displayFolder(defaultFolder(home), home))
    }

    private fun withTemp(block: (Path) -> Unit) {
        val p = Files.createTempFile("anydm-test", ".torrent")
        try {
            block(p)
        } finally {
            p.deleteIfExists()
        }
    }

    private val tiny = "d4:infod4:name1:a6:lengthi5eee".encodeToByteArray()

    @Test
    fun loaderReadsAndParsesAFile() {
        withTemp { p ->
            Files.write(p, tiny)
            val r = assertIs<TorrentResult.Ok>(runBlocking { DiskTorrentLoader().load(p) })
            assertEquals("a", r.meta.name)
            assertEquals(listOf(TorrentFile("a", 5)), r.meta.files)
        }
    }

    @Test
    fun loaderReportsInvalidContent() {
        withTemp { p ->
            Files.write(p, "not a torrent".encodeToByteArray())
            assertEquals(TorrentResult.Err("Not bencoded"), runBlocking { DiskTorrentLoader().load(p) })
        }
    }

    @Test
    fun loaderRejectsAnOversizedSparseFile() {
        withTemp { p ->
            RandomAccessFile(p.toFile(), "rw").use { it.setLength(MAX_TORRENT_BYTES + 1L) }
            assertEquals(TorrentResult.Err("Too large"), runBlocking { DiskTorrentLoader().load(p) })
        }
    }

    @Test
    fun loaderReportsAMissingFileAndADirectory() {
        withTemp { p ->
            val missing = p.resolveSibling(p.fileName.toString() + ".gone")
            assertEquals(TorrentResult.Err("Can't read file"), runBlocking { DiskTorrentLoader().load(missing) })
            assertEquals(TorrentResult.Err("Can't read file"), runBlocking { DiskTorrentLoader().load(p.parent) })
        }
    }

    @Test
    fun loaderRefusesAFifoInsteadOfBlockingOnIt() {
        org.junit.Assume.assumeTrue(!System.getProperty("os.name").startsWith("Windows"))
        val dir = Files.createTempDirectory("anydm-fifo")
        val fifo = dir.resolve("pipe.torrent")
        try {
            val made = ProcessBuilder("mkfifo", fifo.toString()).start().waitFor()
            org.junit.Assume.assumeTrue(made == 0)
            var result: TorrentResult? = null
            val t = Thread { result = runBlocking { DiskTorrentLoader().load(fifo) } }
            t.isDaemon = true
            t.start()
            t.join(3_000)
            val finished = !t.isAlive
            if (!finished) Files.newOutputStream(fifo).close() // lets a reader that is stuck opening the pipe go
            t.join(3_000)
            assertEquals(true, finished, "the loader blocked on the pipe")
            assertEquals(TorrentResult.Err("Can't read file"), result)
        } finally {
            fifo.deleteIfExists()
            dir.deleteIfExists()
        }
    }

    @Test
    fun loaderReportsADirectoryAsUnreadableEvenWhenItsSizeIsSmall() {
        val dir = Files.createTempDirectory("anydm-dir")
        try {
            assertEquals(TorrentResult.Err("Can't read file"), runBlocking { DiskTorrentLoader().load(dir) })
        } finally {
            dir.deleteIfExists()
        }
    }

    @Test
    fun aPickedNameThePlatformCannotRepresentIsNoPathInsteadOfACrash() {
        assertEquals(null, pickedPath("/tmp", "bad\u0000name.torrent"))
        assertEquals(null, pickedPath(null, "bad\u0000name.torrent"))
        assertEquals(Path.of("/tmp", "a.torrent"), pickedPath("/tmp", "a.torrent"))
        assertEquals(Path.of("a.torrent"), pickedPath(null, "a.torrent"))
    }
}
