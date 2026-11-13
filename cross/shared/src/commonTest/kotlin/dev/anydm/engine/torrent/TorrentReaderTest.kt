package dev.anydm.engine.torrent

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

class TorrentReaderTest {
    private fun read(bytes: ByteArray): TorrentMeta = assertIs<TorrentResult.Ok>(readTorrent(bytes)).meta

    private fun reason(bytes: ByteArray): String = assertIs<TorrentResult.Err>(readTorrent(bytes)).reason

    /** The piece entries of a one-byte torrent, for tests that write the `info` dictionary by hand. */
    private fun onePiece() = arrayOf("piece length" to bint(16_384), "pieces" to bstr(ByteArray(20)))

    @Test
    fun singleFileTorrentHasOneFileNamedAfterTheTorrent() {
        val m = read(singleTorrent("ubuntu.iso", 5_700_000_000L))
        assertEquals("ubuntu.iso", m.name)
        assertEquals(listOf(TorrentFile("ubuntu.iso", 5_700_000_000L)), m.files)
        assertEquals(5_700_000_000L, m.totalSize)
    }

    @Test
    fun multiFilePathsAreJoinedWithSlashInOrder() {
        val m =
            read(
                multiTorrent(
                    "Show",
                    listOf(
                        fileEntry(30, "Season 1", "E01.mkv"),
                        fileEntry(10, "notes.txt"),
                        fileEntry(20, "Season 1", "extras", "E02.mkv"),
                    ),
                ),
            )
        assertEquals("Show", m.name)
        assertEquals(
            listOf(TorrentFile("Season 1/E01.mkv", 30), TorrentFile("notes.txt", 10), TorrentFile("Season 1/extras/E02.mkv", 20)),
            m.files,
        )
        assertEquals(60, m.totalSize)
    }

    @Test
    fun zeroLengthFilesAreAccepted() {
        val m = read(multiTorrent("t", listOf(fileEntry(0, "empty.txt"))))
        assertEquals(0, m.totalSize)
        assertEquals(1, m.files.size)
    }

    @Test
    fun utf8NamesDecode() {
        assertEquals("Фильм ✓", read(singleTorrent("Фильм ✓", 1)).name)
    }

    @Test
    fun nameUtf8IsPreferredWhenPresent() {
        val info = bdict("name" to bstr("legacy"), "name.utf-8" to bstr("modern"), "length" to bint(1), *onePiece())
        assertEquals("modern", read(bdict("info" to info)).name)
    }

    @Test
    fun pathUtf8IsPreferredWhenPresent() {
        val file = bdict("length" to bint(1), "path" to blist(bstr("old")), "path.utf-8" to blist(bstr("new"), bstr("f.txt")))
        assertEquals("new/f.txt", read(multiTorrent("t", listOf(file))).files.single().path)
    }

