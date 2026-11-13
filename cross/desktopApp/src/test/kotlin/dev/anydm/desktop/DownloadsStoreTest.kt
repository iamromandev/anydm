package dev.anydm.desktop

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Works in a JUnit temp folder: nothing here touches the real app-data folder. */
class DownloadsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: Path get() = tmp.root.toPath().resolve("anydm")
    private val path get() = dir.resolve("downloads.json")

    private val sample =
        DownloadsFile(
            downloads =
                listOf(
                    DownloadDto(
                        "1",
                        "https://x.test/a",
                        "a",
                        "/abs",
                        10,
                        4,
                        DownloadStatus.Paused,
                        null,
                        "\"e\"",
                        null,
                        DownloadKind.Direct,
                        null,
                    ),
                ),
        )

    private fun write(text: String) {
        Files.createDirectories(dir)
        path.writeText(text)
    }

    @Test
    fun aSavedFileLoadsBackEqual() {
        assertEquals(SaveResult.Ok, FileDownloadsStore(dir).save(sample))
        assertEquals(JsonLoad.Loaded(sample), FileDownloadsStore(dir).load())
    }

    @Test
    fun aMissingFileGivesMissingAndCreatesNothing() {
        assertEquals(JsonLoad.Missing, FileDownloadsStore(dir).load())
        assertTrue(!dir.exists())
    }

    @Test
    fun theFileIsDownloadsJsonWithTheVersion() {
        FileDownloadsStore(dir).save(sample)
        assertTrue(path.readText().contains("\"version\": 2"))
    }

    @Test
    fun aCorruptFileIsMovedToBadAndResets() {
        write("{ not json")
        assertIs<JsonLoad.Reset>(FileDownloadsStore(dir).load())
        assertTrue(!path.exists())
        assertEquals("{ not json", dir.resolve("downloads.json.bad").readText())
    }

    @Test
    fun versionNinetyNineIsMovedToBad() {
        write("""{"version":99,"downloads":[]}""")
        assertIs<JsonLoad.Reset>(FileDownloadsStore(dir).load())
        assertTrue(dir.resolve("downloads.json.bad").exists())
    }

    @Test
    fun anInvalidRowDoesNotMakeTheFileBad() {
        write(
            """{"version":1,"downloads":[{"id":"1","name":"","status":"Paused","done":0},{"id":"2","name":"a","url":"https://x.test/a","status":"Paused","done":3}]}""",
        )
        val r = assertIs<JsonLoad.Loaded<DownloadsFile>>(FileDownloadsStore(dir).load())
        assertEquals(listOf("2"), fromDownloadsFile(r.value).map { it.id })
        assertTrue(path.exists())
        assertTrue(!dir.resolve("downloads.json.bad").exists())
    }

    @Test
    fun aRowWithoutANameOrAnIdIsADroppedRowNotABadFile() {
        write("""{"version":1,"downloads":[{"status":"Paused"},{"id":"2","name":"a","url":"https://x.test/a","status":"Paused"}]}""")
        val r = assertIs<JsonLoad.Loaded<DownloadsFile>>(FileDownloadsStore(dir).load())
        assertEquals(listOf("2"), fromDownloadsFile(r.value).map { it.id })
    }

    @Test
    fun unknownFieldsAreIgnoredAndTheFileStays() {
        write("""{"version":1,"future":true,"downloads":[{"id":"1","name":"a","status":"Paused","done":3,"extra":1,"kind":"Torrent"}]}""")
        val r = assertIs<JsonLoad.Loaded<DownloadsFile>>(FileDownloadsStore(dir).load())
        assertEquals(
            "a",
            r.value.downloads
                .single()
                .name,
        )
        assertTrue(path.exists())
    }

    @Test
    fun anInterruptedWriteKeepsTheOldFile() {
        FileDownloadsStore(dir).save(sample)
        val ops = RecordingFileOps(failOn = "moveAtomic")
        val r = FileDownloadsStore(dir, ops).save(sample.copy(downloads = emptyList()))
        assertIs<SaveResult.Failed>(r)
        assertEquals(JsonLoad.Loaded(sample), FileDownloadsStore(dir).load())
        assertEquals(listOf("downloads.json"), dir.listDirectoryEntries().map { it.name })
    }

    @Test
    fun theStoresCleanOnlyTheirOwnLeftOverTemps() {
        Files.createDirectories(dir)
        val mine = dir.resolve("downloads123.tmp").also { it.writeText("x") }
        val theirs = dir.resolve("settings123.tmp").also { it.writeText("x") }
        FileDownloadsStore(dir).load()
        assertTrue(!mine.exists())
        assertTrue(theirs.exists())
    }

    @Test
    fun theTempFileOfADownloadsSaveIsNamedAfterDownloads() {
        val ops = RecordingFileOps()
        FileDownloadsStore(dir, ops).save(sample)
        assertTrue(
            ops.temps
                .single()
                .name
                .startsWith("downloads"),
        )
    }

    @Test
    fun theInMemoryStoreKeepsWhatWasSaved() {
        val s = InMemoryDownloadsStore()
        assertEquals(JsonLoad.Missing, s.load())
        s.save(sample)
        assertEquals(JsonLoad.Loaded(sample), s.load())
        assertEquals(listOf(sample), s.saves)
    }
}
