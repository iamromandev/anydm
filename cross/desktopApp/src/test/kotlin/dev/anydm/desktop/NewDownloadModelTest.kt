package dev.anydm.desktop

import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.InspectResult
import dev.anydm.engine.core.Download
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DuplicateFound
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.net.BindStatus
import dev.anydm.engine.net.BondedNetwork
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkInfo
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.protocol.StepResult
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.store.TransferSettings
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
internal class NewDownloadModelTest {
    private val mib = 1024L * 1024
    private val link = "https://example.com/files/Project.zip"
    private val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10"

    private class Setup(
        val source: FakeSource,
        val looks: MutableStateFlow<NetworkLooks>,
        val model: NewDownloadModel,
    ) {
        val state get() = model.state.value
    }

    private fun TestScope.check() {
        // The link is looked at after a pause in the typing.
        advanceTimeBy(DEBOUNCE + 1)
        runCurrent()
    }

    private fun awaitReal(done: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!done() && System.nanoTime() < deadline) Thread.sleep(5)
    }

    private fun dialog(
        initial: String = "",
        configure: (FakeSource) -> Unit = {},
        block: suspend TestScope.(Setup) -> Unit,
    ) = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        source.transfer = TransferSettings(defaultSaveFolder = "/home/me/Downloads")
        configure(source)
        val looks = MutableStateFlow(NetworkLooks())
        val model =
            NewDownloadModel(
                source,
                looks,
                backgroundScope,
                initial = initial,
                homeFolder = "/home/me",
                debounceMillis = DEBOUNCE,
                subnet = { subnetOf(it) { _, _ -> null } },
            )
        block(Setup(source, looks, model))
    }

    private fun info(vararg paths: Pair<String, Long>) =
        TorrentInfo(
            "Sintel",
            "08ada5a7a6183aae1e09d831df6748d566095a10",
            paths.mapIndexed {
                i,
                (path, size),
                ->
                TorrentFile(i, path, size)
            },
            byteArrayOf(1),
        )

    private fun sintel() = info("Sintel/Sintel.de.srt" to 1_600L, "Sintel/Sintel.en.srt" to 1_500L, "Sintel/Sintel.mp4" to 123 * mib)

    private fun network(
        key: String,
        name: String,
        address: String,
        enabled: Boolean = true,
        up: Boolean = true,
        speed: Double? = null,
    ) = BondedNetwork(NetworkInfo(key, name, up, listOf(address)), enabled, BindStatus.ISOLATED, speed, false)

    // Resolving a link

    @Test
    fun `an empty field has nothing to resolve and nothing to start`() =
        dialog { d ->
            assertEquals(LinkState.Idle, d.state.link)
            assertFalse(d.state.canStart)
            assertEquals("Download", d.state.startLabel)
        }

    @Test
    fun `a link is looked at after a pause in the typing and shows its name and size`() =
        dialog { d ->
            d.model.setUrl(link)
            assertEquals(LinkState.Checking, d.state.link)
            assertTrue(d.source.probed.isEmpty())
            check()
            val ready = assertIs<LinkState.Ready>(d.state.link)
            assertEquals("Project.zip", ready.name)
            assertEquals(1000L, ready.size)
            assertEquals("Project.zip", d.state.fileName)
            assertTrue(d.state.canStart)
        }

    @Test
    fun `typing on within the pause checks only the last link`() =
        dialog { d ->
            d.model.setUrl("https://example.com/a")
            advanceTimeBy(DEBOUNCE / 2)
            d.model.setUrl("https://example.com/ab")
            check()
            assertEquals(listOf("https://example.com/ab"), d.source.probed)
        }

    @Test
    fun `the answer for a link that has been replaced is not shown`() =
        dialog { d ->
            val gate = CompletableDeferred<Unit>()
            d.source.gate = gate
            d.model.setUrl("https://example.com/first.zip")
            check()
            d.model.setUrl("https://example.com/second.zip")
            check()
            gate.complete(Unit)
            runCurrent()
            assertEquals("second.zip", assertIs<LinkState.Ready>(d.state.link).name)
        }

    @Test
    fun `the answer for a replaced link is dropped even when the engine call could not be interrupted`() =
        dialog { d ->
            d.source.probeIgnoresCancel = true
            val gate = CompletableDeferred<Unit>()
            d.source.gate = gate
            d.model.setUrl("https://example.com/first.zip")
            check()
            d.model.setUrl("https://example.com/second.zip")
            check()
            gate.complete(Unit)
            runCurrent()
            assertEquals("second.zip", assertIs<LinkState.Ready>(d.state.link).name)
        }

    @Test
    fun `two starts before the state has caught up add once`() =
        runTest(kotlinx.coroutines.test.StandardTestDispatcher()) {
            val source = FakeSource()
            source.transfer = TransferSettings(defaultSaveFolder = "/home/me/Downloads")
            val model = NewDownloadModel(source, MutableStateFlow(NetworkLooks()), backgroundScope, link, "/home/me")
            runCurrent()
            assertTrue(model.state.value.canStart)
            val gate = CompletableDeferred<Unit>()
            source.gate = gate
            model.start()
            model.start()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, source.added.size)
        }

    @Test
    fun `clearing the field forgets the link and its files`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(sintel())
            d.model.setUrl(magnet)
            check()
            assertTrue(d.state.files.isNotEmpty())
            d.model.clear()
            assertEquals("", d.state.url)
            assertEquals(LinkState.Idle, d.state.link)
            assertTrue(d.state.files.isEmpty())
            assertFalse(d.state.canStart)
        }

    @Test
    fun `text that is no link is refused without asking the engine`() =
        dialog { d ->
            d.model.setUrl("just some words")
            check()
            assertIs<LinkState.Failed>(d.state.link)
            assertTrue(d.source.probed.isEmpty())
            assertFalse(d.state.canStart)
        }

    @Test
    fun `a link that can't be read says why`() =
        dialog { d ->
            d.source.probes[link] = LinkProbeResult.Failed(StepResult.Failed(null, "the server answered 404"))
            d.model.setUrl(link)
            check()
            assertEquals("the server answered 404", assertIs<LinkState.Failed>(d.state.link).message)
        }

    @Test
    fun `a stopped engine is a failed check, not a crash`() =
        dialog { d ->
            d.source.refuseAll = true
            d.model.setUrl(link)
            check()
            assertEquals("The engine has stopped.", assertIs<LinkState.Failed>(d.state.link).message)
        }

    @Test
    fun `a link of a kind nothing here can read is a failed check`() =
        dialog { d ->
            d.source.probes[link] = LinkProbeResult.Unavailable
            d.model.setUrl(link)
            check()
            assertIs<LinkState.Failed>(d.state.link)
        }

    @Test
    fun `a file that can't be split says it uses one connection`() =
        dialog { d ->
            d.source.probes[link] = LinkProbeResult.Probed(link, DownloadKind.DIRECT, "Project.zip", 5 * mib, false, false)
            d.model.setUrl(link)
            check()
            assertEquals("Uses one connection. This server doesn't support parallel downloads.", d.state.connectionNote)
        }

    @Test
    fun `a file of unknown size says so and still starts`() =
        dialog { d ->
            d.source.probes[link] = LinkProbeResult.Probed(link, DownloadKind.DIRECT, "Project.zip", null, true, false)
            d.model.setUrl(link)
            check()
            assertEquals("Uses one connection. The file size is unknown.", d.state.connectionNote)
            assertTrue(d.state.canStart)
        }

    @Test
    fun `a file that splits has no note`() =
        dialog { d ->
            d.model.setUrl(link)
            check()
            assertNull(d.state.connectionNote)
        }

    // Prefill

    @Test
    fun `a dialog opened with a link checks it at once`() =
        dialog(initial = link) { d ->
            assertEquals(link, d.state.url)
            assertIs<LinkState.Ready>(d.state.link)
        }

    @Test
    fun `a blank dialog takes the link on the clipboard`() =
        dialog(configure = { it.clipboardText = "see\n$link\n" }) { d ->
            // The clipboard is read off the caller's thread: the link arrives a moment later.
            awaitReal { d.state.url == link }
            runCurrent()
            assertEquals(link, d.state.url)
            assertIs<LinkState.Ready>(d.state.link)
        }

    @Test
    fun `a clipboard with no link leaves the field alone`() =
        dialog(configure = { it.clipboardText = "just words" }) { d ->
            // Wait for the read itself: asserting before it would pass for any outcome.
            assertTrue(d.source.clipboardRead.await(5, java.util.concurrent.TimeUnit.SECONDS))
            runCurrent()
            assertEquals("", d.state.url)
            assertTrue(d.source.probed.isEmpty())
        }

    @Test
    fun `a dialog opened with a link ignores the clipboard`() =
        dialog(initial = link, configure = { it.clipboardText = "https://example.com/other.zip" }) { d ->
            assertEquals(link, d.state.url)
            // The clipboard is not even read when the dialog was opened with a link.
            assertEquals(1L, d.source.clipboardRead.count)
        }

    // A torrent

    @Test
    fun `a magnet link shows the torrent's name, its files and their total size`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(sintel())
            d.model.setUrl(magnet)
            check()
            val ready = assertIs<LinkState.Ready>(d.state.link)
            assertEquals("Sintel", ready.name)
            assertEquals(3, d.state.files.size)
            assertEquals("Sintel", d.state.folder)
            assertEquals(listOf("Sintel.de.srt", "Sintel.en.srt", "Sintel.mp4"), d.state.files.map { it.shown })
            assertTrue(d.state.files.all { it.selected })
            assertEquals(3, d.state.selectedCount)
            assertEquals(1_600L + 1_500L + 123 * mib, d.state.selectedSize)
            assertTrue(d.state.canStart)
        }

    @Test
    fun `ticking files off changes the count and the size`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(sintel())
            d.model.setUrl(magnet)
            check()
            d.model.toggleFile(0)
            d.model.toggleFile(1)
            assertEquals(1, d.state.selectedCount)
            assertEquals(123 * mib, d.state.selectedSize)
            assertEquals(listOf(false, false, true), d.state.files.map { it.selected })
            d.model.toggleFile(0)
            assertEquals(2, d.state.selectedCount)
        }

    @Test
    fun `the folder's box ticks everything, or clears everything`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(sintel())
            d.model.setUrl(magnet)
            check()
            d.model.toggleFile(1)
            d.model.toggleAllFiles()
            assertEquals(3, d.state.selectedCount)
            d.model.toggleAllFiles()
            assertEquals(0, d.state.selectedCount)
            assertFalse(d.state.canStart)
        }

    @Test
    fun `with every file ticked off a torrent can't be started`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(info("Sintel/a" to 1L))
            d.model.setUrl(magnet)
            check()
            assertTrue(d.state.canStart)
            d.model.toggleFile(0)
            assertFalse(d.state.canStart)
            d.model.start()
            assertTrue(d.source.torrentsAdded.isEmpty())
        }

    @Test
    fun `a torrent started with every file asks for all of them`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(sintel())
            d.model.setUrl(magnet)
            check()
            d.model.start()
            val add = d.source.torrentsAdded.single()
            assertEquals(magnet, add.source)
            assertNull(add.files)
            assertEquals("/home/me/Downloads", add.template.saveFolder)
            assertNotNull(add.info)
            assertTrue(d.state.done)
        }

    @Test
    fun `a torrent started with some files ticked off asks for the files left, by their torrent paths`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(sintel())
            d.model.setUrl(magnet)
            check()
            d.model.toggleFile(0)
            d.model.toggleFile(1)
            d.model.start()
            assertEquals(
                setOf("Sintel/Sintel.mp4"),
                d.source.torrentsAdded
                    .single()
                    .files,
            )
        }

    @Test
    fun `a single file torrent has no folder row`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(info("movie.mkv" to 5L))
            d.model.setUrl(magnet)
            check()
            assertNull(d.state.folder)
            assertEquals(listOf("movie.mkv"), d.state.files.map { it.shown })
        }

    @Test
    fun `a torrent that can't be read says why, and one this engine can't take says so`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Failed(StepResult.Failed(null, "no peers answered"))
            d.model.setUrl(magnet)
            check()
            assertEquals("no peers answered", assertIs<LinkState.Failed>(d.state.link).message)
            d.source.inspects[magnet] = InspectResult.Unavailable
            d.model.setUrl("$magnet&dn=x")
            d.source.inspects["$magnet&dn=x"] = InspectResult.Unavailable
            check()
            assertIs<LinkState.Failed>(d.state.link)
        }

    @Test
    fun `a torrent file is read by its path and asks the engine for the address it makes`() =
        dialog { d ->
            // The engine takes a path to a torrent as the file address it makes of it.
            val address =
                dev.anydm.engine.os.LinkIntake
                    .parse("/home/me/a.torrent")!!
                    .link
            assertEquals("file:///home/me/a.torrent", address)
            d.source.inspects[address] = InspectResult.Inspected(sintel())
            d.model.fill("/home/me/a.torrent")
            assertIs<LinkState.Ready>(d.state.link)
            assertEquals("/home/me/a.torrent", d.state.url)
        }

    @Test
    fun `a torrent is not probed as a file as well`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(sintel())
            d.model.setUrl(magnet)
            check()
            assertTrue(d.source.probed.isEmpty())
        }

    // Starting

    @Test
    fun `a file is added with its kind, link, folder and size and the dialog is done`() =
        dialog { d ->
            d.model.setUrl(link)
            check()
            d.model.start()
            val added = d.source.added.single()
            assertEquals(DownloadKind.DIRECT, added.kind)
            assertEquals(listOf(link), added.mirrors)
            assertEquals("/home/me/Downloads", added.saveFolder)
            assertEquals(1000L, added.size)
            assertNull(added.fileName)
            assertFalse(added.allowDuplicate)
            assertTrue(d.state.done)
        }

    @Test
    fun `a name typed over the suggested one is used`() =
        dialog { d ->
            d.model.setUrl(link)
            check()
            d.model.setFileName("renamed.zip")
            d.model.start()
            assertEquals(
                "renamed.zip",
                d.source.added
                    .single()
                    .fileName,
            )
        }

    @Test
    fun `an emptied name falls back to the suggested one`() =
        dialog { d ->
            d.model.setUrl(link)
            check()
            d.model.setFileName("   ")
            d.model.start()
            assertNull(
                d.source.added
                    .single()
                    .fileName,
            )
        }

    @Test
    fun `a link with a login is added with its login`() =
        dialog { d ->
            val login = "https://me:secret@example.com/a.zip"
            d.source.probes[login] = LinkProbeResult.Probed("https://example.com/a.zip", DownloadKind.DIRECT, "a.zip", 5, true, false)
            d.model.setUrl(login)
            check()
            d.model.start()
            assertEquals(
                listOf(login),
                d.source.added
                    .single()
                    .mirrors,
            )
        }

    @Test
    fun `the folder can be changed`() =
        dialog { d ->
            d.model.setSaveFolder("/data/dl")
            d.model.setUrl(link)
            check()
            d.model.start()
            assertEquals(
                "/data/dl",
                d.source.added
                    .single()
                    .saveFolder,
            )
        }

    @Test
    fun `the folder is the settings' one, else Downloads in the home folder, and reads with a tilde`() {
        dialog { d -> assertEquals("~/Downloads", d.state.saveFolderShown) }
        dialog(configure = { it.transfer = TransferSettings() }) { d ->
            assertEquals("/home/me/Downloads", d.state.saveFolder)
        }
        dialog(configure = { it.transfer = TransferSettings(defaultSaveFolder = "/srv/dl") }) { d ->
            assertEquals("/srv/dl", d.state.saveFolderShown)
        }
    }

    @Test
    fun `nothing starts while the link is still being checked or has failed`() =
        dialog { d ->
            d.model.setUrl(link)
            assertFalse(d.state.canStart)
            assertEquals("Checking", d.state.startLabel)
            d.model.start()
            assertTrue(d.source.added.isEmpty())
            d.model.setUrl("not a link")
            check()
            assertFalse(d.state.canStart)
        }

    @Test
    fun `a link already in the list is flagged, and starting it anyway says so to the engine`() =
        dialog { d ->
            d.source.probes[link] =
                LinkProbeResult.Probed(
                    link,
                    DownloadKind.DIRECT,
                    "Project.zip",
                    5,
                    true,
                    true,
                    DuplicateFound(DownloadId(3), DuplicateReason.SAME_URL),
                )
            d.model.setUrl(link)
            check()
            assertTrue(d.state.duplicate)
            assertEquals("Download anyway", d.state.startLabel)
            d.model.start()
            assertTrue(
                d.source.added
                    .single()
                    .allowDuplicate,
            )
        }

    @Test
    fun `an add the engine refuses as a duplicate offers to go ahead`() =
        dialog { d ->
            d.source.addResults[link] = CommandResult.Duplicate(DuplicateFound(DownloadId(3), DuplicateReason.IN_HISTORY))
            d.model.setUrl(link)
            check()
            d.model.start()
            assertFalse(d.state.done)
            assertFalse(d.state.starting)
            assertTrue(d.state.duplicate)
            assertNotNull(d.state.error)
            assertEquals("Download anyway", d.state.startLabel)
        }

    @Test
    fun `an add the engine refuses says why and can be tried again`() =
        dialog { d ->
            d.source.addResults[link] = CommandResult.Invalid("the folder is read-only")
            d.model.setUrl(link)
            check()
            d.model.start()
            assertEquals("the folder is read-only", d.state.error)
            assertFalse(d.state.done)
            assertTrue(d.state.canStart)
        }

    @Test
    fun `a store error on add is shown, not thrown`() =
        dialog { d ->
            d.source.addThrows = IllegalStateException("the engine has stopped")
            d.model.setUrl(link)
            check()
            d.model.start()
            assertNotNull(d.state.error)
            assertFalse(d.state.starting)
        }

    @Test
    fun `a torrent the engine refuses says why`() =
        dialog { d ->
            d.source.inspects[magnet] = InspectResult.Inspected(sintel())
            d.model.setUrl(magnet)
            check()
            d.source.torrentResult = AddTorrentResult.Failed(StepResult.Failed(null, "metadata timed out"))
            d.model.start()
            assertEquals("metadata timed out", d.state.error)
            d.source.torrentResult = AddTorrentResult.EmptySelection
            d.model.start()
            assertNotNull(d.state.error)
            d.source.torrentResult = AddTorrentResult.Refused(CommandResult.Duplicate(DuplicateFound(null, DuplicateReason.ON_DISK)))
            d.model.start()
            assertTrue(d.state.duplicate)
            assertFalse(d.state.done)
            d.source.torrentResult = AddTorrentResult.Added(Download(DownloadId(9), DownloadKind.TORRENT))
            d.model.start()
            assertTrue(
                d.source.torrentsAdded
                    .last()
                    .template.allowDuplicate,
            )
            assertTrue(d.state.done)
        }

    @Test
    fun `starting twice at once adds once`() =
        dialog { d ->
            d.model.setUrl(link)
            check()
            val gate = CompletableDeferred<Unit>()
            d.source.gate = gate
            d.model.start()
            d.model.start()
            gate.complete(Unit)
            runCurrent()
            assertEquals(1, d.source.added.size)
        }

    @Test
    fun `an edit that lands before the state catches up does not crash start or leave it stuck`() =
        runTest(kotlinx.coroutines.test.StandardTestDispatcher()) {
            val source = FakeSource()
            source.transfer = TransferSettings(defaultSaveFolder = "/home/me/Downloads")
            val model = NewDownloadModel(source, MutableStateFlow(NetworkLooks()), backgroundScope, link, "/home/me")
            runCurrent()
            assertTrue(model.state.value.canStart)
            // The field changes, but the state shown still says the old link can start.
            model.setUrl("https://example.com/other.zip")
            model.start()
            runCurrent()
            assertTrue(source.added.isEmpty())
            assertFalse(model.state.value.starting)
            // And the dialog still works afterwards.
            model.fill(link)
            runCurrent()
            model.start()
            runCurrent()
            assertEquals(1, source.added.size)
            assertTrue(model.state.value.done)
        }

    @Test
    fun `a link longer than a link can be is refused without asking the engine`() =
        dialog { d ->
            d.model.fill("https://example.com/" + "a".repeat(9_000))
            assertIs<LinkState.Failed>(d.state.link)
            assertTrue(d.source.probed.isEmpty())
            assertFalse(d.state.canStart)
        }

    @Test
    fun `a clipboard that answers after the person has typed does not overwrite the field`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.transfer = TransferSettings(defaultSaveFolder = "/home/me/Downloads")
            val answer = CompletableDeferred<String?>()
            val read = java.util.concurrent.CountDownLatch(1)
            val model =
                NewDownloadModel(
                    object : EngineSource by source {
                        override val clipboard =
                            dev.anydm.engine.platform
                                .Clipboard { answer.await().also { read.countDown() } }
                    },
                    MutableStateFlow(NetworkLooks()),
                    backgroundScope,
                    "",
                    "/home/me",
                    DEBOUNCE,
                )
            model.fill("https://example.com/typed.zip")
            answer.complete(link)
            // Wait for the late answer to be read before asserting that it changed nothing.
            assertTrue(read.await(5, java.util.concurrent.TimeUnit.SECONDS))
            runCurrent()
            assertEquals("https://example.com/typed.zip", model.state.value.url)
        }

    @Test
    fun `links begun from many threads at once leave a field and an answer that agree`() {
        val source = FakeSource()
        source.transfer = TransferSettings(defaultSaveFolder = "/home/me/Downloads")
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
        try {
            repeat(200) { round ->
                val model = NewDownloadModel(source, MutableStateFlow(NetworkLooks()), scope, "", "/home/me", 0)
                val go = java.util.concurrent.CountDownLatch(1)
                val threads =
                    (0 until 4).map { t ->
                        Thread {
                            go.await()
                            model.fill("https://example.com/r$round-t$t.zip")
                        }.also { it.start() }
                    }
                go.countDown()
                threads.forEach { it.join() }
                // The state flow follows the draft a moment behind: settle on a reading that does not change before judging it.
                val deadline = System.nanoTime() + 5_000_000_000
                var now = model.state.value
                while (System.nanoTime() < deadline) {
                    now = model.state.value
                    Thread.sleep(5)
                    if (now.link is LinkState.Ready && model.state.value == now) break
                }
                assertEquals(now.url, assertIs<LinkState.Ready>(now.link).link)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the subnet of a network is worked out once for a listing, not for every keystroke`() =
        runTest(UnconfinedTestDispatcher()) {
            val source = FakeSource()
            source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("en7", "USB", "172.20.10.2")))
            var asked = 0
            val model =
                NewDownloadModel(
                    source,
                    MutableStateFlow(NetworkLooks()),
                    backgroundScope,
                    "",
                    "/home/me",
                    DEBOUNCE,
                    subnet = {
                        asked++
                        subnetOf(it) { _, _ -> null }
                    },
                )
            val before = asked
            "https://example.com/abc".forEachIndexed { i, _ -> model.setUrl("https://example.com/abc".take(i + 1)) }
            assertEquals(before, asked)
            source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("en7", "USB", "172.20.10.9")))
            assertTrue(asked > before)
        }

    // Networks

    @Test
    fun `the networks show as chips with the names and colors people gave them`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB Tethering", "172.20.10.2")))
            d.looks.value = NetworkLooks(colorIds = mapOf("en0" to "teal", "usb0" to "rose"), names = mapOf("usb0" to "Pixel"))
            val chips = d.state.chips
            assertEquals(listOf("Wi-Fi", "Pixel"), chips.map { it.name })
            assertEquals(listOf("teal", "rose"), chips.map { it.colorId })
            assertEquals(listOf("WIFI", "USB"), chips.map { it.kind.tag })
            assertTrue(chips.all { it.on })
        }

    @Test
    fun `a network that is off or down is not in use`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(
                    listOf(network("en0", "Wi-Fi", "192.168.1.5", enabled = false), network("usb0", "USB", "172.20.10.2", up = false)),
                )
            assertTrue(d.state.chips.none { it.on })
        }

    @Test
    fun `a network row carries the OS name, speed, whether it is up and whether the user switched it on`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(
                    listOf(
                        network("en0", "Wi-Fi", "192.168.1.5", speed = 2_000_000.0),
                        network("usb0", "USB", "172.20.10.2", enabled = false, up = false, speed = 0.0),
                    ),
                )
            d.looks.value = NetworkLooks(names = mapOf("en0" to "Home"))
            val (wifi, usb) = d.state.chips
            assertEquals("Home", wifi.name)
            assertEquals("Wi-Fi", wifi.osName)
            assertEquals(2_000_000L, wifi.speed)
            assertTrue(wifi.up && wifi.enabled && wifi.on)
            assertNull(usb.speed)
            assertTrue(!usb.up && !usb.enabled && !usb.on)
        }

    @Test
    fun `the networks section shows for one network and says the switch is for all downloads`() =
        dialog { d ->
            d.source.networks.value = BondingState(emptyList())
            assertTrue(d.state.chips.isEmpty())
            assertNull(d.state.networksNote)
            assertFalse(d.state.noNetworkOn)
            d.source.networks.value = BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5")))
            assertEquals(1, d.state.chips.size)
            assertEquals(NETWORK_SWITCH_HINT, d.state.networksNote)
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2")))
            assertEquals(NETWORK_SWITCH_HINT, d.state.networksNote)
        }

    @Test
    fun `switching a network goes to the engine and the row follows what it says`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2")))
            d.model.setNetworkEnabled("usb0", false)
            assertEquals(listOf("setNetworkEnabled usb0 false"), d.source.calls)
            assertFalse(
                d.state.chips
                    .single { it.key == "usb0" }
                    .enabled,
            )
            assertNull(d.state.networkError)
        }

    @Test
    fun `a switch the engine refuses says so and leaves the row as it was`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2")))
            d.source.results["setNetworkEnabled usb0 false"] = CommandResult.NotFound
            d.model.setNetworkEnabled("usb0", false)
            assertEquals("The network could not be switched.", d.state.networkError)
            assertTrue(
                d.state.chips
                    .single { it.key == "usb0" }
                    .enabled,
            )
            d.source.results.remove("setNetworkEnabled usb0 false")
            d.model.setNetworkEnabled("usb0", false)
            assertNull(d.state.networkError)
        }

    @Test
    fun `switching a network that is off back on goes to the engine`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(
                    listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2", enabled = false)),
                )
            d.model.setNetworkEnabled("usb0", true)
            assertEquals(listOf("setNetworkEnabled usb0 true"), d.source.calls)
            assertTrue(
                d.state.chips
                    .single { it.key == "usb0" }
                    .enabled,
            )
        }

    @Test
    fun `an older failure that finishes after a newer success for the same network shows no error`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2")))
            val older = CompletableDeferred<Unit>()
            d.source.gates["setNetworkEnabled usb0 false"] = older
            d.source.results["setNetworkEnabled usb0 false"] = CommandResult.NotFound
            d.model.setNetworkEnabled("usb0", false)
            d.model.setNetworkEnabled("usb0", true)
            assertNull(d.state.networkError)
            older.complete(Unit)
            assertNull(d.state.networkError)
        }

    @Test
    fun `a failure for one network is not wiped by a success for another`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2")))
            d.source.results["setNetworkEnabled usb0 false"] = CommandResult.NotFound
            d.model.setNetworkEnabled("usb0", false)
            d.model.setNetworkEnabled("en0", false)
            assertEquals("The network could not be switched.", d.state.networkError)
        }

    @Test
    fun `the error goes when the network is gone or the engine reports the state asked for, and does not come back`() =
        dialog { d ->
            val two = listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2"))
            d.source.networks.value = BondingState(two)
            d.source.results["setNetworkEnabled usb0 false"] = CommandResult.NotFound
            d.model.setNetworkEnabled("usb0", false)
            assertEquals("The network could not be switched.", d.state.networkError)
            // The engine reports the state that was asked for.
            d.source.networks.value = BondingState(listOf(two[0], two[1].copy(enabled = false)))
            assertNull(d.state.networkError)
            d.source.networks.value = BondingState(two)
            assertNull(d.state.networkError)
            // A failed switch, then the section is hidden, then it is back.
            d.model.setNetworkEnabled("usb0", false)
            assertEquals("The network could not be switched.", d.state.networkError)
            d.source.networks.value = BondingState(listOf(two[0]))
            d.source.networks.value = BondingState(two)
            assertNull(d.state.networkError)
        }

    @Test
    fun `a command that throws something else says the network could not be switched`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2")))
            d.source.commandThrows = java.io.IOException("boom")
            d.model.setNetworkEnabled("usb0", false)
            assertEquals("The network could not be switched.", d.state.networkError)
        }

    @Test
    fun `a switch with the engine stopped says so`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2")))
            d.source.refuseAll = true
            d.model.setNetworkEnabled("usb0", false)
            assertEquals("The engine has stopped.", d.state.networkError)
        }

    @Test
    fun `with every network off or down the form says no network is on`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("usb0", "USB", "172.20.10.2")))
            assertFalse(d.state.noNetworkOn)
            d.source.networks.value =
                BondingState(
                    listOf(network("en0", "Wi-Fi", "192.168.1.5", enabled = false), network("usb0", "USB", "172.20.10.2", up = false)),
                )
            assertTrue(d.state.noNetworkOn)
        }

    @Test
    fun `two networks in use on one subnet bring a warning with their names`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("en7", "USB Tethering", "192.168.1.9")))
            d.looks.value = NetworkLooks(names = mapOf("en7" to "Pixel"))
            assertEquals(
                "Wi-Fi and Pixel share a subnet (192.168.1.0/24), so the computer sends both down one route and they can't be combined.",
                d.state.subnetWarning,
            )
        }

    @Test
    fun `no warning when the subnets differ, when one network is off, or when there is only one`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("en7", "USB", "172.20.10.2")))
            assertNull(d.state.subnetWarning)
            d.source.networks.value =
                BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5"), network("en7", "USB", "192.168.1.9", enabled = false)))
            assertNull(d.state.subnetWarning)
            d.source.networks.value = BondingState(listOf(network("en0", "Wi-Fi", "192.168.1.5")))
            assertNull(d.state.subnetWarning)
        }

    @Test
    fun `three networks on one subnet are all named`() =
        dialog { d ->
            d.source.networks.value =
                BondingState(
                    listOf(
                        network("a", "A", "10.0.0.2"),
                        network("b", "B", "10.0.0.3"),
                        network("c", "C", "10.0.0.4"),
                    ),
                )
            assertTrue(d.state.subnetWarning!!.startsWith("A, B and C share a subnet (10.0.0.0/24)"))
        }

    // Subnets

    @Test
    fun `a subnet is the address with its host bits cleared`() {
        assertEquals("192.168.1.0/24", subnetText("192.168.1.77", 24))
        assertEquals("10.0.0.0/8", subnetText("10.20.30.40", 8))
        assertEquals("172.20.10.0/28", subnetText("172.20.10.2", 28))
        assertEquals("192.168.4.0/22", subnetText("192.168.5.9", 22))
        assertNull(subnetText("fe80::1", 64))
        assertNull(subnetText("not an address", 24))
    }

    @Test
    fun `a network's subnet uses the first IPv4 address and a 24 bit prefix when the OS gives none`() {
        val dual = NetworkInfo("en0", "Wi-Fi", true, listOf("fe80::1", "192.168.1.5"))
        assertEquals("192.168.1.0/24", subnetOf(dual) { _, _ -> null })
        assertEquals("192.168.0.0/16", subnetOf(dual) { _, _ -> 16 })
        assertNull(subnetOf(NetworkInfo("en0", "x", true, listOf("fe80::1"))) { _, _ -> null })
    }

    private companion object {
        const val DEBOUNCE = 600L
    }
}
