package dev.anydm.desktop

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
        listOf(DownloadsFile(version = 2), DownloadsFile(version = 0)).forEach {
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
}
