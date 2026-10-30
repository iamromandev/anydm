package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PacingTest {
    @Test
    fun presetsHaveTheirValues() {
        Pacing(PacingPreset.OFF).values() shouldBe PacingValues(0.0, 0.0, 0.0)
        Pacing(PacingPreset.BALANCED).values() shouldBe PacingValues(1.0, 1.0, 3.0)
        Pacing(PacingPreset.CAREFUL).values() shouldBe PacingValues(3.0, 5.0, 15.0)
        Pacing(PacingPreset.CUSTOM, PacingValues(2.0, 4.0, 6.0)).values() shouldBe PacingValues(2.0, 4.0, 6.0)
    }

    @Test
    fun customIsBounded() {
        shouldThrow<IllegalArgumentException> { PacingValues(121.0, 0.0, 0.0) }
        shouldThrow<IllegalArgumentException> { PacingValues(-1.0, 0.0, 0.0) }
        shouldThrow<IllegalArgumentException> { PacingValues(1.0, 9.0, 3.0) }
        PacingValues(120.0, 120.0, 120.0).requestSleep shouldBe 120.0
    }

    @Test
    fun flagsOnlyForPositiveValues() {
        PacingValues(0.0, 0.0, 0.0).ytDlpFlags() shouldBe emptyList()
        Pacing(PacingPreset.BALANCED).values().ytDlpFlags() shouldBe
            listOf("--sleep-requests", "1.0", "--sleep-interval", "1.0", "--max-sleep-interval", "3.0", "--sleep-subtitles", "1.0")
        PacingValues(0.0, 2.0, 2.0).ytDlpFlags() shouldBe listOf("--sleep-interval", "2.0")
        PacingValues(0.5, 0.0, 0.0).ytDlpFlags() shouldBe listOf("--sleep-requests", "0.5", "--sleep-subtitles", "0.5")
    }
}
