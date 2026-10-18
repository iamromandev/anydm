package dev.anydm.engine.e2e

import dev.anydm.engine.core.DownloadStatus
import dev.anydm.engine.http.Fault
import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.openDatabaseFile
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The story's done-when (#538, #576): the real engine, killed with kill -9 at random points (sometimes with a power cut
 * after), restarted on the same database and folder, ends with the source's exact bytes. `-Pe2e.cases=N` runs more
 * random cases; `-Pe2e.seed=S` replays one.
 */
class CrashEndToEndTest {
    private val size = 24 shl 20
    private val mib = 1L shl 20
    private val source = Random(11).nextBytes(size)
    private val server = FaultServer()
    private val roots = mutableListOf<Path>()

    init {
        server.serve("/f.bin", source, etag = "\"c1\"")
        // 6 MiB/s a connection: a whole run takes about two seconds, so kills land mid-transfer.
        server.fault("/f.bin", Fault.Throttle(6 * mib))
    }

    @AfterTest
    fun cleanUp() {
        server.close()
        roots.forEach { deleteRecursively(it) }
    }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).toList()

    /** Kills at each stop (then a power cut if it says so), runs to the end, and checks the result. */
    private fun runCase(
        label: String,
        stops: List<Pair<Long, Boolean>>,
        random: Random,
    ) {
        val root = newTempFolder("anydm-crash-").also { roots += it }
        val folder = File(root.toString(), "save").apply { mkdirs() }
        val database = File(root.toString(), "anydm.db")
        val stderr = File(root.toString(), "child.err")
        val clue = "$label: stops ${stops.map { (at, cut) -> "${at / 1024} KiB${if (cut) " + power cut" else ""}" }}"

        fun start() =
            EngineProcessRunner.start(EngineProcess::class.java.name, listOf(database.path, folder.path, server.url("/f.bin")), stderr)

        withClue(clue) {
            for ((at, powerCut) in stops) {
                start().killAt(at, 60.seconds)
                if (powerCut) runBlocking { spoilUnsynced(Path(database.path), Path(folder.path), random) }
            }
            start().awaitDone(90.seconds)

            sha256(File(folder, "f.bin").readBytes()) shouldBe sha256(source)
            folder.list()!!.filter { it.endsWith(".anydm") } shouldBe emptyList()
            val store = Store(openDatabaseFile(Path(database.path)), Dispatchers.IO)
            try {
                runBlocking {
                    store.downloads
                        .list()
                        .single()
                        .status
                } shouldBe DownloadStatus.COMPLETED
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun randomKillsAndPowerCutsGiveIdenticalBytes() {
        val cases = System.getProperty("e2e.cases")?.toIntOrNull() ?: 12
        val seed = System.getProperty("e2e.seed")?.toLongOrNull() ?: Random.nextLong()
        println("CrashEndToEndTest seed $seed ($cases cases)")
        repeat(cases) { case ->
            val random = Random(seed + case)
            val stops = List(random.nextInt(1, 4)) { random.nextLong((size * 0.95).toLong()) to random.nextBoolean() }
            runCase("seed $seed case $case", stops, random)
        }
    }

    @Test
    fun aKillBeforeTheFirstByte() = runCase("kill before the first byte", listOf(0L to false), Random(1))

    @Test
    fun aKillAtHalfWithAPowerCut() = runCase("kill at half, power cut", listOf(12 * mib to true), Random(2))

    @Test
    fun threeKillsInARow() = runCase("three kills", listOf(size / 5L to false, size / 2L to false, size * 4L / 5 to false), Random(3))

    @Test
    fun manyQuickKillsLeaveAUsableDatabase() = runCase("five quick kills", listOf(4L, 5, 6, 7, 8).map { it * mib / 2 to false }, Random(4))
}
