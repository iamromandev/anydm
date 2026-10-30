package dev.anydm.engine

import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ErrorClass
import dev.anydm.engine.core.RequestContext
import dev.anydm.engine.os.AddLinkResult
import dev.anydm.engine.os.EntryPoint
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.os.OsSettings
import dev.anydm.engine.protocol.LinkProbe
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.Settings
import dev.anydm.engine.store.StoreTestBase
import dev.anydm.engine.torrent.FakeTorrentService
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentInfo
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Links from outside the app land in the queue (OSI-07), and say so in an event. */
abstract class EngineLinksContract : StoreTestBase() {
    private val hash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"

    private fun linksTest(body: suspend TestScope.(EngineHarness, List<EngineEvent.IncomingLink>) -> Unit) =
        runTest {
            val h = EngineHarness(this, newStore())
            val heard = mutableListOf<EngineEvent.IncomingLink>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                h.engine.events.collect { if (it is EngineEvent.IncomingLink) heard += it }
            }
            h.engine.start()
            try {
                body(h, heard)
            } finally {
                h.engine.stop()
            }
        }

    private suspend fun EngineHarness.added(result: AddLinkResult) = result.shouldBeInstanceOf<AddLinkResult.Added>().download

    @Test
    fun aWebAddressBecomesADirectDownloadAndSaysSo() =
        linksTest { h, heard ->
            val job = h.added(h.engine.addLink("https://files.example.com/a.zip", EntryPoint.OS_LINK))
            runCurrent()

            job.kind shouldBe DownloadKind.DIRECT
            h.store.downloads
                .mirrors(job.id)
                .map { it.url } shouldContainExactly listOf("https://files.example.com/a.zip")
            heard.map { it.link to it.entry } shouldContainExactly listOf("https://files.example.com/a.zip" to EntryPoint.OS_LINK)
            heard.single().id shouldBe job.id
            heard.single().kind shouldBe DownloadKind.DIRECT
        }

    @Test
    fun aLoginInTheLinkIsNotInTheEvent() =
        linksTest { h, heard ->
            h.added(h.engine.addLink("ftp://ann:secret@example.com/a.zip", EntryPoint.SECOND_LAUNCH))
            runCurrent()

            heard.single().link shouldBe "ftp://example.com/a.zip"
            heard.single().kind shouldBe DownloadKind.FTP
            heard.toString().contains("secret") shouldBe false
        }

    @Test
    fun anAnydmLinkOpensTheLinkItCarries() =
        linksTest { h, heard ->
            val job = h.added(h.engine.addLink("anydm://add?url=https%3A%2F%2Fexample.com%2Fb.zip", EntryPoint.OS_LINK))
            runCurrent()

            h.store.downloads
                .mirrors(job.id)
                .map { it.url } shouldContainExactly listOf("https://example.com/b.zip")
            heard.single().link shouldBe "https://example.com/b.zip"
        }

    @Test
    fun aMagnetLinkBecomesATorrentWithAllFiles() =
        linksTest { h, heard ->
            val magnet = "magnet:?xt=urn:btih:$hash&dn=x"
            val job = h.added(h.engine.addLink(magnet, EntryPoint.OS_LINK))
            runCurrent()

            job.kind shouldBe DownloadKind.TORRENT
            heard.single().kind shouldBe DownloadKind.TORRENT
            heard.single().id shouldBe job.id
        }

    @Test
    fun theContextIsKeptWithTheDownload() =
        linksTest { h, _ ->
            val context = RequestContext(listOf("X-Token" to "t"), referrer = "https://example.com/dl")
            val job = h.added(h.engine.addLink("https://example.com/c.zip", EntryPoint.BRIDGE, context))

            val stored = h.store.downloads.context(job.id)
            stored?.headers shouldBe listOf("X-Token" to "t")
            stored?.referrer shouldBe "https://example.com/dl"
        }

    @Test
    fun whatIsNotALinkAddsNothing() =
        linksTest { h, heard ->
            h.engine.addLink("hello world", EntryPoint.SECOND_LAUNCH) shouldBe AddLinkResult.NotALink
            h.engine.addLink("javascript:alert(1)", EntryPoint.BRIDGE) shouldBe AddLinkResult.NotALink
            runCurrent()

            h.engine.downloads.value shouldBe emptyList()
            heard shouldBe emptyList()
        }

    @Test
    fun aLinkAlreadyInTheListIsADuplicateAndSaysNothing() =
        linksTest { h, heard ->
            h.added(h.engine.addLink("https://example.com/d.zip", EntryPoint.OS_LINK))
            runCurrent()

            h.engine.addLink("https://example.com/d.zip", EntryPoint.OS_LINK).shouldBeInstanceOf<AddLinkResult.Duplicate>()
            runCurrent()

            h.engine.downloads.value.size shouldBe 1
            heard.size shouldBe 1
        }

    @Test
    fun probingALinkReadsItAndAddsNothing() =
        linksTest { h, heard ->
            h.protocol.linkProbe = StepResult.Ok(LinkProbe("a.zip", 5000, rangesSupported = true))
            val context = RequestContext(listOf("X-A" to "1"), cookies = "sid=abc")

            val probed = h.engine.probeLink("https://example.com/a.zip", context)

            probed shouldBe LinkProbeResult.Probed("https://example.com/a.zip", DownloadKind.DIRECT, "a.zip", 5000, true, false)
            h.protocol.probedLinks.single() shouldBe ("https://example.com/a.zip" to context)
            h.engine.downloads.value shouldBe emptyList()
            heard shouldBe emptyList()
        }

    @Test
    fun aLinkAlreadyQueuedProbesAsADuplicate() =
        linksTest { h, _ ->
            h.added(h.engine.addLink("https://example.com/a.zip", EntryPoint.OS_LINK))
            runCurrent()

            h.engine
                .probeLink("https://example.com/a.zip")
                .shouldBeInstanceOf<LinkProbeResult.Probed>()
                .duplicate shouldBe true
        }

    @Test
    fun aLinkThatCannotBeReadSaysWhy() =
        linksTest { h, _ ->
            val failure = StepResult.Failed(ErrorClass.GONE, "HTTP 404")
            h.protocol.linkProbe = failure

            h.engine.probeLink("https://example.com/missing.zip") shouldBe LinkProbeResult.Failed(failure)
        }

    @Test
    fun probingWhatIsNotALinkSaysSo() =
        linksTest { h, _ ->
            h.engine.probeLink("hello") shouldBe LinkProbeResult.NotALink
            h.protocol.probedLinks shouldBe emptyList()
        }

    @Test
    fun aMagnetLinkProbesAsAnInspectedTorrent() =
        runTest {
            val service = FakeTorrentService()
            val magnet = "magnet:?xt=urn:btih:$hash&dn=x"
            service.known[magnet] = TorrentInfo("x", hash, listOf(TorrentFile(0, "x.bin", 4096)), ByteArray(0))
            val h = EngineHarness(this, newStore(), torrents = service)
            h.engine.start()
            try {
                h.engine.probeLink(magnet) shouldBe LinkProbeResult.Probed(magnet, DownloadKind.TORRENT, "x", 4096, false, false)
            } finally {
                h.engine.stop()
            }
        }

    @Test
    fun theBridgesExtensionsAndNotificationsAreSettings() =
        linksTest { h, _ ->
            h.engine.setBridgeOrigins(setOf("chrome-extension://mine")) shouldBe CommandResult.Done(null)
            h.engine.setNotifications(false) shouldBe CommandResult.Done(null)

            val settings = h.store.settings.load()
            settings.bridgeOrigins shouldBe setOf("chrome-extension://mine")
            settings.notifications shouldBe false
        }

    @Test
    fun anOriginThatIsNotAnExtensionIsNotSaved() =
        linksTest { h, _ ->
            h.engine.setBridgeOrigins(setOf("https://evil.example")).shouldBeInstanceOf<CommandResult.Invalid>()

            h.store.settings
                .load()
                .bridgeOrigins shouldBe setOf(Settings.CHROME_EXTENSION_ORIGIN)
        }

    @Test
    fun theOsSettingsStartOffAndFollowTheSetters() =
        linksTest { h, _ ->
            h.engine.osSettings.value shouldBe OsSettings(clipboardWatch = false, globalHotkey = false)

            h.engine.setClipboardWatch(true) shouldBe CommandResult.Done(null)
            h.engine.osSettings.value shouldBe OsSettings(clipboardWatch = true, globalHotkey = false)
            h.engine.setGlobalHotkey(true) shouldBe CommandResult.Done(null)
            h.engine.osSettings.value shouldBe OsSettings(clipboardWatch = true, globalHotkey = true)
            h.engine.setClipboardWatch(false)
            h.engine.osSettings.value shouldBe OsSettings(clipboardWatch = false, globalHotkey = true)

            h.store.settings
                .load()
                .globalHotkey shouldBe true
            h.store.settings
                .load()
                .clipboardWatch shouldBe false
        }

    @Test
    fun whatWasSwitchedOnBeforeTheEngineStartedIsOnWhenItStarts() =
        runTest {
            val store = newStore()
            store.settings.update { it.copy(clipboardWatch = true, globalHotkey = true) }
            val h = EngineHarness(this, store)
            h.engine.start()
            try {
                h.engine.osSettings.value shouldBe OsSettings(clipboardWatch = true, globalHotkey = true)
            } finally {
                h.engine.stop()
            }
        }
}
