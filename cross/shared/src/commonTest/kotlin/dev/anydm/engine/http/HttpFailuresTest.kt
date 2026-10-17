package dev.anydm.engine.http

import dev.anydm.engine.core.ErrorClass.AUTH
import dev.anydm.engine.core.ErrorClass.GONE
import dev.anydm.engine.core.ErrorClass.RATE_LIMITED
import dev.anydm.engine.core.ErrorClass.TRANSIENT
import dev.anydm.engine.http.HttpIoKind.BAD_URL
import dev.anydm.engine.http.HttpIoKind.CONNECT
import dev.anydm.engine.http.HttpIoKind.OTHER
import dev.anydm.engine.http.HttpIoKind.RESET
import dev.anydm.engine.http.HttpIoKind.TIMEOUT
import dev.anydm.engine.http.HttpIoKind.TLS
import dev.anydm.engine.http.HttpIoKind.TOO_MANY_REDIRECTS
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HttpFailuresTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")

    @Test
    fun statusTable() {
        val expected =
            mapOf(
                401 to AUTH,
                403 to AUTH,
                407 to AUTH,
                404 to GONE,
                410 to GONE,
                429 to RATE_LIMITED,
                408 to TRANSIENT,
                416 to TRANSIENT,
                500 to TRANSIENT,
                503 to TRANSIENT,
                599 to TRANSIENT,
                400 to null,
                418 to null,
                304 to null,
            )
        for ((status, errorClass) in expected) {
            withClue(status) {
                classOf(status) shouldBe errorClass
                failedOf(response(status), now).errorClass shouldBe errorClass
            }
        }
    }

    @Test
    fun ioTable() {
        val expected =
            mapOf(
                CONNECT to TRANSIENT,
                TIMEOUT to TRANSIENT,
                RESET to TRANSIENT,
                OTHER to TRANSIENT,
                TLS to null,
                TOO_MANY_REDIRECTS to null,
                BAD_URL to null,
            )
        for ((kind, errorClass) in expected) withClue(kind) { failedOf(HttpIoException(kind, "h")).errorClass shouldBe errorClass }
    }

    @Test
    fun ioMessagesNameTheHost() {
        val expected =
            mapOf(
                CONNECT to "can't connect (h)",
                TIMEOUT to "timed out (h)",
                RESET to "connection broke (h)",
                TLS to "TLS handshake failed (h)",
                TOO_MANY_REDIRECTS to "more than 5 redirects (h)",
                BAD_URL to "not an HTTP URL (h)",
                OTHER to "network error (h)",
            )
        for ((kind, message) in expected) {
            withClue(kind) {
                HttpIoException(kind, "h", IllegalStateException("secret.example/path?token=s")).message shouldBe message
                failedOf(HttpIoException(kind, "h")).message shouldBe message
            }
        }
    }

    @Test
    fun retryAfterFollows408And429And5xx() {
        for (status in listOf(408, 429, 500, 503)) {
            withClue(status) { failedOf(response(status, "Retry-After" to "7"), now).retryAfter shouldBe 7.seconds }
        }
    }

    @Test
    fun retryAfterOnlyOnRetryableStatuses() {
        for (status in listOf(401, 403, 404, 410, 416, 418)) {
            withClue(status) { failedOf(response(status, "Retry-After" to "7"), now).retryAfter.shouldBeNull() }
        }
    }

    @Test
    fun messagesHoldOnlyTheHost() {
        failedOf(response(503, finalUrl = "https://u:p@h:8443/a/f.zip?token=s"), now).message shouldBe "HTTP 503 from h:8443"
    }
}
