package dev.anydm.engine.http

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PercentTest {
    @Test
    fun escapesDecode() {
        percentDecodedBytes("a%20b")!!.decodeToString() shouldBe "a b"
        percentDecodedBytes("%E2%82%AC%e2%82%ac")!!.decodeToString() shouldBe "€€"
        percentDecodedBytes("ü")!!.decodeToString() shouldBe "ü"
    }

    @Test
    fun malformedEscapesAreNull() {
        for (bad in listOf("%+1", "%z1", "%4", "%", "a%2")) withClue(bad) { percentDecodedBytes(bad).shouldBeNull() }
    }
}
