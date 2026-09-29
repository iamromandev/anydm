package dev.anydm.store

import dev.anydm.api.FetchedTorrent
import dev.anydm.api.SearchApi
import dev.anydm.model.FoundTorrent
import dev.anydm.model.SearchAnswer
import dev.anydm.model.SearchSources
import kotlinx.coroutines.CompletableDeferred

fun found(
    title: String,
    magnet: String? = "magnet:?xt=urn:btih:aa",
    link: String? = null,
    indexers: List<String> = listOf("apibay"),
) = FoundTorrent(title = title, magnet = magnet, link = link, indexers = indexers)

/** A scripted search API: what each call answers, and what it was asked. */
class FakeSearchApi : SearchApi {
    var enabled = true
    var sourcesFailure: Exception? = null
    var answer = SearchAnswer(results = listOf(found("a")), asked = listOf("apibay"), tookMs = 5)
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    val searches = mutableListOf<Triple<String, String, Boolean>>()
    var fetched: FetchedTorrent = FetchedTorrent.Magnet("magnet:?xt=urn:btih:ff")
    var fetchFailure: Exception? = null
    val fetches = mutableListOf<String>()

    override suspend fun searchSources(): SearchSources = sourcesFailure?.let { throw it } ?: SearchSources(enabled, listOf("apibay"))

    override suspend fun search(
        q: String,
        category: String,
        fresh: Boolean,
    ): SearchAnswer {
        searches += Triple(q, category, fresh)
        gate?.await()
        failure?.let { throw it }
        return answer
    }

    override suspend fun fetchTorrent(link: String): FetchedTorrent {
        fetches += link
        fetchFailure?.let { throw it }
        return fetched
    }
}
