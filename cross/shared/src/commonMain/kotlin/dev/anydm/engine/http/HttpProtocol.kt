package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.protocol.FinalizeResult
import dev.anydm.engine.protocol.PlanContext
import dev.anydm.engine.protocol.ProbeRequest
import dev.anydm.engine.protocol.ProbeResult
import dev.anydm.engine.protocol.Protocol
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.Transfer
import dev.anydm.engine.protocol.TransferIo
import dev.anydm.engine.protocol.TransferPlan

/** What a probe learned about a direct URL (HTTP-01, HTTP-03). */
data class HttpProbe(
    override val name: String,
    override val size: Long?,
    /** `etag:<value>` (a weak `W/` kept), else `last-modified:<value>`, else null. */
    override val validator: String?,
    val rangesSupported: Boolean,
    val finalUrl: String,
    val contentType: String?,
) : ProbeResult

/**
 * Direct HTTP(S) downloads. The probe asks for the first KiB with a `Range` so one round trip shows the size, range
 * support and validator; every request carries the job's [RequestContext] (HTTP-10). Transfers arrive with #569.
 */
class HttpProtocol(
    private val transport: HttpTransport,
    private val userAgent: String = "anydm",
) : Protocol {
    override suspend fun probe(request: ProbeRequest): StepResult<ProbeResult> {
        val headers = listOf("Range" to "bytes=0-${PROBE_BYTES - 1}") + contextHeaders(request.context)
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
                StepResult.Ok(probeOf(response, size = range, rangesSupported = true))
            }

            200 -> {
                StepResult.Ok(probeOf(response, size = plainSize(response.headers), rangesSupported = false))
            }

            416 -> {
                unsatisfied(response.headers["Content-Range"])
                    ?.let { StepResult.Ok(probeOf(response, size = it, rangesSupported = true)) }
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
    ): StepResult<TransferPlan> = notYet

    override suspend fun transfer(
        transfer: Transfer,
        io: TransferIo,
    ): StepResult<Unit> = notYet

    override suspend fun finalize(transfer: Transfer): StepResult<FinalizeResult> = notYet

    /** The context's headers, then Referer, User-Agent and Cookie (RequestContext admits only what HTTP can send). */
    private fun contextHeaders(context: RequestContext?): List<Pair<String, String>> {
        val derived =
            listOfNotNull(
                context?.referrer?.let { "Referer" to it },
                "User-Agent" to (context?.userAgent ?: userAgent),
                context?.cookies?.let { "Cookie" to it },
            )
        val own = context?.headers.orEmpty().filterNot { it.first.equals("Range", ignoreCase = true) }
        val overridden = own.map { it.first.lowercase() }.toSet()
        return own + derived.filterNot { it.first.lowercase() in overridden }
    }

    private fun probeOf(
        response: HttpResponse,
        size: Long?,
        rangesSupported: Boolean,
    ): HttpProbe {
        val contentType = response.headers["Content-Type"]
        return HttpProbe(
            name = fileNameFor(response.headers["Content-Disposition"], response.finalUrl, contentType),
            size = size,
            validator = validatorOf(response.headers),
            rangesSupported = rangesSupported,
            finalUrl = response.finalUrl,
            contentType = contentType,
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
