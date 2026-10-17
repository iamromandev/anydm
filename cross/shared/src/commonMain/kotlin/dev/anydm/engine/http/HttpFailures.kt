package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.protocol.StepResult
import kotlin.time.Instant

/**
 * The class of an HTTP error status (HTTP-09): credentials refused → AUTH, gone → GONE, slow down → RATE_LIMITED, and
 * a timeout, a server error or a range the file no longer has (the next probe plans afresh) → TRANSIENT. Anything else
 * has no class and fails the job.
 */
internal fun classOf(status: Int): ErrorClass? =
    when (status) {
        401, 403, 407 -> ErrorClass.AUTH
        404, 410 -> ErrorClass.GONE
        429 -> ErrorClass.RATE_LIMITED
        408, 416, in 500..599 -> ErrorClass.TRANSIENT
        else -> null
    }

/** A network failure is TRANSIENT, except those retrying can't fix: TLS, a redirect loop, a URL that isn't HTTP. */
internal fun classOf(kind: HttpIoKind): ErrorClass? =
    when (kind) {
        HttpIoKind.TLS, HttpIoKind.TOO_MANY_REDIRECTS, HttpIoKind.BAD_URL -> null
        HttpIoKind.CONNECT, HttpIoKind.TIMEOUT, HttpIoKind.RESET, HttpIoKind.OTHER -> ErrorClass.TRANSIENT
    }

/** [response]'s status as a failure naming only the host; 408, 429 and 5xx carry the server's `Retry-After`. */
internal fun failedOf(
    response: HttpResponse,
    now: Instant,
): StepResult.Failed {
    val status = response.status
    val waits = status == 408 || status == 429 || status in 500..599
    return StepResult.Failed(
        errorClass = classOf(status),
        message = "HTTP $status from ${hostOf(response.finalUrl)}",
        retryAfter = if (waits) retryAfterOf(response.headers["Retry-After"], now) else null,
    )
}

internal fun failedOf(e: HttpIoException): StepResult.Failed = StepResult.Failed(classOf(e.kind), checkNotNull(e.message))
