package dev.anydm.engine.os

import dev.anydm.engine.log.Log
import dev.anydm.engine.log.RecordingLogSink
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The pairings on disk: whole or not at all, private to the user, and never trusted when damaged. */
class FilePairStoreTest {
    private val folder: File = Files.createTempDirectory("anydm-pairs").toFile()
    private val file = File(folder, FilePairStore.FILE_NAME)
    private val sink = RecordingLogSink()
    private val posix =
        file
            .toPath()
            .fileSystem
            .supportedFileAttributeViews()
            .contains("posix")

    @AfterTest
    fun cleanUp() {
        // A test may have made the folder unwritable.
        runCatching { if (posix) Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwx------")) }
        folder.deleteRecursively()
    }

    private fun store(f: File = file) = FilePairStore(f, Log(sink, Clock.System))

    private fun record(
        n: Int,
        label: String = "Chrome 130 (macOS)",
    ) = PairRecord(
        id = n.toString(16).padStart(32, '0'),
        label = label,
        secret = n.toString(16).padStart(64, 'a'),
        createdAt = Instant.parse("2026-11-02T10:00:00Z"),
        lastUsed = Instant.parse("2026-11-02T10:05:30Z"),
    )

    private fun permissions(f: File): Set<PosixFilePermission> = Files.getPosixFilePermissions(f.toPath())

    private val ownerOnly = PosixFilePermissions.fromString("rw-------")

    private fun List<PairRecord>.ids() = map { it.id }

    @Test
    fun aMissingFileIsNoPairingsAndMakesNothing() {
        store().load() shouldBe emptyList()

        folder.list()!!.toList() shouldBe emptyList()
    }

    @Test
    fun whatIsSavedIsLoaded() {
        store().save(listOf(record(1), record(2, "Firefox 131 (Linux)")))

        val loaded = store().load()

        loaded.ids() shouldContainExactly listOf(record(1).id, record(2).id)
        loaded[1].label shouldBe "Firefox 131 (Linux)"
        loaded[0].secret shouldBe record(1).secret
        loaded[0].createdAt shouldBe Instant.parse("2026-11-02T10:00:00Z")
        loaded[0].lastUsed shouldBe Instant.parse("2026-11-02T10:05:30Z")
    }

    @Test
    fun savingNothingEmptiesTheFile() {
        store().save(listOf(record(1)))
        store().save(emptyList())

        store().load() shouldBe emptyList()
    }

    @Test
    fun theFileIsForItsOwnerOnlyAndStaysSoWhenReplaced() {
        assumeTrue("no POSIX file modes here", posix)
        store().save(listOf(record(1)))
        permissions(file) shouldBe ownerOnly

        store().save(listOf(record(1), record(2)))

        permissions(file) shouldBe ownerOnly
    }

    @Test
    fun aFileSomeoneElseCouldReadIsTightenedWhenLoaded() {
        assumeTrue("no POSIX file modes here", posix)
        store().save(listOf(record(1)))
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

        store().load().ids() shouldContainExactly listOf(record(1).id)

        permissions(file) shouldBe ownerOnly
    }

    @Test
    fun theFolderIsMadeWhenItIsMissing() {
        val nested = File(folder, "a/b/$FILE")

        store(nested).save(listOf(record(1)))

        store(nested).load().ids() shouldContainExactly listOf(record(1).id)
    }

    @Test
    fun noTemporaryFileIsLeftBehind() {
        repeat(5) { store().save(listOf(record(it))) }

        folder.list()!!.toList() shouldBe listOf(FilePairStore.FILE_NAME)
    }

    @Test
    fun aSaveThatCannotBeDoneThrowsAndKeepsTheOldFile() {
        assumeTrue("no POSIX file modes here", posix)
        store().save(listOf(record(1)))
        Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("r-x------"))
        // Running as root nothing is unwritable.
        assumeTrue("running as a user for whom nothing is unwritable", !Files.isWritable(folder.toPath()))

        shouldThrow<IOException> { store().save(listOf(record(1), record(2))) }

        Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwx------"))
        store().load().ids() shouldContainExactly listOf(record(1).id)
        folder.list()!!.toList() shouldBe listOf(FilePairStore.FILE_NAME)
    }

    @Test
    fun aCorruptFileIsNoPairingsAndIsKeptAside() {
        file.writeText("{not json at all")

        store().load() shouldBe emptyList()

        file.exists() shouldBe false
        File(folder, "${FilePairStore.FILE_NAME}.bad").readText() shouldBe "{not json at all"
        if (posix) permissions(File(folder, "${FilePairStore.FILE_NAME}.bad")) shouldBe ownerOnly
        // And the store works again.
        store().save(listOf(record(1)))
        store().load().ids() shouldContainExactly listOf(record(1).id)
    }

