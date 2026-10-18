package dev.anydm.engine.platform

import dev.anydm.engine.files.OsProfile
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CurrentOsTest {
    @Test
    fun osNamesMapToProfiles() {
        osProfileFor("Mac OS X") shouldBe OsProfile.MACOS
        osProfileFor("Darwin") shouldBe OsProfile.MACOS
        osProfileFor("Windows 11") shouldBe OsProfile.WINDOWS
        osProfileFor("Linux") shouldBe OsProfile.LINUX
        osProfileFor("FreeBSD") shouldBe OsProfile.LINUX
    }
}
