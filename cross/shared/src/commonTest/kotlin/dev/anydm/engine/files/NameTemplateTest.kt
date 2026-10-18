package dev.anydm.engine.files

import dev.anydm.engine.files.TemplateField.ID
import dev.anydm.engine.files.TemplateField.PLAYLIST_INDEX
import dev.anydm.engine.files.TemplateField.TITLE
import dev.anydm.engine.files.TemplateField.UPLOADER
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class NameTemplateTest {
    private fun template(text: String): NameTemplate = NameTemplate.parse(text).shouldBeInstanceOf<TemplateParse.Ok>().template

    private fun fields(vararg pairs: Pair<TemplateField, String>) = NameFields(mapOf(*pairs))

    @Test
    fun rendersAllFields() {
        template("{playlist_index}. {title} [{id}]")
            .render(fields(TITLE to "Song", ID to "x1", PLAYLIST_INDEX to "03")) shouldBe "03. Song [x1]"
    }

    @Test
    fun unknownFieldIsAParseError() {
        NameTemplate.parse("{titel}") shouldBe TemplateParse.UnknownField("titel")
    }

    @Test
    fun missingFieldDropsItsSeparator() {
        val t = template("{title} - {uploader}")
        t.render(fields(TITLE to "Song")) shouldBe "Song"
        t.render(fields(UPLOADER to "Band")) shouldBe "Band"
    }

    @Test
    fun missingFieldDropsEmptyBrackets() {
        template("{title} [{resolution}]").render(fields(TITLE to "Song")) shouldBe "Song"
    }

    @Test
    fun allFieldsMissingRendersEmpty() {
        template("{title} - {uploader}").render(NameFields()) shouldBe ""
    }

    @Test
    fun literalTextWithoutFieldsIsKept() {
        template("(1) fixed").render(NameFields()) shouldBe "(1) fixed"
    }

    @Test
    fun literalTextAroundPresentFieldsIsKept() {
        template("(1) {title}").render(fields(TITLE to "Song")) shouldBe "(1) Song"
    }
}
