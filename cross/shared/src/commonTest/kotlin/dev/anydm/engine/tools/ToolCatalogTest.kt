package dev.anydm.engine.tools

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class ToolCatalogTest {
    private val mac = Platform(OsFamily.MACOS, Arch.ARM64)
    private val linux = Platform(OsFamily.LINUX, Arch.X64)
    private val hash = "a".repeat(64)

    private fun artifact(
        url: String = "https://example.com/deno.zip",
        sha256: String = hash,
        archive: ArchiveKind = ArchiveKind.ZIP,
    ) = Artifact(url, sha256, archive)

    @Test
    fun anEntryIsFoundByToolAndPlatform() {
        val entry = CatalogEntry("2.1.4", listOf(artifact()))
        val catalog = ToolCatalog(mapOf((Tool.DENO to mac) to entry), mapOf(Tool.DENO to ToolVersion.parse("2.0.0")!!))

        catalog.entryFor(Tool.DENO, mac) shouldBe entry
        catalog.entryFor(Tool.DENO, linux) shouldBe null
        catalog.entryFor(Tool.FFMPEG, mac) shouldBe null
    }

    @Test
    fun theMinimumVersionIsPerTool() {
        val catalog = ToolCatalog(emptyMap(), mapOf(Tool.DENO to ToolVersion.parse("2.0.0")!!))

        catalog.minimum(Tool.DENO) shouldBe ToolVersion.parse("2.0.0")
    }

    @Test
    fun aToolWithoutAMinimumIsAnError() {
        shouldThrow<IllegalArgumentException> { ToolCatalog(emptyMap(), emptyMap()).minimum(Tool.DENO) }
    }

    @Test
    fun anEntryNeedsAnArtifactAndAParsableVersion() {
        shouldThrow<IllegalArgumentException> { CatalogEntry("2.1.4", emptyList()) }
        shouldThrow<IllegalArgumentException> { CatalogEntry("nightly", listOf(artifact())) }
    }

    @Test
    fun anArtifactNeedsHttpsAndALowercaseSha256() {
        shouldThrow<IllegalArgumentException> { artifact(url = "http://example.com/x.zip") }
        shouldThrow<IllegalArgumentException> { artifact(sha256 = "abc") }
        shouldThrow<IllegalArgumentException> { artifact(sha256 = "A".repeat(64)) }
    }

    @Test
    fun theFileNameOfAnArtifactIsTheLastPathSegment() {
        artifact(url = "https://example.com/dl/v1/ffmpeg-7.0.zip?x=1").fileName shouldBe "ffmpeg-7.0.zip"
    }

    @Test
    fun ytDlpHasOneAssetPerSupportedPlatform() {
        ytDlpAsset(Platform(OsFamily.MACOS, Arch.ARM64)) shouldBe "yt-dlp_macos"
        ytDlpAsset(Platform(OsFamily.MACOS, Arch.X64)) shouldBe "yt-dlp_macos"
        ytDlpAsset(Platform(OsFamily.WINDOWS, Arch.X64)) shouldBe "yt-dlp.exe"
        ytDlpAsset(Platform(OsFamily.LINUX, Arch.X64)) shouldBe "yt-dlp_linux"
        ytDlpAsset(Platform(OsFamily.LINUX, Arch.ARM64)) shouldBe "yt-dlp_linux_aarch64"
        ytDlpAsset(Platform(OsFamily.WINDOWS, Arch.ARM64)) shouldBe null
    }

    @Test
    fun pinnedMinimumsAreSetForEveryTool() {
        for (tool in Tool.entries) ToolCatalog.Pinned.minimum(tool)
    }

    private val allPlatforms = OsFamily.entries.flatMap { os -> Arch.entries.map { Platform(os, it) } }

    @Test
    fun denoIsPinnedForEveryDesktopPlatformButWindowsOnArm() {
        for (platform in allPlatforms) {
            val expected = !(platform.os == OsFamily.WINDOWS && platform.arch == Arch.ARM64)
            (ToolCatalog.Pinned.entryFor(Tool.DENO, platform) != null) shouldBe expected
        }
    }

    @Test
    fun ffmpegIsPinnedForWindowsAndLinuxButNotMacOs() {
        ToolCatalog.Pinned.entryFor(Tool.FFMPEG, Platform(OsFamily.WINDOWS, Arch.X64)) shouldNotBe null
        ToolCatalog.Pinned.entryFor(Tool.FFMPEG, Platform(OsFamily.LINUX, Arch.X64)) shouldNotBe null
        ToolCatalog.Pinned.entryFor(Tool.FFMPEG, Platform(OsFamily.LINUX, Arch.ARM64)) shouldNotBe null
        ToolCatalog.Pinned.entryFor(Tool.FFMPEG, Platform(OsFamily.MACOS, Arch.X64)) shouldBe null
        ToolCatalog.Pinned.entryFor(Tool.FFMPEG, Platform(OsFamily.MACOS, Arch.ARM64)) shouldBe null
    }

    @Test
    fun ytDlpIsNeverPinned() {
        for (platform in allPlatforms) ToolCatalog.Pinned.entryFor(Tool.YT_DLP, platform) shouldBe null
    }

    @Test
    fun pinnedEntriesComeFromTheirPublishersWithTheArchiveKindTheirNameSays() {
        for (tool in listOf(Tool.DENO, Tool.FFMPEG)) {
            for (platform in allPlatforms) {
                val entry = ToolCatalog.Pinned.entryFor(tool, platform) ?: continue
                for (artifact in entry.artifacts) {
                    val host =
                        if (tool ==
                            Tool.DENO
                        ) {
                            "https://github.com/denoland/deno/releases/download/"
                        } else {
                            "https://github.com/BtbN/FFmpeg-Builds/releases/download/"
                        }
                    artifact.url.startsWith(host) shouldBe true
                    artifact.archive shouldBe if (artifact.fileName.endsWith(".tar.xz")) ArchiveKind.TAR_XZ else ArchiveKind.ZIP
                }
            }
        }
    }

    @Test
    fun theWindowsAndLinuxFfmpegBuildsShipFfprobeToo() {
        // One archive holds both executables there; the tool is complete after that one job.
        for (platform in listOf(
            Platform(OsFamily.WINDOWS, Arch.X64),
            Platform(OsFamily.LINUX, Arch.X64),
            Platform(OsFamily.LINUX, Arch.ARM64),
        )) {
            ToolCatalog.Pinned
                .entryFor(Tool.FFMPEG, platform)!!
                .artifacts.size shouldBe 1
        }
    }

    @Test
    fun pinnedVersionsMeetTheirOwnMinimum() {
        for (tool in listOf(Tool.DENO, Tool.FFMPEG)) {
            for (platform in allPlatforms) {
                val entry = ToolCatalog.Pinned.entryFor(tool, platform) ?: continue
                (ToolVersion.parse(entry.version)!! >= ToolCatalog.Pinned.minimum(tool)) shouldBe true
            }
        }
    }
}
