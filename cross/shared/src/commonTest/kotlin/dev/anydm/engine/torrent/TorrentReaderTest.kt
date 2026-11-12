package dev.anydm.engine.torrent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.measureTime

class TorrentReaderTest {
    private fun read(bytes: ByteArray): TorrentMeta = assertIs<TorrentResult.Ok>(readTorrent(bytes)).meta

    private fun reason(bytes: ByteArray): String = assertIs<TorrentResult.Err>(readTorrent(bytes)).reason

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
        val info = bdict("name" to bstr("legacy"), "name.utf-8" to bstr("modern"), "length" to bint(1))
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
        val info = bdict("name" to bstr(bad), "length" to bint(1))
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
        val m = read(multiTorrent("t", listOf(fileEntry(Long.MAX_VALUE - 1, "a"), fileEntry(1, "b"))))
        assertEquals(Long.MAX_VALUE, m.totalSize)
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
        val withoutFiller = multiTorrent("big", files, listOf("pieces" to bstr(ByteArray(0))))
        val filler = ByteArray(MAX_TORRENT_BYTES - withoutFiller.size - 16) { (it % 251).toByte() }
        val bytes = multiTorrent("big", files, listOf("pieces" to bstr(filler)))
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
}
