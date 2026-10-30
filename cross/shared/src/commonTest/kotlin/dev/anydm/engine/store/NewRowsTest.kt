package dev.anydm.engine.store

import dev.anydm.engine.core.DownloadKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.io.files.Path
import kotlin.test.Test

class NewRowsTest {
    @Test
    fun newDownloadRejectsImpossibleValues() {
        val valid = NewDownload(DownloadKind.DIRECT, mirrors = listOf("https://example.com/a"))

        valid.priority shouldBe 0
        shouldThrow<IllegalArgumentException> { valid.copy(size = -1) }
        shouldThrow<IllegalArgumentException> { valid.copy(speedLimit = 0) }
        shouldThrow<IllegalArgumentException> { valid.copy(mirrors = listOf("https://example.com/a", " ")) }
    }

    @Test
    fun newFileRejectsImpossibleValues() {
        val valid = NewFile(index = 0, path = "a/b.txt")

        valid.selected shouldBe true
        shouldThrow<IllegalArgumentException> { valid.copy(index = -1) }
        shouldThrow<IllegalArgumentException> { valid.copy(path = " ") }
        shouldThrow<IllegalArgumentException> { valid.copy(size = -1) }
    }

    @Test
    fun newSegmentRejectsImpossibleRanges() {
        NewSegment(start = 0, end = 1)

        shouldThrow<IllegalArgumentException> { NewSegment(start = -1, end = 10) }
        shouldThrow<IllegalArgumentException> { NewSegment(start = 10, end = 10) }
        shouldThrow<IllegalArgumentException> { NewSegment(start = 11, end = 10) }
    }

    @Test
    fun settingsHaveTheirDefaultsAndRejectImpossibleValues() {
        val defaults = Settings()

        defaults.concurrencyLimit shouldBe 3
        defaults.defaultSaveFolder shouldBe null
        defaults.globalPause shouldBe false
        defaults.maxRetries shouldBe 5
        Settings(maxRetries = 0).maxRetries shouldBe 0
        shouldThrow<IllegalArgumentException> { Settings(concurrencyLimit = 0) }
        shouldThrow<IllegalArgumentException> { Settings(maxRetries = -1) }
        shouldThrow<IllegalArgumentException> { Settings(defaultSaveFolder = Path("relative")) }
    }
}
