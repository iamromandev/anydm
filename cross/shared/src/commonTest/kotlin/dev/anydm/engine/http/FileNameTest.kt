package dev.anydm.engine.http

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FileNameTest {
    private data class Row(
        val disposition: String?,
        val url: String,
        val type: String?,
        val expected: String,
    )

    private val rows =
        listOf(
            Row("attachment; filename*=UTF-8''%E2%82%AC%20rates.zip", "https://h/x", null, "€ rates.zip"),
            Row("attachment; filename*=UTF-8''%D0%B2%D0%B8%D0%B4%D0%B5%D0%BE.mp4", "https://h/x", null, "видео.mp4"),
            Row("attachment; filename*=ISO-8859-1''na%EFve.txt", "https://h/x", null, "naïve.txt"),
            Row("attachment; filename=\"a \\\"quoted\\\" name.pdf\"", "https://h/x", null, "a \"quoted\" name.pdf"),
            Row("attachment; filename=plain.bin", "https://h/x", null, "plain.bin"),
            Row("attachment; filename=\"old.zip\"; filename*=UTF-8''new.zip", "https://h/x", null, "new.zip"),
            Row(null, "https://h/dir/file%20name.tar.gz?x=1#f", null, "file name.tar.gz"),
            Row(null, "https://h/dir/", null, "dir"),
            Row(null, "https://h/", "application/zip", "download.zip"),
            Row(null, "https://h/", null, "download"),
            Row("attachment; filename=\"a/b\\\\c.txt\"", "https://h/x", null, "a_b_c.txt"),
            Row("attachment; filename=\"..\"", "https://h/x.iso", null, "x.iso"),
        )

    @Test
    fun everyRuleInOrder() {
        for (row in rows) {
            withClue(row) { fileNameFor(row.disposition, row.url, row.type) shouldBe row.expected }
        }
    }
}