    @Test
    fun aFileThatIsNotTextIsCorruptAndKeptAside() {
        val bytes = byteArrayOf('{'.code.toByte(), 0xff.toByte(), 0xfe.toByte(), '}'.code.toByte())
        file.writeBytes(bytes)

        store().load() shouldBe emptyList()

        file.exists() shouldBe false
        File(folder, "${FilePairStore.FILE_NAME}.bad").readBytes().toList() shouldBe bytes.toList()
    }

    @Test
    fun aFileOfAnotherShapeIsCorruptToo() {
        for (text in listOf(
            "",
            "[]",
            "{}",
            "null",
            """{"version":1,"pairs":"x"}""",
            """{"version":1}""",
        )) {
            file.writeText(text)

            store().load() shouldBe emptyList()
            file.exists() shouldBe false
        }
    }

    @Test
    fun aFileOfAnUnknownVersionIsUnreadableAndLeftAlone() {
        // Perhaps a newer anydm wrote it: it is not damaged, and what this version does not understand it must not move aside,
        // rewrite or delete.
        for (text in listOf(
            """{"version":2,"pairs":[]}""",
            """{"version":0,"pairs":[]}""",
            """{"version":1.5,"pairs":[]}""",
            """{"version":3}""",
            // Not a number at all: a version this code can't tell, so it is not ours to move aside either.
            """{"version":"1","pairs":[]}""",
            """{"version":"x","pairs":[]}""",
            """{"version":null,"pairs":[]}""",
            """{"version":true,"pairs":[]}""",
            """{"version":[1],"pairs":[]}""",
        )) {
            file.writeText(text)

            shouldThrow<IOException> { store().load() }

            file.readText() shouldBe text
            folder.list()!!.toList() shouldBe listOf(FilePairStore.FILE_NAME)
        }
    }

    @Test
    fun aFileOfAnUnknownVersionMakesThePairingsUnavailableAndIsNeverWrittenOver() {
        val text = """{"version":2,"pairs":[{"id":"x"}]}"""
        file.writeText(text)
        runBlocking {
            val pairing = BridgePairing(store(), Clock.System)
            val peer = PairingPeer()
            val code = pairing.startPairing().code
            val attempt = peer.attempt(code)
            val hello = peer.startHello("0".repeat(32))

            pairing.browsers() shouldBe PairedBrowsers.Unavailable
            pairing.revoke("0".repeat(32)) shouldBe RevokeResult.STORE_UNAVAILABLE
            pairing.hello(hello.id, hello.nonceE, hello.proof, peer.port) shouldBe HelloReply.StoreUnavailable
            pairing.pair(ORIGIN, "Firefox/131.0", attempt.nonceE, attempt.proofE, peer.port) shouldBe PairReply.NotSaved
        }

        file.readText() shouldBe text
        folder.list()!!.toList() shouldBe listOf(FilePairStore.FILE_NAME)
    }

    @Test
    fun aDamagedFileDoesNotReplaceAnEarlierOneKeptAside() {
        val bad = File(folder, "${FilePairStore.FILE_NAME}.bad")
        val texts = listOf("{first damaged", "{second damaged", "{third damaged")
        for (text in texts) {
            file.writeText(text)

            store().load() shouldBe emptyList()
        }

        // The first stays where it was; the others are beside it, none lost.
        bad.readText() shouldBe texts[0]
        val aside = folder.list()!!.filter { it.startsWith("${FilePairStore.FILE_NAME}.bad") }.map { File(folder, it).readText() }
        aside.sorted() shouldContainExactly texts.sorted()
        file.exists() shouldBe false
    }

    @Test
    fun whenEveryNameForAKeptCopyIsTakenTheDamagedFileIsDeletedAndNothingKeptIsReplaced() {
        val kept =
            listOf(File(folder, "${FilePairStore.FILE_NAME}.bad")) +
                (1 until 100).map { File(folder, "${FilePairStore.FILE_NAME}.bad.$it") }
        kept.forEach { it.writeText("kept ${it.name}") }
        file.writeText("{damaged again")

        store().load() shouldBe emptyList()

        file.exists() shouldBe false
        kept.forEach { it.readText() shouldBe "kept ${it.name}" }
        folder.list()!!.size shouldBe kept.size
    }

