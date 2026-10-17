package dev.anydm.engine.http

import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.protocol.StoredBytes
import kotlin.time.Instant

/** The most bytes one relink sample compares (HTTP-13). */
internal const val SAMPLE = 64 * 1024

/**
 * What a relink compares, from file 0's written ranges: A, the first [SAMPLE] bytes of a range that starts at 0; and B,
 * [SAMPLE] bytes centred in the largest range (the earliest of equals), dropped if it overlaps A. A file with the same
 * header but another body (ISOs begin with 32 KiB of zeros) fails on B.
 */
internal fun samplesOf(written: List<LongRange>): List<LongRange> {
    val a = written.firstOrNull { it.first == 0L }?.let { 0L until minOf(SAMPLE.toLong(), it.last + 1) }
    val largest = written.maxByOrNull { it.last - it.first } ?: return listOfNotNull(a)
    val end = largest.last + 1
    val start = (largest.first + (end - largest.first) / 2 - SAMPLE / 2).coerceIn(largest.first, maxOf(largest.first, end - SAMPLE))
    val b = start until minOf(start + SAMPLE, end)
    return if (a != null && b.first <= a.last) listOf(a) else listOfNotNull(a, b)
}

/**
 * Fetches each of [samples] from the probed link and compares it with [stored]; null when all match. A link without
 * ranges (or one that answers a sample with 200) is compared on the sample at 0 only, read from the start of a plain
 * GET. Every body is closed.
 */
internal suspend fun compareSamples(
    transport: HttpTransport,
    probe: HttpProbe,
    headers: List<Pair<String, String>>,
    stored: StoredBytes,
    samples: List<LongRange>,
    now: () -> Instant,
): StepResult.Failed? {
    if (probe.rangesSupported) {
        for (sample in samples) {
            val fetched =
                when (val got = ranged(transport, probe, headers, sample, now)) {
                    null -> return whole(transport, probe, headers, stored, samples, now)
                    is StepResult.Failed -> return got
                    is StepResult.Ok -> got.value
                }
            compare(stored, sample.first, fetched, sample.count())?.let { return it }
        }
        return null
    }
    return whole(transport, probe, headers, stored, samples, now)
}

/** The sample's bytes from a `Range` request; null for a 200 (the link ignores ranges). */
private suspend fun ranged(
    transport: HttpTransport,
    probe: HttpProbe,
    headers: List<Pair<String, String>>,
    sample: LongRange,
    now: () -> Instant,
): StepResult<ByteArray>? {
    val response =
        try {
            transport.open(HttpRequest(probe.finalUrl, headers = listOf("Range" to "bytes=${sample.first}-${sample.last}") + headers))
        } catch (e: HttpIoException) {
            return failedOf(e)
        }
    try {
        val asked = "bytes ${sample.first}-${sample.last}/${probe.size}"
        return when {
            response.status == 200 -> null
            response.status >= 400 -> failedOf(response, now())
            response.status != 206 || response.headers["Content-Range"]?.trim() != asked -> wrongly
            else -> readUpTo(response.body, sample.count()).takeIf { it.size == sample.count() }?.let { StepResult.Ok(it) } ?: wrongly
        }
    } catch (e: HttpIoException) {
        return failedOf(e)
    } finally {
        response.body.close()
    }
}

/** Compares the sample at 0 with the start of a plain GET. */
private suspend fun whole(
    transport: HttpTransport,
    probe: HttpProbe,
    headers: List<Pair<String, String>>,
    stored: StoredBytes,
    samples: List<LongRange>,
    now: () -> Instant,
): StepResult.Failed? {
    val first =
        samples.firstOrNull { it.first == 0L }
            ?: return StepResult.Failed(null, "can't compare: ${hostOf(probe.finalUrl)} ignores ranges and nothing is written at the start")
    val response =
        try {
            transport.open(HttpRequest(probe.finalUrl, headers = headers))
        } catch (e: HttpIoException) {
            return failedOf(e)
        }
    try {
        if (response.status >= 400) return failedOf(response, now())
        if (response.status != 200) return wrongly
        val fetched = readUpTo(response.body, first.count())
        return compare(stored, 0, fetched, first.count())
    } catch (e: HttpIoException) {
        return failedOf(e)
    } finally {
        response.body.close()
    }
}

private val wrongly = StepResult.Failed(null, "new link answered the sample range wrongly")

/** Up to [length] bytes, fewer if the body ends first. */
private suspend fun readUpTo(
    body: HttpBody,
    length: Int,
): ByteArray {
    val buffer = ByteArray(length)
    var got = 0
    while (got < length) {
        val n = body.read(buffer, got, length - got)
        if (n < 0) break
        got += n
    }
    return buffer.copyOf(got)
}

/** [fetched] against the stored bytes at [offset]; a short [fetched] differs where it ends. */
private suspend fun compare(
    stored: StoredBytes,
    offset: Long,
    fetched: ByteArray,
    length: Int,
): StepResult.Failed? {
    val mine =
        when (val read = stored.read(0, offset, length)) {
            is StepResult.Failed -> return read
            is StepResult.Ok -> read.value
        }
    val at = (0 until length).firstOrNull { it >= fetched.size || fetched[it] != mine[it] } ?: return null
    return StepResult.Failed(null, "new link's bytes differ near offset ${offset + at}")
}
