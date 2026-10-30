package dev.anydm.engine.e2e

import dev.anydm.engine.http.FaultServer
import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** The child engine process the crash tests kill (#576): it downloads a file and says so. */
class EngineProcessTest {
    private val root = newTempFolder("anydm-e2e-process-")
    private val server = FaultServer()
    private val bytes = Random(7).nextBytes(4 shl 20)
    private val folder = File(root.toString(), "save").apply { mkdirs() }
    private val database = File(root.toString(), "anydm.db")
    private val stderr = File(root.toString(), "child.err")

    init {
        server.serve("/f.bin", bytes, etag = "\"e1\"")
    }

    @AfterTest
    fun cleanUp() {
        server.close()
        deleteRecursively(root)
    }

    private fun run() =
        EngineProcessRunner.start(
            EngineProcess::class.java.name,
            listOf(database.path, folder.path, server.url("/f.bin")),
            stderr,
        )

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).toList()

    @Test
    fun anUninterruptedRunDownloadsTheFile() {
        run().awaitDone(60.seconds)

        sha256(File(folder, "f.bin").readBytes()) shouldBe sha256(bytes)
        folder.list()!!.filter { it.endsWith(".anydm") } shouldBe emptyList()
    }

    @Test
    fun aCompletedJobRestartsAsDone() {
        run().awaitDone(60.seconds)

        run().awaitDone(10.seconds)
    }
}
