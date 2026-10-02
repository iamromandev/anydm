package dev.anydm.api

import dev.anydm.model.TaskStatus
import dev.anydm.model.toTask
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private const val TASK = """{"type":"download","id":"t1","status":"paused","media_kind":"file","title":"a"}"""

private fun ok(json: String) = """{"status":"success","code":200,"data":$json}"""

class AnydmApiTest {
    private val seen = mutableListOf<HttpRequestData>()

    private fun api(
        key: String? = "k",
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = AnydmApi(
        ServerConfig("http://nas:8030/", key),
        MockEngine { request ->
            seen += request
            answer(request)
        },
    )

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun bodyOf(request: HttpRequestData) = request.body.toByteArray().decodeToString()

    @Test
    fun `every call carries the key, and none when there isn't one`() =
        runTest {
            api { json(ok("""{"all":1}""")) }.summary()
            assertEquals("k", seen.last().headers[API_KEY_HEADER])
            api(key = null) { json(ok("""{"all":1}""")) }.summary()
            assertEquals(null, seen.last().headers[API_KEY_HEADER])
        }

    @Test
    fun `the list asks for its page, filter and sort`() =
        runTest {
            val page =
                api { json("""{"status":"success","data":[$TASK],"meta":{"page":2,"total_pages":4}}""") }
                    .listTasks(page = 2, pageSize = 25, group = "downloading", sort = "title")
            val url = seen.last().url
            assertEquals("/download", url.encodedPath)
            assertEquals("2", url.parameters["page"])
            assertEquals("25", url.parameters["page_size"])
            assertEquals("downloading", url.parameters["group"])
            assertEquals("title", url.parameters["sort"])
            assertEquals(4, page.meta.totalPages)
            assertEquals(
                TaskStatus.PAUSED,
                page.items
                    .single()
                    .toTask()
                    .status,
            )
        }

    @Test
    fun `actions post to the task and answer with its row`() =
        runTest {
            val client = api { json(ok(TASK)) }
            client.pause("t1")
            assertEquals(HttpMethod.Post, seen.last().method)
            assertEquals("/download/t1/pause", seen.last().url.encodedPath)
            client.resume("t1")
            assertEquals("/download/t1/resume", seen.last().url.encodedPath)
            client.stopSeeding("t1")
            assertEquals("/download/t1/seed/stop", seen.last().url.encodedPath)
        }

    @Test
    fun `a collection is paused, resumed and removed by its own routes`() =
        runTest {
            val client = api { json(ok(TASK)) }
            client.pause("c1", collection = true)
            assertEquals("/collection/c1/pause", seen.last().url.encodedPath)
            client.resume("c1", collection = true)
            assertEquals("/collection/c1/resume", seen.last().url.encodedPath)
            client.remove("c1", deleteFiles = false, collection = true)
            assertEquals(HttpMethod.Delete, seen.last().method)
            assertEquals("/collection/c1", seen.last().url.encodedPath)
        }

    @Test
    fun `remove accepts an empty 204`() =
        runTest {
            api { respond("", HttpStatusCode.NoContent) }.remove("t1", deleteFiles = true)
            assertEquals(HttpMethod.Delete, seen.last().method)
            assertEquals("true", seen.last().url.parameters["delete_files"])
        }

    @Test
    fun `bulk says how many rows it touched`() =
        runTest {
            assertEquals(3, api { json(ok("""{"affected":3}""")) }.bulk("pause_all"))
            assertEquals("""{"action":"pause_all"}""", bodyOf(seen.last()))
        }

    @Test
    fun `adding sends what the API expects`() =
        runTest {
            val client = api { json(ok(TASK), HttpStatusCode.Created) }
            client.addUrl("https://x/a.iso")
            assertEquals("/download/url", seen.last().url.encodedPath)
            assertEquals("""{"url":"https://x/a.iso"}""", bodyOf(seen.last()))
            client.addTorrent("magnet:?xt=urn:btih:abc")
            assertEquals("""{"torrent":"magnet:?xt=urn:btih:abc","files":[]}""", bodyOf(seen.last()))
            client.addMedia("https://youtu.be/x", "720")
            assertEquals("""{"url":"https://youtu.be/x","preset":"720"}""", bodyOf(seen.last()))
        }

    @Test
    fun `a link no site supports becomes a direct download`() =
        runTest {
            val client =
                api { request ->
                    if (request.url.encodedPath == "/extract") {
                        json("""{"status":"error","code":400,"type":"unsupported_url","message":"no"}""", HttpStatusCode.BadRequest)
                    } else {
                        json(ok(TASK), HttpStatusCode.Created)
                    }
                }
            client.addLink("https://x/a.iso", preferred = "best")
            assertEquals("/download/url", seen.last().url.encodedPath)
        }

    @Test
    fun `a page is downloaded at the preferred preset, else the first it offers`() =
        runTest {
            val client =
                api { request ->
                    if (request.url.encodedPath == "/extract") {
                        json(ok("""{"type":"media","extractor":"Youtube","id":"x","presets":["720","480"]}"""))
                    } else {
                        json(ok(TASK), HttpStatusCode.Created)
                    }
                }
            client.addLink("https://youtu.be/x", preferred = "1080")
            assertEquals("""{"url":"https://youtu.be/x","preset":"720"}""", bodyOf(seen.last()))
        }

    @Test
    fun `a playlist link is refused with a pointer to the web app`() =
        runTest {
            val error =
                assertFailsWith<PlaylistLink> {
                    api { json(ok("""{"type":"playlist","extractor":"YoutubeTab","id":"PL1","title":"list"}""")) }
                        .addLink("https://youtube.com/playlist?list=PL1", preferred = "best")
                }
            assertEquals("Playlists are added from the web app for now", error.message)
        }

    @Test
    fun `a 401 anywhere is Unauthorized`() =
        runTest {
            assertIs<Unauthorized>(
                assertFailsWith<ApiException> {
                    api { json("""{"status":"error","code":401,"message":"bad key"}""", HttpStatusCode.Unauthorized) }.summary()
                },
            )
        }

    @Test
    fun `a file URL carries the key in its query, for a player that can't send headers`() {
        val client = api { json(ok("null")) }
        // A download's one file is index 0; every file is fetched by its index.
        assertEquals("http://nas:8030/download/t1/file/0?api_key=k", client.fileUrl("t1"))
        assertEquals("http://nas:8030/download/t1/file/2?api_key=k", client.fileUrl("t1", 2))
        assertEquals("http://nas:8030/download/t1/file/0", api(key = null) { json(ok("null")) }.fileUrl("t1"))
    }

    @Test
    fun `choosePreset takes the preferred one, else the first offered`() {
        assertEquals("720", choosePreset(listOf("1080", "720"), "720"))
        assertEquals("1080", choosePreset(listOf("1080", "720"), "2160"))
        assertEquals(null, choosePreset(emptyList(), "best"))
    }

    @Test
    fun `a collection's videos come from its downloads page`() =
        runTest {
            val rows = api { json(ok("[$TASK]")) }.entries("g1")
            assertEquals("/collection/g1/downloads", seen.last().url.encodedPath)
            assertEquals(HttpMethod.Get, seen.last().method)
            assertEquals(listOf("t1"), rows.map { it.id })
        }
}
