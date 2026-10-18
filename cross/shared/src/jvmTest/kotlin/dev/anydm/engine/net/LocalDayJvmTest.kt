package dev.anydm.engine.net

import io.kotest.matchers.shouldBe
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Instant

class LocalDayJvmTest {
    private val saved = TimeZone.getDefault()

    @AfterTest
    fun restoreZone() = TimeZone.setDefault(saved)

    @Test
    fun theDayFollowsTheSystemZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka")) // UTC+6
        localDayOf(Instant.parse("2026-10-18T17:59:59Z")) shouldBe "2026-10-18"
        localDayOf(Instant.parse("2026-10-18T18:00:00Z")) shouldBe "2026-10-19"
    }
}
