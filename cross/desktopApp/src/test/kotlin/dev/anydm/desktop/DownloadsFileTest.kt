package dev.anydm.desktop

import dev.anydm.engine.http.Span
import dev.anydm.engine.torrent.PieceSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DownloadsFileTest {
    private fun row(
        id: String = "1",
        status: DownloadStatus = DownloadStatus.Paused,
        done: Long = 40,
    ) = DownloadItem(
        id,
        "a.zip",
        100,
        done,
        0,
        status,
        folder = "/abs",
        url = "https://x.test/a.zip",
        etag = "\"e\"",
        lastModified = "Mon",
    )

    private fun file(vararg dtos: DownloadDto) = DownloadsFile(downloads = dtos.toList())

    private fun dto(
        id: String = "1",
        name: String = "a.zip",
        folder: String? = "/abs",
        done: Long = 0,
        url: String? = "https://x.test/a.zip",
        kind: DownloadKind = DownloadKind.Direct,
    ) = DownloadDto(id, url, name, folder, 10, done, DownloadStatus.Paused, null, null, null, kind, null)

    @Test
    fun aListRoundTripsInOrder() {
        val rows = listOf(row("2", DownloadStatus.Failed).copy(error = "HTTP 404"), row("1", DownloadStatus.Finished, 100))
        assertEquals(rows, fromDownloadsFile(toDownloadsFile(rows)))
    }

    @Test
    fun theSimulatedMarkOfAMagnetRowIsSavedAndComesBack() {
        val magnet = DownloadItem("1", "Sintel", 100, 0, 0, DownloadStatus.Paused, DownloadKind.Torrent, simulated = true)
        val saved = toDownloadsFile(listOf(magnet))
        assertEquals(true, saved.downloads.single().sim)
        assertEquals(listOf(magnet), fromDownloadsFile(saved))
        val json = downloadsJson.encodeToString(DownloadsFile.serializer(), saved)
        assertEquals(
            true,
            downloadsJson
                .decodeFromString(DownloadsFile.serializer(), json)
                .downloads
                .single()
                .sim,
        )
    }

    @Test
    fun aRowSavedBeforeTheMarkExistedReadsAsNotSimulated() {
        val old = """{"version":2,"downloads":[{"id":"1","name":"Sintel","status":"Paused","kind":"Torrent"}]}"""
        val rows = fromDownloadsFile(downloadsJson.decodeFromString(DownloadsFile.serializer(), old))
        assertEquals(false, rows.single().simulated)
    }

    @Test
    fun downloadingAndQueuedRowsComeBackPausedWithNoSpeed() {
        val rows = listOf(row("1", DownloadStatus.Downloading).copy(bytesPerSec = 99), row("2", DownloadStatus.Queued))
        val back = fromDownloadsFile(toDownloadsFile(rows))
        assertEquals(listOf(DownloadStatus.Paused, DownloadStatus.Paused), back.map { it.status })
        assertEquals(listOf(0L, 0L), back.map { it.bytesPerSec })
    }

    @Test
    fun finishedAndFailedStatusesStay() {
        val back = fromDownloadsFile(toDownloadsFile(listOf(row("1", DownloadStatus.Finished), row("2", DownloadStatus.Failed))))
        assertEquals(listOf(DownloadStatus.Finished, DownloadStatus.Failed), back.map { it.status })
    }

    @Test
    fun anUnknownSizeIsStoredAsNullAndComesBack() {
        val f = toDownloadsFile(listOf(row().copy(totalBytes = UNKNOWN_SIZE)))
        assertNull(f.downloads.single().total)
        assertEquals(UNKNOWN_SIZE, fromDownloadsFile(f).single().totalBytes)
    }

    @Test
    fun aTorrentRowKeepsItsFiles() {
        val t = DownloadItem("t", "x", 10, 0, 0, DownloadStatus.Paused, DownloadKind.Torrent, files = listOf(SampleFile("a", 5)))
        assertEquals(listOf(t), fromDownloadsFile(toDownloadsFile(listOf(t))))
    }

    @Test
    fun aGoodFileValidates() {
        assertNull(validateDownloads(file(dto("1"), dto("2"))))
    }

    @Test
    fun aTorrentRowNeedsNoUrl() {
        assertNull(validateDownloads(file(dto(url = null, kind = DownloadKind.Torrent))))
    }

    @Test
    fun aFileOfAnotherVersionIsRefusedWithAReason() {
        listOf(DownloadsFile(version = 3), DownloadsFile(version = 0)).forEach {
            assertNotNull(validateDownloads(it), "should be refused: $it")
        }
    }

    private fun usable(vararg dtos: DownloadDto) = fromDownloadsFile(file(*dtos)).map { it.id }

    @Test
    fun anUnusableRowIsDroppedAndTheOthersAreKept() {
        val bad =
            listOf(
                dto("x", name = " "),
                dto("x", folder = "relative/dir"),
                dto("x", done = -1),
                dto("x", url = null),
                dto("x", url = "ftp://x.test/a"),
                dto("x", url = "file:///etc/passwd"),
                dto("", name = "a"),
            )
        bad.forEach {
            assertNull(validateDownloads(file(it)), "the file itself is fine: $it")
            assertEquals(listOf("1", "2"), usable(dto("1"), it, dto("2")), "the bad row should be dropped: $it")
        }
    }

    @Test
    fun aDuplicateIdKeepsTheFirstRowOnly() {
        val rows = fromDownloadsFile(file(dto("1", name = "first"), dto("1", name = "second"), dto("2")))
        assertEquals(listOf("1" to "first", "2" to "a.zip"), rows.map { it.id to it.name })
    }

    @Test
    fun aSchemeInCapitalsIsAnHttpLink() {
        assertEquals(listOf("1", "2"), usable(dto("1", url = "HTTPS://x.test/a.zip"), dto("2", url = "Http://x.test/b")))
    }

    @Test
    fun aVeryLongSignedLinkIsKept() {
        val url = "https://cdn.test/a.zip?sig=" + "x".repeat(10 * 1024)
        assertEquals(listOf("1"), usable(dto("1", url = url)))
        assertEquals(url, fromDownloadsFile(file(dto("1", url = url))).single().url)
    }

    @Test
    fun aRowWithoutAFolderUsesTheDefault() {
        assertNull(validateDownloads(file(dto(folder = null))))
    }

    private val v1Fixture =
        """
        {"version":1,"downloads":[
          {"id":"1","url":"https://x.test/a.zip","name":"a.zip","folder":"/abs","total":100,"done":40,"status":"Paused","etag":"\"e\""}
        ]}
        """.trimIndent()

    @Test
    fun aVersionOneFileLoadsEveryRowWithOneConnectionAndNoRanges() {
        val file = downloadsJson.decodeFromString(DownloadsFile.serializer(), v1Fixture)
        assertNull(validateDownloads(file))
        val row = fromDownloadsFile(file).single()
        assertEquals(1, row.connections)
        assertNull(row.spans)
        assertEquals(40L, row.doneBytes)
    }

    @Test
    fun theNextSaveAfterAVersionOneLoadWritesVersionTwo() {
        val file = downloadsJson.decodeFromString(DownloadsFile.serializer(), v1Fixture)
        val saved = toDownloadsFile(fromDownloadsFile(file))
        assertEquals(2, saved.version)
        assertEquals(
            2,
            downloadsJson
                .decodeFromString(
                    DownloadsFile.serializer(),
                    downloadsJson.encodeToString(DownloadsFile.serializer(), saved),
                ).version,
        )
    }

    @Test
    fun connectionsAndRangesRoundTripThroughTheJson() {
        val spans = listOf(Span(0, 49, 50), Span(50, 99, 10))
        val rows = listOf(row().copy(connections = 4, spans = spans))
        val json = downloadsJson.encodeToString(DownloadsFile.serializer(), toDownloadsFile(rows))
        val back = downloadsJson.decodeFromString(DownloadsFile.serializer(), json)
        assertEquals(rows, fromDownloadsFile(back))
        assertEquals(4, back.downloads.single().connections)
        assertEquals(listOf(RangeDto(0, 49, 50), RangeDto(50, 99, 10)), back.downloads.single().ranges)
    }

    @Test
    fun thePartIdOfARowRoundTripsNextToItsRanges() {
        val rows = listOf(row().copy(connections = 4, spans = listOf(Span(0, 99, 10)), partId = "00112233445566778899aabbccddeeff"))
        val json = downloadsJson.encodeToString(DownloadsFile.serializer(), toDownloadsFile(rows))
        val back = downloadsJson.decodeFromString(DownloadsFile.serializer(), json)
        assertEquals("00112233445566778899aabbccddeeff", back.downloads.single().partId)
        assertEquals(rows, fromDownloadsFile(back))
        assertEquals(DOWNLOADS_VERSION, back.version, "no schema bump")
    }

    @Test
    fun aVersionTwoRowWithRangesButNoPartIdLoadsWithNone() {
        val back = fromDownloadsFile(file(ranged(RangeDto(0, 9, 5)).copy(connections = 4)))
        assertNull(back.single().partId)
        assertEquals(listOf(Span(0, 9, 5)), back.single().spans)
    }

    @Test
    fun aRowWithoutRangesWritesNone() {
        assertNull(toDownloadsFile(listOf(row())).downloads.single().ranges)
    }

    private fun ranged(vararg r: RangeDto) = dto("x").copy(ranges = r.toList())

    @Test
    fun aRowWhoseRangesAreBrokenIsDroppedAndTheOthersAreKept() {
        val bad =
            listOf(
                ranged(RangeDto(0, 5, 1), RangeDto(5, 9, 0)), // overlap
                ranged(RangeDto(5, 9, 0), RangeDto(0, 4, 0)), // out of order
                ranged(RangeDto(0, 9, 11)), // done past its length
                ranged(RangeDto(0, 9, -1)), // negative done
                ranged(RangeDto(0, 10, 0)), // past the total (10 bytes: 0..9)
                ranged(RangeDto(5, 4, 0)), // end before start
                ranged(RangeDto(-1, 4, 0)),
            )
        bad.forEach {
            assertNull(validateDownloads(file(it)), "the file itself is fine: $it")
            assertEquals(listOf("1", "2"), usable(dto("1"), it, dto("2")), "the bad row should be dropped: $it")
        }
    }

    @Test
    fun rangesThatPartitionTheFileAreKeptWhateverTheirProgress() {
        assertEquals(listOf("x"), usable(ranged(RangeDto(0, 3, 4), RangeDto(4, 6, 1), RangeDto(7, 9, 0))))
        assertEquals(listOf("x"), usable(ranged(RangeDto(0, 9, 3))))
    }

    @Test
    fun rangesWithAGapBetweenThemDropTheRow() {
        assertEquals(emptyList(), usable(ranged(RangeDto(0, 3, 4), RangeDto(4, 6, 1), RangeDto(8, 9, 0))))
    }

    @Test
    fun rangesNotStartingAtZeroDropTheRow() {
        assertEquals(emptyList(), usable(ranged(RangeDto(2, 9, 0))))
    }

    @Test
    fun rangesThatStopShortOfTheTotalDropTheRow() {
        assertEquals(emptyList(), usable(ranged(RangeDto(0, 3, 4), RangeDto(4, 6, 0)))) // the total is 10: bytes 7..9 are nobody's
    }

    @Test
    fun anEmptyListOfRangesDropsTheRow() {
        assertEquals(emptyList(), usable(dto("x").copy(ranges = emptyList())))
    }

    @Test
    fun withTheTotalUnknownTheRangesStillHaveToBeContiguousFromZero() {
        fun unknownTotal(vararg r: RangeDto) =
            DownloadDto("x", "https://x.test/a.zip", "a.zip", "/abs", null, 0, DownloadStatus.Paused, ranges = r.toList())
        assertEquals(listOf("x"), usable(unknownTotal(RangeDto(0, 3, 0), RangeDto(4, 6, 0))))
        assertEquals(emptyList(), usable(unknownTotal(RangeDto(0, 3, 0), RangeDto(5, 6, 0))))
    }

    @Test
    fun connectionsOutsideOneToSixteenAreClampedNotDropped() {
        val rows = fromDownloadsFile(file(dto("1").copy(connections = 40), dto("2").copy(connections = 0), dto("3").copy(connections = -5)))
        assertEquals(listOf(16, 1, 1), rows.map { it.connections })
    }

    // Real torrents: `infoHash`, `pieces` (base64) and `pieceCount` are optional fields of the same version (R14, NFR6)
    private val hex = "ab".repeat(20)

    private fun torrent(
        infoHash: String? = hex,
        pieces: PieceSet? = PieceSet.of(10, listOf(0, 3, 9)),
    ) = DownloadItem(
        "t",
        "x",
        100,
        30,
        0,
        DownloadStatus.Paused,
        DownloadKind.Torrent,
        files = listOf(SampleFile("a", 100)),
        infoHash = infoHash,
        pieces = pieces,
    )

    @Test
    fun aRealTorrentRowRoundTripsItsInfoHashAndPiecesThroughTheJson() {
        val t = torrent()
        val dto = toDownloadsFile(listOf(t)).downloads.single()
        assertEquals(listOf(hex, t.pieces!!.toBase64(), 10), listOf(dto.infoHash, dto.pieces, dto.pieceCount))
        val json = downloadsJson.encodeToString(DownloadsFile.serializer(), toDownloadsFile(listOf(t)))
        assertEquals(listOf(t), fromDownloadsFile(downloadsJson.decodeFromString(DownloadsFile.serializer(), json)))
    }

    @Test
    fun aTorrentRowThatNeverRanHasAnInfoHashAndNoPieces() {
        val t = torrent(pieces = null)
        val dto = toDownloadsFile(listOf(t)).downloads.single()
        assertEquals(listOf<Any?>(hex, null, null), listOf(dto.infoHash, dto.pieces, dto.pieceCount))
        assertEquals(listOf(t), fromDownloadsFile(toDownloadsFile(listOf(t))))
    }

    @Test
    fun aRowFromBeforeRealTorrentsLoadsWithNeitherInfoHashNorPieces() {
        val json =
            """{"version":2,"downloads":[{"id":"1","name":"x","total":10,"done":0,"status":"Paused","kind":"Torrent",""" +
                """"files":[{"name":"a","bytes":10}]}]}"""
        val row = fromDownloadsFile(downloadsJson.decodeFromString(DownloadsFile.serializer(), json)).single()
        assertEquals(listOf(DownloadKind.Torrent, null, null), listOf(row.kind, row.infoHash, row.pieces))
    }

    @Test
    fun aRowWithABadInfoHashIsDroppedAndTheOthersAreKept() {
        for (bad in listOf("", "ab", "AB".repeat(20), "zz".repeat(20), "ab".repeat(21))) {
            val f = DownloadsFile(downloads = listOf(toDownloadsFile(listOf(torrent())).downloads.single().copy(infoHash = bad), dto("2")))
            assertEquals(listOf("2"), usableRows(f).map { it.id }, "'$bad'")
        }
    }

    @Test
    fun piecesThatDoNotDecodeForTheirCountBecomeAnEmptyClaimNotAFreshStart() {
        // Null pieces would mean "never ran": a fresh start that picks new file names. A damaged claim is a claim of nothing.
        val good = toDownloadsFile(listOf(torrent())).downloads.single()
        for (bad in listOf(
            good.copy(pieces = "!!!!"),
            good.copy(pieceCount = 999),
            good.copy(pieceCount = null),
            good.copy(pieceCount = -1),
        )) {
            val row = fromDownloadsFile(DownloadsFile(downloads = listOf(bad))).single()
            assertEquals(0, row.pieces?.count, "$bad")
        }
    }

    @Test
    fun theInFlightSetAndThePeersAreNotSaved() {
        val dto = toDownloadsFile(listOf(torrent().copy(inFlight = setOf(1, 2), peers = 5))).downloads.single()
        val back = fromDownloadsFile(DownloadsFile(downloads = listOf(dto))).single()
        assertEquals(listOf(emptySet<Int>(), 0), listOf(back.inFlight, back.peers))
    }

    // AC12: downloads.json as the previous build wrote it. That build kept no link for a magnet row (only a link row had a url), so
    // only rows 1, 2, 6 and 7 are shapes it really wrote. Rows 3, 4 and 5 (a magnet url, marked simulated or not) are hypothetical:
    // no previous build wrote them, they are what the migration must read should a file have one.
    private val magnetHex = "08ada5a7a6183aae1e09d831df6748d566095a10"
    private val magnetUrl = "magnet:?xt=urn:btih:$magnetHex&dn=Sintel&tr=udp%3A%2F%2Ft.example%3A1337"
    private val rowsPreviousBuildWrote =
        """
        {"id":"1","name":"Sintel","total":123,"done":50,"status":"Downloading","kind":"Torrent","sim":true,"files":[{"name":"Sintel.mp4","bytes":123}]},
        {"id":"2","name":"Sintel","total":123,"done":0,"status":"Finished","kind":"Torrent","sim":true},
        {"id":"6","name":"Old","total":100,"done":40,"status":"Paused","kind":"Torrent"},
        {"id":"7","name":"a.zip","url":"https://x.test/a.zip","total":100,"done":0,"status":"Paused"}
        """.trimIndent()
    private val hypotheticalMagnetRows =
        """
        {"id":"3","name":"Marked","url":"$magnetUrl","total":123,"done":50,"status":"Downloading","kind":"Torrent","sim":true,"files":[{"name":"x","bytes":1}]},
        {"id":"4","name":"Unmarked","url":"$magnetUrl","total":123,"done":50,"status":"Paused","kind":"Torrent"},
        {"id":"5","name":"Finished marked","url":"$magnetUrl","total":123,"done":123,"status":"Finished","kind":"Torrent","sim":true}
        """.trimIndent()
    private val oldFileWithHypotheticalMagnetRows =
        """{"version":2,"downloads":[$rowsPreviousBuildWrote,$hypotheticalMagnetRows]}"""

    private fun restored(copies: TorrentFileStore = NoTorrentFiles) =
        restoredRows(
            downloadsJson.decodeFromString(DownloadsFile.serializer(), oldFileWithHypotheticalMagnetRows),
            MemoryDiskIo(),
            "/home/u/Downloads",
            copies,
        ).associateBy { it.id }

    @Test
    fun anOldMagnetRowWithItsLinkBecomesARealMagnetRowAtZeroWhetherMarkedSimulatedOrNot() {
        for (id in listOf("3", "4", "5")) {
            val r = restored().getValue(id)
            assertEquals(
                listOf<Any?>(magnetHex, false, 0L, null, null, UNKNOWN_SIZE, DownloadStatus.Paused, false, null),
                listOf(r.infoHash, r.simulated, r.doneBytes, r.pieces, r.files, r.totalBytes, r.status, r.noCopy, r.error),
                id,
            )
            assertEquals(true, r.awaitingMetadata, id)
            assertEquals(magnetUrl, r.url, id)
        }
    }

    @Test
    fun anOldSimulatedMagnetRowWithNoLinkHasNothingToFetchFromSoItCannotResumeAndKeepsNoSimulation() {
        for (id in listOf("1", "2")) {
            val r = restored().getValue(id)
            assertEquals(
                listOf<Any?>(null, false, 0L, DownloadStatus.Paused, true, false),
                listOf(r.infoHash, r.simulated, r.doneBytes, r.status, r.noCopy, r.awaitingMetadata),
                id,
            )
            // review m3: nothing of the simulation is left to show ("0 of 123 B", a made-up file list)
            assertEquals(listOf<Any?>(UNKNOWN_SIZE, null), listOf(r.totalBytes, r.files), id)
        }
    }

    @Test
    fun anOldTorrentRowWithNoHashAndNoMarkStaysCantResumeAndLinkRowsAreUntouched() {
        val old = restored().getValue("6")
        assertEquals(listOf(true, 40L, DownloadStatus.Paused), listOf(old.noCopy, old.doneBytes, old.status))
        assertEquals(false, restored().getValue("7").noCopy)
    }

    @Test
    fun aMagnetRowWhoseCopyIsThereTakesItsFilesAndTotalAndANeverRunRowItsNameFromIt() {
        val bytes = TestTorrents.bytes("Real", listOf("a.bin" to 40L, "d/b.bin" to 60L))
        val hex = TestTorrents.meta(bytes).infoHashHex
        val url = "magnet:?xt=urn:btih:$hex&tr=udp%3A%2F%2Ft.example%3A1337"
        val copies = FakeTorrentFiles().also { it.saved[hex] = bytes }

        fun rowOf(pieces: PieceSet?) =
            DownloadsFile(
                downloads =
                    listOf(
                        DownloadDto(
                            "1",
                            url,
                            "dn",
                            null,
                            null,
                            0,
                            DownloadStatus.Paused,
                            null,
                            null,
                            null,
                            DownloadKind.Torrent,
                            null,
                            infoHash = hex,
                        ).let { if (pieces == null) it else it.copy(pieces = pieces.toBase64(), pieceCount = pieces.size) },
                    ),
            )
        val fresh = restoredRows(rowOf(null), MemoryDiskIo(), "/h", copies).single()
        assertEquals(listOf("Real", 100L, listOf("a.bin", "d/b.bin")), listOf(fresh.name, fresh.totalBytes, fresh.files?.map { it.name }))
        assertEquals(false, fresh.awaitingMetadata)
        val ran = restoredRows(rowOf(PieceSet.of(8, listOf(0))), MemoryDiskIo(), "/h", copies).single()
        assertEquals(listOf("dn", 100L), listOf(ran.name, ran.totalBytes), "a row that ran keeps the name its files have")
        assertEquals(2, ran.files?.size)
    }

    @Test
    fun aMagnetRowWhoseCopyIsMissingOrUnreadableStaysWaiting() {
        val hex = "ab".repeat(20)
        val row =
            DownloadDto(
                "1",
                "magnet:?xt=urn:btih:$hex&tr=udp%3A%2F%2Ft.example%3A1",
                "dn",
                null,
                null,
                0,
                DownloadStatus.Paused,
                null,
                null,
                null,
                DownloadKind.Torrent,
                null,
                infoHash = hex,
            )
        val junk = FakeTorrentFiles().also { it.saved[hex] = byteArrayOf(1, 2, 3) }
        for (copies in listOf(FakeTorrentFiles(), junk)) {
            val r = restoredRows(DownloadsFile(downloads = listOf(row)), MemoryDiskIo(), "/h", copies).single()
            assertEquals(listOf(true, "dn"), listOf(r.awaitingMetadata, r.name))
        }
    }
}
