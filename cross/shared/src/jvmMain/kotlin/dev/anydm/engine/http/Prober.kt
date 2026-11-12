package dev.anydm.engine.http

import java.io.IOException

interface LinkProber {
    suspend fun probe(url: String): ProbeOutcome
}

/** HEAD first; GET (closed right after the headers) when HEAD is refused or fails to get an answer. */
class HttpProber(
    private val transport: HttpTransport,
) : LinkProber {
    override suspend fun probe(url: String): ProbeOutcome =
        try {
            val head =
                try {
                    transport.open(url, "HEAD", emptyMap()).use { describe(it) }
                } catch (_: IOException) {
                    null // some servers drop or stall a HEAD: one GET decides
                }
            if (head != null) {
                head
            } else {
                transport.open(url, "GET", emptyMap()).use { describe(it) ?: ProbeOutcome.Failed(Reasons.http(it.status)) }
            }
        } catch (e: TransportException) {
            ProbeOutcome.Failed(e.reason)
        } catch (_: IOException) {
            ProbeOutcome.Failed(Reasons.NETWORK)
        }

    /** The result for a 2xx, a failure for 404/410, null when a GET is worth trying. */
    private fun describe(r: HttpOpen): ProbeOutcome? {
        if (r.status in 200..299) {
            val total =
                r.headers["content-length"]?.firstOrNull()?.toLongOrNull()
                    ?: r.headers["content-range"]
                        ?.firstOrNull()
                        ?.substringAfter('/')
                        ?.toLongOrNull()
            val name = nameFrom(r.headers["content-disposition"]?.firstOrNull(), r.finalUrl)
            val resumable = r.headers["accept-ranges"].orEmpty().any { it.contains("bytes", ignoreCase = true) }
            return ProbeOutcome.Ok(ProbeResult(name, total, resumable))
        }
        return if (r.status == 404 || r.status == 410) ProbeOutcome.Failed(Reasons.http(r.status)) else null
    }
}
