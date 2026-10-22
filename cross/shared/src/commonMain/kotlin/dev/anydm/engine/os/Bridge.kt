package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind

/**
 * What the browser bridge asks of the app (OSI-02): adding from a page takes two steps. [add] reads the link without adding
 * it and gives back what it found with a token; [confirm] redeems the token, once and within its life, and adds the download
 * exactly as it was read. The HTTP side only speaks this, so it is tested without an engine.
 */
interface Bridge {
    suspend fun add(request: AddRequest): AddReply

    suspend fun confirm(token: String): ConfirmReply

    /** What the extension sends to [add]: the link and what the page's request carried. */
    data class AddRequest(
        val url: String,
        val cookies: String? = null,
        val headers: List<Pair<String, String>> = emptyList(),
        val referrer: String? = null,
        val pageUrl: String? = null,
    ) {
        override fun toString(): String =
            "AddRequest(url=$url, cookies=${if (cookies != null) "***" else null}, headers=${headers.map { it.first }})"
    }

    enum class Problem {
        /** The request cannot be used as sent: a header or cookie HTTP can't carry. */
        BAD_REQUEST,

        /** Not a web, FTP or torrent link. */
        NOT_A_LINK,

        /** The link was asked and did not give a file: it is not there, needs a login, or does not answer. */
        UNREADABLE,

        /** Nothing here can read this kind of link, or the link could not be added. */
        UNAVAILABLE,

        /** The link is already in the list. */
        DUPLICATE,
    }

    sealed interface AddReply {
        data class Probed(
            val token: String,
            val name: String?,
            val size: Long?,
            val kind: DownloadKind,
            val rangesSupported: Boolean,
            val duplicate: Boolean,
        ) : AddReply

        data class Refused(
            val problem: Problem,
            val message: String,
        ) : AddReply
    }

    sealed interface ConfirmReply {
        data class Added(
            val id: DownloadId,
        ) : ConfirmReply

        /** The token was never given, was used, or ran out. */
        data object UnknownToken : ConfirmReply

        data class Refused(
            val problem: Problem,
            val message: String,
        ) : ConfirmReply
    }
}
