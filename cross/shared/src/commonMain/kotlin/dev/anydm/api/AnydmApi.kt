package dev.anydm.api

import dev.anydm.model.AnydmJson
import dev.anydm.model.BulkResultDto
import dev.anydm.model.ExtractMediaDto
import dev.anydm.model.FetchedDto
import dev.anydm.model.PageMetaDto
import dev.anydm.model.PlaylistDto
import dev.anydm.model.SearchAnswer
import dev.anydm.model.SearchSources
import dev.anydm.model.SummaryDto
import dev.anydm.model.TaskDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val API_KEY_HEADER = "X-API-Key"

/** How long an ordinary call may take. The event stream has no limit: it's meant to stay open. */
const val REQUEST_TIMEOUT_MS = 30_000L

/** One page of a list, and where it sits among the rest. */
data class Page<T>(
    val items: List<T>,
    val meta: PageMetaDto,
)

/** What `POST /extract` says a link is. */
sealed interface Extracted {
    data class Media(
        val media: ExtractMediaDto,
    ) : Extracted

    data class Listing(
        val listing: PlaylistDto,
    ) : Extracted
}

/** The preferred preset when the page offers it, else the first it does: the web's `choosePreset`. */
fun choosePreset(
    offered: List<String>,
    preferred: String,
): String? = offered.firstOrNull { it == preferred } ?: offered.firstOrNull()

/** The anydm API, over whichever Ktor engine the platform gives it. */
class AnydmApi(
    config: ServerConfig,
    engine: HttpClientEngine,
) : TaskApi,
    SearchApi {
    private val config = config.normalized()
    private val client =
        HttpClient(engine) {
            expectSuccess = false
            install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MS }
        }

    override suspend fun listTasks(
        page: Int,
        pageSize: Int,
        group: String,
        sort: String,
    ): Page<TaskDto> {
        val envelope =
            call(
                HttpMethod.Get,
                listOf("download"),
                query = mapOf("page" to page, "page_size" to pageSize, "group" to group, "sort" to sort),
            )
        return Page(decode(envelope.data), envelope.meta ?: PageMetaDto())
    }

    override suspend fun summary(): SummaryDto = decode(call(HttpMethod.Get, listOf("download", "summary")).data)

    override suspend fun entries(id: String): List<TaskDto> = decode(call(HttpMethod.Get, listOf("download", id, "entries")).data)

    suspend fun extract(url: String): Extracted {
        val data = call(HttpMethod.Post, listOf("extract"), body = obj("url" to url)).data
        val type =
            data
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.contentOrNull
        return if (type == "playlist" || type == "channel") {
            Extracted.Listing(decode(data))
        } else {
            Extracted.Media(decode(data))
        }
    }

    suspend fun addMedia(
        url: String,
        preset: String,
    ): TaskDto = decode(call(HttpMethod.Post, listOf("download", "media"), body = obj("url" to url, "preset" to preset)).data)

    suspend fun addUrl(url: String): TaskDto = decode(call(HttpMethod.Post, listOf("download", "url"), body = obj("url" to url)).data)

    /** A magnet link, or a `.torrent` file base64-encoded. An empty list takes every file. */
    override suspend fun addTorrent(
        torrent: String,
        files: List<Int>,
    ): TaskDto =
        decode(
            call(
                HttpMethod.Post,
                listOf("download", "torrent"),
                body = JsonObject(mapOf("torrent" to JsonPrimitive(torrent), "files" to JsonArray(files.map { JsonPrimitive(it) }))),
            ).data,
        )

    /**
     * A link nobody has looked at yet, routed as the web's `addLink` does: a page is downloaded at
     * [preferred] or the closest preset it offers, a link no site supports is a direct download,
     * and a playlist or channel is refused with [PlaylistLink].
     */
    override suspend fun addLink(
        url: String,
        preferred: String,
    ): TaskDto {
        val found =
            try {
                extract(url)
            } catch (error: ApiException) {
                if (error.type == "unsupported_url") return addUrl(url)
                throw error
            }
        return when (found) {
            is Extracted.Listing -> {
                throw PlaylistLink(found.listing)
            }

            is Extracted.Media -> {
                val preset =
                    choosePreset(found.media.presets, preferred)
                        ?: throw ApiException("Nothing on this page can be downloaded yet", null, null)
                addMedia(url, preset)
            }
        }
    }

    override suspend fun pause(id: String): TaskDto = decode(call(HttpMethod.Post, listOf("download", id, "pause")).data)

    override suspend fun resume(id: String): TaskDto = decode(call(HttpMethod.Post, listOf("download", id, "resume")).data)

    override suspend fun remove(
        id: String,
        deleteFiles: Boolean,
    ) {
        call(HttpMethod.Delete, listOf("download", id), query = mapOf("delete_files" to deleteFiles))
    }

    override suspend fun stopSeeding(id: String) {
        call(HttpMethod.Post, listOf("download", id, "seed", "stop"))
    }

    /** `pause_all`, `resume_all` or `clear_finished`; how many rows it touched. */
    override suspend fun bulk(action: String): Int =
        decode<BulkResultDto>(call(HttpMethod.Post, listOf("download", "bulk"), body = obj("action" to action)).data).affected

    /** A finished file's URL, with the key in its query: for a player or a save that can't send headers. */
    fun fileUrl(
        id: String,
        fileIndex: Int? = null,
    ): String =
        URLBuilder(config.baseUrl)
            .apply {
                appendPathSegments("download", id, "file")
                if (fileIndex != null) appendPathSegments(fileIndex.toString())
                config.apiKey?.let { parameters.append("api_key", it) }
            }.buildString()

    /** The live stream (see [openEvents]). A member, so it can stand in for [TaskApi.events]. */
    override fun events(): Flow<ServerEvent> = openEvents()

    override suspend fun searchSources(): SearchSources = decode(call(HttpMethod.Get, listOf("search", "sources")).data)

    override suspend fun search(
        q: String,
        category: String,
        fresh: Boolean,
    ): SearchAnswer {
        val query =
            buildMap<String, Any> {
                if (q.isNotEmpty()) put("q", q)
                put("category", category)
                if (fresh) put("fresh", 1)
            }
        return decode(call(HttpMethod.Get, listOf("search"), query = query).data)
    }

    override suspend fun fetchTorrent(link: String): FetchedTorrent {
        val fetched = decode<FetchedDto>(call(HttpMethod.Post, listOf("search", "torrent"), body = obj("link" to link)).data)
        return fetched.magnet?.let { FetchedTorrent.Magnet(it) }
            ?: fetched.torrent?.let { FetchedTorrent.File(it) }
            ?: throw ApiException("The indexer sent nothing to add", null, null)
    }

    fun close() = client.close()

    internal val baseUrl: String get() = config.baseUrl
    internal val apiKey: String? get() = config.apiKey
    internal val http: HttpClient get() = client

    private suspend fun call(
        method: HttpMethod,
        path: List<String>,
        query: Map<String, Any> = emptyMap(),
        body: JsonElement? = null,
    ): Envelope {
        val response =
            client.request(URLBuilder(config.baseUrl).apply { appendPathSegments(path) }.buildString()) {
                this.method = method
                config.apiKey?.let { header(API_KEY_HEADER, it) }
                query.forEach { (name, value) -> parameter(name, value) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                }
            }
        return unwrap(response.status.value, response.bodyAsText())
    }

    private fun obj(vararg fields: Pair<String, String>) = JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) })

    private inline fun <reified T> decode(data: JsonElement?): T =
        AnydmJson.decodeFromJsonElement(data ?: throw ApiException("The API answered with no data", null, null))
}
