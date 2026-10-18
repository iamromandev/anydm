package dev.anydm.engine.files

import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadKind
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CategoryMatcherTest {
    private fun category(
        id: Long,
        priority: Int = 0,
        vararg rules: CategoryRule,
    ) = Category(CategoryId(id), "c$id", "/dl/c$id", null, rules.toList(), priority)

    private fun facts(
        name: String = "file.bin",
        mime: String? = null,
        host: String? = null,
        kind: DownloadKind = DownloadKind.DIRECT,
    ) = SourceFacts(name, mime, host, kind)

    private fun matched(
        categories: List<Category>,
        facts: SourceFacts,
    ) = CategoryMatcher(categories).match(facts)?.id?.value

    @Test
    fun matchesByExtensionIgnoringCase() {
        val video = category(1, 0, CategoryRule.Extension(setOf("mkv", "mp4")))
        matched(listOf(video), facts(name = "a.MKV")) shouldBe 1
        matched(listOf(video), facts(name = "a.zip")) shouldBe null
    }

    @Test
    fun matchesByMimePattern() {
        val video = category(1, 0, CategoryRule.Mime(setOf("video/*")))
        matched(listOf(video), facts(mime = "video/mp4")) shouldBe 1
        matched(listOf(video), facts(mime = "audio/mp4")) shouldBe null
        matched(listOf(video), facts(mime = null)) shouldBe null
    }

    @Test
    fun matchesByExactMime() {
        val pdf = category(1, 0, CategoryRule.Mime(setOf("application/pdf")))
        matched(listOf(pdf), facts(mime = "application/pdf")) shouldBe 1
        matched(listOf(pdf), facts(mime = "application/pdfx")) shouldBe null
    }

    @Test
    fun matchesTheDomainAndItsSubdomainsOnly() {
        val site = category(1, 0, CategoryRule.Domain(setOf("example.com")))
        matched(listOf(site), facts(host = "example.com")) shouldBe 1
        matched(listOf(site), facts(host = "cdn.example.com")) shouldBe 1
        matched(listOf(site), facts(host = "notexample.com")) shouldBe null
        matched(listOf(site), facts(host = null)) shouldBe null
    }

    @Test
    fun matchesByKind() {
        val torrents = category(1, 0, CategoryRule.Kind(setOf(DownloadKind.TORRENT)))
        matched(listOf(torrents), facts(kind = DownloadKind.TORRENT)) shouldBe 1
        matched(listOf(torrents), facts(kind = DownloadKind.DIRECT)) shouldBe null
    }

    @Test
    fun aCategoryMatchesWhenAnyOfItsRulesDoes() {
        val media = category(1, 0, CategoryRule.Extension(setOf("mp3")), CategoryRule.Mime(setOf("video/*")))
        matched(listOf(media), facts(name = "a.mp3")) shouldBe 1
        matched(listOf(media), facts(mime = "video/webm")) shouldBe 1
    }

    @Test
    fun lowestPriorityNumberWinsThenLowestId() {
        val a = category(5, 1, CategoryRule.Extension(setOf("zip")))
        val b = category(9, 0, CategoryRule.Extension(setOf("zip")))
        val c = category(2, 0, CategoryRule.Extension(setOf("zip")))
        matched(listOf(a, b, c), facts(name = "x.zip")) shouldBe 2
    }

    @Test
    fun noMatchIsNull() {
        matched(emptyList(), facts()) shouldBe null
    }
}
