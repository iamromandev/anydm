package dev.anydm.engine.http

import dev.anydm.engine.core.Checksum
import dev.anydm.engine.core.Mirror
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.PlannedFile
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.RelinkRequest
import dev.anydm.engine.protocol.Relinked
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.TransferPlan
import dev.anydm.engine.store.NewSegment
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.TimeSource

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
    /** From `Digest` or `Repr-Digest` (HTTP-14). */
    override val checksum: Checksum? = null,
) : ProbeResult {
    override val mime: String? get() =
        contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
}

/**
 * Direct HTTP(S) downloads. The probe asks for the first KiB with a `Range` so one round trip shows the size, range
 * support and validator; every request carries the job's [RequestContext] (HTTP-10). A file with a known size on a
 * server with ranges is planned in blocks (HTTP-05) fetched over connections that grow up to the per-host cap [connections] (HTTP-06).
 * A 401 is answered with the login [credentials] hold for that origin (HTTP-11). The job's other mirrors whose size
 * and strong ETag match the probe fetch blocks too (HTTP-12).
 */
class HttpProtocol(
    transport: HttpTransport,
    private val userAgent: String = "anydm",
    private val connections: () -> Int = { 4 },
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    private val clock: Clock = Clock.System,
    credentials: CredentialStore = InMemoryCredentials(),
) : Protocol {
    /** Every request goes through it, so the probe and each block worker log in (HTTP-11). */
    private val auth = AuthTransport(transport, credentials)

    /** Connection slots per host, shared by every download this protocol serves (HTTP-06). */
    internal val slots = HostSlots { connections().coerceIn(1, 16) }

    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> =
        probeUrl(request.mirror.url, request.context, credentials = true)

    /** The probe of [url]; without [credentials] the context's Authorization, Proxy-Authorization and Cookie stay home. */
    private suspend fun probeUrl(
        url: String,
        context: RequestContext?,
        credentials: Boolean,
    ): StepResult<HttpProbe> {
        val sent =
            requestHeaders(context).let { all ->
                if (credentials) all else all.filterNot { it.first.lowercase() in credentialHeaders }
            }
        val headers = listOf("Range" to "bytes=0-${PROBE_BYTES - 1}") + sent + wantDigests
        val response =
            try {
                auth.open(HttpRequest(url, headers = headers))
            } catch (e: HttpIoException) {
                return failedOf(e)
            }
        try {
            skimBody(response.body)
        } finally {
            response.body.close()
        }
        return when (response.status) {
            206 -> {
                val range = contentRange(response.headers["Content-Range"])
                StepResult.Ok(probeOf(response, size = range, rangesSupported = true, context))
            }

            200 -> {
                StepResult.Ok(probeOf(response, size = plainSize(response.headers), rangesSupported = false, context))
            }

            416 -> {
                unsatisfied(response.headers["Content-Range"])
                    ?.let { StepResult.Ok(probeOf(response, size = it, rangesSupported = true, context)) }
                    ?: failedOf(response, clock.now())
            }

            else -> {
                failedOf(response, clock.now())
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
        val run = RangeTransfer(auth, sourceOf(probe.finalUrl, transfer.mirror, probe.context), io, slots, timeSource, clock = clock)
        if (file.segments.isEmpty() || !isStrongEtag(probe.validator) || transfer.mirrors.isEmpty()) return run.run(file)
        // Other mirrors join as their probes match; the run never waits for them (HTTP-12).
        return coroutineScope {
            val probes =
                transfer.mirrors.map { mirror ->
                    launch {
                        val credentials = originOf(mirror.url) == originOf(transfer.mirror.url)
                        val other = (probeUrl(mirror.url, probe.context, credentials) as? StepResult.Ok)?.value ?: return@launch
                        if (other.rangesSupported && other.size == probe.size && other.validator == probe.validator) {
                            run.addSource(sourceOf(other.finalUrl, transfer.mirror, probe.context))
                        }
                    }
                }
            run.run(file).also { probes.forEach { it.cancel() } }
        }
    }

    /**
     * Blocks from [finalUrl]: the context's credentials go only to [chosen]'s own origin. The probe sent them there
     * alone; a redirect elsewhere dropped them, and so do we.
     */
    private fun sourceOf(
        finalUrl: String,
        chosen: Mirror,
        context: RequestContext?,
    ): Source {
        val all = requestHeaders(context)
        val headers = if (originOf(finalUrl) == originOf(chosen.url)) all else all.filterNot { it.first.lowercase() in credentialHeaders }
        return Source(finalUrl, headers, originOf(finalUrl))
    }

    /**
     * Probes [RelinkRequest.url], refuses another size, then compares up to two samples of the bytes already stored
     * with the new link's (HTTP-13). Returns the new link's validator for the queue to adopt.
     */
    override suspend fun relink(request: RelinkRequest): StepResult<Relinked> {
        val probe =
            when (val probed = probeUrl(request.url, request.context, credentials = true)) {
                is StepResult.Failed -> return probed
                is StepResult.Ok -> probed.value
            }
        val old = request.download.size
        val new = probe.size
        if (old != null && new != null && old != new) return StepResult.Failed(null, "new link is $new bytes, not $old")
        val samples =
            samplesOf(
                request.stored.files
                    .firstOrNull { it.index == 0 }
                    ?.written
                    .orEmpty(),
            )
        if (samples.isNotEmpty()) {
            // The samples go where the probe was answered; credentials only if that is still the new link's origin.
            val headers =
                requestHeaders(request.context).let { all ->
                    if (originOf(probe.finalUrl) ==
                        originOf(request.url)
                    ) {
                        all
                    } else {
                        all.filterNot { it.first.lowercase() in credentialHeaders }
                    }
                }
            compareSamples(auth, probe, headers, request.stored, samples, clock::now)?.let { return it }
        }
        return StepResult.Ok(Relinked(probe.validator))
    }

    /** The transfer already checked that every byte arrived; the runner renames the partial into place. */
    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> =
        StepResult.Ok(FinalizeResult(transfer.files.map { it.file.path }))

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
            checksum = checksumOf(response.headers),
        )
    }

    private companion object {
        const val PROBE_BYTES = 1024
        val ownedHeaders = setOf("range", "accept-encoding")
        val credentialHeaders = setOf("authorization", "proxy-authorization", "cookie")
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
}

/** A strong `etag:` validator: only those promise identical bytes, so only those let mirrors share blocks (HTTP-12). */
internal fun isStrongEtag(validator: String?): Boolean =
    validator != null && validator.startsWith("etag:") && !validator.startsWith("etag:W/")

/** The host of [url], without any user or password. */
internal fun hostOf(url: String): String =
    url
        .substringAfter("://", url)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('@')

/** Scheme, host and port of [url] (the port spelled out for http and https), without any user or password. */
internal fun originOf(url: String): String {
    val scheme = url.substringBefore("://", "").lowercase()
    val authority = hostOf(url).lowercase()
    val hasPort = authority.substringAfterLast(']').contains(':')
    val port =
        when {
            hasPort -> ""
            scheme == "https" -> ":443"
            scheme == "http" -> ":80"
            else -> ""
        }
    return "$scheme://$authority$port"
}
