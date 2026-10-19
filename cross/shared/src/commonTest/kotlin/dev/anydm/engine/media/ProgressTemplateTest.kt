package dev.anydm.engine.media

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ProgressTemplateTest {
    private val line =
        """{"status":"downloading","filename":"out.f137.mp4",""" +
            """"downloaded_bytes":1234,"total_bytes":5000,"speed":321.5,"eta":12}"""

    @Test
    fun aValidLineIsRead() {
        ProgressTemplate.parse(line) shouldBe YtDlpProgress("downloading", "out.f137.mp4", 1234L, 5000L, 321.5)
    }

    @Test
    fun noiseGivesNull() {
        for (noise in listOf("", "[download] 12% of 5MiB", "WARNING: x", "{not json", """["a"]""", """{"foo":1}""", "NA")) {
            ProgressTemplate.parse(noise) shouldBe null
        }
    }

    @Test
    fun aTrailingCarriageReturnIsFine() {
        ProgressTemplate.parse("$line\r")!!.downloaded shouldBe 1234L
    }

    @Test
    fun anAbsentTotalFallsBackToTheEstimate() {
        val estimated = """{"status":"downloading","filename":"a","downloaded_bytes":1,"total_bytes":null,"total_bytes_estimate":900.5}"""

        ProgressTemplate.parse(estimated)!!.total shouldBe 900L
    }

    @Test
    fun negativeAndNonNumericValuesReadAsNull() {
        val odd = """{"status":"downloading","filename":"a","downloaded_bytes":-5,"total_bytes":"NA","speed":null}"""

        val progress = ProgressTemplate.parse(odd)!!
        progress.downloaded shouldBe null
        progress.total shouldBe null
        progress.speed shouldBe null
        progress.status shouldBe "downloading"
    }

    @Test
    fun twoFilesAreCountedOnceEach() {
        val tally = ProgressTally()

        tally.add(YtDlpProgress("downloading", "v", 100, null, null)) shouldBe 100L
        tally.add(YtDlpProgress("finished", "v", 400, 400, null)) shouldBe 300L
        tally.add(YtDlpProgress("downloading", "a", 50, null, null)) shouldBe 50L

        tally.total shouldBe 450L
    }

    @Test
    fun aDecreasingOrRepeatedValueAddsNothing() {
        val tally = ProgressTally()
        tally.add(YtDlpProgress("downloading", "v", 300, null, null))

        tally.add(YtDlpProgress("downloading", "v", 100, null, null)) shouldBe 0L
        tally.add(YtDlpProgress("downloading", "v", 300, null, null)) shouldBe 0L
        tally.add(YtDlpProgress("downloading", "v", null, null, null)) shouldBe 0L

        tally.total shouldBe 300L
    }

    @Test
    fun aFileResumedFromItsPartialAddsOnlyTheNewBytes() {
        val tally = ProgressTally()
        tally.add(YtDlpProgress("downloading", "v", 1000, null, null))

        // the restarted process reports from where its partial file stands
        tally.add(YtDlpProgress("downloading", "v", 1000, null, null)) shouldBe 0L
        tally.add(YtDlpProgress("downloading", "v", 1500, null, null)) shouldBe 500L
    }

    @Test
    fun aSeededFileCountsOnlyWhatItGrowsBy() {
        val tally = ProgressTally()
        tally.seed("v", 1000)

        tally.total shouldBe 1000L
        tally.add(YtDlpProgress("downloading", "v", 1200, null, null)) shouldBe 200L
        tally.total shouldBe 1200L
    }
}
