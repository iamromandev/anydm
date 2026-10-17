package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class RequestContextTest {
    // Final review: a CR/LF value split into extra headers through the store, and a non-ASCII value crashed the job.
    @Test
    fun whatHttpCannotSendIsRefused() {
        val bad =
            listOf<() -> RequestContext>(
                { RequestContext(headers = listOf("X-A" to "a\r\nX-Evil: 1")) },
                { RequestContext(headers = listOf("X A" to "1")) },
                { RequestContext(headers = listOf("X-A: b" to "1")) },
                { RequestContext(headers = listOf("" to "1")) },
                { RequestContext(headers = listOf("X-Name" to "Роман")) },
                { RequestContext(userAgent = "Mozilla é") },
                { RequestContext(referrer = "https://r/\n") },
                { RequestContext(cookies = "c=1\u0001") },
            )
        bad.forEachIndexed { i, make -> withClue(i) { shouldThrow<IllegalArgumentException> { make() } } }
    }

    @Test
    fun ordinaryValuesAreKept() {
        val context = RequestContext(listOf("X-A" to "v\twith tab ~", "X-B" to ""), "https://r/?q=1", "Mozilla/5.0 (X11)", "a=1; b=2")
        context.headers shouldBe listOf("X-A" to "v\twith tab ~", "X-B" to "")
    }
}
