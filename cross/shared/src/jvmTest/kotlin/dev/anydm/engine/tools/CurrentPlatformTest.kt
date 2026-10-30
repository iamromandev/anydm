package dev.anydm.engine.tools

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CurrentPlatformTest {
    @Test
    fun mapsOsAndArchNames() {
        platformOf("Mac OS X", "aarch64") shouldBe Platform(OsFamily.MACOS, Arch.ARM64)
        platformOf("Mac OS X", "x86_64") shouldBe Platform(OsFamily.MACOS, Arch.X64)
        platformOf("Windows 11", "amd64") shouldBe Platform(OsFamily.WINDOWS, Arch.X64)
        platformOf("Linux", "aarch64") shouldBe Platform(OsFamily.LINUX, Arch.ARM64)
        platformOf("Linux", "arm64") shouldBe Platform(OsFamily.LINUX, Arch.ARM64)
        platformOf("Linux", "amd64") shouldBe Platform(OsFamily.LINUX, Arch.X64)
        platformOf("FreeBSD", "amd64") shouldBe Platform(OsFamily.LINUX, Arch.X64)
    }

    @Test
    fun theCurrentPlatformIsOneOfTheKnownOnes() {
        val current = Platform.current()
        (current.os in OsFamily.entries && current.arch in Arch.entries) shouldBe true
    }
}
