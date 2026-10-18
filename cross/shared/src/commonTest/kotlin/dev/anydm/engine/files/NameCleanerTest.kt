package dev.anydm.engine.files

import dev.anydm.engine.files.OsProfile.LINUX
import dev.anydm.engine.files.OsProfile.MACOS
import dev.anydm.engine.files.OsProfile.WINDOWS
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlin.test.Test

class NameCleanerTest {
    @Test
    fun windowsRemovesForbiddenCharacters() {
        NameCleaner(WINDOWS).clean("a<b>c:d\"e/f\\g|h?i*j.txt") shouldBe "a_b_c_d_e_f_g_h_i_j.txt"
    }

    @Test
    fun linuxKeepsColonButRemovesSlashAndNul() {
        NameCleaner(LINUX).clean("a:b/c\u0000d") shouldBe "a:b_c_d"
    }

    @Test
    fun macosAlsoReplacesColon() {
        NameCleaner(MACOS).clean("a:b/c\u0000d") shouldBe "a_b_c_d"
    }

    @Test
    fun windowsReservedNamesGetPrefix() {
        val cleaner = NameCleaner(WINDOWS)
        cleaner.clean("CON") shouldBe "_CON"
        cleaner.clean("con.txt") shouldBe "_con.txt"
        cleaner.clean("COM1.tar.gz") shouldBe "_COM1.tar.gz"
        cleaner.clean("LPT9") shouldBe "_LPT9"
        cleaner.clean("CONSOLE.txt") shouldBe "CONSOLE.txt"
    }

    @Test
    fun trailingDotsAndSpacesAreTrimmedOnWindowsOnly() {
        NameCleaner(WINDOWS).clean("name. . ") shouldBe "name"
        NameCleaner(LINUX).clean("name. . ") shouldBe "name. . "
    }

    @Test
    fun controlCharactersAreRemovedEverywhere() {
        for (os in OsProfile.entries) NameCleaner(os).clean("a\tb\nc") shouldBe "a_b_c"
    }

    @Test
    fun emptyOrDotNamesBecomeDownload() {
        NameCleaner(LINUX).clean("") shouldBe "download"
        NameCleaner(LINUX).clean(".") shouldBe "download"
        NameCleaner(LINUX).clean("..") shouldBe "download"
        NameCleaner(WINDOWS).clean("???") shouldBe "download"
    }

    @Test
    fun namesOfOnlyDotsOrReservedNamesStayUsable() {
        NameCleaner(WINDOWS).clean("....") shouldBe "download"
        NameCleaner(WINDOWS).clean("CON.") shouldBe "_CON"
    }

    @Test
    fun longNameKeepsExtensionAndFitsTheLimit() {
        val long = "é".repeat(300) + ".mp4"
        for (os in listOf(MACOS, LINUX)) {
            val cleaned = NameCleaner(os).clean(long)
            cleaned.encodeToByteArray().size shouldBeLessThanOrEqualTo 255
            cleaned shouldEndWith ".mp4"
        }
        val windows = NameCleaner(WINDOWS).clean(long)
        windows.length shouldBeLessThanOrEqualTo 255
        windows shouldEndWith ".mp4"
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        val emoji = "😀".repeat(200) + ".txt"
        for (os in OsProfile.entries) {
            val cleaned = NameCleaner(os).clean(emoji)
            cleaned shouldEndWith ".txt"
            cleaned.encodeToByteArray().decodeToString() shouldBe cleaned
        }
    }

    @Test
    fun aLongNameLeavesRoomForThePartialSuffixAndAClashNumber() {
        // The partial adds ".<19 digit id>.anydm" and a clash " (10000)": the name must still be creatable.
        val suffixes = ".1234567890123456789.anydm".length + " (10000)".length
        for (os in OsProfile.entries) {
            val cleaned = NameCleaner(os).clean("é".repeat(300) + ".mp4")
            val size = if (os == WINDOWS) cleaned.length else cleaned.encodeToByteArray().size
            (size + suffixes) shouldBeLessThanOrEqualTo 255
        }
    }

    @Test
    fun truncationKeepsATarExtensionWhole() {
        for (os in OsProfile.entries) NameCleaner(os).clean("é".repeat(300) + ".tar.gz") shouldEndWith ".tar.gz"
    }

    @Test
    fun windowsReservedNamesAreFoundThroughTrailingSpacesAndSuperscripts() {
        val cleaner = NameCleaner(WINDOWS)
        cleaner.clean("CON .txt") shouldBe "_CON .txt"
        cleaner.clean("COM0") shouldBe "_COM0"
        cleaner.clean("LPT0.log") shouldBe "_LPT0.log"
        cleaner.clean("COM\u00B9") shouldBe "_COM\u00B9"
        cleaner.clean("LPT\u00B2.txt") shouldBe "_LPT\u00B2.txt"
    }
}
