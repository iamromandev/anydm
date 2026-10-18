package dev.anydm.engine.platform

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/** Two jobs placing the same name at once (FIL-04) must never replace each other on a real disk. */
class DesktopAtomicMoveRaceTest {
    private val root = Files.createTempDirectory("anydm-race-test-")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun exactlyOneOfTwoSimultaneousMovesWins() {
        runBlocking(Dispatchers.Default) {
            val fs = DesktopFileSystem()
            repeat(400) { round ->
                val a = Path(root.toString(), "a$round")
                val b = Path(root.toString(), "b$round")
                val target = Path(root.toString(), "t$round")
                Files.write(a.toNioPath(), byteArrayOf(1))
                Files.write(b.toNioPath(), byteArrayOf(2))

                val results =
                    listOf(
                        async { fs.atomicMove(a, target, replace = false) },
                        async { fs.atomicMove(b, target, replace = false) },
                    ).awaitAll()

                results.count { it is FileResult.Ok } shouldBe 1
                val lost = results.filterIsInstance<FileResult.AlreadyExists>().single()
                lost.path shouldBe target
                Files.exists(a.toNioPath()) xor Files.exists(b.toNioPath()) shouldBe true
            }
        }
    }

    @Test
    fun aTakenTargetIsStillRefused() {
        runBlocking {
            val fs = DesktopFileSystem()
            val a = Path(root.toString(), "a")
            val target = Path(root.toString(), "t")
            Files.write(a.toNioPath(), byteArrayOf(1))
            Files.write(target.toNioPath(), byteArrayOf(9))

            (fs.atomicMove(a, target, replace = false) as FileResult.AlreadyExists).path shouldBe target
            Files.readAllBytes(target.toNioPath()).toList() shouldBe listOf<Byte>(9)
            Files.exists(a.toNioPath()) shouldBe true
        }
    }
}
