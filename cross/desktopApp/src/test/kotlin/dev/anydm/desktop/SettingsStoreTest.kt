package dev.anydm.desktop

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Every test works in a JUnit temp folder: nothing here touches the real app-data folder. */
class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: Path get() = tmp.root.toPath().resolve("anydm")
    private val settingsPath get() = dir.resolve("settings.json")
    private val badPath get() = dir.resolve("settings.json.bad")

    private val sample = SettingsFile(theme = ThemeChoice.Dark, unit = SpeedUnit.Mbps, saveFolder = "/x/y")

    private fun write(
        text: String,
        path: Path = settingsPath,
    ) {
        Files.createDirectories(path.parent)
        path.writeText(text)
    }

    private fun entries() = dir.listDirectoryEntries().map { it.name }.sorted()

    // R8: saving

    @Test
    fun aSavedFileLoadsBackEqual() {
        val store = FileSettingsStore(dir)
        assertEquals(SaveResult.Ok, store.save(sample))
        assertEquals(LoadResult.Loaded(sample), FileSettingsStore(dir).load())
    }

    @Test
    fun theFolderIsCreatedOnTheFirstSave() {
        val nested = tmp.root.toPath().resolve("a/b/anydm")
        assertFalse(nested.exists())
        assertEquals(SaveResult.Ok, FileSettingsStore(nested).save(sample))
        assertTrue(nested.resolve("settings.json").exists())
    }

    @Test
    fun loadingNeverCreatesTheFolder() {
        assertEquals(LoadResult.Missing, FileSettingsStore(dir).load())
        assertFalse(dir.exists())
    }

    @Test
    fun theWrittenFileIsJsonWithTheVersion() {
        FileSettingsStore(dir).save(sample)
        val text = settingsPath.readBytes().decodeToString()
        assertTrue(text.contains("\"version\": 1"), text)
        assertTrue(text.contains("\"saveFolder\": \"/x/y\""), text)
    }

    @Test
    fun noTempFileIsLeftBehind() {
        val store = FileSettingsStore(dir)
        store.save(sample)
        store.save(sample.copy(theme = ThemeChoice.Light))
        assertEquals(listOf("settings.json"), entries())
    }

    @Test
    fun aSaveWritesATempFileInTheSameFolderThenMovesItAtomically() {
        val ops = RecordingFileOps(RealFileOps)
        FileSettingsStore(dir, ops).save(sample)
        assertEquals(listOf("ensureDir", "writeTemp", "moveAtomic"), ops.log)
        val (from, to) = ops.moves.single()
        assertEquals(dir, from.parent)
        assertEquals(settingsPath, to)
        assertTrue(from != to)
    }

    @Test
    fun aFailureBetweenTheTempWriteAndTheMoveKeepsTheOldFileIntact() {
        FileSettingsStore(dir).save(sample)
        val before = settingsPath.readBytes()
        val ops = RecordingFileOps(RealFileOps, failOn = "moveAtomic")
        val result = FileSettingsStore(dir, ops).save(sample.copy(theme = ThemeChoice.Light, saveFolder = "/other"))
        assertIs<SaveResult.Failed>(result)
        assertContentEquals(before, settingsPath.readBytes())
        assertEquals(LoadResult.Loaded(sample), FileSettingsStore(dir).load())
        assertEquals(listOf("settings.json"), entries(), "the temp file is cleaned up")
    }

    @Test
    fun aHalfWrittenTempFileNeverReplacesTheOldOne() {
        FileSettingsStore(dir).save(sample)
        val before = settingsPath.readBytes()
        val ops = RecordingFileOps(RealFileOps, failOn = "writeTemp", partialBytes = 10)
        assertIs<SaveResult.Failed>(FileSettingsStore(dir, ops).save(sample.copy(saveFolder = "/other")))
        assertContentEquals(before, settingsPath.readBytes())
        // A crash can leave the half-written temp file behind; it is never the settings file.
        assertTrue(entries().all { it == "settings.json" || it.endsWith(".tmp") }, entries().toString())
        assertEquals(LoadResult.Loaded(sample), FileSettingsStore(dir).load())
    }

    @Test
    fun aFolderThatCannotBeCreatedFailsTheSaveWithoutThrowing() {
        Files.createDirectories(dir.parent)
        dir.writeText("this is a file, not a folder")
        assertIs<SaveResult.Failed>(FileSettingsStore(dir).save(sample))
        val ops = RecordingFileOps(RealFileOps, failOn = "ensureDir")
        assertIs<SaveResult.Failed>(FileSettingsStore(tmp.root.toPath().resolve("other"), ops).save(sample))
    }

    @Test
    fun theWrittenBytesNeverContainAProxyPassword() {
        val secret = "p4ssw0rd-never-on-disk"
        val nets = listOf(Network("wifi", "Wi-Fi", NetworkKind.WiFi, true, 0, proxy = Proxy(ProxyType.Http, "h.example", 80, "u", secret)))
        val f = toFile(ThemeChoice.System, SpeedUnit.MBps, null, initialLimits(nets, emptyMap()), nets)
        FileSettingsStore(dir).save(f)
        val bytes = settingsPath.readBytes()
        assertFalse(bytes.decodeToString().contains(secret))
        assertEquals(-1, indexOf(bytes, secret.encodeToByteArray()))
    }

    private fun indexOf(
        hay: ByteArray,
        needle: ByteArray,
    ): Int = (0..hay.size - needle.size).firstOrNull { i -> needle.indices.all { hay[i + it] == needle[it] } } ?: -1

    // R9: loading

    @Test
    fun aMissingFileGivesMissing() {
        assertEquals(LoadResult.Missing, FileSettingsStore(dir).load())
    }

    @Test
    fun aCorruptFileIsMovedToBadAndResets() {
        write("{ this is not json")
        val result = FileSettingsStore(dir).load()
        assertIs<LoadResult.Reset>(result)
        assertFalse(settingsPath.exists())
        assertEquals("{ this is not json", badPath.readBytes().decodeToString())
        assertEquals(LoadResult.Missing, FileSettingsStore(dir).load(), "the notice comes once")
    }

    @Test
    fun anInvalidValueIsMovedToBadAndResets() {
        val text = """{"version":1,"networks":[{"id":"wifi","name":"W","proxy":{"type":"Http","host":"h.example","port":70000}}]}"""
        write(text)
        assertIs<LoadResult.Reset>(FileSettingsStore(dir).load())
        assertEquals(text, badPath.readBytes().decodeToString())
        assertFalse(settingsPath.exists())
    }

    @Test
    fun aNewerVersionIsMovedToBadAndResets() {
        val text = """{"version":99,"theme":"Dark"}"""
        write(text)
        assertIs<LoadResult.Reset>(FileSettingsStore(dir).load())
        assertEquals(text, badPath.readBytes().decodeToString())
        assertFalse(settingsPath.exists())
    }

    @Test
    fun anOlderBadFileIsReplaced() {
        write("old bad", badPath)
        write("{ corrupt")
        assertIs<LoadResult.Reset>(FileSettingsStore(dir).load())
        assertEquals("{ corrupt", badPath.readBytes().decodeToString())
    }

    @Test
    fun unknownFieldsAreIgnoredAndTheFileStays() {
        write("""{"version":1,"theme":"Dark","somethingNew":[1,2,3]}""")
        assertEquals(LoadResult.Loaded(SettingsFile(theme = ThemeChoice.Dark)), FileSettingsStore(dir).load())
        assertTrue(settingsPath.exists())
        assertFalse(badPath.exists())
    }

    @Test
    fun aFileOverOneHundredKbResetsWithoutBeingRead() {
        val big = " ".repeat(MAX_SETTINGS_BYTES + 1)
        write("""{"version":1}$big""")
        assertIs<LoadResult.Reset>(FileSettingsStore(dir).load())
        assertTrue(badPath.exists())
        assertFalse(settingsPath.exists())
    }

    @Test
    fun aFileOfExactlyOneHundredKbStillLoads() {
        val json = """{"version":1}"""
        write(json + " ".repeat(MAX_SETTINGS_BYTES - json.length))
        assertEquals(MAX_SETTINGS_BYTES.toLong(), Files.size(settingsPath))
        assertEquals(LoadResult.Loaded(SettingsFile()), FileSettingsStore(dir).load())
    }

    @Test
    fun theReadIsCappedSoAHugeFileIsNeverLoadedWhole() {
        write("""{"version":1}""" + " ".repeat(MAX_SETTINGS_BYTES * 3))
        val ops =
            object : FileOps by RealFileOps {
                var asked = -1

                override fun read(
                    path: Path,
                    maxBytes: Int,
                ): ByteArray? {
                    asked = maxBytes
                    return RealFileOps.read(path, maxBytes)
                }
            }
        FileSettingsStore(dir, ops).load()
        assertEquals(MAX_SETTINGS_BYTES, ops.asked)
    }

    @Test
    fun anUnreadableFileResetsWithoutThrowing() {
        write("""{"version":1}""")
        val ops = RecordingFileOps(RealFileOps, failOn = "read")
        assertIs<LoadResult.Reset>(FileSettingsStore(dir, ops).load())
    }

    @Test
    fun aFailedMoveToBadStillResets() {
        write("{ corrupt")
        val ops = RecordingFileOps(RealFileOps, failOn = "moveReplace")
        assertIs<LoadResult.Reset>(FileSettingsStore(dir, ops).load())
    }

    // S4: no atomic move, and a bad file that stays where it is

    @Test
    fun withoutAnAtomicMoveTheSaveFallsBackToAReplacingMove() {
        FileSettingsStore(dir).save(sample)
        val ops = RecordingFileOps(RealFileOps, atomicUnsupported = true)
        val next = sample.copy(theme = ThemeChoice.Light)
        assertEquals(SaveResult.Ok, FileSettingsStore(dir, ops).save(next))
        assertEquals(listOf("ensureDir", "writeTemp", "moveAtomic", "moveReplace"), ops.log)
        assertEquals(LoadResult.Loaded(next), FileSettingsStore(dir).load())
        assertEquals(listOf("settings.json"), entries(), "no temp file is left")
    }

    @Test
    fun aFailingFallbackKeepsTheOldFileAndCleansUp() {
        FileSettingsStore(dir).save(sample)
        val before = settingsPath.readBytes()
        val ops =
            object : FileOps by RecordingFileOps(RealFileOps, atomicUnsupported = true) {
                override fun moveReplace(
                    from: Path,
                    to: Path,
                ): Unit = throw java.io.IOException("injected")
            }
        assertIs<SaveResult.Failed>(FileSettingsStore(dir, ops).save(sample.copy(saveFolder = "/other")))
        assertContentEquals(before, settingsPath.readBytes())
        assertEquals(listOf("settings.json"), entries())
    }

    @Test
    fun aBadFileThatCannotBeSetAsideIsNeverSavedOver() {
        write("{ corrupt")
        val store = FileSettingsStore(dir, RecordingFileOps(RealFileOps, failOn = "moveReplace"))
        assertIs<LoadResult.Reset>(store.load())
        assertIs<SaveResult.Failed>(store.save(sample))
        assertIs<SaveResult.Failed>(store.save(sample.copy(theme = ThemeChoice.Light)), "saves stay held for the session")
        assertEquals("{ corrupt", settingsPath.readBytes().decodeToString())
        assertEquals(listOf("settings.json"), entries())
    }

    @Test
    fun aFileThatCannotBeReadIsNeverSavedOver() {
        write("""{"version":1,"theme":"Dark"}""")
        val store = FileSettingsStore(dir, RecordingFileOps(RealFileOps, failOn = "read"))
        assertEquals(LoadResult.Reset("unreadable"), store.load())
        assertIs<SaveResult.Failed>(store.save(sample))
        assertEquals("""{"version":1,"theme":"Dark"}""", settingsPath.readBytes().decodeToString())
    }

    @Test
    fun aBadFileThatWasSetAsideDoesNotHoldTheSaves() {
        write("{ corrupt")
        val store = FileSettingsStore(dir)
        assertIs<LoadResult.Reset>(store.load())
        assertEquals(SaveResult.Ok, store.save(sample))
        assertEquals("{ corrupt", badPath.readBytes().decodeToString())
        assertEquals(LoadResult.Loaded(sample), FileSettingsStore(dir).load())
    }

    // Nit: temp files a crash left behind

    @Test
    fun leftOverTempFilesAreRemovedAtStart() {
        write("""{"version":1}""")
        write("half", dir.resolve("settings123.tmp"))
        write("half", dir.resolve("settings987654321.tmp"))
        write("keep", dir.resolve("notes.tmp"))
        assertEquals(LoadResult.Loaded(SettingsFile()), FileSettingsStore(dir).load())
        assertEquals(listOf("notes.tmp", "settings.json"), entries())
    }

    @Test
    fun cleaningTempFilesNeverCreatesTheFolderOrFailsTheLoad() {
        assertEquals(LoadResult.Missing, FileSettingsStore(dir).load())
        assertFalse(dir.exists())
    }

    @Test
    fun aTypicalFileLoadsInUnderTwoHundredMilliseconds() {
        val nets =
            (1..40).map {
                NetworkDto("net$it", "Network $it", NetworkColor.Green, ProxyDto(ProxyType.Socks5, "10.0.0.$it", 1080, "user$it"))
            }
        val limits = LimitsDto(networks = nets.associate { it.id to NetworkLimitsDto(5_000_000, 9_000_000_000, DataPeriod.Week) })
        FileSettingsStore(dir).save(SettingsFile(limits = limits, networks = nets))
        assertTrue(Files.size(settingsPath) < MAX_SETTINGS_BYTES)
        FileSettingsStore(dir).load() // the first call pays for class loading, which is not what the budget is about
        val start = System.nanoTime()
        val result = FileSettingsStore(dir).load()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertIs<LoadResult.Loaded>(result)
        assertTrue(ms < 200, "took $ms ms")
    }

    // InMemorySettingsStore

    @Test
    fun theInMemoryStoreKeepsWhatWasSavedAndCountsSaves() {
        val store = InMemorySettingsStore()
        assertEquals(LoadResult.Missing, store.load())
        store.save(sample)
        assertEquals(LoadResult.Loaded(sample), store.load())
        assertEquals(listOf(sample), store.saves)
    }

    @Test
    fun theInMemoryStoreCanStartWithAFile() {
        assertEquals(LoadResult.Loaded(sample), InMemorySettingsStore(sample).load())
    }

    @Test
    fun bytesOfAnOverwrittenFileAreReplacedNotAppended() {
        val store = FileSettingsStore(dir)
        store.save(sample.copy(saveFolder = "/a/very/long/folder/name/" + "x".repeat(500)))
        store.save(sample)
        assertEquals(LoadResult.Loaded(sample), store.load())
    }
}
