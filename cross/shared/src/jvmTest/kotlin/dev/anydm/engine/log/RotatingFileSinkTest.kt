package dev.anydm.engine.log

import dev.anydm.engine.platform.deleteRecursively
import dev.anydm.engine.platform.newTempFolder
import dev.anydm.engine.platform.toNioPath
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test

class RotatingFileSinkTest {
    private val root = newTempFolder("anydm-log-test-")
    private val folder = Path(root, "logs")

    @AfterTest
    fun cleanUp() = deleteRecursively(root)

    private fun linesOf(name: String): List<String> = Files.readAllLines(Path(folder, name).toNioPath())

    private fun names(): Set<String> = Files.list(folder.toNioPath()).use { s -> s.map { it.fileName.toString() }.toList().toSet() }

    @Test
    fun itCreatesItsFolder() {
        RotatingFileSink(folder).apply {
            write("hello")
            close()
        }

        linesOf("anydm.log") shouldBe listOf("hello")
    }

    @Test
    fun itRollsWhenTheNextLineWouldPassTheLimit() {
        val sink = RotatingFileSink(folder, maxBytes = 100)
        val line = "x".repeat(29) // 30 bytes with its newline
        repeat(4) { sink.write(line) }
        sink.close()

        linesOf("anydm.1.log").size shouldBe 3
        linesOf("anydm.log").size shouldBe 1
    }

    @Test
    fun itKeepsOnlyKeepFiles() {
        val sink = RotatingFileSink(folder, maxBytes = 20, keep = 3)
        for (n in 1..10) sink.write("line $n")
        sink.close()

        names() shouldBe setOf("anydm.log", "anydm.1.log", "anydm.2.log")
        linesOf("anydm.log").last() shouldBe "line 10"
        val first = { name: String -> linesOf(name).first().removePrefix("line ").toInt() }
        (first("anydm.2.log") < first("anydm.1.log")) shouldBe true
    }

    @Test
    fun anUnwritableFolderNeverThrows() {
        Files.writeString(folder.toNioPath(), "a file where the folder should be")
        val sink = RotatingFileSink(folder)

        repeat(3) { sink.write("lost") }

        sink.dropped shouldBe 3
    }

    @Test
    fun itNotesDroppedLines() {
        var failures = 2
        val sink =
            RotatingFileSink(folder, open = { path ->
                if (failures-- > 0) throw IOException("disk gone")
                Files.newOutputStream(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            })
        sink.write("one")
        sink.write("two")
        sink.write("three")
        sink.close()

        val lines = linesOf("anydm.log")
        lines.first() shouldEndWith "WARN  log 2 lines dropped"
        lines.last() shouldBe "three"
    }

    @Test
    fun concurrentLinesStayWhole() {
        val sink = RotatingFileSink(folder, maxBytes = 40_000)
        val pattern = Regex("""t\d \d+ x{40}""")
        (0 until 4)
            .map { t -> thread { for (n in 0 until 500) sink.write("t$t $n " + "x".repeat(40)) } }
            .forEach { it.join() }
        sink.close()

        val all = names().flatMap { linesOf(it) }
        all.size shouldBe 2000
        all.all { pattern.matches(it) } shouldBe true
    }

    @Test
    fun aLineLongerThanTheLimitIsStillWritten() {
        val sink = RotatingFileSink(folder, maxBytes = 50)
        sink.write("short")
        sink.write("y".repeat(200))
        sink.write("after")
        sink.close()

        names().flatMap { linesOf(it) }.contains("y".repeat(200)) shouldBe true
    }

    @Test
    fun utf8LinesRoundTrip() {
        RotatingFileSink(folder).apply {
            write("файл видео 🎬.mp4")
            close()
        }

        linesOf("anydm.log") shouldBe listOf("файл видео 🎬.mp4")
    }
}
