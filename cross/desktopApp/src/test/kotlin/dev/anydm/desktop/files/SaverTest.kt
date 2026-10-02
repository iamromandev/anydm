package dev.anydm.desktop.files

import com.sun.net.httpserver.HttpServer
import dev.anydm.model.FileDto
import dev.anydm.model.TaskDto
import dev.anydm.model.TaskFile
import dev.anydm.model.toTask
import kotlinx.coroutines.test.runTest
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaverTest {
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ok") { exchange ->
                val body = "hello anydm".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            createContext("/missing") { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            start()
        }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val dir = Files.createTempDirectory("anydm-save")

    @AfterTest
    fun stop() = server.stop(0)

    private val fileUrl = { id: String, index: Int? -> if (index == null) "u/$id" else "u/$id/$index" }

    @Test
    fun `a single download saves its one file under its own name`() {
        val task = TaskDto(id = "t", mediaKind = "file", status = "complete", title = "clip", files = listOf(FileDto(path = "clip.mp4")))
                .toTask()
        assertEquals(listOf(SaveTarget("u/t", "clip.mp4")), saveTargets(task, fileUrl))
    }

    @Test
    fun `a torrent of several files saves each selected one`() {
        val task =
            TaskDto(id = "t", platform = "torrent", status = "seeding", title = "pack").toTask().copy(
                files =
                    listOf(
                        TaskFile(0, "pack/a.mkv", 1, true, 1),
                        TaskFile(1, "pack/b.nfo", 1, false, 0),
                        TaskFile(2, "pack/c.mkv", 1, true, 1),
                    ),
            )
        assertEquals(listOf(SaveTarget("u/t/0", "a.mkv"), SaveTarget("u/t/2", "c.mkv")), saveTargets(task, fileUrl))
    }

    @Test
    fun `a name already taken gets a number before its extension`() {
        Files.writeString(dir.resolve("clip.mp4"), "x")
        Files.writeString(dir.resolve("clip (1).mp4"), "x")
        assertEquals("clip (2).mp4", uniqueTarget(dir, "clip.mp4").name)
        assertEquals("README", uniqueTarget(dir, "README").name)
    }

    @Test
    fun `a save lands whole, with its progress reported`() =
        runTest {
            var last = 0L to (null as Long?)
            val saved = Saver().save(SaveTarget("$base/ok", "hello.txt"), dir) { done, total -> last = done to total }
            assertEquals("hello anydm", saved.readText())
            assertEquals(11L to 11L, last)
        }

    @Test
    fun `a failed save says why and leaves nothing behind`() =
        runTest {
            val error = assertFailsWith<SaveFailed> { Saver().save(SaveTarget("$base/missing", "gone.txt"), dir) }
            assertEquals("The server answered 404", error.message)
            assertTrue(dir.listDirectoryEntries().none { it.name.startsWith("gone") })
        }
}
