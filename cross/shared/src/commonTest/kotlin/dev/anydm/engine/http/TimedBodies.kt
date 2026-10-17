package dev.anydm.engine.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A body that hands out [chunk] bytes every [every] of (virtual) time. With [failAfter] it throws a reset once that many
 * bytes are out; with [stallAfter] it hands out nothing more once that many are out; with [gate] it waits for it
 * before handing out its last chunk.
 */
class PacedBody(
    private val bytes: ByteArray,
    private val every: Duration,
    private val chunk: Int = 64 * 1024,
    private val failAfter: Int? = null,
    private val gate: CompletableDeferred<Unit>? = null,
    private val stallAfter: Int? = null,
) : HttpBody {
    private var at = 0
    var closed = false
        private set
    var atGate = false
        private set

    override suspend fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (at == bytes.size) return -1
        if (failAfter != null && at >= failAfter) throw HttpIoException(HttpIoKind.RESET, "connection reset")
        if (stallAfter != null && at >= stallAfter) awaitCancellation()
        delay(every)
        if (gate != null && at + minOf(chunk, length) >= bytes.size) {
            atGate = true
            gate.await()
        }
        val n = minOf(chunk, length, bytes.size - at)
        bytes.copyInto(into, offset, at, at + n)
        at += n
        return n
    }

    override fun close() {
        closed = true
    }
}

/** One link of [bytesPerSecond] that every body reading through it shares. */
class SharedPipe(
    private val bytesPerSecond: Long,
) {
    private val lock = Mutex()

    suspend fun take(n: Int) = lock.withLock { delay((n * 1000L / bytesPerSecond).milliseconds) }
}

class PipedBody(
    private val bytes: ByteArray,
    private val pipe: SharedPipe,
    private val chunk: Int = 64 * 1024,
) : HttpBody {
    private var at = 0
    var closed = false
        private set

    override suspend fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (at == bytes.size) return -1
        val n = minOf(chunk, length, bytes.size - at)
        pipe.take(n)
        bytes.copyInto(into, offset, at, at + n)
        at += n
        return n
    }

    override fun close() {
        closed = true
    }
}

/** Hands out [bytes] once, then never anything more. */
class HangingBody(
    private val bytes: ByteArray,
) : HttpBody {
    var closed = false
        private set
    private var sent = false

    override suspend fun read(
        into: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (sent) awaitCancellation()
        sent = true
        val n = minOf(length, bytes.size)
        bytes.copyInto(into, offset, 0, n)
        return n
    }

    override fun close() {
        closed = true
    }
}

/** Like [servingRanges], with each 206 body made by [body] from the range's bytes. */
fun pacedRanges(
    bytes: ByteArray,
    finalUrl: String = "https://h/f.bin",
    body: (ByteArray) -> HttpBody,
): suspend (HttpRequest) -> HttpResponse =
    { request ->
        val start = request.rangeStart()
        val last =
            request.headers
                .first { it.first == "Range" }
                .second
                .substringAfter('-')
                .toInt()
        checkNotNull(start) { "only ranged requests" }
        response(
            206,
            "Content-Range" to "bytes $start-$last/${bytes.size}",
            finalUrl = finalUrl,
            body = body(bytes.copyOfRange(start.toInt(), last + 1)),
        )
    }
