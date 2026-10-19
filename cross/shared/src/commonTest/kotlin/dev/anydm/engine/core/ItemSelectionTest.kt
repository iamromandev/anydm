package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import kotlin.test.Test

class ItemSelectionTest {
    @Test
    fun itemSelectionRefusesABadRange() {
        for (bad in listOf("abc", "", "1:5;2", "x".repeat(201), "1 : 5")) {
            shouldThrow<IllegalArgumentException> { ItemSelection(range = bad) }
        }
    }

    @Test
    fun itemSelectionRefusesABadLimit() {
        for (bad in listOf(0, -1, 5001)) {
            shouldThrow<IllegalArgumentException> { ItemSelection(limit = bad) }
        }
    }
}
