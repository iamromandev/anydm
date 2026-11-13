package dev.anydm.desktop

import dev.anydm.engine.http.RealDiskIo
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Also delete the files" for a torrent row: only the torrent's own files go, on a real disk, never the user's other things. */
class TorrentRemovalTest {
    private val base: Path = Files.createTempDirectory("torrent-removal")
    private val save: Path = Files.createDirectories(base.resolve("save"))
    private val outside: Path = Files.createDirectories(base.resolve("outside"))
    private val copies = FakeTorrentFiles()

    // a.bin and d/b.bin
    private val bytes = TestTorrents.bytes("Show", listOf("a.bin" to 100L, "d/b.bin" to 200L))
    private val meta = TestTorrents.meta(bytes)
    private val hex = meta.infoHashHex
    private val root = save.resolve("Show")

    @AfterTest
    fun tearDown() {
        base.toFile().deleteRecursively()
    }

    private fun write(
        p: Path,
        size: Int = 3,
    ): Path {
        Files.createDirectories(p.parent)
        Files.write(p, ByteArray(size))
        return p
    }

    private fun link(
        at: Path,
        to: Path,
    ): Boolean =
        try {
            Files.createDirectories(at.parent)
            Files.createSymbolicLink(at, to)
            true
        } catch (_: UnsupportedOperationException) {
            false
        }

    private fun discard(
        opener: FakeOpener = FakeOpener(canTrash = false),
        own: OwnTorrent = OwnTorrent(hex, save, "Show", finished = false),
        keepCopy: Boolean = true,
    ) {
        if (keepCopy) copies.saved[hex] = bytes
        val env = DownloadEnv(FakeEngine(), FakeProber(), opener, RealDiskIo(), torrentFiles = copies)
        runBlocking { runEngineCommand(env, EngineCmd.Discard(setOf("1"), emptyList(), listOf(hex), listOf(own))) }
    }

    private fun ownParts() = listOf(write(root.resolve("a.bin.part")), write(root.resolve("d/b.bin.part")))

    @Test
    fun aForeignFileInsideTheRootStaysAndSoDoTheRootAndTheFolderAboveIt() {
        ownParts()
        val foreign = write(root.resolve("notes.txt"))
        discard()
        assertTrue(Files.exists(foreign), "the user's file")
        assertTrue(Files.isDirectory(root), "the root holds it")
        assertTrue(Files.isDirectory(save))
        assertFalse(Files.exists(root.resolve("a.bin.part")))
        assertFalse(Files.exists(root.resolve("d/b.bin.part")))
        assertFalse(Files.exists(root.resolve("d")), "a folder the delete emptied goes")
    }

    @Test
    fun aForeignFileInsideTheTorrentsOwnSubfolderKeepsThatSubfolder() {
        ownParts()
        val foreign = write(root.resolve("d/mine.txt"))
        discard()
        assertTrue(Files.exists(foreign))
        assertTrue(Files.isDirectory(root.resolve("d")))
        assertFalse(Files.exists(root.resolve("d/b.bin.part")))
    }

    @Test
    fun aRootThatHoldsOnlyTheTorrentsFilesGoesToTheTrashAsAWhole() {
        ownParts()
        val opener = FakeOpener()
        discard(opener)
        assertEquals(listOf(root), opener.trashed.toList())
    }

    @Test
    fun withoutATrashEveryFileAndTheEmptiedFoldersGoButTheSaveFolderStays() {
        ownParts()
        discard(FakeOpener(canTrash = false))
        assertFalse(Files.exists(root), "nothing of the torrent is left")
        assertTrue(Files.isDirectory(save), "the folder the user chose is never removed")
    }

    @Test
    fun aRootWithAForeignFileSendsOnlyTheTorrentsFilesToTheTrash() {
        val parts = ownParts()
        write(root.resolve("notes.txt"))
        val opener = FakeOpener()
        discard(opener)
        assertEquals(parts.toSet(), opener.trashed.toSet())
    }

    @Test
    fun aLinkInsideTheRootIsNeverFollowedAndNeitherIsWhatItPointsTo() {
        ownParts()
        write(outside.resolve("precious.txt"))
        if (!link(root.resolve("shortcut"), outside)) return
        discard()
        assertTrue(Files.exists(outside.resolve("precious.txt")), "what the link points to")
        assertTrue(Files.isSymbolicLink(root.resolve("shortcut")), "the link is the user's too")
        assertFalse(Files.exists(root.resolve("a.bin.part")))
    }

