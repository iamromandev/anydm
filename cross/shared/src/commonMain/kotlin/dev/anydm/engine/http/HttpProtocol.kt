package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.NewSegment

/** What a probe learned about a direct URL (HTTP-01, HTTP-03). */
data class HttpProbe(
    override val name: String,
    override val size: Long?,
    /** `etag:<value>` (a weak `W/` kept), else `last-modified:<value>`, else null. */
    override val validator: String?,
    val rangesSupported: Boolean,
    val finalUrl: String,
    val contentType: String?,
    /** What the probe sent besides the URL; transfers send the same (HTTP-10). */
    val context: RequestContext? = null,
) : ProbeResult

/**
 * Direct HTTP(S) downloads. The probe asks for the first KiB with a `Range` so one round trip shows the size, range
 * support and validator; every request carries the job's [RequestContext] (HTTP-10). A file with a known size on a
 * server with ranges is planned in blocks (HTTP-05) fetched over [connections] connections, read once per run.
 */
class HttpProtocol(
    private val transport: HttpTransport,
    private val userAgent: String = "anydm",
    private val connections: () -> Int = { 4 },
) : Protocol {
    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> {
        val headers = listOf("Range" to "bytes=0-${PROBE_BYTES - 1}") + requestHeaders(request.context)
        val response =
            try {
                transport.open(HttpRequest(request.mirror.url, headers = headers))
            } catch (e: HttpIoException) {
                return failed(e)
            }
        try {
            skimBody(response.body)
        } finally {
            response.body.close()
        }
        return when (response.status) {
            206 -> {
                val range = contentRange(response.headers["Content-Range"])
                StepResult.Ok(probeOf(response, size = range, rangesSupported = true, request.context))
            }

            200 -> {
                StepResult.Ok(probeOf(response, size = plainSize(response.headers), rangesSupported = false, request.context))
            }

            416 -> {
                unsatisfied(response.headers["Content-Range"])
                    ?.let { StepResult.Ok(probeOf(response, size = it, rangesSupported = true, request.context)) }
                    ?: failed(response)
            }

            else -> {
                failed(response)
            }
        }
    }

    override fun plan(
        probe: ProbeResult,
        context: PlanContext,
    ): StepResult<TransferPlan> {
        check(probe is HttpProbe) { "HttpProtocol can't plan a ${probe::class.simpleName}" }
        val size = probe.size
        val segments =
            when {
                size == null || size == 0L || !probe.rangesSupported -> {
                    emptyList()
                }

                else -> {
                    context.stored
                        .firstOrNull { it.file.index == 0 && it.file.size == size && it.segments.isNotEmpty() }
                        ?.segments
                        ?.map { NewSegment(it.start, it.end) }
                        ?: planBlocks(size, connections().coerceIn(1, 16))
                }
            }
        val path = context.download.fileName ?: probe.name
        return StepResult.Ok(TransferPlan(listOf(PlannedFile(index = 0, path = path, size = size, segments = segments))))
    }

    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> {
        val probe = transfer.probe
        check(probe is HttpProbe) { "HttpProtocol can't transfer a ${probe::class.simpleName}" }
        val file = transfer.files.single()
        if (file.segments.isEmpty()) return notYet
        val connections = connections().coerceIn(1, 16)
        return RangeTransfer(transport, probe.finalUrl, requestHeaders(probe.context), io, connections).run(file)
    }

    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> = notYet

    /**
     * What every request sends: the context's headers, then Referer, User-Agent and Cookie (RequestContext admits only
     * what HTTP can send), and `Accept-Encoding: identity` — otherwise OkHttp asks for gzip and drops Content-Length,
     * which breaks sizes and ranges. A context's own Range or Accept-Encoding is never sent.
     */
    internal fun requestHeaders(context: RequestContext?): List<Pair<String, String>> {
        val derived =
            listOfNotNull(
                context?.referrer?.let { "Referer" to it },
                "User-Agent" to (context?.userAgent ?: userAgent),
                context?.cookies?.let { "Cookie" to it },
            )
        val own = context?.headers.orEmpty().filterNot { it.first.lowercase() in ownedHeaders }
        val overridden = own.map { it.first.lowercase() }.toSet()
        return own + derived.filterNot { it.first.lowercase() in overridden } + ("Accept-Encoding" to "identity")
    }

    private fun probeOf(
        response: HttpResponse,
        size: Long?,
        rangesSupported: Boolean,
        context: RequestContext?,
    ): HttpProbe {
        val contentType = response.headers["Content-Type"]
        return HttpProbe(
            name = fileNameFor(response.headers["Content-Disposition"], response.finalUrl, contentType),
            size = size,
            validator = validatorOf(response.headers),
            rangesSupported = rangesSupported,
            finalUrl = response.finalUrl,
            contentType = contentType,
            context = context,
        )
    }

    private fun failed(response: HttpResponse): StepResult.Failed =
        StepResult.Failed(classOf(response.status), "HTTP ${response.status} from ${hostOf(response.finalUrl)}")

    private fun failed(e: HttpIoException): StepResult.Failed =
        StepResult.Failed(
            errorClass = if (e.kind == HttpIoKind.TOO_MANY_REDIRECTS) null else ErrorClass.TRANSIENT,
            message = e.message?.takeUnless { it.isBlank() } ?: e.kind.name.lowercase(),
        )

    private companion object {
        const val PROBE_BYTES = 1024
        val ownedHeaders = setOf("range", "accept-encoding")
        val notYet = StepResult.Failed(null, "HTTP transfers arrive with #569")
        val totalRange = Regex("""bytes\s+\d+-\d+/(\d+|\*)""", RegexOption.IGNORE_CASE)
        val unsatisfiedRange = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
    }

    /** Reads at most [PROBE_BYTES]: the headers are the answer, and a server that ignores the range isn't drained. */
    private suspend fun skimBody(body: HttpBody) {
        val buffer = ByteArray(PROBE_BYTES)
        var total = 0
        try {
            while (total < PROBE_BYTES) {
                val n = body.read(buffer, total, PROBE_BYTES - total)
                if (n < 0) break
                total += n
            }
        } catch (_: HttpIoException) {
            // The headers already arrived; a body that breaks off changes nothing about them.
        }
    }

    /** The total in `bytes a-b/N`; null for `*` or anything that doesn't parse. */
    private fun contentRange(header: String?): Long? =
        header
            ?.trim()
            ?.let(totalRange::matchEntire)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    private fun unsatisfied(header: String?): Long? =
        header
            ?.trim()
            ?.let(unsatisfiedRange::matchEntire)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    /** Content-Length counts encoded bytes, so it's the file's size only without a real Content-Encoding. */
    private fun plainSize(headers: Headers): Long? {
        val encoding = headers["Content-Encoding"]?.trim()
        if (encoding != null && encoding.isNotEmpty() && !encoding.equals("identity", ignoreCase = true)) return null
        return headers["Content-Length"]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun validatorOf(headers: Headers): String? =
        headers["ETag"]?.trim()?.takeIf { it.isNotEmpty() }?.let { "etag:$it" }
            ?: headers["Last-Modified"]?.trim()?.takeIf { it.isNotEmpty() }?.let { "last-modified:$it" }

    private fun classOf(status: Int): ErrorClass? =
        when (status) {
            401, 403 -> ErrorClass.AUTH
            404, 410 -> ErrorClass.GONE
            429 -> ErrorClass.RATE_LIMITED
            408 -> ErrorClass.TRANSIENT
            in 500..599 -> ErrorClass.TRANSIENT
            else -> null
        }
}

/** The host of [url], without any user or password. */
internal fun hostOf(url: String): String =
    url
        .substringAfter("://", url)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')
