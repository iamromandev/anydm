package dev.anydm.engine.http

sealed interface ResumeDecision {
    data object Continue : ResumeDecision

    data object Restart : ResumeDecision

    data class Fail(
        val reason: String,
    ) : ResumeDecision
}

/** What to do with a response to a (possibly ranged) request that already has [done] bytes on disk. */
fun resumeDecision(
    status: Int,
    contentRange: String?,
    done: Long,
): ResumeDecision =
    when {
        status == 206 -> {
            val start =
                contentRange?.let {
                    Regex("""bytes\s+(\d+)-""")
                        .find(it)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                }
            if (start == null || start == done) ResumeDecision.Continue else ResumeDecision.Fail(Reasons.CHANGED)
        }

        status == 200 -> {
            ResumeDecision.Restart
        }

        status == 416 -> {
            ResumeDecision.Fail(Reasons.CHANGED)
        }

        else -> {
            ResumeDecision.Fail(Reasons.http(status))
        }
    }

/**
 * The validator for an `If-Range` header: a strong ETag, else the `Last-Modified` date, else none. A weak ETag (`W/"..."`) never
 * matches an `If-Range` (RFC 9110, 13.1.5), so sending one makes the server answer 200 to every ranged request.
 */
fun ifRangeOf(
    etag: String?,
    modified: String?,
): String? = etag?.takeUnless { it.trimStart().startsWith("W/") } ?: modified
