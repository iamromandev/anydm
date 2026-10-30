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
        // Final review: a character outside the BMP is two UTF-16 units; encoded one by one they became "??".
        percentDecodedBytes("a😀%41")!!.decodeToString() shouldBe "a😀A"
    }

    @Test
    fun malformedEscapesAreNull() {
        for (bad in listOf("%+1", "%z1", "%4", "%", "a%2")) withClue(bad) { percentDecodedBytes(bad).shouldBeNull() }
    }
}