    @Test
    fun aFolderOfTheTorrentThatIsALinkIsNotWalkedInto() {
        write(root.resolve("a.bin.part"))
        write(outside.resolve("b.bin.part"), 5) // the same name as the torrent's file, but behind a link
        if (!link(root.resolve("d"), outside)) return
        discard()
        assertTrue(Files.exists(outside.resolve("b.bin.part")), "outside the torrent's folder")
        assertFalse(Files.exists(root.resolve("a.bin.part")))
    }

    @Test
    fun aRootThatIsALinkIsLeftAlone() {
        write(outside.resolve("a.bin.part"))
        write(outside.resolve("d/b.bin.part"))
        if (!link(root, outside)) return
        discard()
        assertTrue(Files.exists(outside.resolve("a.bin.part")))
        assertTrue(Files.exists(outside.resolve("d/b.bin.part")))
    }

    @Test
    fun aFinishedRowsFilesGoAndItsForeignNeighboursStay() {
        val a = write(root.resolve("a.bin"), 100)
        val b = write(root.resolve("d/b.bin"), 200)
        val foreign = write(root.resolve("cover.jpg"))
        discard(own = OwnTorrent(hex, save, "Show", finished = true))
        assertFalse(Files.exists(a))
        assertFalse(Files.exists(b))
        assertTrue(Files.exists(foreign))
    }

    @Test
    fun aRowThatIsNotFinishedLeavesAFileAtAFinalPathAloneWhileItsPartFileIsThere() {
        write(root.resolve("a.bin.part"))
        val users = write(root.resolve("a.bin"), 100) // same size, but its part file says the torrent is not done with it
        discard()
        assertTrue(Files.exists(users))
        assertFalse(Files.exists(root.resolve("a.bin.part")))
    }

    @Test
    fun aFileARenameThatStoppedHalfWayAlreadyMovedIsTheTorrentsOwnAndGoes() {
        val moved = write(root.resolve("a.bin"), 100) // renamed: no part file, the full size
        write(root.resolve("d/b.bin.part"), 200)
        discard()
        assertFalse(Files.exists(moved))
        assertFalse(Files.exists(root))
    }

    @Test
    fun aFileOfAnotherSizeAtAFinalPathIsNotTheTorrentsEvenWithNoPartFile() {
        val users = write(root.resolve("a.bin"), 7)
        write(root.resolve("d/b.bin.part"), 200)
        discard()
        assertTrue(Files.exists(users))
    }

    @Test
    fun aSingleFileTorrentTakesItsFileAndItsPartFileAndLeavesTheFolderAlone() {
        val one = TestTorrents.bytes("one.bin", listOf("one.bin" to 50L), single = true)
        val m = TestTorrents.meta(one)
        val part = write(save.resolve("one.bin.part"))
        val other = write(save.resolve("other.bin"))
        copies.saved[m.infoHashHex] = one
        val env = DownloadEnv(FakeEngine(), FakeProber(), FakeOpener(canTrash = false), RealDiskIo(), torrentFiles = copies)
        val own = OwnTorrent(m.infoHashHex, save, "one.bin", finished = false)
        runBlocking { runEngineCommand(env, EngineCmd.Discard(setOf("1"), emptyList(), listOf(m.infoHashHex), listOf(own))) }
        assertFalse(Files.exists(part))
        assertTrue(Files.exists(other))
        assertTrue(Files.isDirectory(save))
    }

    @Test
    fun aCopyThatIsGoneMeansNothingIsDeleted() {
        val parts = ownParts()
        discard(keepCopy = false)
        for (p in parts) assertTrue(Files.exists(p), "$p")
    }

    @Test
    fun aNameThatLeavesTheFolderDeletesNothing() {
        val victim = write(save.resolve("a.bin.part"))
        discard(own = OwnTorrent(hex, save, "../save/Show", finished = false))
        assertTrue(Files.exists(victim))
    }

    @Test
    fun theCopyIsReadBeforeItIsDeletedInTheSameCommand() {
        ownParts()
        discard()
        assertEquals(listOf(hex), copies.deleted.toList(), "the copy goes with the row")
        assertFalse(Files.exists(root), "and its files were still found")
    }
}
