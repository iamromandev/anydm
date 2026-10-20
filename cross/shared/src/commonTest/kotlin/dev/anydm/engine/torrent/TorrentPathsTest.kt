package dev.anydm.engine.torrent

import dev.anydm.engine.files.NameCleaner
import dev.anydm.engine.files.OsProfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class TorrentPathsTest {
    private val cleaner = NameCleaner(OsProfile.LINUX)

    private fun info(
        vararg paths: String,
        name: String = "pack",
        sizes: List<Long> = paths.map { 100L },
    ) = TorrentInfo(name, "a".repeat(40), paths.mapIndexed { i, p -> TorrentFile(i, p, sizes[i]) }, ByteArray(3))

    @Test
    fun anOrdinaryTorrentPasses() {
        checkTorrent(info("pack/a.bin", "pack/sub/b.bin")) shouldBe null
        checkTorrent(info("one.bin", name = "one.bin")) shouldBe null
    }

    @Test
    fun noFilesIsRefused() {
        checkTorrent(info()) shouldContain "no files"
    }

    @Test
    fun aBlankNameIsRefused() {
        checkTorrent(info("a.bin", name = " ")) shouldContain "no name"
    }

    @Test
    fun dotDotAndAbsoluteAreRefused() {
        for (bad in listOf("pack/../x", "../x", "/abs/x", "pack/./x", "pack\\x", "pack//x", "pack/")) {
            checkTorrent(info(bad)) shouldContain "can't be placed"
        }
    }

    @Test
    fun aColonInANameIsCleanedNotRefused() {
        // "Star Wars: Episode IV" is an ordinary folder name; the runner refuses a colon in a planned path, so it is replaced
        val colons = info("Star Wars: Episode IV/movie.mkv", "Star Wars: Episode IV/Ep 1: Pilot.txt", name = "Star Wars: Episode IV")

        checkTorrent(colons) shouldBe null
        plannedPaths(colons, "Star Wars: Episode IV", cleaner) shouldBe
            listOf("Star Wars_ Episode IV/movie.mkv", "Star Wars_ Episode IV/Ep 1_ Pilot.txt")
    }

    @Test
    fun namesThatDifferOnlyByAColonCollide() {
        checkTorrent(info("pack/a:b.bin", "pack/a_b.bin")) shouldContain "same file"
    }

    @Test
    fun anAnydmSuffixIsRefused() {
        checkTorrent(info("pack/a.bin.1.anydm")) shouldContain ".anydm"
    }

    @Test
    fun caseCollidingPathsAreRefused() {
        checkTorrent(info("pack/A.bin", "pack/a.BIN")) shouldContain "same file"
    }

    @Test
    fun aFileInsideAFileIsRefused() {
        checkTorrent(info("pack", "pack/x")) shouldContain "inside another"
    }

    @Test
    fun aNegativeSizeIsRefused() {
        checkTorrent(info("pack/a.bin", sizes = listOf(-1L))) shouldContain "size"
    }

    @Test
    fun twentyThousandFilesPassAndOneMoreIsRefused() {
        val ok = info(*Array(20_000) { "pack/f$it.bin" })
        val tooMany = info(*Array(20_001) { "pack/f$it.bin" })

        checkTorrent(ok) shouldBe null
        checkTorrent(tooMany) shouldContain "20001"
    }

    @Test
    fun plannedPathsReplaceTheRoot() {
        plannedPaths(info("pack/a.bin", "pack/sub/b.bin"), "Renamed", cleaner) shouldBe listOf("Renamed/a.bin", "Renamed/sub/b.bin")
    }

    @Test
    fun aSingleFileUsesThePlacedName() {
        plannedPaths(info("one.bin", name = "one.bin"), "Other.bin", cleaner) shouldBe listOf("Other.bin")
    }

    @Test
    fun plannedPathsAreCleaned() {
        plannedPaths(info("pack/a\u0001b.bin", "pack/dir/c.bin"), "We/ird", cleaner) shouldBe listOf("We_ird/a_b.bin", "We_ird/dir/c.bin")
    }

    @Test
    fun totalAddsTheSizes() {
        info("pack/a", "pack/b", sizes = listOf(30L, 12L)).total shouldBe 42L
    }

    @Test
    fun noChoiceMeansEveryFileAtTheDefault() {
        resolvePriorities(emptyList(), 3) shouldBe listOf(4, 4, 4)
        resolvePriorities(listOf(0, 7, 4), 3) shouldBe listOf(0, 7, 4)
        resolvePriorities(listOf(0, 7), 3) shouldBe null
    }

    @Test
    fun prioritiesForASelection() {
        val torrent = info("pack/a.bin", "pack/b.bin", "pack/c.bin")

        prioritiesFor(torrent, null) shouldBe listOf(4, 4, 4)
        prioritiesFor(torrent, setOf("pack/b.bin")) shouldBe listOf(0, 4, 0)
        prioritiesFor(torrent, setOf("pack/a.bin", "pack/c.bin", "pack/missing")) shouldBe listOf(4, 0, 4)
    }

    @Test
    fun aSelectionNamingNoFileIsNull() {
        val torrent = info("pack/a.bin")

        prioritiesFor(torrent, emptySet()) shouldBe null
        prioritiesFor(torrent, setOf("pack/other.bin")) shouldBe null
    }
}
