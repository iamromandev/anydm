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
}
