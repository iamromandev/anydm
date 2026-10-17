package dev.anydm.engine.log

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class RedactorTest {
    private val redactor = Redactor()

    private val cases =
        listOf(
            "Cookie: a=1; b=2" to "Cookie: ***",
            "set-cookie: sid=9" to "set-cookie: ***",
            "Authorization: Bearer abc" to "Authorization: ***",
            "Proxy-Authorization: Basic xyz" to "Proxy-Authorization: ***",
            "GET https://ann:pw@host/a.zip" to "GET https://***:***@host/a.zip",
            "https://h/a?token=abc&part=2" to "https://h/a?token=***&part=2",
            "https://h/a?part=2&SIG=zz" to "https://h/a?part=2&SIG=***",
            "https://b.s3.amazonaws.com/k?X-Amz-Signature=f00&X-Amz-Expires=60" to
                "https://b.s3.amazonaws.com/k?X-Amz-Signature=***&X-Amz-Expires=60",
            "yt-dlp --cookies c.txt --password p -u ann:pw URL" to "yt-dlp --cookies *** --password *** -u *** URL",
            "--password=p" to "--password=***",
            "--add-header \"Cookie: a=1\"" to "--add-header \"Cookie: ***\"",
            "--cookies-from-browser chrome" to "--cookies-from-browser ***",
            "{\"password\":\"x\",\"name\":\"n\"}" to "{\"password\":\"***\",\"name\":\"n\"}",
            "\"token\": \"y\"" to "\"token\": \"***\"",
        )

    @Test
    fun everyFormIsMasked() {
        for ((input, expected) in cases) withClue(input) { redactor.redact(input) shouldBe expected }
    }

    @Test
    fun textWithoutSecretsIsUnchanged() {
        for (text in listOf("job 12 completed (job-1.zip)", "https://h/a?part=2", "queue stopped", "")) {
            redactor.redact(text) shouldBe text
        }
    }

    @Test
    fun extraNamesAreMaskedToo() {
        Redactor(extraQueryNames = setOf("hdnea")).redact("https://h/a?hdnea=1") shouldBe "https://h/a?hdnea=***"
    }

    @Test
    fun aSecretNeverSurvivesAnyForm() =
        runTest {
            checkAll(200, Arb.string(6..30, Codepoint.alphanumeric())) { secret ->
                val forms =
                    listOf(
                        "Cookie: $secret",
                        "https://u:$secret@h/",
                        "https://h/?token=$secret",
                        "--password $secret",
                        "\"secret\":\"$secret\"",
                    )
                for (form in forms) withClue(form) { redactor.redact(form) shouldNotContain secret }
            }
        }

    @Test
    fun redactingTwiceChangesNothing() {
        for ((input, _) in cases) {
            val once = redactor.redact(input)
            withClue(input) { redactor.redact(once) shouldBe once }
        }
    }

    @Test
    fun nonAsciiTextIsKept() {
        redactor.redact("файл видео 🎬.mp4") shouldBe "файл видео 🎬.mp4"
        redactor.redact("https://h/a?token=видео") shouldBe "https://h/a?token=***"
    }
}