    @Test
    fun invalidUtf8IsReplacedNotRejected() {
        val bad = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())
        val info = bdict("name" to bstr(bad), "length" to bint(1), *onePiece())
        assertEquals("a�b", read(bdict("info" to info)).name)
        val file = bdict("length" to bint(1), "path" to blist(bstr(bad)))
        assertEquals("a�b", read(multiTorrent("t", listOf(file))).files.single().path)
    }

    @Test
    fun inputOverTenMegabytesIsTooLarge() {
        assertEquals("Too large", reason(ByteArray(MAX_TORRENT_BYTES + 1)))
    }

    @Test
    fun notBencodedInputIsRejected() {
        assertEquals("Not bencoded", reason("hello".encodeToByteArray()))
        assertEquals("Not bencoded", reason(ByteArray(0)))
        assertEquals("Not bencoded", reason(singleTorrent() + "x".encodeToByteArray()))
    }

    @Test
    fun missingOrWrongTypedInfoIsRejected() {
        assertEquals("Missing info", reason(bdict("announce" to bstr("x"))))
        assertEquals("Missing info", reason(bdict("info" to bstr("x"))))
        assertEquals("Missing info", reason(bint(5)))
        assertEquals("Missing info", reason(blist()))
    }

    @Test
    fun missingOrWrongTypedNameIsRejected() {
        assertEquals("Missing name", reason(bdict("info" to bdict("length" to bint(1)))))
        assertEquals("Missing name", reason(bdict("info" to bdict("name" to bint(1), "length" to bint(1)))))
        assertEquals("Missing name", reason(bdict("info" to bdict("name" to bstr(""), "length" to bint(1)))))
    }

    @Test
    fun missingLengthAndFilesIsRejected() {
        assertEquals("Missing files", reason(bdict("info" to bdict("name" to bstr("a")))))
        assertEquals("Missing files", reason(bdict("info" to bdict("name" to bstr("a"), "files" to bstr("x")))))
        assertEquals("Missing files", reason(multiTorrent("a", emptyList())))
    }

    @Test
    fun badFileSizesAreRejected() {
        assertEquals("Bad file size", reason(singleTorrent(length = -1)))
        assertEquals("Bad file size", reason(bdict("info" to bdict("name" to bstr("a"), "length" to bstr("5")))))
        assertEquals("Bad file size", reason(multiTorrent("t", listOf(fileEntry(-5, "a")))))
        assertEquals("Bad file size", reason(multiTorrent("t", listOf(bdict("path" to blist(bstr("a")))))))
        assertEquals("Bad file size", reason(multiTorrent("t", listOf(bdict("length" to bstr("1"), "path" to blist(bstr("a")))))))
    }

    @Test
    fun totalSizeThatOverflowsIsRejected() {
        val big = Long.MAX_VALUE / 2 + 1
        assertEquals("Bad file size", reason(multiTorrent("t", listOf(fileEntry(big, "a"), fileEntry(big, "b")))))
        // A total that fits a Long is still no torrent: its piece hashes could never fit the file. The size maths must not wrap.
        assertEquals("Bad pieces", reason(multiTorrent("t", listOf(fileEntry(Long.MAX_VALUE - 1, "a"), fileEntry(1, "b")))))
    }

    @Test
    fun badPathsAreRejected() {
        val bad =
            listOf(
                listOf(""),
                listOf("."),
                listOf(".."),
                listOf("a", "..", "b"),
                listOf("a/b"),
                listOf("a\\b"),
                listOf("/etc"),
                listOf("a", ""),
                emptyList(),
            )
        bad.forEach { path ->
            val file = bdict("length" to bint(1), "path" to blist(path.map { bstr(it) }))
            assertEquals("Bad file path", reason(multiTorrent("t", listOf(file))), "path $path")
        }
        assertEquals("Bad file path", reason(multiTorrent("t", listOf(bdict("length" to bint(1))))))
        assertEquals("Bad file path", reason(multiTorrent("t", listOf(bdict("length" to bint(1), "path" to bstr("a"))))))
        assertEquals("Bad file path", reason(multiTorrent("t", listOf(bdict("length" to bint(1), "path" to blist(bint(1)))))))
        assertEquals("Bad file path", reason(multiTorrent("t", listOf(bint(1)))))
    }

    @Test
    fun dotsInsideAComponentAreFine() {
        assertEquals("..a/b..", read(multiTorrent("t", listOf(fileEntry(1, "..a", "b..")))).files.single().path)
    }

    @Test
    fun duplicatePathsAreRejected() {
        assertEquals("Duplicate file path", reason(multiTorrent("t", listOf(fileEntry(1, "a", "b"), fileEntry(2, "a", "b")))))
        assertEquals("Duplicate file path", reason(multiTorrent("t", listOf(fileEntry(1, "a"), fileEntry(2, "b"), fileEntry(3, "a")))))
    }

    @Test
    fun theFileCountIsCappedAtOneHundredThousand() {
        fun many(n: Int) = multiTorrent("t", List(n) { fileEntry(1, "f$it") })
        assertEquals(MAX_FILES, read(many(MAX_FILES)).files.size)
        assertEquals("Too many files", reason(many(MAX_FILES + 1)))
    }

    @Test
    fun deepNestingIsRejected() {
        assertEquals("Too deeply nested", reason(nestedLists(10_000)))
        val deepInfo = bdict("info" to bdict("name" to bstr("a"), "length" to bint(1), "x" to nestedLists(100)))
        assertEquals("Too deeply nested", reason(deepInfo))
    }

    @Test
    fun hugeDeclaredStringLengthIsRejected() {
        assertEquals("Not bencoded", reason("d4:infod4:name999999999:xee".encodeToByteArray()))
    }

    @Test
    fun everyTruncationOfAValidTorrentReturnsErr() {
        val bytes = multiTorrent("t", listOf(fileEntry(3, "a", "b.txt"), fileEntry(4, "c.txt")))
        read(bytes)
        for (n in 0 until bytes.size) assertIs<TorrentResult.Err>(readTorrent(bytes.copyOf(n)), "prefix of $n bytes")
    }

    @Test
    fun aTenMegabyteValidFixtureReadsInUnderOneSecond() {
        val files = List(90_000) { fileEntry(1_000L + it, "dir${it % 100}", "file-$it.bin") }
        val withoutFiller = multiTorrent("big", files, listOf("pad" to bstr(ByteArray(0))))
        val filler = ByteArray(MAX_TORRENT_BYTES - withoutFiller.size - 16) { (it % 251).toByte() }
        val bytes = multiTorrent("big", files, listOf("pad" to bstr(filler)))
        assertTrue(bytes.size in 10_000_000..MAX_TORRENT_BYTES, "fixture is ${bytes.size} bytes")
        var meta: TorrentMeta? = null
        val took = measureTime { meta = read(bytes) }
        assertEquals(90_000, meta!!.files.size)
        assertTrue(took.inWholeMilliseconds < 1_000, "took $took")
    }

    private fun withComponent(component: String) = multiTorrent("t", listOf(fileEntry(1, "dir", component)))

    private fun withName(name: String) = singleTorrent(name)

    @Test
    fun aNameOfExactly255BytesIsAcceptedAndOneMoreIsRejected() {
        assertEquals(255, read(withName("n".repeat(255))).name.length)
        assertEquals("Name too long", reason(withName("n".repeat(256))))
        // 127 two-byte characters are 254 bytes; 128 are 256 bytes although they are fewer than 255 characters.
        assertEquals(127, read(withName("é".repeat(127))).name.length)
        assertEquals("Name too long", reason(withName("é".repeat(128))))
    }

    @Test
    fun aComponentOfExactly255BytesIsAcceptedAndOneMoreIsRejected() {
        assertEquals("dir/" + "c".repeat(255), read(withComponent("c".repeat(255))).files.single().path)
        assertEquals("Name too long", reason(withComponent("c".repeat(256))))
        assertEquals("Name too long", reason(withComponent("é".repeat(128))))
    }

    @Test
    fun aJoinedPathOfExactly4096BytesIsAcceptedAndOneMoreIsRejected() {
        fun path(lastLen: Int) = List(15) { "a".repeat(255) } + "b".repeat(127) + "c".repeat(lastLen)
        val fits = read(multiTorrent("t", listOf(fileEntry(1, *path(128).toTypedArray())))).files.single().path
        assertEquals(4096, fits.length)
        assertEquals("Path too long", reason(multiTorrent("t", listOf(fileEntry(1, *path(129).toTypedArray())))))
    }

    @Test
    fun theNameFollowsTheSameComponentRulesAsPaths() {
        listOf(
            "a/b",
            "a\\b",
            ".",
            "..",
            "/etc",
            "a:b",
            "C:",
            "a\u0001b",
            "a\u007Fb",
            "a\u0000b",
            "a\u202Eb",
            "a\u2066b",
            "a\u2069b",
        ).forEach {
            assertEquals("Bad name", reason(withName(it)), "name ${it.map { c -> c.code }}")
        }
        assertEquals("Missing name", reason(withName("")))
    }

    @Test
    fun forbiddenCharactersInAComponentAreRejected() {
        val control = (0x00..0x1F).map { it.toChar().toString() } + "\u007F"
        val bidi = ('\u202A'..'\u202E').map { it.toString() } + ('\u2066'..'\u2069').map { it.toString() }
        (control + bidi + ":").forEach {
            assertEquals("Bad file path", reason(withComponent("a${it}b")), "component char ${it.single().code}")
        }
        assertEquals("Bad file path", reason(withComponent("C:")))
    }

    @Test
    fun forbiddenCharactersAreRejectedInEveryNameAndComponent() {
        for (c in listOf('\u0007', '\u202E', ':')) {
            assertEquals("Bad name", reason(withName("x$c")), "name char ${c.code}")
            assertEquals("Bad file path", reason(multiTorrent("t", listOf(fileEntry(1, "ok", "x$c", "f")))), "middle char ${c.code}")
        }
    }

    @Test
    fun characterRangesJustOutsideTheForbiddenOnesAreFine() {
        assertEquals("dir/a b\u2029c\u202Fd\u2065e\u206Af", read(withComponent("a b\u2029c\u202Fd\u2065e\u206Af")).files.single().path)
    }

    @Test
    fun aPathThatIsAPrefixOfAnotherFileIsAConflict() {
        assertEquals("Bad file path", reason(multiTorrent("t", listOf(fileEntry(1, "a"), fileEntry(1, "a", "b")))))
        assertEquals("Bad file path", reason(multiTorrent("t", listOf(fileEntry(1, "a", "b"), fileEntry(1, "a")))))
        assertEquals(
            "Bad file path",
            reason(multiTorrent("t", listOf(fileEntry(1, "x", "a"), fileEntry(1, "y"), fileEntry(1, "x", "a", "b", "c")))),
        )
    }

    @Test
    fun siblingsWithACommonTextPrefixAreNotConflicts() {
        val m = read(multiTorrent("t", listOf(fileEntry(1, "a"), fileEntry(1, "a b"), fileEntry(1, "a.b", "c"), fileEntry(1, "ab", "c"))))
        assertEquals(4, m.files.size)
    }

    @Test
    fun aV2OnlyTorrentWithAFileTreeAndNoFilesIsMissingFiles() {
        val tree = bdict("a.txt" to bdict("" to bdict("length" to bint(3))))
        val info = bdict("name" to bstr("v2"), "file tree" to tree, "meta version" to bint(2), "piece length" to bint(16384))
        assertEquals("Missing files", reason(bdict("info" to info)))
    }

    @Test
    fun aSingleFileTorrentIsMarkedSingleAndAMultiFileOneWithOneEntryIsNot() {
        assertTrue(read(singleTorrent("a.iso", 5)).isSingle)
        assertFalse(read(multiTorrent("d", listOf(fileEntry(5, "a.iso")))).isSingle)
    }

    @Test
    fun hostileNestedEmptyValuesAreTooComplex() {
        fun inInfo(junk: ByteArray) =
            concat(listOf("d4:infod6:lengthi1e4:name1:a1:xl".encodeToByteArray(), junk, "eee".encodeToByteArray()))
        for (unit in listOf("le", "0:", "de")) {
            assertEquals("Too complex", reason(inInfo(repeated(unit, 5_000_000))), "unit $unit")
        }
    }

    @Test
    fun aHundredThousandFileTorrentStillReadsUnderOneSecond() {
        val files = List(MAX_FILES) { fileEntry(1_000L + it, "dir${it % 100}", "sub${it % 7}", "file-$it.bin") }
        val bytes = multiTorrent("big", files)
        var meta: TorrentMeta? = null
        val took = measureTime { meta = read(bytes) }
        assertEquals(MAX_FILES, meta!!.files.size)
        assertTrue(took.inWholeMilliseconds < 1_000, "took $took")
    }

    @Test
    fun theInfoHashIsTheSha1OfTheInfoDictionaryAsWritten() {
        val built = torrentOf(listOf("a.bin" to 40_000), 16_384, contentOf(40_000), single = true)
        val m = read(built.bytes)
        assertEquals(20, m.infoHash.size)
        assertContentEquals(sha1(built.info), m.infoHash)
        assertEquals(m.infoHash.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }, m.infoHashHex)
        assertEquals(40, m.infoHashHex.length)
    }

    @Test
    fun theInfoHashCoversTheRawBytesNotACanonicalReEncode() {
        // Keys out of order: name before length. A reader that re-encodes would sort them and hash something else.
        val pieces = sha1(ByteArray(5))
        val info =
            concat(
                listOf(
                    "d4:name1:a".encodeToByteArray(),
                    "6:lengthi5e".encodeToByteArray(),
                    "12:piece lengthi16384e".encodeToByteArray(),
                    "6:pieces20:".encodeToByteArray(),
                    pieces,
                    "e".encodeToByteArray(),
                ),
            )
        val file = concat(listOf("d4:info".encodeToByteArray(), info, "8:url-list0:e".encodeToByteArray()))
        val m = read(file)
        assertContentEquals(sha1(info), m.infoHash)
        val decoded = assertIs<DecodeResult.Ok>(decodeBencode(info)).value
        assertFalse(sha1(encodeBencode(decoded)).contentEquals(m.infoHash), "the sorted re-encode must differ from the raw bytes")
    }

    @Test
    fun theInfoHashIgnoresWhatSurroundsTheInfoDictionary() {
        val built = torrentOf(listOf("a.bin" to 100), 16_384, contentOf(100), single = true)
        val withTrackers = torrentOf(listOf("a.bin" to 100), 16_384, contentOf(100), single = true, announce = "http://t/a")
        assertContentEquals(read(built.bytes).infoHash, read(withTrackers.bytes).infoHash)
    }

    @Test
    fun pieceLengthAndPieceHashesAreReadFromTheFile() {
        val data = contentOf(40_000, seed = 3)
        val m = read(torrentOf(listOf("a.bin" to 40_000), 16_384, data, single = true).bytes)
        assertEquals(16_384L, m.pieceLength)
        assertEquals(3, m.pieceHashes.size)
        assertEquals(3, m.pieceCount)
        assertContentEquals(sha1(data, 0, 16_384), m.pieceHashes[0])
        assertContentEquals(sha1(data, 16_384, 16_384), m.pieceHashes[1])
        assertContentEquals(sha1(data, 32_768, 40_000 - 32_768), m.pieceHashes[2])
    }

    @Test
    fun pieceSizesAreFullExceptForAShortLastPiece() {
        val m = read(torrentOf(listOf("a.bin" to 40_000), 16_384, contentOf(40_000), single = true).bytes)
        assertEquals(listOf(16_384L, 16_384L, 40_000L - 32_768), List(3) { m.pieceSize(it) })
        assertEquals(0L, m.pieceSize(-1))
        assertEquals(0L, m.pieceSize(3))
        val exact = read(torrentOf(listOf("a.bin" to 32_768), 16_384, contentOf(32_768), single = true).bytes)
        assertEquals(listOf(16_384L, 16_384L), List(exact.pieceCount) { exact.pieceSize(it) })
        assertEquals(exact.totalSize, List(exact.pieceCount) { exact.pieceSize(it) }.sum())
    }

    @Test
    fun aMultiFileTorrentHashesPiecesAcrossFileBorders() {
        val data = contentOf(50_000, seed = 5)
        val built = torrentOf(listOf("d/a" to 20_000, "e" to 0, "d/b" to 30_000), 16_384, data)
        val m = read(built.bytes)
        assertEquals(listOf("d/a", "e", "d/b"), m.files.map { it.path })
        assertEquals(4, m.pieceCount)
        assertContentEquals(sha1(data, 16_384, 16_384), m.pieceHashes[1])
        assertContentEquals(sha1(data, 49_152, 50_000 - 49_152), m.pieceHashes[3])
    }

    @Test
    fun trackersKeepTheirTiersAndOrder() {
        val tiers = listOf(listOf("udp://a:1/announce", "http://b/announce"), listOf("https://c/announce"))
        val m =
            read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, announce = "http://ignored/", announceList = tiers).bytes)
        assertEquals(tiers, m.trackers)
    }

    @Test
    fun withoutAnAnnounceListTheAnnounceUrlIsTheOnlyTracker() {
        val m = read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, announce = "http://t/announce").bytes)
        assertEquals(listOf(listOf("http://t/announce")), m.trackers)
    }

    @Test
    fun noTrackersAtAllIsAnEmptyList() {
        assertEquals(emptyList(), read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true).bytes).trackers)
        assertEquals(emptyList(), read(singleTorrent()).trackers)
    }

    @Test
    fun trackersWithOtherSchemesOrNoHostAreDroppedAndEmptyTiersVanish() {
        val tiers =
            listOf(
                listOf("wss://a/x", "ftp://b/x", "http://", "", "magnet:?x"),
                listOf("UDP://Keep:80/a", "udp://also:6969"),
                listOf("http://has space/x", "http://ctl\u0001/x", "http://é/x"),
            )
        val m = read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, announceList = tiers).bytes)
        assertEquals(listOf(listOf("udp://Keep:80/a", "udp://also:6969")), m.trackers)
    }

    @Test
    fun trackerUrlsAreStoredWithTheSchemeInLowerCaseAndTheRestAsWritten() {
        val tiers = listOf(listOf("UDP://Keep:80/A?X=Y", "HTTP://Host/Announce", "HtTpS://h/p"))
        val m = read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, announceList = tiers).bytes)
        assertEquals(listOf(listOf("udp://Keep:80/A?X=Y", "http://Host/Announce", "https://h/p")), m.trackers)
        val one = read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, announce = "HTTP://T/Announce").bytes)
        assertEquals(listOf(listOf("http://T/Announce")), one.trackers)
    }

    @Test
    fun aPieceLengthOfEightKibIsRefusedAsBadPieces() {
        // The minimum piece length is 16 KiB: a block is 16 KiB, so a smaller piece would make no sense for the picker.
        val info =
            bdict(
                "info" to
                    bdict("name" to bstr("a"), "length" to bint(1), "piece length" to bint(8_192), "pieces" to bstr(ByteArray(20))),
            )
        assertEquals("Bad pieces", reason(info))
    }

    @Test
    fun anAnnounceListWithNothingUsableFallsBackToTheAnnounceUrl() {
        val m =
            read(
                torrentOf(
                    listOf("a" to 1),
                    16_384,
                    contentOf(1),
                    single = true,
                    announce = "http://t/announce",
                    announceList = listOf(listOf("wss://x/")),
                ).bytes,
            )
        assertEquals(listOf(listOf("http://t/announce")), m.trackers)
    }

    @Test
    fun anUnusableAnnounceAndNoListMeansNoTrackers() {
        val m = read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, announce = "wss://x/").bytes)
        assertEquals(emptyList(), m.trackers)
    }

    @Test
    fun malformedAnnounceListEntriesAreSkippedNotFatal() {
        val info = assertIs<DecodeResult.Ok>(decodeBencode(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true).bytes)).value
        val infoBytes = encodeBencode((info as BValue.BDict).entries.getValue("info"))
        val list = blist(bstr("http://notalist/"), blist(bint(1), bstr("http://ok/a")), bint(7))
        val m = read(bdict("announce-list" to list, "info" to infoBytes))
        assertEquals(listOf(listOf("http://ok/a")), m.trackers)
    }

    @Test
    fun anOverlongTrackerUrlIsDropped() {
        val long = "http://h/" + "a".repeat(MAX_TRACKER_URL_BYTES)
        val m =
            read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, announceList = listOf(listOf(long, "http://ok/"))).bytes)
        assertEquals(listOf(listOf("http://ok/")), m.trackers)
    }

    @Test
    fun atMostMaxTrackersAreKept() {
        val tiers = List(MAX_TRACKERS + 50) { listOf("http://t$it/") }
        val m = read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, announceList = tiers).bytes)
        assertEquals(MAX_TRACKERS, m.trackers.sumOf { it.size })
        assertEquals("http://t0/", m.trackers.first().first())
    }

    @Test
    fun thePrivateFlagIsReadAndDefaultsToFalse() {
        fun private(flag: Boolean?) = read(torrentOf(listOf("a" to 1), 16_384, contentOf(1), single = true, private = flag).bytes).private
        assertTrue(private(true))
        assertFalse(private(false))
        assertFalse(private(null))
    }

    @Test
    fun aPiecesStringOfTheWrongLengthIsBadPieces() {
        val pieceLength = 16_384L

        fun withPieces(pieces: ByteArray) =
            bdict(
                "info" to
                    bdict(
                        "name" to bstr("a"),
                        "length" to bint(40_000),
                        "piece length" to bint(pieceLength),
                        "pieces" to bstr(pieces),
                    ),
            )
        assertIs<TorrentResult.Ok>(readTorrent(withPieces(ByteArray(60))))
        for (n in listOf(0, 19, 20, 40, 59, 61, 80, 120)) assertEquals("Bad pieces", reason(withPieces(ByteArray(n))), "$n bytes of hashes")
    }

    @Test
    fun missingOrWrongTypedPiecesAndPieceLengthAreBadPieces() {
        fun info(vararg extra: Pair<String, ByteArray>) = bdict("info" to bdict(listOf("name" to bstr("a"), "length" to bint(1)) + extra))
        assertEquals("Bad pieces", reason(info()))
        assertEquals("Bad pieces", reason(info("piece length" to bint(16_384))))
        assertEquals("Bad pieces", reason(info("pieces" to bstr(ByteArray(20)))))
        assertEquals("Bad pieces", reason(info("piece length" to bint(16_384), "pieces" to bint(5))))
        assertEquals("Bad pieces", reason(info("piece length" to bstr("16384"), "pieces" to bstr(ByteArray(20)))))
    }

    @Test
    fun thePieceLengthMustBeAPowerOfTwoFromSixteenKibToSixtyFourMib() {
        fun withPieceLength(pieceLength: Long) =
            bdict(
                "info" to
                    bdict("name" to bstr("a"), "length" to bint(1), "piece length" to bint(pieceLength), "pieces" to bstr(ByteArray(20))),
            )
        for (ok in listOf(16_384L, 32_768L, 1L shl 20, 64L shl 20)) assertIs<TorrentResult.Ok>(readTorrent(withPieceLength(ok)), "$ok")
        for (bad in listOf(0L, -16_384L, 1L, 8_192L, 16_383L, 3 * 16_384L, 24_576L, 100_000L, 128L shl 20, Long.MAX_VALUE, 1L shl 62)) {
            assertEquals("Bad pieces", reason(withPieceLength(bad)), "piece length $bad")
        }
    }

    @Test
    fun aTorrentOfNothingHasNoPiecesAndNeedsNone() {
        fun empty(pieces: ByteArray) =
            bdict(
                "info" to
                    bdict(
                        "name" to bstr("a"),
                        "files" to blist(fileEntry(0, "x")),
                        "piece length" to bint(16_384),
                        "pieces" to bstr(pieces),
                    ),
            )
        val m = read(empty(ByteArray(0)))
        assertEquals(0, m.pieceCount)
        assertEquals(emptyList(), m.pieceHashes)
        assertEquals("Bad pieces", reason(empty(ByteArray(20))))
    }

    @Test
    fun pieceCountMathDoesNotWrapForHugeTotals() {
        // 2^62 bytes in 16 KiB pieces would need 2^48 hashes; the file has one.
        val info = bdict("name" to bstr("a"), "length" to bint(1L shl 62), "piece length" to bint(16_384), "pieces" to bstr(ByteArray(20)))
        assertEquals("Bad pieces", reason(bdict("info" to info)))
    }

    @Test
    fun metasWithTheSameContentAreEqualWhateverTheArrayIdentity() {
        val a =
            TorrentMeta(
                "n",
                listOf(TorrentFile("n", 1)),
                true,
                ByteArray(20) { 1 },
                16_384,
                listOf(ByteArray(20) { 2 }),
                listOf(listOf("http://t/")),
            )
        val b =
            TorrentMeta(
                "n",
                listOf(TorrentFile("n", 1)),
                true,
                ByteArray(20) { 1 },
                16_384,
                listOf(ByteArray(20) { 2 }),
                listOf(listOf("http://t/")),
            )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, b.copy(infoHash = ByteArray(20) { 9 }))
        assertNotEquals(a, b.copy(pieceHashes = listOf(ByteArray(20) { 3 })))
    }
}
