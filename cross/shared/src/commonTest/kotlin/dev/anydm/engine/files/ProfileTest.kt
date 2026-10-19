package dev.anydm.engine.files

import dev.anydm.engine.core.ProfileId
import io.kotest.assertions.throwables.shouldThrow
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.test.Test

class ProfileTest {
    private val folder = Path(SystemTemporaryDirectory, "music").toString()

    private fun profile(
        name: String = "Music",
        saveFolder: String? = null,
        nameTemplate: String? = null,
        speedLimit: Long? = null,
    ) = Profile(ProfileId(1), name, null, null, saveFolder, nameTemplate, speedLimit)

    private fun new(
        name: String = "Music",
        saveFolder: String? = null,
        nameTemplate: String? = null,
        speedLimit: Long? = null,
    ) = NewProfile(name, null, null, saveFolder, nameTemplate, speedLimit)

    @Test
    fun aValidProfileIsMade() {
        profile(saveFolder = folder, nameTemplate = "{uploader} - {title}", speedLimit = 1)
        new(saveFolder = folder, nameTemplate = "{title}", speedLimit = 5000)
        profile(name = "a".repeat(80))
    }

    @Test
    fun theNameIsOneToEightyCharactersOnOneLine() {
        for (bad in listOf("", "  ", "a".repeat(81), "two\nlines", "tab\tin", "nul\u0000")) {
            shouldThrow<IllegalArgumentException> { profile(name = bad) }
            shouldThrow<IllegalArgumentException> { new(name = bad) }
        }
    }

    @Test
    fun theFolderMustBeAbsolute() {
        shouldThrow<IllegalArgumentException> { profile(saveFolder = "music/relative") }
        shouldThrow<IllegalArgumentException> { new(saveFolder = "") }
    }

    @Test
    fun theTemplateMustParse() {
        shouldThrow<IllegalArgumentException> { profile(nameTemplate = "{not_a_field}") }
        shouldThrow<IllegalArgumentException> { new(nameTemplate = "{title}{nope}") }
    }

    @Test
    fun theSpeedLimitIsPositive() {
        for (bad in listOf(0L, -5L)) {
            shouldThrow<IllegalArgumentException> { profile(speedLimit = bad) }
            shouldThrow<IllegalArgumentException> { new(speedLimit = bad) }
        }
    }
}
