package dev.anydm.engine.torrent

import dev.anydm.engine.http.DiskIo
import dev.anydm.engine.http.Fail
import dev.anydm.engine.http.RealDiskIo
import dev.anydm.engine.http.Reasons
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentStoreTest {
    private val dir: Path = Files.createTempDirectory("torrent-store")
    private val disk = RealDiskIo()

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private class LowSpace(
        private val inner: DiskIo,
        private val free: Long,
    ) : DiskIo by inner {
        override fun freeSpace(dir: Path) = free
    }

    private val piece = 16 * 1024

    private fun single(
        size: Int,
        name: String = "movie.bin",
    ): Pair<TorrentMeta, ByteArray> {
        val data = contentOf(size, 3)
        val built = torrentOf(listOf(name to size), piece, data, name = name, single = true)
        return assertIs<TorrentResult.Ok>(readTorrent(built.bytes)).meta to data
    }

    /** Three files, a zero-length one in the middle, and pieces that span them. */
    private fun multi(name: String = "album"): Pair<TorrentMeta, ByteArray> {
        val files = listOf("a.bin" to 20_000, "sub/empty" to 0, "sub/b.bin" to 30_000, "c.bin" to 5)
        val data = contentOf(50_005, 4)
        val built = torrentOf(files, piece, data, name = name)
        return assertIs<TorrentResult.Ok>(readTorrent(built.bytes)).meta to data
    }

    private fun writeAll(
        store: TorrentStore,
        meta: TorrentMeta,
        data: ByteArray,
    ) {
        for (i in 0 until meta.pieceCount) {
            val from = i * piece
            store.writePiece(i, data.copyOfRange(from, from + meta.pieceSize(i).toInt()))
        }
    }

    private fun open(
        meta: TorrentMeta,
        resume: Boolean = false,
        disk: DiskIo = this.disk,
        name: String = meta.name,
        taken: (Path) -> Boolean = { false },
    ) = TorrentStore.open(disk, meta, dir, name, resume, taken)

    @Test
    fun aSingleFileIsWrittenToItsPartFileAndRenamedAtTheEnd() {
        val (meta, data) = single(40_000)
        val store = open(meta)
        assertEquals("movie.bin", store.root)
        assertEquals(40_000L, disk.size(dir.resolve("movie.bin.part")), "reserved at its full size")
        writeAll(store, meta, data)
        assertFalse(Files.exists(dir.resolve("movie.bin")), "not final before the end")
        assertEquals("movie.bin", store.finish())
        assertContentEquals(data, Files.readAllBytes(dir.resolve("movie.bin")))
        assertFalse(Files.exists(dir.resolve("movie.bin.part")))
    }

    @Test
    fun piecesThatSpanFilesAreSplitAtTheBordersAndEveryFileEndsRight() {
        val (meta, data) = multi()
        val store = open(meta)
        assertEquals(20_000L, disk.size(dir.resolve("album/a.bin.part")))
        assertEquals(30_000L, disk.size(dir.resolve("album/sub/b.bin.part")))
        assertEquals(5L, disk.size(dir.resolve("album/c.bin.part")))
        assertNull(disk.size(dir.resolve("album/sub/empty.part")), "a zero-length file is made at the end")
        // written back to front: order does not matter
        for (i in meta.pieceCount - 1 downTo 0) {
            store.writePiece(i, data.copyOfRange(i * piece, i * piece + meta.pieceSize(i).toInt()))
        }
        store.finish()
        assertContentEquals(data.copyOfRange(0, 20_000), Files.readAllBytes(dir.resolve("album/a.bin")))
        assertContentEquals(data.copyOfRange(20_000, 50_000), Files.readAllBytes(dir.resolve("album/sub/b.bin")))
        assertContentEquals(data.copyOfRange(50_000, 50_005), Files.readAllBytes(dir.resolve("album/c.bin")))
        assertEquals(0L, Files.size(dir.resolve("album/sub/empty")))
        assertTrue(Files.walk(dir).use { s -> s.noneMatch { it.toString().endsWith(".part") } }, "no .part is left")
    }

    @Test
    fun readPieceGivesBackWhatWasWrittenAcrossFiles() {
        val (meta, data) = multi()
        val store = open(meta)
        writeAll(store, meta, data)
        for (i in 0 until meta.pieceCount) {
            assertContentEquals(data.copyOfRange(i * piece, i * piece + meta.pieceSize(i).toInt()), store.readPiece(i), "piece $i")
        }
        assertNull(store.readPiece(meta.pieceCount), "no such piece")
        store.close()
    }

    @Test
    fun readPieceIsNullWhenOneOfItsFilesIsMissing() {
        val (meta, data) = multi()
        val store = open(meta)
        writeAll(store, meta, data)
        store.close()
        disk.delete(dir.resolve("album/sub/b.bin.part"))
        assertNull(store.readPiece(1), "piece 1 runs from a.bin into b.bin")
        assertContentEquals(data.copyOfRange(0, piece), store.readPiece(0), "piece 0 is in a.bin alone")
    }

    @Test
    fun aPieceWithAMissingFileHasNoData() {
        val (meta, data) = multi()
        val store = open(meta)
        writeAll(store, meta, data)
        store.close()
        disk.delete(dir.resolve("album/a.bin.part"))
        // the reopened store makes the file again at full size, zero-filled: its content is wrong but the read does not fail
        val reopened = open(meta, resume = true)
        assertTrue(Files.exists(dir.resolve("album/a.bin.part")))
        assertFalse(reopened.readPiece(0)!!.contentEquals(data.copyOfRange(0, piece)), "the data is gone")
        reopened.close()
    }

    @Test
    fun readPieceIsNullWhenNothingIsThereToRead() {
        val (meta, _) = single(40_000)
        val store = open(meta)
        store.close()
        disk.delete(dir.resolve("movie.bin.part"))
        assertNull(store.readPiece(0))
    }

    @Test
    fun aFreshStartPicksAFreeNameWhenTheFinalFileExists() {
        val (meta, data) = single(1_000)
        Files.writeString(dir.resolve("movie.bin"), "mine")
        val store = open(meta)
        assertEquals("movie (1).bin", store.root)
        writeAll(store, meta, data)
        assertEquals("movie (1).bin", store.finish())
        assertEquals("mine", Files.readString(dir.resolve("movie.bin")), "the user's file is not touched")
        assertContentEquals(data, Files.readAllBytes(dir.resolve("movie (1).bin")))
    }

    @Test
    fun aFreshStartPicksAFreeNameWhenAFileOfTheFolderExists() {
        val (meta, _) = multi()
        Files.createDirectories(dir.resolve("album/sub"))
        Files.writeString(dir.resolve("album/sub/b.bin"), "mine")
        assertEquals("album (1)", open(meta).root)
        assertEquals("mine", Files.readString(dir.resolve("album/sub/b.bin")))
    }

    @Test
    fun aFreshStartPicksAFreeNameWhenTheFolderNameIsAFile() {
        val (meta, _) = multi()
        Files.writeString(dir.resolve("album"), "a file")
        assertEquals("album (1)", open(meta).root)
    }

    @Test
    fun aFreshStartPicksAFreeNameWhenAFolderOfThatNameExistsWithOtherThings() {
        val (meta, _) = multi()
        Files.createDirectories(dir.resolve("album"))
        Files.writeString(dir.resolve("album/notes.txt"), "mine")
        val store = open(meta)
        assertEquals("album (1)", store.root, "a folder of that name is the user's, however little of it the torrent would touch")
        assertEquals("mine", Files.readString(dir.resolve("album/notes.txt")))
        assertFalse(Files.exists(dir.resolve("album/a.bin.part")), "nothing of the torrent went into it")
    }

    @Test
    fun aFreshStartPicksAFreeNameWhenAnEmptyFolderOfThatNameExists() {
        val (meta, _) = multi()
        Files.createDirectories(dir.resolve("album"))
        assertEquals("album (1)", open(meta).root)
    }

    @Test
    fun aResumeKeepsItsOwnFolderThatHoldsItsPartFiles() {
        val (meta, _) = multi()
        val first = open(meta)
        first.close()
        assertEquals("album", open(meta, resume = true).root)
    }

    @Test
    fun theOwnPathsOfAMultiFileTorrentAreItsFinalsAndTheirPartFilesUnderTheRoot() {
        val (meta, _) = multi()
        val paths = torrentFilePaths(dir, "album (1)", meta)
        assertEquals(meta.files.map { dir.resolve("album (1)/${it.path}") }, paths.finals)
        assertEquals(meta.files.map { dir.resolve("album (1)/${it.path}.part") }, paths.parts)
    }

    @Test
    fun theOwnPathsOfASingleFileTorrentAreTheRootAndItsPartFile() {
        val (meta, _) = single(1_000)
        val paths = torrentFilePaths(dir, "movie (1).bin", meta)
        assertEquals(listOf(dir.resolve("movie (1).bin")), paths.finals)
        assertEquals(listOf(dir.resolve("movie (1).bin.part")), paths.parts)
    }

    @Test
    fun aPartFileThatWouldBeAnotherFileOfTheTorrentGetsTheSameSuffixTheStoreGaveIt() {
        val data = contentOf(2_000, 5)
        val built = torrentOf(listOf("a" to 1_000, "a.part" to 1_000), piece, data, name = "dup")
        val meta = assertIs<TorrentResult.Ok>(readTorrent(built.bytes)).meta
        val store = open(meta)
        val paths = torrentFilePaths(dir, store.root, meta)
        store.close()
        for (part in paths.parts) assertTrue(Files.isRegularFile(part), "$part is where the store put it")
    }

    @Test
    fun theOwnPathsNeverLeaveTheFolder() {
        val (meta, _) = single(10)
        assertFailsWith<IllegalArgumentException> { torrentFilePaths(dir, "../escape", meta) }
    }

    @Test
    fun aFreshStartPicksAFreeNameWhenAnotherTorrentHoldsTheName() {
        val (meta, _) = single(1_000)
        val first = open(meta)
        val second = open(meta)
        assertEquals("movie.bin", first.root)
        assertEquals("movie (1).bin", second.root, "the first one's part file is in the way")
    }

    @Test
    fun aPathThatAnotherRunHoldsIsSkipped() {
        val (meta, _) = single(1_000)
        val store = open(meta, taken = { it == dir.resolve("movie.bin") })
        assertEquals("movie (1).bin", store.root)
    }

    @Test
    fun aResumeKeepsTheNameAndTheDataOfItsPartFiles() {
        val (meta, data) = single(40_000)
        val first = open(meta)
        first.writePiece(1, data.copyOfRange(piece, 2 * piece))
        first.close()
        val again = open(meta, resume = true)
        assertEquals("movie.bin", again.root)
        assertContentEquals(data.copyOfRange(piece, 2 * piece), again.readPiece(1))
        again.close()
    }

    @Test
    fun aReservationThatDoesNotFitFailsWithDiskFullAndLeavesNothing() {
        val (meta, _) = multi()
        val e = assertFailsWith<Fail> { open(meta, disk = LowSpace(disk, 49_999)) }
        assertEquals(Reasons.DISK_FULL, e.reason)
        assertTrue(Files.walk(dir).use { s -> s.noneMatch { Files.isRegularFile(it) } }, "no file is left behind")
    }

    @Test
    fun aReservationThatFitsExactlyWorks() {
        val (meta, _) = multi()
        open(meta, disk = LowSpace(disk, 50_005)).close()
        assertEquals(50_005L, disk.size(dir.resolve("album/a.bin.part"))!! + disk.size(dir.resolve("album/sub/b.bin.part"))!! + 5)
    }

    @Test
    fun aResumeOnlyNeedsRoomForWhatIsMissing() {
        val (meta, _) = single(40_000)
        Files.write(dir.resolve("movie.bin.part"), ByteArray(30_000))
        assertEquals(Reasons.DISK_FULL, assertFailsWith<Fail> { open(meta, resume = true, disk = LowSpace(disk, 9_999)) }.reason)
        open(meta, resume = true, disk = LowSpace(disk, 10_000)).close()
        assertEquals(40_000L, disk.size(dir.resolve("movie.bin.part")))
    }

    @Test
    fun aFinalNameTakenMeanwhileIsNeverOverwritten() {
        val (meta, data) = single(1_000)
        val store = open(meta, resume = true)
        writeAll(store, meta, data)
        Files.writeString(dir.resolve("movie.bin"), "a newcomer")
        assertEquals("movie (1).bin", store.finish())
        assertEquals("a newcomer", Files.readString(dir.resolve("movie.bin")))
        assertContentEquals(data, Files.readAllBytes(dir.resolve("movie (1).bin")))
    }

    @Test
    fun aFolderWhoseNameWasTakenMeanwhileMovesItsFilesToAFreeFolder() {
        val (meta, data) = multi()
        val store = open(meta, resume = true)
        writeAll(store, meta, data)
        Files.createDirectories(dir.resolve("album/sub"))
        Files.writeString(dir.resolve("album/c.bin"), "mine")
        assertEquals("album (1)", store.finish())
        assertEquals("mine", Files.readString(dir.resolve("album/c.bin")))
        assertContentEquals(data.copyOfRange(20_000, 50_000), Files.readAllBytes(dir.resolve("album (1)/sub/b.bin")))
        assertEquals(0L, Files.size(dir.resolve("album (1)/sub/empty")))
        assertFalse(Files.exists(dir.resolve("album/a.bin.part")))
    }

    @Test
    fun aTorrentOfNothingFinishesWithItsEmptyFile() {
        val built = torrentOf(listOf("e.txt" to 0), piece, ByteArray(0), name = "e.txt", single = true)
        val meta = assertIs<TorrentResult.Ok>(readTorrent(built.bytes)).meta
        val store = open(meta)
        store.finish()
        assertEquals(0L, Files.size(dir.resolve("e.txt")))
    }

    @Test
    fun writingTheWrongNumberOfBytesIsRefusedAndNothingIsWritten() {
        val (meta, _) = single(40_000)
        val store = open(meta)
        assertFailsWith<IllegalArgumentException> { store.writePiece(0, ByteArray(10)) }
        assertFailsWith<IllegalArgumentException> { store.writePiece(7, ByteArray(piece)) }
        assertFailsWith<IllegalArgumentException> { store.writePiece(-1, ByteArray(piece)) }
        store.close()
        assertTrue(Files.readAllBytes(dir.resolve("movie.bin.part")).all { it == 0.toByte() })
    }

    @Test
    fun theLastPieceIsShortAndFitsExactly() {
        val (meta, data) = single(40_000) // 16384 + 16384 + 7232
        assertEquals(3, meta.pieceCount)
        val store = open(meta)
        store.writePiece(2, data.copyOfRange(2 * piece, 40_000))
        assertContentEquals(data.copyOfRange(2 * piece, 40_000), store.readPiece(2))
        store.close()
    }

    @Test
    fun closeCanBeCalledAgainAndFinishAfterCloseStillRenames() {
        val (meta, data) = single(1_000)
        val store = open(meta)
        writeAll(store, meta, data)
        store.close()
        store.close()
        store.finish()
        assertContentEquals(data, Files.readAllBytes(dir.resolve("movie.bin")))
    }

    // NFR4: the pieces are written from the blocks they arrived in
    @Test
    fun aPieceGivenAsBlocksIsWrittenAcrossFilesLikeOneGivenWhole() {
        val (meta, data) = multi()
        val store = open(meta)
        for (i in 0 until meta.pieceCount) {
            val whole = data.copyOfRange(i * piece, i * piece + meta.pieceSize(i).toInt())
            // blocks that do not line up with the files' borders
            store.writePiece(i, (whole.indices step 4_000).map { whole.copyOfRange(it, minOf(whole.size, it + 4_000)) })
            assertContentEquals(whole, store.readPiece(i), "piece $i")
        }
        assertFailsWith<IllegalArgumentException> { store.writePiece(0, listOf(ByteArray(10))) }
        assertFailsWith<IllegalArgumentException> { store.writePiece(0, emptyList()) }
        store.finish()
        assertContentEquals(data.copyOfRange(20_000, 50_000), Files.readAllBytes(dir.resolve("album/sub/b.bin")))
    }

    /** A torrent of the files [names] (20,000 bytes each) under `album`, and its content. */
    private fun named(vararg names: String): Pair<TorrentMeta, ByteArray> {
        val data = contentOf(20_000 * names.size, 5)
        val built = torrentOf(names.map { it to 20_000 }, piece, data, name = "album")
        return assertIs<TorrentResult.Ok>(readTorrent(built.bytes)).meta to data
    }

    @Test
    fun aFileNamedLikeAnotherFilesPartFileGetsAnotherPartNameAndNoDataIsLost() {
        for (names in listOf(listOf("a", "a.part"), listOf("a.part", "a"))) {
            val (meta, data) = named(*names.toTypedArray())
            val store = open(meta, name = "album${names.first()}")
            val root = dir.resolve("album${names.first()}")
            assertEquals(names.size, Files.list(root).use { it.count() }.toInt(), "one part file for each file: $names")
            writeAll(store, meta, data)
            store.finish()
            for ((i, n) in names.withIndex()) {
                assertContentEquals(data.copyOfRange(i * 20_000, (i + 1) * 20_000), Files.readAllBytes(root.resolve(n)), "$n of $names")
            }
            assertEquals(
                names.toSet(),
                Files
                    .list(root)
                    .use { s ->
                        s.map { it.fileName.toString() }.toList()
                    }.toSet(),
                "nothing else is left",
            )
        }
    }

    @Test
    fun twoFilesWhoseNamesDifferOnlyInCaseAreRefusedBeforeAnythingIsWrittenSinceTheyMayBeOneFileOnDisk() {
        val (meta, _) = named("A", "a")
        assertEquals(Reasons.NOT_WRITABLE, assertFailsWith<Fail> { open(meta) }.reason)
        assertTrue(Files.walk(dir).use { s -> s.noneMatch { Files.isRegularFile(it) } }, "no file is left behind")
    }

    // R14: a finish that failed half way
    @Test
    fun aResumeAfterAFinishThatStoppedHalfWayMakesNoPartFileForAFileAlreadyRenamedAndFinishesTheRest() {
        val (meta, data) = multi()
        val store = open(meta)
        writeAll(store, meta, data)
        store.close()
        // what finish() leaves when it fails after the first file
        Files.move(dir.resolve("album/a.bin.part"), dir.resolve("album/a.bin"))
        val again = open(meta, resume = true)
        assertEquals("album", again.root)
        assertFalse(Files.exists(dir.resolve("album/a.bin.part")), "no zero-filled part file beside the file that is already final")
        assertContentEquals(data.copyOfRange(0, piece), again.readPiece(0), "its data is read where it is")
        assertEquals("album", again.finish(), "the files already in place are not in the way")
        assertContentEquals(data.copyOfRange(0, 20_000), Files.readAllBytes(dir.resolve("album/a.bin")))
        assertContentEquals(data.copyOfRange(20_000, 50_000), Files.readAllBytes(dir.resolve("album/sub/b.bin")))
        assertContentEquals(data.copyOfRange(50_000, 50_005), Files.readAllBytes(dir.resolve("album/c.bin")))
        assertTrue(Files.walk(dir).use { s -> s.noneMatch { it.toString().endsWith(".part") } }, "no .part is left")
    }

    @Test
    fun aFileMissingBothWaysIsStillMadeAgainOnResume() {
        val (meta, data) = multi()
        val store = open(meta)
        writeAll(store, meta, data)
        store.close()
        Files.move(dir.resolve("album/a.bin.part"), dir.resolve("album/a.bin"))
        Files.delete(dir.resolve("album/sub/b.bin.part"))
        open(meta, resume = true).close()
        assertTrue(Files.exists(dir.resolve("album/sub/b.bin.part")), "R14: a missing file restarts from zero")
    }

    @Test
    fun aFinalFileOfAnotherSizeIsNotTakenForTheTorrentsOwn() {
        val (meta, data) = single(1_000)
        Files.writeString(dir.resolve("movie.bin"), "mine, and not 1000 bytes")
        val store = open(meta, resume = true)
        writeAll(store, meta, data)
        assertEquals("movie (1).bin", store.finish())
        assertEquals("mine, and not 1000 bytes", Files.readString(dir.resolve("movie.bin")))
    }
}
