package dev.anydm.engine.os

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineHarness
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.store.StoreTestBase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Links handed over as program arguments, by a launch or a second one (OSI-07, OSI-08). */
abstract class StartupArgsTest : StoreTestBase() {
    private val hash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

    private fun argsTest(body: suspend TestScope.(EngineHarness, StartupArgs, List<EngineEvent.IncomingLink>) -> Unit) =
        runTest {
            val h = EngineHarness(this, newStore())
            val heard = mutableListOf<EngineEvent.IncomingLink>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                h.engine.events.collect { if (it is EngineEvent.IncomingLink) heard += it }
            }
            h.engine.start()
            try {
                body(h, StartupArgs(h.engine), heard)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun everyLinkIsAddedInTheOrderGiven() =
        argsTest { h, args, heard ->
            val results =
                args.handle(
                    listOf("https://example.com/1.zip", "magnet:?xt=urn:btih:$hash", "ftp://example.com/3.zip"),
                    EntryPoint.SECOND_LAUNCH,
                )
            runCurrent()

            results.size shouldBe 3
            results.forEach { it.shouldBeInstanceOf<AddLinkResult.Added>() }
            heard.map { it.kind } shouldContainExactly listOf(DownloadKind.DIRECT, DownloadKind.TORRENT, DownloadKind.FTP)
            heard.all { it.entry == EntryPoint.SECOND_LAUNCH } shouldBe true
        }

    @Test
    fun flagsAndStrayWordsAreIgnoredAndTheRestStillGoIn() =
        argsTest { h, args, heard ->
            val results =
                args.handle(
                    listOf("--minimized", "https://example.com/1.zip", "hello", "-v", "https://example.com/2.zip"),
                    EntryPoint.OS_LINK,
                )
            runCurrent()

            results.map { it::class.simpleName } shouldContainExactly listOf("NotALink", "Added", "NotALink", "NotALink", "Added")
            heard.size shouldBe 2
        }

    @Test
    fun noArgumentsAddNothing() =
        argsTest { h, args, heard ->
            args.handle(emptyList(), EntryPoint.OS_LINK) shouldBe emptyList()
            heard shouldBe emptyList()
        }

    @Test
    fun theSameLinkTwiceIsAddedOnce() =
        argsTest { h, args, heard ->
            val results = args.handle(listOf("https://example.com/1.zip", "https://example.com/1.zip"), EntryPoint.OS_LINK)
            runCurrent()

            results[0].shouldBeInstanceOf<AddLinkResult.Added>()
            results[1].shouldBeInstanceOf<AddLinkResult.Duplicate>()
            heard.size shouldBe 1
        }
}
