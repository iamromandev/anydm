package dev.anydm.api

import dev.anydm.model.TaskStatus
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EventsTest {
    private val stream =
        listOf(
            "event: tasks",
            """data: [{"id":"a","status":"downloading","kind":"file"}]""",
            "",
            ": ping",
            "",
            "event: progress",
            """data: {"id":"v","parent_id":"g","speed_bps":10}""",
            "",
            "event: task",
            """data: {"id":"a","status":"complete","kind":"file"}""",
            "",
            "event: disk",
            """data: {"total_bytes":100,"free_bytes":40,"min_free_bytes":10}""",
            "",
            "event: something_new",
            "data: {}",
            "",
        ).joinToString("\n", postfix = "\n")

    @Test
    fun `the stream becomes typed events, skipping what this build doesn't know`() =
        runTest {
            var key: String? = null
            val api =
                AnydmApi(
                    ServerConfig("http://nas:8030", "k"),
                    MockEngine { request ->
                        key = request.headers[API_KEY_HEADER]
                        assertEquals("/download/events", request.url.encodedPath)
                        respond(stream, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                    },
                )

            val events = api.events().toList()

            assertEquals("k", key)
            assertEquals(4, events.size)
            assertEquals(TaskStatus.DOWNLOADING, assertIs<ServerEvent.Snapshot>(events[0]).tasks.single().status)
            assertEquals("g", assertIs<ServerEvent.Progress>(events[1]).progress.parentId)
            assertEquals(TaskStatus.COMPLETE, assertIs<ServerEvent.TaskChanged>(events[2]).task.status)
            assertEquals(40, assertIs<ServerEvent.Disk>(events[3]).disk.freeBytes)
        }

    @Test
    fun `a refused key ends the stream with Unauthorized`() =
        runTest {
            val api =
                AnydmApi(
                    ServerConfig("http://nas:8030", "bad"),
                    MockEngine {
                        respond(
                            """{"status":"error","code":401,"message":"bad key"}""",
                            HttpStatusCode.Unauthorized,
                            headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            assertIs<Unauthorized>(kotlin.runCatching { api.events().toList() }.exceptionOrNull())
        }
}
