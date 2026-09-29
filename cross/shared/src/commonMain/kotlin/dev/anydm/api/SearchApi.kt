package dev.anydm.api

import dev.anydm.model.SearchAnswer
import dev.anydm.model.SearchSources

/** What a result's `.torrent` link turned into. */
sealed interface FetchedTorrent {
    data class Magnet(
        val value: String,
    ) : FetchedTorrent

    /** A `.torrent` file, base64-encoded: what `TaskApi.addTorrent` takes. */
    data class File(
        val base64: String,
    ) : FetchedTorrent
}

/** What `SearchStore` asks of the API. `AnydmApi` implements it; tests fake it. */
interface SearchApi {
    suspend fun searchSources(): SearchSources

    /** An empty [q] browses the indexers' latest releases; [fresh] skips the API's browse cache. */
    suspend fun search(
        q: String,
        category: String,
        fresh: Boolean = false,
    ): SearchAnswer

    suspend fun fetchTorrent(link: String): FetchedTorrent
}
