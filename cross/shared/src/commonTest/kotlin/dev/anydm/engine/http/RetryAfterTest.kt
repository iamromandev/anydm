package dev.anydm.engine.http

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RetryAfterTest {
    private val now = Instant.parse("1994-11-06T08:49:00Z")

    @Test
    fun deltaSeconds() {
        retryAfterOf("120", now) shouldBe 120.seconds
        retryAfterOf("  0 ", now) shouldBe Duration.ZERO
    }

    @Test
    fun imfFixdate() {
        retryAfterOf("Sun, 06 Nov 1994 08:49:37 GMT", now) shouldBe 37.seconds
    }

    @Test
    fun rfc850() {
        retryAfterOf("Sunday, 06-Nov-94 08:49:37 GMT", now) shouldBe 37.seconds
    }

    @Test
    fun rfc850CenturyRule() {
        val in2030 = Instant.parse("2030-01-01T00:00:00Z")
        // 94 would be 2094, more than 50 years after 2030, so it is 1994 (past); 35 stays 2035.
        retryAfterOf("Sunday, 06-Nov-94 08:49:37 GMT", in2030) shouldBe Duration.ZERO
        retryAfterOf("Monday, 01-Jan-35 00:00:00 GMT", in2030) shouldBe (Instant.parse("2035-01-01T00:00:00Z") - in2030)
    }

    @Test
    fun asctime() {
        retryAfterOf("Sun Nov  6 08:49:37 1994", now) shouldBe 37.seconds
    }

    @Test
    fun aPastDateIsZero() {
        retryAfterOf("Sun, 06 Nov 1994 08:00:00 GMT", now) shouldBe Duration.ZERO
    }

    @Test
    fun leapDay() {
        retryAfterOf("Thu, 29 Feb 2024 00:00:10 GMT", Instant.parse("2024-02-29T00:00:00Z")) shouldBe 10.seconds
    }

    @Test
    fun garbageIsNull() {
        val values =
            listOf(
                null,
                "",
                "  ",
                "-5",
                "1.5",
                "soon",
                "Sun, 06 Foo 1994 08:49:37 GMT",
                "Sun, 06 Nov 1994 25:49:37 GMT",
                "Sun, 32 Nov 1994 08:49:37 GMT",
                "Sun, 29 Feb 2023 08:49:37 GMT",
                "Sun, 06 Nov 1994 08:49:37 PST",
            )
        for (value in values) withClue(value) { retryAfterOf(value, now).shouldBeNull() }
    }

    @Test
    fun hugeIsClamped() {
        retryAfterOf("99999999999999999999999", now) shouldBe 1_000_000_000.seconds
        retryAfterOf("2000000000", now) shouldBe 1_000_000_000.seconds
    }
}
