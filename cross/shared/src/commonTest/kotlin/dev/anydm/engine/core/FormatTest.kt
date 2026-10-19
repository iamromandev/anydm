package dev.anydm.engine.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FormatTest {
    @Test
    fun aSubtitleChoiceNeedsALanguage() {
        shouldThrow<IllegalArgumentException> { SubtitleChoice(emptyList()) }
    }

    @Test
    fun aLanguageCannotStartWithADashOrHoldAComma() {
        shouldThrow<IllegalArgumentException> { SubtitleChoice(listOf("-x")) }
        shouldThrow<IllegalArgumentException> { SubtitleChoice(listOf("en,fr")) }
        shouldThrow<IllegalArgumentException> { SubtitleChoice(listOf("e")) }
        SubtitleChoice(listOf("en", "pt-BR", "zh-Hans")).languages.size shouldBe 3
    }

    @Test
    fun aCategoryMustBeAWord() {
        shouldThrow<IllegalArgumentException> { SponsorBlockChoice(SponsorBlockMode.CUT, listOf("--x")) }
        shouldThrow<IllegalArgumentException> { SponsorBlockChoice(SponsorBlockMode.CUT, emptyList()) }
        SponsorBlockChoice(SponsorBlockMode.MARK).categories shouldBe DEFAULT_SPONSORBLOCK_CATEGORIES
    }

    @Test
    fun anAudioOnlyFormatHasNoSubtitlesOrSponsorBlock() {
        shouldThrow<IllegalArgumentException> { Format(audioOnly = AudioFormat.MP3, subtitles = SubtitleChoice(listOf("en"))) }
        shouldThrow<IllegalArgumentException> {
            Format(
                audioOnly = AudioFormat.MP3,
                sponsorBlock = SponsorBlockChoice(SponsorBlockMode.CUT),
            )
        }
        Format(audioOnly = AudioFormat.MP3, embed = EmbedChoice(metadata = true)).embed.any shouldBe true
    }

    @Test
    fun anAudioLanguageIsALanguageCode() {
        shouldThrow<IllegalArgumentException> { Format(audioLanguage = "-x") }
        shouldThrow<IllegalArgumentException> { Format(audioLanguage = "english please") }
        Format(audioLanguage = "en-US").audioLanguage shouldBe "en-US"
        Format(audioLanguage = "fra").audioLanguage shouldBe "fra"
    }

    @Test
    fun theDefaultsAskForNothingExtra() {
        val format = Format()

        format.audioChannels shouldBe AudioChannels.ANY
        format.subtitles shouldBe null
        format.sponsorBlock shouldBe null
        format.embed.any shouldBe false
    }
}
