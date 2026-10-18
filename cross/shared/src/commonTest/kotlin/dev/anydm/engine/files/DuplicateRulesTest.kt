package dev.anydm.engine.files

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DuplicateRulesTest {
    @Test
    fun trackingParametersDontMakeUrlsDiffer() {
        DuplicateRules.sameUrl("https://h/f.zip?utm_source=a", "https://h/f.zip") shouldBe true
        DuplicateRules.sameUrl("https://h/f.zip?fbclid=x&id=1", "https://h/f.zip?id=1") shouldBe true
    }

    @Test
    fun aDifferentPathOrQueryDiffers() {
        DuplicateRules.sameUrl("https://h/f.zip", "https://h/g.zip") shouldBe false
        DuplicateRules.sameUrl("https://h/f.zip?id=1", "https://h/f.zip?id=2") shouldBe false
    }

    @Test
    fun sameNameSizeAndValidatorIsTheSameFile() {
        DuplicateRules.sameFile("a.zip", 10, "etag:\"1\"", "A.ZIP", 10, "etag:\"1\"") shouldBe true
    }

    @Test
    fun sameNameAndSizeWithoutValidatorsIsTheSameFile() {
        DuplicateRules.sameFile("a.zip", 10, null, "a.zip", 10, null) shouldBe true
        DuplicateRules.sameFile("a.zip", 10, "etag:\"1\"", "a.zip", 10, null) shouldBe true
    }

    @Test
    fun differentValidatorsAreDifferentFiles() {
        DuplicateRules.sameFile("a.zip", 10, "etag:\"1\"", "a.zip", 10, "etag:\"2\"") shouldBe false
    }

    @Test
    fun differentNameOrSizeIsNotTheSameFile() {
        DuplicateRules.sameFile("a.zip", 10, null, "b.zip", 10, null) shouldBe false
        DuplicateRules.sameFile("a.zip", 10, null, "a.zip", 11, null) shouldBe false
    }

    @Test
    fun anUnknownSizeNeverMatches() {
        DuplicateRules.sameFile("a.zip", null, null, "a.zip", null, null) shouldBe false
        DuplicateRules.sameFile("a.zip", 10, null, "a.zip", null, null) shouldBe false
    }
}
