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
            "event: downloads",
            """data: [{"type":"download","id":"a","status":"downloading","media_kind":"file"}]""",
            "",
            ": ping",
            "",
            "event: progress",
            """data: {"id":"v","collection_id":"g","live":{"speed_bps":10}}""",
            "",
            "event: download",
            """data: {"type":"download","id":"a","status":"complete","media_kind":"file"}""",
            "",
            "event: collection",
            """data: {"type":"collection","id":"c1","kind":"playlist","status":"downloading"}""",
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
            assertEquals(5, events.size)
            assertEquals(TaskStatus.DOWNLOADING, assertIs<ServerEvent.Snapshot>(events[0]).tasks.single().status)
            val progress = assertIs<ServerEvent.Progress>(events[1]).progress
            assertEquals("g", progress.collectionId)
            assertEquals(10, progress.live?.speedBps)
            assertEquals(TaskStatus.COMPLETE, assertIs<ServerEvent.TaskChanged>(events[2]).task.status)
            assertEquals("c1", assertIs<ServerEvent.CollectionChanged>(events[3]).task.id)
            assertEquals(40, assertIs<ServerEvent.Disk>(events[4]).disk.freeBytes)
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

class StreamTimeoutTest {
    @Test
    fun `the event stream has no request time limit, while ordinary calls keep one`() =
        runTest {
            val limits = mutableMapOf<String, Long?>()
            val api =
                AnydmApi(
                    ServerConfig("http://nas:8030", null),
                    MockEngine { request ->
                        limits[request.url.encodedPath] =
                            request.getCapabilityOrNull(io.ktor.client.plugins.HttpTimeoutCapability)?.requestTimeoutMillis
                        if (request.url.encodedPath == "/download/events") {
                            respond("", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                        } else {
                            respond(
                                """{"status":"success","data":{"all":0}}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    },
                )

            api.summary()
            api.events().toList()

            assertEquals(io.ktor.client.plugins.HttpTimeoutConfig.INFINITE_TIMEOUT_MS, limits["/download/events"])
            assertEquals(REQUEST_TIMEOUT_MS, limits["/download/summary"])
        }
}
