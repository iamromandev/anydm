package dev.anydm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewDownloadModelTest {
    private fun direct(raw: String) = assertIs<Preview.Direct>(parsePreview(raw))

    private val all = sintelFiles.map { it.name }.toSet()

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
    fun localhostLinkIsResumableWith400Mb() {
        for (host in listOf("127.0.0.1:8765", "localhost")) {
            val p = direct("http://$host/Project.zip")
            assertTrue(p.resumable)
            assertTrue(p.parallel)
            assertEquals(400L * 1024 * 1024, p.totalBytes)
        }
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
        assertEquals("My Movie", assertIs<Preview.Torrent>(parsePreview("magnet:?xt=urn:btih:a&dn=My+Movie")).name)
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
    fun magnetIsTorrentNamedSintelOrDn() {
        val a = assertIs<Preview.Torrent>(parsePreview("magnet:?xt=urn:btih:abc123"))
        assertEquals("Sintel", a.name)
        assertEquals("DIR", a.badge)
        assertFalse(a.parallel)
        assertEquals(11, a.files.size)
        val b = assertIs<Preview.Torrent>(parsePreview("magnet:?xt=urn:btih:abc&dn=My%20Movie&tr=x"))
        assertEquals("My Movie", b.name)
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
        assertEquals("400.0 MB · resumable", previewDetail(direct("http://localhost/a.zip"), all))
        assertEquals(
            "size unknown · Uses one connection. This server doesn't support parallel downloads.",
            previewDetail(direct("https://x.test/a.zip"), all),
        )
        val t = parsePreview("magnet:?xt=urn:btih:abc")!!
        assertEquals("11 files · 123.3 MB", previewDetail(t, all))
        assertEquals("1 file · 1.5 KB", previewDetail(t, setOf(sintelFiles.last().name)))
    }

    @Test
    fun newDownloadItemForDirectAndTorrent() {
        val d = newDownloadItem(direct("https://x.test/a.zip"), all, "9")
        assertEquals(DownloadItem("9", "a.zip", 0, 0, d.bytesPerSec, DownloadStatus.Downloading), d)
        assertTrue(d.bytesPerSec > 0)
        val r = newDownloadItem(direct("http://localhost/a.zip"), all, "10")
        assertEquals(400L * 1024 * 1024, r.totalBytes)
        val t = newDownloadItem(parsePreview("magnet:?xt=urn:btih:abc")!!, all - "Sintel.mp4", "11")
        assertEquals("Sintel", t.name)
        assertEquals(torrentSummary(sintelFiles, all - "Sintel.mp4").second, t.totalBytes)
    }

    @Test
    fun nextIdIsUniqueAndIgnoresNonNumeric() {
        val items = listOf("1", "7", "x").map { DownloadItem(it, "n", 1, 0, 0, DownloadStatus.Paused) }
        assertEquals("8", nextId(items))
        assertEquals("1", nextId(emptyList()))
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
