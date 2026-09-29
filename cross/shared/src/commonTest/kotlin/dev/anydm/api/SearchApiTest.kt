package dev.anydm.api

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
import kotlin.test.assertNull

private const val FOUND =
    """{"title":"Big Buck Bunny 1080p","size":725614592,"seeders":150,"published":"2026-09-28T10:00:00Z","category":"movies","info_hash":"dd82","magnet":"magnet:?xt=urn:btih:dd82","indexers":["prowlarr-1","apibay"]}"""
private const val BARE = """{"title":"Sintel Soundtrack","link":"http://prowlarr:9696/1/dl","indexers":["prowlarr-1"]}"""

private fun answer(vararg results: String) =
    """{"status":"success","code":200,"data":{"results":[${results.joinToString(
        ",",
    )}],"errors":[{"indexer":"nyaa","message":"timed out after 15 s"}],"asked":["prowlarr-1","nyaa"],"took_ms":912}}"""

class SearchApiTest {
    private val seen = mutableListOf<HttpRequestData>()

    private fun api(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        AnydmApi(
            ServerConfig("http://nas:8030/", "k"),
            MockEngine { request ->
                seen += request
                answer(request)
            },
        )

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun `the sources say whether search is on`() =
        runTest {
            val sources = api { json("""{"status":"success","data":{"enabled":true,"indexers":["apibay","nyaa"]}}""") }.searchSources()
            assertEquals("/search/sources", seen.last().url.encodedPath)
            assertEquals(true, sources.enabled)
            assertEquals(listOf("apibay", "nyaa"), sources.indexers)
        }

    @Test
    fun `a search sends q and the category, and no fresh unless asked`() =
        runTest {
            api { json(answer(FOUND)) }.search("big buck", "movies")
            val url = seen.last().url
            assertEquals("/search", url.encodedPath)
            assertEquals("big buck", url.parameters["q"])
            assertEquals("movies", url.parameters["category"])
            assertNull(url.parameters["fresh"])
        }

    @Test
    fun `an empty query browses - no q, and fresh when asked`() =
        runTest {
            api { json(answer()) }.search("", "tv", fresh = true)
            val url = seen.last().url
            assertNull(url.parameters["q"])
            assertEquals("tv", url.parameters["category"])
            assertEquals("1", url.parameters["fresh"])
        }

    @Test
    fun `an answer reads results, errors, who was asked and the time, with absent fields as unknown`() =
        runTest {
            val found = api { json(answer(FOUND, BARE)) }.search("x", "all")
            val first = found.results[0]
            assertEquals(725_614_592L, first.sizeBytes)
            assertEquals(150, first.seeders)
            assertNull(first.leechers)
            assertNull(first.link)
            assertEquals("dd82", first.infoHash)
            assertEquals(listOf("prowlarr-1", "apibay"), first.indexers)
            val bare = found.results[1]
            assertNull(bare.magnet)
            assertNull(bare.seeders)
            assertEquals("other", bare.category)
            assertEquals("http://prowlarr:9696/1/dl", bare.link)
            assertEquals("nyaa", found.errors.single().indexer)
            assertEquals(listOf("prowlarr-1", "nyaa"), found.asked)
            assertEquals(912L, found.tookMs)
        }

    @Test
    fun `a search that failed everywhere keeps each source's reason`() =
        runTest {
            val body =
                """{"status":"error","code":502,"message":"No indexer answered the search","type":"search_failed","details":[{"subject":"apibay","description":"couldn't reach it"},{"subject":"nyaa","description":"answered 503"}]}"""
            val error = assertFailsWith<ApiException> { api { json(body, HttpStatusCode.BadGateway) }.search("x", "all") }
            assertEquals("No indexer answered the search", error.message)
            assertEquals("search_failed", error.type)
            assertEquals(
                listOf(ErrorDetail("apibay", "couldn't reach it"), ErrorDetail("nyaa", "answered 503")),
                error.details,
            )
        }

    @Test
    fun `fetching a torrent posts the link and reads a magnet or a file`() =
        runTest {
            val magnet = api { json("""{"status":"success","data":{"magnet":"magnet:?xt=urn:btih:aa"}}""") }.fetchTorrent("http://p/dl")
            assertEquals(HttpMethod.Post, seen.last().method)
            assertEquals("/search/torrent", seen.last().url.encodedPath)
            assertEquals(
                """{"link":"http://p/dl"}""",
                seen
                    .last()
                    .body
                    .toByteArray()
                    .decodeToString(),
            )
            assertEquals(FetchedTorrent.Magnet("magnet:?xt=urn:btih:aa"), magnet)
            val file = api { json("""{"status":"success","data":{"torrent":"ZDg6"}}""") }.fetchTorrent("http://p/dl")
            assertEquals(FetchedTorrent.File("ZDg6"), file)
        }

    @Test
    fun `an answer with neither a magnet nor a file is refused`() =
        runTest {
            val error = assertFailsWith<ApiException> { api { json("""{"status":"success","data":{}}""") }.fetchTorrent("http://p/dl") }
            assertEquals("The indexer sent nothing to add", error.message)
        }
}