    @Test
    fun theFilesKeptAsideAreOnlyForTheOwnerToo() {
        assumeTrue("no POSIX file modes here", posix)
        file.writeText("{first damaged")
        store().load()
        file.writeText("{second damaged")
        store().load()

        folder
            .list()!!
            .filter { it.startsWith("${FilePairStore.FILE_NAME}.bad") }
            .forEach { permissions(File(folder, it)) shouldBe ownerOnly }
    }

    @Test
    fun aFileFarTooBigIsNotRead() {
        file.writeText("x".repeat(2 * 1024 * 1024))

        store().load() shouldBe emptyList()

        file.exists() shouldBe false
    }

    @Test
    fun anEntryThatIsNotWholeIsDroppedAndTheRestKept() {
        store().save(listOf(record(1), record(2), record(3), record(4)))
        val text = file.readText()
        val damaged =
            Json.parseToJsonElement(text).jsonObject.getValue("pairs").jsonArray.map { it.jsonObject }.let { entries ->
                """{"version":1,"pairs":[${entries[0]},""" +
                    """${entries[1].toString().replace(record(2).secret, "nothex")},""" +
                    """${entries[2].toString().replace("2026-11-02T10:00:00Z", "yesterday")},""" +
                    """{"id":"x"},${entries[3]},${entries[3]}]}"""
            }
        file.writeText(damaged)

        val loaded = store().load()

        loaded.ids() shouldContainExactly listOf(record(1).id, record(4).id)
        // What was there is kept aside, and what is good is written again.
        File(folder, "${FilePairStore.FILE_NAME}.bad").readText() shouldBe damaged
        store().load().ids() shouldContainExactly listOf(record(1).id, record(4).id)
    }

    @Test
    fun aLongOrOddLabelIsCleanedWhenLoaded() {
        store().save(listOf(record(1)))
        file.writeText(file.readText().replace("Chrome 130 (macOS)", "a\\u0007" + "b".repeat(100)))

        store().load().single().label shouldBe "a" + "b".repeat(59)
    }

    @Test
    fun theFileNeverHoldsThePairingCodeOrAnythingButTheRecord() {
        store().save(listOf(record(1)))

        Json.parseToJsonElement(file.readText()).jsonObject.keys shouldBe setOf("version", "pairs")
        Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("pairs")
            .jsonArray
            .single()
            .jsonObject.keys shouldBe setOf("id", "label", "secret", "createdAt", "lastUsed")
        Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("version")
            .jsonPrimitive.content shouldBe "1"
    }

    @Test
    fun theLogNeverHoldsASecret() {
        file.writeText("""{"version":1,"pairs":[{"id":"${record(1).id}","secret":"nothex-${record(1).secret}"}]}""")

        store().load()

        sink.lines.joinToString("\n") shouldNotContain record(1).secret
    }

