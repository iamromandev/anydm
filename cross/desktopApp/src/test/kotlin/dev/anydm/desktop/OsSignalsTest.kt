package dev.anydm.desktop

import dev.anydm.engine.EngineEvent
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.AfterQueueState
import dev.anydm.engine.os.ClipboardLink
import dev.anydm.engine.os.HotkeyStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class OsSignalsTest {
    private val link = ClipboardLink("https://example.com/a.zip", DownloadKind.DIRECT)

    private fun TestScope.model(
        source: FakeSource,
        window: WindowModel = WindowModel(MemoryPrefs(), trayAvailable = true),
    ) = AppModel(
        source,
        object : FirstRun {
            override fun isDone() = true

            override fun markDone() = Unit
        },
        backgroundScope + UnconfinedTestDispatcher(testScheduler),
        FakeFiles(),
        window = window,
    ).also { it.start() }

    // --- clipboard review

    @Test
    fun `a copied link is offered, and accepting opens the add review with it`() =
        runTest {
            val source = FakeSource()
            val window = WindowModel(MemoryPrefs(), trayAvailable = true).also { it.hide() }
            val model = model(source, window)
            source.clipboardLinks.emit(link)
            assertEquals(link, model.os.value.clipboard)

            model.acceptClipboard()

            assertNull(model.os.value.clipboard)
            assertTrue(window.state.value.visible)
            assertTrue(
                "https://example.com/a.zip" in
                    model.state.value.adding!!
                        .state.value.text,
            )
        }

    @Test
    fun `a copied link can be dismissed, and a newer one replaces an older one`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.clipboardLinks.emit(link)
            source.clipboardLinks.emit(link.copy(link = "https://example.com/b.zip"))
            assertEquals(
                "https://example.com/b.zip",
                model.os.value.clipboard!!
                    .link,
            )
            model.dismissClipboard()
            assertNull(model.os.value.clipboard)
            assertNull(model.state.value.adding)
        }

    // --- hotkey

    @Test
    fun `the hotkey opens the add review with the copied link and shows the window`() =
        runTest {
            val source = FakeSource()
            val window = WindowModel(MemoryPrefs(), trayAvailable = true).also { it.hide() }
            val model = model(source, window)
            val magnet = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
            source.hotkeyPresses.emit(magnet)
            assertTrue(window.state.value.visible)
            assertTrue(
                magnet in
                    model.state.value.adding!!
                        .state.value.text,
            )
        }

    @Test
    fun `the hotkey keeps only the links of what was copied`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.hotkeyPresses.emit("my password is hunter2\nhttps://example.com/a.zip\nnot a link")
            val text =
                model.state.value.adding!!
                    .state.value.text
            assertTrue("https://example.com/a.zip" in text)
            assertFalse("hunter2" in text)
        }

    @Test
    fun `the hotkey with nothing to add says so and opens nothing`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.hotkeyPresses.emit("   ")
            source.hotkeyPresses.emit("just some words")
            assertNull(model.state.value.adding)
            assertTrue(
                model.state.value.banners
                    .any { it is Banner.Error && it.message.contains("no link") },
            )
        }

    @Test
    fun `a huge clipboard is cut before it is looked at`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.hotkeyPresses.emit("x".repeat(500_000) + "\nhttps://example.com/late.zip")
            assertNull(model.state.value.adding)
        }

    @Test
    fun `the hotkey and its status follow the engine`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.osSettings.value = source.osSettings.value.copy(globalHotkey = true)
            source.hotkeyStatus.value = HotkeyStatus.Active
            assertTrue(model.os.value.hotkeyOn)
            assertEquals(HotkeyStatus.Active, model.os.value.hotkey)
        }

    @Test
    fun `a hotkey that needs permission or cannot work says what to do, only while it is on`() {
        assertNull(hotkeyNotice(OsSignals(hotkeyOn = false, hotkey = HotkeyStatus.NeedsPermission)))
        assertNull(hotkeyNotice(OsSignals(hotkeyOn = true, hotkey = HotkeyStatus.Active)))
        val permission = hotkeyNotice(OsSignals(hotkeyOn = true, hotkey = HotkeyStatus.NeedsPermission))!!
        assertTrue(permission.text.contains("Accessibility"))
        assertTrue(permission.text.contains("off and on"))
        val wayland = hotkeyNotice(OsSignals(hotkeyOn = true, hotkey = HotkeyStatus.Unsupported("Wayland does not allow it")))!!
        assertTrue(wayland.text.contains("Wayland"))
        val other = hotkeyNotice(OsSignals(hotkeyOn = true, hotkey = HotkeyStatus.Unsupported("no capture library for this CPU")))!!
        assertFalse(other.text.contains("Wayland"))
        assertTrue(other.text.contains("no capture library"))
        val failed = hotkeyNotice(OsSignals(hotkeyOn = true, hotkey = HotkeyStatus.Failed("no keyboard")))!!
        assertTrue(failed.text.contains("no keyboard"))
    }

    @Test
    fun `a hotkey notice can be dismissed until the status changes`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.osSettings.value = source.osSettings.value.copy(globalHotkey = true)
            source.hotkeyStatus.value = HotkeyStatus.NeedsPermission
            assertNotNull(hotkeyNotice(model.os.value))
            model.dismissHotkeyNotice()
            assertNull(hotkeyNotice(model.os.value))
            source.hotkeyStatus.value = HotkeyStatus.Failed("x")
            assertNotNull(hotkeyNotice(model.os.value))
        }

    @Test
    fun `a notice closed before comes back when the permission is asked for again`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.osSettings.value = source.osSettings.value.copy(globalHotkey = true)
            source.hotkeyStatus.value = HotkeyStatus.NeedsPermission
            model.dismissHotkeyNotice()
            assertNull(hotkeyNotice(model.os.value))
            // Off and on again is how the user retries: the status goes Off, then asks again.
            source.hotkeyStatus.value = HotkeyStatus.Off
            source.hotkeyStatus.value = HotkeyStatus.NeedsPermission
            assertNotNull(hotkeyNotice(model.os.value))
        }

    // --- indicators

    @Test
    fun `keep awake shows only while something runs and the setting is on`() {
        assertEquals(null, indicators(OsSignals(keepAwake = true), active = 0).firstOrNull { it.tag == "status-awake" })
        assertEquals(null, indicators(OsSignals(keepAwake = false), active = 2).firstOrNull { it.tag == "status-awake" })
        assertEquals("Keeping awake", indicators(OsSignals(keepAwake = true), active = 2).first { it.tag == "status-awake" }.text)
    }

    @Test
    fun `an armed after-queue action and its countdown show`() {
        assertNull(indicators(OsSignals(), 0).firstOrNull { it.tag == "status-after-queue" })
        val armed = OsSignals(afterQueue = AfterQueueState(AfterQueueAction.SLEEP, null))
        assertEquals("After the queue: sleep", indicators(armed, 1).first { it.tag == "status-after-queue" }.text)
        val counting = OsSignals(afterQueue = AfterQueueState(AfterQueueAction.SHUT_DOWN, Instant.parse("2026-10-30T12:00:30Z")))
        assertEquals(
            "Shutting down in 25 s",
            indicators(counting, 0, now = Instant.parse("2026-10-30T12:00:05Z")).first { it.tag == "status-after-queue" }.text,
        )
    }

    @Test
    fun `the bridge and the hotkey have an indicator`() {
        assertEquals(
            "Browser bridge: port 18401",
            indicators(OsSignals(bridge = BridgeStatus.Listening(18401)), 0)
                .first {
                    it.tag ==
                        "status-bridge"
                }.text,
        )
        assertEquals("Browser bridge: off", indicators(OsSignals(bridge = BridgeStatus.Off), 0).first { it.tag == "status-bridge" }.text)
        assertTrue(
            indicators(OsSignals(bridge = BridgeStatus.Failed("no free port")), 0)
                .first {
                    it.tag == "status-bridge"
                }.text
                .contains("no free port"),
        )
        assertNull(indicators(OsSignals(hotkeyOn = false), 0).firstOrNull { it.tag == "status-hotkey" })
        assertEquals(
            "Hotkey on",
            indicators(OsSignals(hotkeyOn = true, hotkey = HotkeyStatus.Active), 0).first { it.tag == "status-hotkey" }.text,
        )
        assertEquals(
            "Hotkey needs permission",
            indicators(OsSignals(hotkeyOn = true, hotkey = HotkeyStatus.NeedsPermission), 0)
                .first {
                    it.tag ==
                        "status-hotkey"
                }.text,
        )
    }

    @Test
    fun `the indicators follow the engine`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.afterQueue.value = AfterQueueState(AfterQueueAction.SLEEP, null)
            source.keepAwake.value = false
            source.bridge.value = BridgeStatus.Listening(18400)
            assertEquals(AfterQueueAction.SLEEP, model.os.value.afterQueue.action)
            assertFalse(model.os.value.keepAwake)
            assertEquals(BridgeStatus.Listening(18400), model.os.value.bridge)
        }

    // --- notification click

    @Test
    fun `the last finished or failed download is remembered for a click on the tray`() =
        runTest {
            val source = FakeSource()
            val window = WindowModel(MemoryPrefs(), trayAvailable = true).also { it.hide() }
            val model = model(source, window)
            source.downloads.value = listOf(snapshot(1, DOWNLOADING), snapshot(2, COMPLETED))
            source.events.emit(EngineEvent.Completed(DownloadId(2), listOf(Path("/dl/b.zip"))))
            assertEquals(DownloadId(2), model.os.value.lastNotified)

            model.trayClicked()

            assertTrue(window.state.value.visible)
            assertEquals(setOf(DownloadId(2)), model.state.value.selection)
            assertNull(model.os.value.lastNotified)
        }

    @Test
    fun `a tray click on a window that is already shown changes nothing else`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.downloads.value = listOf(snapshot(2, COMPLETED))
            source.events.emit(EngineEvent.Completed(DownloadId(2), listOf(Path("/dl/b.zip"))))
            model.navigate(Route.Settings)

            model.trayClicked()

            assertEquals(Route.Settings, model.state.value.route)
            assertEquals(emptySet(), model.state.value.selection)
        }

    @Test
    fun `the tray menu can open the last finished download`() =
        runTest {
            val source = FakeSource()
            val window = WindowModel(MemoryPrefs(), trayAvailable = true)
            val model = model(source, window)
            source.downloads.value = listOf(snapshot(2, COMPLETED))
            source.events.emit(EngineEvent.Completed(DownloadId(2), listOf(Path("/dl/b.zip"))))
            model.navigate(Route.Settings)

            model.trayAction(TrayAction.OPEN_LAST)

            assertEquals(Route.Downloads(Filter.All), model.state.value.route)
            assertEquals(setOf(DownloadId(2)), model.state.value.selection)
        }

    @Test
    fun `a download that was removed is not remembered`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.events.emit(EngineEvent.Completed(DownloadId(2), listOf(Path("/dl/b.zip"))))
            source.events.emit(EngineEvent.Removed(DownloadId(2)))
            assertNull(model.os.value.lastNotified)
        }

    @Test
    fun `a tool's own download is not remembered`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            val tool = snapshot(3, COMPLETED)
            source.downloads.value = listOf(tool.copy(download = tool.download.copy(kind = DownloadKind.TOOL)))
            source.events.emit(EngineEvent.Completed(DownloadId(3), emptyList()))
            assertNull(model.os.value.lastNotified)
        }

    @Test
    fun `a tray click with nothing to show only shows the window`() =
        runTest {
            val window = WindowModel(MemoryPrefs(), trayAvailable = true).also { it.hide() }
            val model = model(FakeSource(), window)
            model.trayClicked()
            assertTrue(window.state.value.visible)
            assertEquals(emptySet(), model.state.value.selection)
        }

    @Test
    fun `a failure is remembered too`() =
        runTest {
            val source = FakeSource()
            val model = model(source)
            source.events.emit(EngineEvent.Failed(DownloadId(5), null, "no route"))
            assertEquals(DownloadId(5), model.os.value.lastNotified)
        }
}
