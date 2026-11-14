package dev.anydm.desktop

import dev.anydm.engine.http.ProbeResult
import dev.anydm.engine.torrent.MagnetResult
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewDownloadModelTest {
    private fun direct(raw: String) = assertIs<Preview.Direct>(parsePreview(raw))

    private val all = sintelFiles.map { it.name }.toSet()

    private val magnetHex = "08ada5a7a6183aae1e09d831df6748d566095a10"
    private val trackers = "&tr=udp%3A%2F%2Ftracker.example%3A1337&tr=https%3A%2F%2Ft2.example%2Fannounce"
    private val magnetText = "magnet:?xt=urn:btih:$magnetHex&dn=Sintel$trackers"
    private val bareMagnet = "magnet:?xt=urn:btih:$magnetHex$trackers"

    private fun magnet(raw: String = magnetText) = assertIs<Preview.Torrent>(parsePreview(raw))

    @Test
    fun plainLinkIsDirectWithOneConnection() {
        val p = direct("https://example.com/files/archive.zip")
        assertEquals("archive.zip", p.name)
        assertEquals("ZIP", p.badge)
        assertNull(p.totalBytes)
        assertFalse(p.resumable)
        assertFalse(p.parallel)
    }

    @Test
    fun aLinkKeepsItsUrlAndClaimsNothingBeforeTheProbe() {
        for (host in listOf("127.0.0.1:8765", "localhost")) {
            val p = direct("  http://$host/Project.zip ")
            assertEquals("http://$host/Project.zip", p.url)
            assertFalse(p.resumable)
            assertNull(p.totalBytes)
        }
    }

    @Test
    fun aProbeResultReplacesNameSizeAndResumability() {
        val base = direct("https://x.test/download?id=7")
        val ready = probedPreview(base, ProbeState.Ready(ProbeResult("report.pdf", 2048, true)))
        assertEquals(listOf("report.pdf", "PDF", 2048L, true), listOf(ready.name, ready.badge, ready.totalBytes, ready.resumable))
        assertEquals(base.url, ready.url)
        assertEquals(false, ready.awaitingProbe)
        assertEquals(base, probedPreview(base, ProbeState.Failed("HTTP 404")))
    }

    @Test
    fun aPreviewWhoseProbeHasNotAnsweredIsMarkedSoItsNameCanBeAwaited() {
        val base = direct("https://x.test/download?id=7")
        val checking = probedPreview(base, ProbeState.Checking)
        assertEquals(true, checking.awaitingProbe)
        assertEquals(base, checking.copy(awaitingProbe = false))
        assertEquals(false, base.awaitingProbe)
        assertEquals(false, probedPreview(base, ProbeState.Idle).awaitingProbe)
    }

    @Test
    fun inputIsTrimmed() {
        assertEquals("a.iso", direct("  https://x.test/a.iso \n").name)
    }

    @Test
    fun queryFragmentAndTrailingSlashUsePathSegment() {
        assertEquals("a.zip", direct("https://x.test/d/a.zip?token=1#frag").name)
        assertEquals("dir", direct("https://x.test/dir/").name)
    }

    @Test
    fun percentEncodedNamesAreDecodedAndInvalidFallsBack() {
        assertEquals("my file.zip", direct("https://x.test/my%20file.zip").name)
        assertEquals("100%.zip", direct("https://x.test/100%.zip").name)
    }

    @Test
    fun validPortsAreAccepted() {
        assertEquals("x", direct("http://host:65535/x").name)
        assertEquals("host", direct("http://host:8080").name)
    }

    @Test
    fun badEscapeOrBlankDecodedNameFallsBackToRawSegment() {
        assertEquals("a%FF.zip", direct("https://x.test/a%FF.zip").name)
        assertEquals("%20", direct("https://x.test/%20").name)
    }

    @Test
    fun magnetDnDecodesPlusAsSpace() {
        assertEquals("My Movie", magnet("magnet:?xt=urn:btih:$magnetHex&dn=My+Movie$trackers").name)
    }

    @Test
    fun emptyPathUsesHostAndFileBadge() {
        val p = direct("https://example.com")
        assertEquals("example.com", p.name)
        assertEquals("FILE", p.badge)
    }

    @Test
    fun malformedOrUnknownInputHasNoPreview() {
        for (raw in listOf(
            "",
            "   ",
            "hello world",
            "http://",
            "https:///x",
            "https://a b/c",
            "https://x.test/a b.zip",
            "ftp://x.test/a",
            "magnet:?dn=x",
            "http://localhost:abc/x",
            "http://host:/x",
            "http://host:65536/x",
            "http://host:99999999999999999999/x",
            "http://host:80abc/x",
            "http://host:80x",
        )) {
            assertNull(parsePreview(raw), raw)
        }
    }

    @Test
    fun aMagnetPreviewIsATorrentNamedByDnOrItsHashWithNoFilesAndAnUnknownSize() {
        val a = magnet()
        assertEquals("Sintel", a.name)
        assertEquals(magnetHex, a.infoHash)
        assertEquals(magnetHex, a.magnet?.infoHashHex)
        assertEquals(listOf("udp://tracker.example:1337", "https://t2.example/announce"), a.magnet?.trackers)
        assertEquals("DIR", a.badge)
        assertFalse(a.parallel)
        assertEquals(emptyList(), a.files, "no sample tree for a magnet")
        assertEquals(magnetHex, magnet(bareMagnet).name, "without dn the hash in hex is the name")
        assertEquals("My Movie", magnet("magnet:?xt=urn:btih:$magnetHex&dn=My%20Movie$trackers").name)
    }

    @Test
    fun aMagnetsDetailSaysTheSizeAndFilesAreUnknownUntilTheMetadataArrives() {
        assertEquals("Unknown until metadata arrives", previewDetail(magnet(), emptySet()))
    }

    @Test
    fun aMagnetNameIsShownCleanedOfControlAndDirectionCharacters() {
        assertEquals("evilgpj.exe", magnet("magnet:?xt=urn:btih:$magnetHex&dn=evil%E2%80%AEgpj.exe$trackers").name)
        assertEquals(255, magnet("magnet:?xt=urn:btih:$magnetHex&dn=${"a".repeat(400)}$trackers").name.length)
    }

    @Test
    fun aMagnetThatIsNotUsableHasNoPreviewButAReasonForTheDialog() {
        for (raw in listOf(
            "magnet:?dn=x",
            "magnet:?xt=urn:btih:${magnetHex.drop(1)}$trackers",
            "magnet:xt=urn:btih:$magnetHex",
            "MAGNET:",
        )) {
            assertNull(parsePreview(raw), raw)
            assertEquals("Not a valid magnet link", magnetProblem(raw), raw)
        }
        for (raw in listOf("magnet:?xt=urn:btih:$magnetHex", "magnet:?xt=urn:btih:$magnetHex&tr=wss%3A%2F%2Ft.example")) {
            assertNull(parsePreview(raw), raw)
            assertEquals("This magnet has no trackers", magnetProblem(raw), raw)
        }
        assertNull(magnetProblem(magnetText))
        assertNull(magnetProblem("https://x.test/a.zip"))
        assertNull(magnetProblem("hello"))
    }

    @Test
    fun theLinkAMagnetRowSavesReadsBackAsTheSameMagnet() {
        val link = magnet().magnet!!
        val url = magnetUrl(link)
        assertEquals(
            MagnetResult.Ok(link),
            dev.anydm.engine.torrent
                .parseMagnet(url),
        )
        val odd =
            dev.anydm.engine.torrent.parseMagnet(
                "magnet:?xt=urn:btih:$magnetHex&dn=A%26B+%C3%A9%20c&tr=http%3A%2F%2Ft.example%2Fa%3Fx%3D1%26y%3D2",
            )
        val oddLink = (odd as MagnetResult.Ok).link
        assertEquals(
            MagnetResult.Ok(oddLink),
            dev.anydm.engine.torrent
                .parseMagnet(magnetUrl(oddLink)),
        )
    }

    @Test
    fun sampleTreeHasMp4And10Subtitles() {
        assertEquals(11, sintelFiles.size)
        assertEquals(1, sintelFiles.count { it.name == "Sintel.mp4" })
        val srt = sintelFiles.filter { it.name.endsWith(".srt") }
        assertEquals(10, srt.size)
        assertTrue(srt.all { it.bytes in 1500..1640 })
    }

    @Test
    fun summaryFollowsTicks() {
        val (n, bytes) = torrentSummary(sintelFiles, all)
        assertEquals(11, n)
        assertEquals("123.3 MB", formatBytes(bytes))
        val (n2, b2) = torrentSummary(sintelFiles, all - "Sintel.mp4")
        assertEquals(10, n2)
        assertEquals("15.1 KB", formatBytes(b2))
        assertEquals(0 to 0L, torrentSummary(sintelFiles, emptySet()))
    }

    @Test
    fun detailTextMatchesSpec() {
        val d = direct("https://x.test/a.zip")
        assertEquals("Checking link…", previewDetail(d, all, ProbeState.Checking))
        assertEquals("HTTP 404", previewDetail(d, all, ProbeState.Failed("HTTP 404")))

        fun ready(
            total: Long?,
            resumable: Boolean,
            connections: Int = 4,
        ) = ProbeState.Ready(ProbeResult("a.zip", total, resumable)).let { previewDetail(probedPreview(d, it), all, it, connections) }
        assertEquals("400.0 MB · Resumable · 4 connections", ready(400L * 1024 * 1024, true))
        assertEquals("400.0 MB · Resumable · 1 connection", ready(400L * 1024 * 1024, true, 1))
        assertEquals("size unknown · Resumable", ready(null, true))
        assertEquals("1.0 KB · Uses one connection. This server doesn't support parallel downloads.", ready(1024, false))
    }

    @Test
    fun theDetailKeepsTheOneConnectionTextWhateverTheCount() {
        val d = direct("https://x.test/a.zip")
        val noRange = ProbeState.Ready(ProbeResult("a.zip", 1024, false))
        assertEquals(
            "1.0 KB · Uses one connection. This server doesn't support parallel downloads.",
            previewDetail(probedPreview(d, noRange), all, noRange, 8),
        )
    }

    private fun probedFor(
        total: Long?,
        resumable: Boolean,
    ) = probedPreview(direct("https://x.test/a.zip"), ProbeState.Ready(ProbeResult("a.zip", total, resumable)))

    @Test
    fun aRowGetsTheChosenConnectionsWhenTheServerCanBeSplit() {
        assertEquals(4, newDownloadItem(probedFor(5000, true), all, "1", null, 4).connections)
        assertEquals(16, newDownloadItem(probedFor(5000, true), all, "1", null, 40).connections)
        assertEquals(1, newDownloadItem(probedFor(5000, true), all, "1", null, 0).connections)
    }

    @Test
    fun aRowStaysOnOneConnectionWithoutRangeSupportOrKnownSize() {
        assertEquals(1, newDownloadItem(probedFor(5000, false), all, "1", null, 8).connections)
        assertEquals(1, newDownloadItem(probedFor(null, true), all, "1", null, 8).connections)
        assertEquals(1, newDownloadItem(direct("https://x.test/a.zip"), all, "1", null, 8).connections)
    }

    @Test
    fun aTorrentRowHasNoConnectionsToChoose() {
        assertEquals(1, newDownloadItem(magnet(), all, "1", null, 8).connections)
    }

    @Test
    fun newDownloadItemForDirectAndTorrent() {
        val d = newDownloadItem(direct("https://x.test/a.zip"), all, "9")
        assertEquals(DownloadItem("9", "a.zip", UNKNOWN_SIZE, 0, 0, DownloadStatus.Downloading, url = "https://x.test/a.zip"), d)
        val probed = probedPreview(direct("https://x.test/a.zip"), ProbeState.Ready(ProbeResult("b.zip", 5000, true)))
        val r = newDownloadItem(probed, all, "10", "/data")
        assertEquals(listOf("b.zip", 5000L, "/data"), listOf(r.name, r.totalBytes, r.folder))
        val t = newDownloadItem(magnet(), emptySet(), "11")
        assertEquals("Sintel", t.name)
        assertEquals(UNKNOWN_SIZE, t.totalBytes)
    }

    private fun loaded(
        name: String,
        vararg files: Pair<String, Long>,
        single: Boolean = false,
    ) = LoadedTorrent("$name.torrent", TorrentMeta(name, files.map { (p, n) -> TorrentFile(p, n) }, single))

    @Test
    fun previewOfALoadedTorrentKeepsItsNameAndJoinedPathsInOrder() {
        val p = previewOf(loaded("Show", "Season 1/E01.mkv" to 30L, "notes.txt" to 0L))
        assertEquals("Show", p.name)
        assertEquals(listOf(SampleFile("Season 1/E01.mkv", 30), SampleFile("notes.txt", 0)), p.files)
        assertEquals("2 files · 30 B", previewDetail(p, initialTicked(p)))
    }

    @Test
    fun badgeIsTheFilesOwnForASingleTopLevelFileElseDir() {
        assertEquals("ISO", previewOf(loaded("ubuntu.iso", "ubuntu.iso" to 5L, single = true)).badge)
        assertEquals("FILE", previewOf(loaded("README", "README" to 5L, single = true)).badge)
        assertEquals("DIR", previewOf(loaded("Show", "a.mkv" to 1L, "b.mkv" to 1L)).badge)
        assertEquals("DIR", previewOf(loaded("Show", "Season 1/a.mkv" to 1L)).badge)
        assertEquals("DIR", magnet().badge)
    }

    @Test
    fun aFolderTorrentWithOneEntryIsStillADir() {
        assertEquals("DIR", previewOf(loaded("Show", "episode.mkv" to 5L)).badge)
        assertEquals("DIR", previewOf(loaded("Show", "episode.mkv" to 5L, single = false)).badge)
    }

    @Test
    fun initialTickedComesFromThePreviewsOwnFiles() {
        val real = previewOf(loaded("Show", "Season 1/E01.mkv" to 30L, "notes.txt" to 0L))
        assertEquals(setOf("Season 1/E01.mkv", "notes.txt"), initialTicked(real))
        assertEquals(emptySet(), initialTicked(magnet()))
        assertEquals(emptySet(), initialTicked(parsePreview("https://x.test/a.zip")))
        assertEquals(emptySet(), initialTicked(null))
    }

    @Test
    fun torrentPreviewsBecomeTorrentItemsAndDirectOnesStayDirect() {
        val t = newDownloadItem(magnet(), emptySet(), "1")
        assertEquals(DownloadKind.Torrent, t.kind)
        assertEquals(DownloadKind.Torrent, newDownloadItem(previewOf(loaded("a", "a" to 1L)), setOf("a"), "2").kind)
        assertEquals(DownloadKind.Direct, newDownloadItem(direct("https://x.test/a.zip"), all, "3").kind)
    }

    @Test
    fun aRealTorrentIsTakenWholeWhateverIsTicked() {
        val p = previewOf(loaded("Show", "a" to 100L, "b" to 20L, "d/c" to 3L))
        val item = newDownloadItem(p, setOf("b"), "1")
        assertEquals(123L, item.totalBytes)
        assertEquals(listOf(SampleFile("a", 100), SampleFile("b", 20), SampleFile("d/c", 3)), item.files)
    }

    @Test
    fun aMagnetRowIsARealTorrentRowWaitingForItsMetadata() {
        val item = newDownloadItem(magnet(), emptySet(), "4", "/data")
        assertEquals(
            listOf<Any?>("Sintel", UNKNOWN_SIZE, 0L, 0L, DownloadKind.Torrent, "/data", magnetHex, null, null, false, false),
            listOf(
                item.name,
                item.totalBytes,
                item.doneBytes,
                item.bytesPerSec,
                item.kind,
                item.folder,
                item.infoHash,
                item.files,
                item.pieces,
                item.simulated,
                item.noCopy,
            ),
        )
        assertEquals(DownloadStatus.Downloading, item.status)
        assertTrue(item.awaitingMetadata)
        assertEquals(
            MagnetResult.Ok(magnet().magnet!!),
            dev.anydm.engine.torrent
                .parseMagnet(checkNotNull(item.url)),
        )
    }

    private val hash = ByteArray(20) { (it + 1).toByte() }
    private val hex = hash.joinToString("") { "%02x".format(it) }

    @Test
    fun aLoadedTorrentsPreviewCarriesItsInfoHashAndSourceAndIsLocked() {
        val path =
            java.nio.file.Path
                .of("/t/show.torrent")
        val meta = TorrentMeta("Show", listOf(TorrentFile("a", 1)), infoHash = hash)
        val p = previewOf(LoadedTorrent("show.torrent", meta, path))
        assertEquals(listOf<Any?>(hex, path, true), listOf(p.infoHash, p.source, p.locked))
        assertEquals(true, magnet().locked, "a magnet is a real torrent too")
    }

    @Test
    fun aRealTorrentRowCarriesItsInfoHashStartsAtZeroSpeedAndHasNoSimulatedSpeed() {
        val meta = TorrentMeta("Show", listOf(TorrentFile("a", 5)), infoHash = hash)
        val item = newDownloadItem(previewOf(LoadedTorrent("s.torrent", meta)), setOf("a"), "1", "/data")
        assertEquals(
            listOf<Any?>(hex, 0L, DownloadKind.Torrent, "/data", null),
            listOf(item.infoHash, item.bytesPerSec, item.kind, item.folder, item.pieces),
        )
        assertEquals(magnetHex, newDownloadItem(magnet(), emptySet(), "2").infoHash)
        assertEquals(0L, newDownloadItem(magnet(), emptySet(), "2").bytesPerSec)
    }

    @Test
    fun aTorrentWithNoUsableTrackerHasNoTrackers() {
        val files = listOf(TorrentFile("a", 1))
        assertEquals(false, hasTrackers(TorrentMeta("x", files)))
        assertEquals(false, hasTrackers(TorrentMeta("x", files, trackers = listOf(emptyList()))))
        assertEquals(true, hasTrackers(TorrentMeta("x", files, trackers = listOf(emptyList(), listOf("udp://t.test:80")))))
    }

    @Test
    fun aMagnetItemHasNoFilesUntilTheMetadataArrivesAndDirectItemsNever() {
        assertNull(newDownloadItem(magnet(), emptySet(), "1").files)
        assertNull(newDownloadItem(direct("https://x.test/a.zip"), all, "2").files)
    }

    @Test
    fun newDownloadItemCarriesItsFolder() {
        assertEquals("/Users/x/Movies", newDownloadItem(direct("https://x.test/a.zip"), all, "1", "/Users/x/Movies").folder)
        assertNull(newDownloadItem(direct("https://x.test/a.zip"), all, "1").folder)
    }

    @Test
    fun nextIdIsUniqueAndIgnoresNonNumeric() {
        val items = listOf("1", "7", "x").map { DownloadItem(it, "n", 1, 0, 0, DownloadStatus.Paused) }
        assertEquals("8", nextId(items))
        assertEquals("1", nextId(emptyList()))
    }

    @Test
    fun noNewRowIsMarkedSimulated() {
        assertEquals(false, newDownloadItem(magnet(), emptySet(), "1").simulated)
        assertEquals(false, newDownloadItem(parsePreview("https://x.test/a.zip")!!, emptySet(), "2").simulated)
        val real = Preview.Torrent("Show", listOf(SampleFile("a", 5)), false, "ab".repeat(20), null)
        assertEquals(false, newDownloadItem(real, emptySet(), "3").simulated)
    }

    @Test
    fun anIssuedIdIsNeverGivenAgainEvenWhenItsRowIsGone() {
        val ids = IdIssuer()
        val row = { id: String -> DownloadItem(id, "n", 1, 0, 0, DownloadStatus.Paused) }
        assertEquals("1", ids.next(emptyList()))
        assertEquals("2", ids.next(listOf(row("1"))))
        assertEquals("3", ids.next(emptyList()), "rows 1 and 2 were removed")
    }

    @Test
    fun anIdIssuerGoesPastTheLargestIdOfTheListToo() {
        val ids = IdIssuer()
        assertEquals("9", ids.next(listOf("8", "x").map { DownloadItem(it, "n", 1, 0, 0, DownloadStatus.Paused) }))
        assertEquals("10", ids.next(emptyList()))
    }
}

class PasteShortcutTest {
    @Test
    fun handledOnlyWhenDialogClosedAndTextIsLinkOrMagnet() {
        for (t in listOf("http://a.b/c", "https://a.b/c", "magnet:?xt=urn:btih:a", "  HTTPS://a.b ")) {
            assertEquals(t.trim(), pasteLink(t, dialogOpen = false), t)
            assertNull(pasteLink(t, dialogOpen = true), t)
        }
        for (t in listOf("hello", "", "ftp://a.b/c")) assertNull(pasteLink(t, dialogOpen = false), t)
        assertNull(pasteLink(null, dialogOpen = false))
    }
}
