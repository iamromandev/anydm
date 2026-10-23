package dev.anydm.engine.os

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.platform.Clipboard
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The clipboard watch (OSI-05), on virtual time. */
class ClipboardWatchTest {
    private class Fixture(
        test: TestScope,
        start: String?,
        failingAtStart: Boolean = false,
    ) {
        var text: String? = start
        var reads = 0
        var failing = failingAtStart
        val enabled = MutableStateFlow(true)
        val heard = mutableListOf<ClipboardLink>()
        val clipboard =
            Clipboard {
                reads++
                if (failing) error("the clipboard is locked")
                text
            }
        val watch = ClipboardWatch(clipboard, every = 500.milliseconds)

        init {
            test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { watch.links.collect { heard += it } }
            test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { watch.run(enabled) }
        }
    }

    private fun watchTest(
        start: String? = null,
        failingAtStart: Boolean = false,
        body: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        val f = Fixture(this, start, failingAtStart)
        // The watch takes its first look (what is there now) before anything is copied.
        runCurrent()
        body(f)
    }

    private suspend fun TestScope.copy(
        f: Fixture,
        text: String?,
    ) {
        f.text = text
        advanceTimeBy(600.milliseconds)
        runCurrent()
    }

    @Test
    fun aCopiedLinkIsAnnouncedOnceAndAgainAfterSomethingElse() =
        watchTest { f ->
            for (text in listOf(
                "hello",
                "https://a.example/x.zip",
                "https://a.example/x.zip",
                "https://b.example/y.zip",
                "https://a.example/x.zip",
            )) {
                copy(f, text)
            }

            f.heard.map { it.link } shouldContainExactly
                listOf("https://a.example/x.zip", "https://b.example/y.zip", "https://a.example/x.zip")
        }

    @Test
    fun theLinkThatWasThereWhenItStartedIsNotAnnounced() =
        watchTest(start = "https://a.example/old.zip") { f ->
            advanceTimeBy(3.seconds)
            f.heard shouldBe emptyList()

            copy(f, "https://a.example/new.zip")
            f.heard.map { it.link } shouldContainExactly listOf("https://a.example/new.zip")
        }

    @Test
    fun theKindIsTheIntakes() =
        watchTest { f ->
            copy(f, "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a")
            copy(f, "ftp://example.com/a.zip")
            copy(f, "https://example.com/x.torrent")

            f.heard.map { it.kind } shouldContainExactly listOf(DownloadKind.TORRENT, DownloadKind.FTP, DownloadKind.TORRENT)
        }

    @Test
    fun whatIsNotALinkOrNotOneLineOrTooLongIsNothing() =
        watchTest { f ->
            for (text in listOf(
                "hello world",
                "file:///etc/x.torrent",
                "/home/me/x.torrent",
                "C:\\Users\\me\\x.torrent",
                "https://a.example/x.zip\nhttps://b.example/y.zip",
                "https://a.example/" + "a".repeat(3000),
                "anydm://add",
                "",
            )) {
                copy(f, text)
            }

            f.heard shouldBe emptyList()
        }

    @Test
    fun surroundingSpaceIsIgnored() =
        watchTest { f ->
            copy(f, "  https://a.example/x.zip \n")

            f.heard.map { it.link } shouldContainExactly listOf("https://a.example/x.zip")
        }

    @Test
    fun aLoginInTheLinkIsNotAnnounced() =
        watchTest { f ->
            copy(f, "https://user:pw@a.example/x.zip")

            f.heard.map { it.link } shouldContainExactly listOf("https://a.example/x.zip")
            f.heard.toString().contains("pw") shouldBe false
        }

    @Test
    fun aClipboardThatCannotBeReadIsSkippedAndTriedAgain() =
        watchTest { f ->
            f.failing = true
            copy(f, "https://a.example/x.zip")
            f.heard shouldBe emptyList()

            f.failing = false
            advanceTimeBy(600.milliseconds)
            runCurrent()
            f.heard.map { it.link } shouldContainExactly listOf("https://a.example/x.zip")
        }

    @Test
    fun aFirstLookThatFailsDoesNotMakeTheOldLinkNews() =
        watchTest(start = "https://a.example/old.zip", failingAtStart = true) { f ->
            f.failing = false
            advanceTimeBy(1.seconds)
            runCurrent()
            f.heard shouldBe emptyList()

            copy(f, "https://a.example/new.zip")
            f.heard.map { it.link } shouldContainExactly listOf("https://a.example/new.zip")
        }

    @Test
    fun proseThatStartsWithALinkIsNotALink() =
        watchTest { f ->
            copy(f, "https://a.example/x.zip is the one I meant")
            copy(f, "Notes: https://a.example/x.zip")

            f.heard shouldBe emptyList()
        }

    @Test
    fun aClipboardWithNoTextIsSkipped() =
        watchTest { f ->
            copy(f, "https://a.example/x.zip")
            copy(f, null)
            copy(f, "https://b.example/y.zip")

            f.heard.map { it.link } shouldContainExactly listOf("https://a.example/x.zip", "https://b.example/y.zip")
        }

    @Test
    fun itOnlyLooksWhileItIsOnAndStartsAgainCleanly() =
        watchTest { f ->
            f.enabled.value = false
            runCurrent()
            val readsWhenOff = f.reads
            copy(f, "https://a.example/x.zip")
            advanceTimeBy(5.seconds)
            f.reads shouldBe readsWhenOff
            f.heard shouldBe emptyList()

            // Switched on with a link already there: that one was copied before, so it is not news.
            f.enabled.value = true
            advanceTimeBy(2.seconds)
            f.heard shouldBe emptyList()

            copy(f, "https://b.example/y.zip")
            f.heard.map { it.link } shouldContainExactly listOf("https://b.example/y.zip")
        }

    @Test
    fun switchingOffAndOnAgainLooksAtTheSamePaceAsBefore() =
        watchTest { f ->
            f.enabled.value = false
            runCurrent()
            f.enabled.value = true
            runCurrent()
            f.enabled.value = false
            runCurrent()
            f.enabled.value = true
            runCurrent()
            val before = f.reads

            advanceTimeBy(5.seconds)
            runCurrent()

            // About two looks a second: one watch, not several.
            ((f.reads - before) in 9..11) shouldBe true
        }
}
