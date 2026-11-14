package dev.anydm.desktop

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Works in a JUnit temp folder: nothing here touches the real app-data folder. */
class TorrentFileStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: Path get() =
        tmp.root
            .toPath()
            .resolve("anydm")
            .resolve("torrents")
    private val hex = "ab".repeat(20)

    @Test
    fun saveKeepsTheBytesUnderTheInfoHashAndMakesTheFolder() {
        val store = DiskTorrentFileStore(dir)
        store.save(hex, byteArrayOf(1, 2, 3))
        assertEquals(dir.resolve("$hex.torrent"), store.path(hex))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(store.path(hex)))
        assertEquals(listOf("$hex.torrent"), dir.listDirectoryEntries().map { it.fileName.toString() }, "no temp file is left")
    }

    @Test
    fun savingAgainReplacesTheCopy() {
        val store = DiskTorrentFileStore(dir)
        store.save(hex, byteArrayOf(1))
        store.save(hex, byteArrayOf(9, 9))
        assertContentEquals(byteArrayOf(9, 9), Files.readAllBytes(store.path(hex)))
    }

    @Test
    fun deleteRemovesTheCopyAndAMissingOneIsNoError() {
        val store = DiskTorrentFileStore(dir)
        store.delete(hex)
        store.save(hex, byteArrayOf(1))
        store.delete(hex)
        assertFalse(store.path(hex).exists())
    }

    @Test
    fun deleteAlsoRemovesTheUnfinishedCopyAMagnetFetchLeftBehindButNotOtherFiles() {
        val store = DiskTorrentFileStore(dir)
        Files.createDirectories(dir)
        val tmp = dir.resolve("$hex.torrent.tmp") // what the engine writes before it moves the metadata into place
        val other = dir.resolve("${"cd".repeat(20)}.torrent.tmp")
        Files.write(tmp, byteArrayOf(1))
        Files.write(other, byteArrayOf(2))
        store.delete(hex)
        assertFalse(tmp.exists(), "the leftover of this hash goes with its row")
        assertTrue(other.exists(), "another torrent's file is not touched")
    }

    @Test
    fun deleteWorksWhenOnlyTheUnfinishedCopyIsThere() {
        val store = DiskTorrentFileStore(dir)
        Files.createDirectories(dir)
        Files.write(dir.resolve("$hex.torrent.tmp"), byteArrayOf(1))
        store.delete(hex)
        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun theFolderIsNotMadeUntilTheFirstSave() {
        DiskTorrentFileStore(dir)
        assertFalse(dir.exists())
    }

    @Test
    fun aNameThatIsNotAnInfoHashIsRefusedSoNoPathEscapesTheFolder() {
        val store = DiskTorrentFileStore(dir)
        for (bad in listOf("../x", "AB".repeat(20), "ab".repeat(19), "", "ab".repeat(20) + "/")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.path(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { store.save(bad, byteArrayOf(1)) }
        }
        assertTrue(isInfoHashHex(hex))
    }

    @Test
    fun manySavesAndDeletesOfTheSameCopyAtOnceNeverFailAndLeaveAWholeCopyAndNoTempFile() {
        val store = DiskTorrentFileStore(dir)
        val a = ByteArray(200_000) { 1 }
        val b = ByteArray(300_000) { 2 }
        val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val threads =
            (0 until 8).map { t ->
                Thread {
                    try {
                        repeat(60) { n ->
                            if (t == 7 && n % 3 == 0) store.delete(hex) else store.save(hex, if ((t + n) % 2 == 0) a else b)
                        }
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(emptyList(), errors.toList(), "no save or delete failed because of another")
        store.save(hex, a)
        assertContentEquals(a, Files.readAllBytes(store.path(hex)))
        assertEquals(listOf("$hex.torrent"), dir.listDirectoryEntries().map { it.fileName.toString() }, "no temp file is left")
    }

    @Test
    fun theCopyIsWholeInItsTempFileBeforeItIsMovedOverTheRealName() {
        var seen: ByteArray? = null
        val store =
            DiskTorrentFileStore(dir) { from, to ->
                seen = Files.readAllBytes(from)
                assertFalse(to.exists(), "nothing is at the real name yet")
                Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            }
        store.save(hex, byteArrayOf(4, 5, 6))
        assertContentEquals(byteArrayOf(4, 5, 6), seen)
        assertContentEquals(byteArrayOf(4, 5, 6), Files.readAllBytes(store.path(hex)))
    }

    @Test
    fun aFileSystemWithoutAtomicMovesStillGetsTheCopyAndLeavesNoTempFile() {
        val store = DiskTorrentFileStore(dir) { _, _ -> throw java.nio.file.AtomicMoveNotSupportedException("a", "b", "no") }
        store.save(hex, byteArrayOf(1))
        store.save(hex, byteArrayOf(7, 7))
        assertContentEquals(byteArrayOf(7, 7), Files.readAllBytes(store.path(hex)))
        assertEquals(listOf("$hex.torrent"), dir.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun aMoveThatFailsLeavesNeitherAHalfCopyNorATempFile() {
        val store = DiskTorrentFileStore(dir) { _, _ -> throw java.io.IOException("disk gone") }
        assertFailsWith<java.io.IOException> { store.save(hex, byteArrayOf(1)) }
        assertEquals(emptyList(), dir.listDirectoryEntries())
    }

    @Test
    fun readGivesTheCopyBackAndNullWhenThereIsNone() {
        val store = DiskTorrentFileStore(dir)
        assertEquals(null, store.read(hex))
        store.save(hex, byteArrayOf(1, 2))
        assertContentEquals(byteArrayOf(1, 2), store.read(hex))
    }
}