    @Test
    fun concurrentSavesAlwaysLeaveAWholeFile() {
        val writers = 8
        val each = 25
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        val failures = AtomicInteger()
        val lists = (0 until writers).map { w -> (1..(w + 1)).map { record(w * 100 + it) } }
        repeat(writers) { w ->
            pool.execute {
                start.await()
                repeat(each) {
                    try {
                        store().save(lists[w])
                    } catch (_: Exception) {
                        failures.incrementAndGet()
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))

        failures.get() shouldBe 0
        val last = store().load()
        // One writer's list, whole; never a mixture or a cut.
        (lists.map { it.ids() }).contains(last.ids()) shouldBe true
        folder.list()!!.toList() shouldBe listOf(FilePairStore.FILE_NAME)
    }

    @Test
    fun aReaderNeverSeesAHalfWrittenFile() {
        val a = (1..20).map { record(it) }
        val b = (101..105).map { record(it) }
        store().save(a)
        val stop = AtomicBoolean(false)
        val bad = AtomicInteger()
        val reader =
            Thread {
                val s = store()
                while (!stop.get()) {
                    val ids = s.load().ids()
                    if (ids != a.ids() && ids != b.ids()) bad.incrementAndGet()
                }
            }
        reader.start()
        repeat(200) { store().save(if (it % 2 == 0) b else a) }
        stop.set(true)
        reader.join(30_000)

        bad.get() shouldBe 0
        File(folder, "${FilePairStore.FILE_NAME}.bad").exists() shouldBe false
    }

    @Test
    fun aBrowserPairedBeforeARestartCanStillSayHello() {
        runBlocking {
            val peer = PairingPeer()
            val first = BridgePairing(store(), Clock.System)
            val code = first.startPairing().code
            val attempt = peer.attempt(code)
            val paired =
                first.pair(
                    "chrome-extension://abc",
                    "Firefox/131.0",
                    attempt.nonceE,
                    attempt.proofE,
                    peer.port,
                ) as PairReply.Paired
            peer.accept(code, attempt, paired) shouldBe true

            // A new run: a new store over the same file, a new pairing authority.
            val second = BridgePairing(store(), Clock.System)
            val request = peer.startHello()
            val hello = second.hello(request.id, request.nonceE, request.proof, peer.port)

            peer.acceptHello(hello as HelloReply.Ok) shouldBe true
            second.listed().single().label shouldBe "Firefox 131"
            if (posix) permissions(file) shouldBe ownerOnly
            file.readText() shouldNotContain code
        }
    }

    // ---- a file that cannot be read is not a file that is not there (S2) ----

    // The folder can't be looked into, so the file is there but can't be told so, let alone read. The test is skipped (an
    // assumption, not a pass) where nothing stops this user (root), as there is nothing to test.
    private fun closeTheFolder() {
        assumeTrue("no POSIX file modes here", posix)
        Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("---------"))
        val stillReadable = Files.isReadable(file.toPath())
        if (stillReadable) openTheFolder()
        assumeTrue("running as a user for whom nothing is unreadable", !stillReadable)
    }

    private fun openTheFolder() = Files.setPosixFilePermissions(folder.toPath(), PosixFilePermissions.fromString("rwx------"))

    @Test
    fun aFileThatCannotBeReadMakesLoadThrowAndIsLeftAlone() {
        assumeTrue("no POSIX file modes here", posix)
        store().save(listOf(record(1)))
        val before = file.readText()
        closeTheFolder()

        shouldThrow<IOException> { store().load() }

        openTheFolder()
        file.readText() shouldBe before
        File(folder, "${FilePairStore.FILE_NAME}.bad").exists() shouldBe false
        store().load().ids() shouldContainExactly listOf(record(1).id)
    }

    @Test
    fun pairingWhileTheFileCannotBeReadKeepsTheOldPairingsAndThenAddsTheNew() {
        assumeTrue("no POSIX file modes here", posix)
        runBlocking {
            val old = PairingPeer(1)
            val first = BridgePairing(store(), Clock.System)
            val oldCode = first.startPairing().code
            val oldAttempt = old.attempt(oldCode)
            val oldPaired =
                first
                    .pair(ORIGIN, "Firefox/131.0", oldAttempt.nonceE, oldAttempt.proofE, old.port)
                    .shouldBeInstanceOf<PairReply.Paired>()
            // A new run, and the file can't be read at its first look.
            val second = BridgePairing(store(), Clock.System)
            val newcomer = PairingPeer(2)
            val code = second.startPairing().code
            val attempt = newcomer.attempt(code)
            closeTheFolder()

            second.pair(ORIGIN, "Firefox/131.0", attempt.nonceE, attempt.proofE, newcomer.port) shouldBe PairReply.NotSaved

            openTheFolder()
            // The app looks at a pairings file it could not read once a second at most.
            Thread.sleep(1_100)
            store().load().size shouldBe 1
            val paired =
                second.pair(ORIGIN, "Firefox/131.0", attempt.nonceE, attempt.proofE, newcomer.port).shouldBeInstanceOf<PairReply.Paired>()
            store().load().map { it.id }.toSet() shouldBe setOf(oldPaired.id, paired.id)
        }
    }

    // ---- temporary files of a save that was cut short ----

    @Test
    fun temporaryFilesOfACrashedSaveAreDeletedWhenLoaded() {
        store().save(listOf(record(1)))
        val stale =
            listOf(
                "bridge-pairs123.tmp",
                "bridge-pairs-9.tmp",
                "bridge-pairs.tmp",
            ).map { File(folder, it).apply { writeText("x") } }
        val others =
            listOf(
                "other.tmp",
                "bridge-pairs.json.bad",
                "bridge-pairs.json.tmp.keep",
            ).map { File(folder, it).apply { writeText("x") } }
        val directory = File(folder, "bridge-pairs-dir.tmp").apply { mkdir() }

        store().load().ids() shouldContainExactly listOf(record(1).id)

        stale.forEach { it.exists() shouldBe false }
        others.forEach { it.exists() shouldBe true }
        directory.isDirectory shouldBe true
        file.exists() shouldBe true
    }

    @Test
    fun temporaryFilesAreDeletedEvenWhenThereIsNoPairingsFileYet() {
        val stale = File(folder, "bridge-pairs77.tmp").apply { writeText("x") }

        store().load() shouldBe emptyList()

        stale.exists() shouldBe false
    }

    private companion object {
        const val FILE = FilePairStore.FILE_NAME
        const val ORIGIN = "chrome-extension://abc"
    }
}
