package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
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

    private fun pick(
        range: String?,
        total: Int,
        limit: Int? = null,
    ) = ItemSelection(range, limit).indices(total)

    @Test
    fun rangesAndOpenEnds() {
        pick("1:3,5,7:", 9) shouldBe listOf(1, 2, 3, 5, 7, 8, 9)
        pick(":2", 5) shouldBe listOf(1, 2)
        pick(":", 3) shouldBe listOf(1, 2, 3)
        pick("4", 5) shouldBe listOf(4)
    }

    @Test
    fun overlapsAreUnique() {
        pick("1:4,3:6,2", 8) shouldBe listOf(1, 2, 3, 4, 5, 6)
    }

    @Test
    fun limitCountsFromTheStart() {
        pick("2:9", 10, limit = 3) shouldBe listOf(2, 3, 4)
        pick(null, 10, limit = 2) shouldBe listOf(1, 2)
    }

    @Test
    fun outOfRangeIsIgnored() {
        pick("8:20,30", 10) shouldBe listOf(8, 9, 10)
        pick("11", 10) shouldBe emptyList()
    }

    @Test
    fun invalidTextParsesToNull() {
        for (bad in listOf("1:5,abc", "0", "5:2", "::", "-1", "9999999999", "1,,2", "1,", ",1", "1:2:3", "0:3")) {
            ItemSelection.parse(bad, null) shouldBe null
        }
        ItemSelection.parse("1:5,8", 10) shouldBe ItemSelection("1:5,8", 10)
    }

    @Test
    fun noRangeMeansAll() {
        pick(null, 4) shouldBe listOf(1, 2, 3, 4)
    }

    @Test
    fun anEmptyTotalGivesNothing() {
        pick("1:5", 0) shouldBe emptyList()
        pick(null, 0) shouldBe emptyList()
    }
}
