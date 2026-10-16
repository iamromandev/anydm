package dev.anydm.engine.core

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.io.files.Path
import kotlin.test.Test

class PartialFilesTest {
    @Test
    fun onlyNamesInsideTheSaveFolderAreSafe() {
        val safe = listOf("a.zip", "Album/1.flac", "a b/c d.mp4", "..a")
        val unsafe = listOf("", "/etc/passwd", "../x", "a/../../x", "a//b", "./a", "a/", "a\\b", "C:x")
        val wrong =
            safe.filterNot(::isSafeRelativePath).map { "$it should be safe" } +
                unsafe.filter(::isSafeRelativePath).map { "'$it' should be unsafe" }
        wrong.shouldBeEmpty()
    }

    @Test
    fun aPartialSitsNextToItsTargetAndBelongsToOneDownload() {
        partialPath(targetPath(stepRoot, "Album/1.flac"), DownloadId(7)) shouldBe Path(stepRoot, "Album", "1.flac.7.anydm")
        partialPath(targetPath(stepRoot, "file.zip"), DownloadId(1)) shouldBe Path(stepRoot, "file.zip.1.anydm")
        partialPath(targetPath(stepRoot, "file.zip"), DownloadId(2)) shouldBe Path(stepRoot, "file.zip.2.anydm")
    }

    @Test
    fun aSetOfPathsMustNotCollide() {
        val fine =
            listOf(
                listOf("a.zip"),
                listOf("Album/1.flac", "Album/2.flac", "Album/cover.jpg"),
                listOf("a", "ab"), // a name prefix is no folder prefix
                listOf("x", "x.y"),
                listOf("x/y", "x2/y"),
            )
        val problems =
            mapOf(
                listOf("x.anydm") to "x.anydm",
                listOf("a/X.AnyDM") to "a/X.AnyDM", // whatever the case
                listOf("x", "x.anydm") to "x.anydm",
                listOf("A.bin", "a.bin") to "a.bin", // the same file on a case-insensitive disk
                listOf("x", "x/y") to "x/y", // a file where a folder must be
                listOf("x/y", "x") to "x/y",
                listOf("a", "b", "a") to "a",
                listOf("../x") to "../x",
                listOf("") to "",
            )
        val wrong =
            fine.filter { pathProblem(it) != null }.map { "$it should be fine: ${pathProblem(it)}" } +
                problems
                    .filter { (paths, _) ->
                        pathProblem(paths).let { it == null || "'${problems.getValue(paths)}'" !in it }
                    }.map { (paths, mentions) ->
                        "$paths should be refused, naming '$mentions': ${pathProblem(paths)}"
                    }
        wrong.shouldBeEmpty()
        pathProblem(emptyList()).shouldBeNull()
        pathProblem(listOf("x.anydm")).shouldNotBeNull() shouldContain "partial"
    }

    @Test
    fun sizesAddUpWithoutOverflowing() {
        listOf(1L, 2L, 3L).saturatingSum() shouldBe 6L
        emptyList<Long>().saturatingSum() shouldBe 0L
        listOf(1L shl 62, 1L shl 62).saturatingSum() shouldBe Long.MAX_VALUE
        listOf(Long.MAX_VALUE, 1L).saturatingSum() shouldBe Long.MAX_VALUE
        listOf(Long.MAX_VALUE, Long.MAX_VALUE, 5L).saturatingSum() shouldBe Long.MAX_VALUE
    }
}
