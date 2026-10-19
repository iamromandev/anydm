package dev.anydm.engine.media

import dev.anydm.engine.core.SubtitleChoice
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class SubtitleSelectorTest {
    private fun track(
        lang: String,
        ext: String = "vtt",
        auto: Boolean = false,
    ) = SubtitleTrack(lang, null, auto, ext, "https://cdn.test/${if (auto) "a" else "s"}/$lang.$ext")

    private fun choose(
        tracks: List<SubtitleTrack>,
        vararg languages: String,
        includeAuto: Boolean = false,
    ) = SubtitleSelector.choose(tracks, SubtitleChoice(languages.toList(), includeAuto))

    @Test
    fun aManualTrackBeatsAnAutoOne() {
        val tracks = listOf(track("en", auto = true), track("en"))

        choose(tracks, "en", includeAuto = true) shouldBe listOf(track("en"))
    }

    @Test
    fun autoIsUsedOnlyWhenAskedAndWhenThereIsNoManualTrack() {
        val tracks = listOf(track("en", auto = true), track("fr"))

        choose(tracks, "en") shouldBe emptyList()
        choose(tracks, "en", includeAuto = true) shouldBe listOf(track("en", auto = true))
        choose(tracks, "fr", includeAuto = true) shouldBe listOf(track("fr"))
    }

    @Test
    fun theOriginalAutoCaptionIsEnOrig() {
        val tracks = listOf(track("en", auto = true), track("en-orig", auto = true))

        choose(tracks, "en", includeAuto = true) shouldBe listOf(track("en-orig", auto = true))
    }

    @Test
    fun aTranslatedAutoTrackIsNotTakenForAnotherLanguage() {
        val tracks = listOf(track("de", auto = true), track("en-orig", auto = true))

        choose(tracks, "fr", includeAuto = true) shouldBe emptyList()
        choose(tracks, "de", includeAuto = true) shouldBe listOf(track("de", auto = true))
    }

    @Test
    fun aRegionalVariantStandsInForItsLanguage() {
        val tracks = listOf(track("en-US"), track("en-GB"), track("fr"))

        choose(tracks, "en") shouldBe listOf(track("en-GB"))
        choose(listOf(track("en"), track("en-US")), "en") shouldBe listOf(track("en"))
    }

    @Test
    fun srtBeatsVttBeatsAss() {
        val tracks = listOf(track("en", "ass"), track("en", "vtt"), track("en", "srt"))

        choose(tracks, "en").single().ext shouldBe "srt"
        choose(tracks.filter { it.ext != "srt" }, "en").single().ext shouldBe "vtt"
        choose(tracks.filter { it.ext == "ass" }, "en").single().ext shouldBe "ass"
    }

    @Test
    fun onlyUnreadableFormatsGiveNoTrack() {
        val tracks = listOf(track("en", "json3"), track("en", "srv3"), track("en", "ttml"))

        choose(tracks, "en") shouldBe emptyList()
    }

    @Test
    fun anUnreadableManualTrackFallsThroughToAuto() {
        val tracks = listOf(track("en", "json3"), track("en", auto = true))

        choose(tracks, "en", includeAuto = true) shouldBe listOf(track("en", auto = true))
    }

    @Test
    fun aLanguageAskedTwiceGivesOneTrack() {
        choose(listOf(track("en")), "en", "EN", "en") shouldBe listOf(track("en"))
    }

    @Test
    fun theOrderOfTheChoiceIsKept() {
        val tracks = listOf(track("en"), track("fr"), track("de"))

        choose(tracks, "fr", "de", "en").map { it.lang } shouldBe listOf("fr", "de", "en")
    }

    @Test
    fun caseDoesNotMatter() {
        choose(listOf(track("EN-us")), "en") shouldBe listOf(track("EN-us"))
    }

    private fun thumb(
        ext: String,
        width: Int? = null,
        height: Int? = null,
        preference: Int? = null,
    ) = Thumbnail("https://cdn.test/t/$width-$ext.$ext", ext, width, height, preference)

    @Test
    fun jpgBeatsWebpAndLargestWins() {
        val small = thumb("jpg", 320, 180)
        val big = thumb("jpg", 1280, 720)
        val webp = thumb("webp", 4000, 3000)

        ThumbnailSelector.choose(listOf(webp, small, big)) shouldBe ChosenThumbnail(big, needsConversion = false)
        ThumbnailSelector.choose(listOf(thumb("png", 100, 100), small)) shouldBe ChosenThumbnail(small, needsConversion = false)
    }

    @Test
    fun preferenceBreaksATieBeforeSize() {
        val liked = thumb("jpg", 320, 180, preference = 5)
        val larger = thumb("jpg", 1280, 720, preference = 0)

        ThumbnailSelector.choose(listOf(larger, liked)) shouldBe ChosenThumbnail(liked, needsConversion = false)
    }

    @Test
    fun onlyWebpNeedsConversion() {
        val webp = thumb("webp", 1280, 720)

        ThumbnailSelector.choose(listOf(thumb("webp", 100, 100), webp, thumb("", null, null))) shouldBe
            ChosenThumbnail(webp, needsConversion = true)
    }

    @Test
    fun noThumbnailsGivesNull() {
        ThumbnailSelector.choose(emptyList()) shouldBe null
    }
}
