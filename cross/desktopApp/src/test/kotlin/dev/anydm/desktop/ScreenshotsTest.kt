package dev.anydm.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import dev.anydm.engine.http.EngineEvent
import dev.anydm.engine.http.ProbeOutcome
import dev.anydm.engine.http.ProbeResult
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentMeta
import dev.anydm.engine.torrent.TorrentResult
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/** Writes review screenshots to build/shots; the assertion only checks that they were produced. */
@OptIn(ExperimentalTestApi::class)
class ScreenshotsTest {
    private val dir = File("build/shots")

    private fun shot(
        name: String,
        state: HomeState,
        dark: Boolean,
        width: Int,
        height: Int,
        link: String? = null,
        files: FilesEnv = FilesEnv.inert(),
        stores: AppStores = AppStores.inMemory(),
        env: DownloadEnv = DownloadEnv.inert(),
        act: ComposeUiTest.() -> Unit = {},
    ) = runDesktopComposeUiTest(width, height) {
        setContent { HomeScreen(state, dark, files = files, platform = Platform.Mac, stores = stores, downloadEnv = env) }
        act()
        if (link != null) {
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput(link)
        }
        dir.mkdirs()
        val file = File(dir, "$name.png")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        assertTrue(file.length() > 0)
    }

    @Test
    fun capture() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            shot("populated-$theme-1280x800", populatedSample(), dark, 1280, 800)
            shot("empty-$theme-1280x800", emptySample(), dark, 1280, 800)
            shot("populated-$theme-900x600", populatedSample(), dark, 900, 600)
        }
    }

    private fun realRows(): HomeState {
        val mb = 1024L * 1024

        fun row(
            id: String,
            name: String,
            total: Long,
            done: Long,
            speed: Long,
            status: DownloadStatus,
            error: String? = null,
        ) = DownloadItem(id, name, total, done, speed, status, url = "https://example.com/$name", error = error)
        return populatedSample().copy(
            downloads =
                listOf(
                    row("1", "ubuntu-24.04.iso", 5_800 * mb, 2_320 * mb, 12 * mb, DownloadStatus.Downloading),
                    row("2", "stream.bin", UNKNOWN_SIZE, 48 * mb, 3 * mb, DownloadStatus.Downloading),
                    row("3", "holiday.mkv", 900 * mb, 360 * mb, 0, DownloadStatus.Paused),
                    row("4", "backup.zip", 120 * mb, 30 * mb, 0, DownloadStatus.Failed, "HTTP 404"),
                    row("5", "disk.img", 700 * mb, 350 * mb, 0, DownloadStatus.Failed, "Disk full"),
                    row("6", "report.pdf", 5 * mb, 5 * mb, 0, DownloadStatus.Finished),
                ),
        )
    }

    private fun prober(outcome: ProbeOutcome) = DownloadEnv.inert().copy(prober = FakeProber(fallback = outcome))

    private fun ComposeUiTest.typeLink(link: String) {
        onNodeWithTag("new-download").performClick()
        onNodeWithTag("url-field").performTextInput(link)
    }

    private fun ComposeUiTest.awaitDetail(text: String) {
        val end = System.nanoTime() + 5_000_000_000L
        while (runCatching { onNodeWithTag("preview-detail").assertTextEquals(text) }.isFailure && System.nanoTime() < end) {
            mainClock.advanceTimeByFrame()
            Thread.sleep(5)
        }
    }

    @Test
    fun captureHttpDownloads() {
        val link = "https://example.com/files/ubuntu-24.04.iso"
        val ok = prober(ProbeOutcome.Ok(ProbeResult("ubuntu-24.04.iso", 5_800L * 1024 * 1024, true)))
        val unknown = prober(ProbeOutcome.Ok(ProbeResult("stream.bin", null, false)))
        val failed = prober(ProbeOutcome.Failed("HTTP 404"))
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("downloads-rows-$size", realRows(), dark, w, h)
                shot("downloads-probe-checking-$size", emptySample(), dark, w, h, env = ok) { typeLink(link) }
                shot("downloads-probe-ready-$size", emptySample(), dark, w, h, env = ok) {
                    typeLink(link)
                    awaitDetail("5.7 GB · Resumable")
                }
                shot("downloads-probe-unknown-$size", emptySample(), dark, w, h, env = unknown) {
                    typeLink(link)
                    awaitDetail("size unknown · Uses one connection. This server doesn't support parallel downloads.")
                }
                shot("downloads-probe-failed-$size", emptySample(), dark, w, h, env = failed) {
                    typeLink(link)
                    awaitDetail("HTTP 404")
                }
                shot("downloads-remove-$size", realRows(), dark, w, h) {
                    onNodeWithTag("select-1").performClick()
                    onNodeWithTag("bar-remove").performClick()
                    onNodeWithTag("remove-delete").performClick()
                }
                shot("downloads-detail-failed-$size", realRows(), dark, w, h) { onNodeWithTag("row-4").performClick() }
                val engine = FakeEngine()
                shot("downloads-detail-running-$size", realRows(), dark, w, h, env = DownloadEnv.inert().copy(engine = engine)) {
                    waitForIdle()
                    // A session of speeds, as the engine's progress reports would have made them.
                    val mb = 1024L * 1024
                    listOf(2, 6, 9, 11, 12, 8, 4, 10, 12, 13, 12).forEachIndexed { i, v ->
                        engine.emit(EngineEvent.Progress("1", (2_320 + i) * mb, 5_800 * mb, v * mb))
                        mainClock.advanceTimeByFrame()
                    }
                    waitForIdle()
                    onNodeWithTag("chevron-1").performClick()
                }
                shot("downloads-detail-finished-$size", realRows(), dark, w, h) {
                    onNodeWithTag("download-list").performScrollToKey("6") // below the fold in the small window
                    onNodeWithTag("chevron-6").performClick()
                }
            }
        }
    }

    @Test
    fun captureDialog() {
        val direct = "https://example.com/files/archive.zip"
        val streams = "http://127.0.0.1:8765/Project.zip"
        val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d8"
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            shot("dialog-direct-$theme-1280x800", populatedSample(), dark, 1280, 800, direct)
            shot("dialog-magnet-$theme-1280x800", populatedSample(), dark, 1280, 800, magnet)
            shot("dialog-streams-$theme-1280x800", populatedSample(), dark, 1280, 800, streams)
            shot("dialog-magnet-$theme-900x540", populatedSample(), dark, 900, 540, magnet)
            shot("dialog-magnet-$theme-900x600", populatedSample(), dark, 900, 600, magnet)
        }
    }

    @Test
    fun captureShortcuts() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                shot("shortcuts-$theme-${w}x$h", populatedSample(), dark, w, h) {
                    onRoot().performKeyInput {
                        keyDown(Key.MetaLeft)
                        pressKey(Key.Slash)
                        keyUp(Key.MetaLeft)
                    }
                }
            }
        }
    }

    @Test
    fun captureNetworksMenu() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            shot("networks-open-$theme-1280x800", populatedSample(), dark, 1280, 800) { openNetworks() }
            shot("networks-off-$theme-1280x800", populatedSample(), dark, 1280, 800) {
                openNetworks()
                onNodeWithTag("network-dot-usb").performClick()
            }
            shot("networks-rename-$theme-1280x800", populatedSample(), dark, 1280, 800) {
                openNetworks()
                onNodeWithTag("network-rename-wifi").performClick()
                onNodeWithTag("network-rename-field").performTextReplacement("Home office")
            }
            shot("networks-rename-error-$theme-1280x800", populatedSample(), dark, 1280, 800) {
                openNetworks()
                onNodeWithTag("network-rename-wifi").performClick()
                onNodeWithTag("network-rename-field").performTextReplacement("usb tethering")
                onNodeWithTag("network-rename-field").performKeyInput { pressKey(Key.Enter) }
            }
            shot("networks-open-$theme-900x600", populatedSample(), dark, 900, 600) { openNetworks() }
            shot("networks-empty-$theme-1280x800", emptySample(), dark, 1280, 800) { openNetworks() }
        }
    }

    @Test
    fun captureLimits() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("limits-general-$size", populatedSample(), dark, w, h) { openLimits() }
                shot("limits-general-bottom-$size", populatedSample(), dark, w, h) {
                    openLimits()
                    onNodeWithTag("schedule-enabled").performScrollTo().performClick()
                    onNodeWithTag("limits-reset-defaults").performScrollTo()
                }
                shot("limits-wifi-$size", populatedSample(), dark, w, h) {
                    openLimits()
                    onNodeWithTag("limits-nav-wifi").performClick()
                    onNodeWithTag("net-speed-limit").performClick()
                    onNodeWithTag("net-speed-field").performTextInput("10")
                    onNodeWithTag("net-data-limit").performClick()
                    onNodeWithTag("net-data-field").performTextInput("5")
                }
                shot("limits-wifi-bottom-$size", populatedSample(), dark, w, h) {
                    openLimits()
                    onNodeWithTag("limits-nav-wifi").performClick()
                    onNodeWithTag("remove-limits").performScrollTo()
                }
                shot("limits-error-$size", populatedSample(), dark, w, h) {
                    openLimits()
                    onNodeWithTag("total-limit").performClick()
                    onNodeWithTag("schedule-enabled").performScrollTo().performClick()
                    onNodeWithTag("schedule-end").performTextReplacement("08:00")
                }
            }
        }
    }

    @Test
    fun captureSettings() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("settings-general-$size", populatedSample(), dark, w, h) { openSettings() }
                shot("settings-appearance-$size", populatedSample(), dark, w, h) {
                    openSettings()
                    onNodeWithTag("settings-nav-appearance").performClick()
                }
                shot("settings-speeddata-$size", populatedSample(), dark, w, h) {
                    openSettings()
                    onNodeWithTag("settings-nav-speeddata").performClick()
                }
                shot("settings-speeddata-wifi-$size", populatedSample(), dark, w, h) {
                    openSettings()
                    onNodeWithTag("settings-nav-speeddata").performClick()
                    onNodeWithTag("limits-nav-wifi").performClick()
                    onNodeWithTag("net-speed-limit").performClick()
                    onNodeWithTag("net-speed-field").performTextInput("10")
                }
                shot("settings-networks-$size", populatedSample().withUsbColor(NetworkColor.Pink), dark, w, h) {
                    openSettings()
                    onNodeWithTag("settings-nav-networks").performClick()
                }
                // The sample password is only ever typed into the masked field, and the keychain here is the "none" one.
                shot(
                    "settings-networks-proxy-note-$size",
                    populatedSample(),
                    dark,
                    w,
                    h,
                    stores = AppStores(InMemorySettingsStore(), UnavailableSecretStore),
                ) {
                    openSettings()
                    onNodeWithTag("settings-nav-networks").performClick()
                    onNodeWithTag("network-page-proxy-edit").performClick()
                    onNodeWithTag("proxy-page-type-socks5").performClick()
                    onNodeWithTag("proxy-page-host").performTextReplacement("127.0.0.1")
                    onNodeWithTag("proxy-page-port").performTextReplacement("1080")
                    onNodeWithTag("proxy-page-user").performTextReplacement("alice")
                    onNodeWithTag("proxy-page-password").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("secret")) }
                    onNodeWithTag("proxy-page-save").performClick()
                    waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("network-page-proxy-note").fetchSemanticsNodes().isNotEmpty() }
                }
            }
        }
        // Notices: a reset file and a failed save, over the list.
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            val reset = AppStores(InMemorySettingsStoreAnswering(LoadResult.Reset("not valid settings")), InMemorySecretStore())
            shot("settings-reset-notice-$theme-1280x800", populatedSample(), dark, 1280, 800, stores = reset) {
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("notice").fetchSemanticsNodes().isNotEmpty() }
            }
        }
    }

    @Test
    fun captureNetworkSettings() {
        val magnet = "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d8"
        val base = populatedSample()
        val withProxy =
            base.copy(
                networks =
                    listOf(
                        base.networks[0].copy(color = NetworkColor.Pink),
                        base.networks[1].copy(
                            color = NetworkColor.Orange,
                            proxy = Proxy(ProxyType.Socks5, "127.0.0.1", 1080, "alice", "secret"),
                        ),
                    ),
            )
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("network-page-$size", base, dark, w, h) {
                    openNetworks()
                    onNodeWithTag("network-chevron-usb").performClick()
                }
                shot("network-page-orange-$size", base.withUsbColor(NetworkColor.Orange), dark, w, h) {
                    openNetworks()
                    onNodeWithTag("network-chevron-usb").performClick()
                }
                shot("network-page-colored-$size", withProxy, dark, w, h) {
                    openNetworks()
                    onNodeWithTag("network-chevron-usb").performClick()
                }
                // The sample password is only ever typed into the masked field.
                shot("network-page-proxy-editor-$size", base, dark, w, h) {
                    openNetworks()
                    onNodeWithTag("network-chevron-usb").performClick()
                    onNodeWithTag("network-page-proxy-edit").performClick()
                    onNodeWithTag("proxy-page-type-http").performClick()
                    onNodeWithTag("proxy-page-host").performTextReplacement("10.0.0.2")
                    onNodeWithTag("proxy-page-port").performTextReplacement("8080")
                    onNodeWithTag("proxy-page-user").performTextReplacement("alice")
                    onNodeWithTag("proxy-page-password").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("secret")) }
                }
                shot("network-page-proxy-error-$size", base, dark, w, h) {
                    openNetworks()
                    onNodeWithTag("network-chevron-usb").performClick()
                    onNodeWithTag("network-page-proxy-edit").performClick()
                    onNodeWithTag("proxy-page-type-socks5").performClick()
                    onNodeWithTag("proxy-page-host").performTextReplacement("http://x")
                    onNodeWithTag("proxy-page-port").performTextReplacement("70000")
                    onNodeWithTag("proxy-page-password").performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("secret")) }
                }
                shot("new-download-proxy-chips-$size", withProxy, dark, w, h) {
                    onNodeWithTag("new-download").performClick()
                    onNodeWithTag("url-field").performTextInput(magnet)
                }
                shot("new-download-proxy-popover-$size", base, dark, w, h) {
                    onNodeWithTag("new-download").performClick()
                    onNodeWithTag("url-field").performTextInput(magnet)
                    onNodeWithTag("proxy-chip-usb").performClick()
                }
                shot("new-download-proxy-popover-http-$size", withProxy, dark, w, h) {
                    onNodeWithTag("new-download").performClick()
                    onNodeWithTag("url-field").performTextInput(magnet)
                    onNodeWithTag("proxy-chip-usb").performClick()
                    onNodeWithTag(
                        "proxy-chip-usb-password",
                    ).performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString("secret")) }
                }
            }
        }
    }

    /** Fakes only: nothing here opens an OS dialog. */
    private fun filesEnv(
        torrent: TorrentResult,
        folder: String? = null,
    ) = FilesEnv(
        object : FilePicker {
            override fun pickTorrent(): Path? = Path.of("/t/The.Show.S01.torrent")

            override fun pickFolder(current: String): String? = folder
        },
        object : TorrentLoader {
            override suspend fun load(path: Path): TorrentResult = torrent
        },
        "/Users/x",
    )

    @Test
    fun captureFiles() {
        val show =
            TorrentResult.Ok(
                TorrentMeta(
                    "The.Show.S01.1080p",
                    listOf(
                        TorrentFile("Season 1/E01.The.Pilot.mkv", 1_800_000_000),
                        TorrentFile("Season 1/E02.The.Second.One.mkv", 1_650_000_000),
                        TorrentFile("Season 1/E03.mkv", 1_700_000_000),
                        TorrentFile("Subs/English.srt", 90_000),
                        TorrentFile("notes.txt", 0),
                    ),
                ),
            )
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("files-torrent-$size", populatedSample(), dark, w, h, files = filesEnv(show)) {
                    onNodeWithTag("new-download").performClick()
                    onNodeWithTag("open-torrent").performClick()
                    waitForIdle()
                }
                shot(
                    "files-folder-$size",
                    populatedSample(),
                    dark,
                    w,
                    h,
                    files = filesEnv(show, "/Users/x/Movies/Shows and Series/Season One"),
                ) {
                    onNodeWithTag("new-download").performClick()
                    onNodeWithTag("open-torrent").performClick()
                    waitForIdle()
                    onNodeWithTag("save-change").performClick()
                    waitForIdle()
                }
                shot("files-error-$size", populatedSample(), dark, w, h, files = filesEnv(TorrentResult.Err("Too large"))) {
                    onNodeWithTag("new-download").performClick()
                    onNodeWithTag("open-torrent").performClick()
                    waitForIdle()
                }
                val env = filesEnv(show)
                shot("files-drop-overlay-$size", populatedSample(), dark, w, h, files = env) {
                    runOnIdle { env.drops.hover(true) }
                    waitForIdle()
                }
                shot("files-empty-$size", emptySample(), dark, w, h)
                val env2 = filesEnv(show)
                shot("files-drop-message-$size", populatedSample(), dark, w, h, files = env2) {
                    runOnIdle { env2.drops.drop(DropItems(listOf(Path.of("/t/pic.png")), null)) }
                    waitForIdle()
                }
            }
        }
    }

    @Test
    fun captureDetail() {
        val base = detailSample()
        val torrent = base.downloads.first { it.id == "3" }
        val finishedTorrent = torrent.copy(id = "4", status = DownloadStatus.Finished, doneBytes = torrent.totalBytes, bytesPerSec = 0)
        val withFinishedTorrent = base.copy(downloads = base.downloads + finishedTorrent)
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("detail-two-$size", base, dark, w, h) { openDetail("1") }
                shot("detail-single-$size", base, dark, w, h) {
                    openDetail("1")
                    onNodeWithTag("detail-check-usb").performScrollTo().performClick()
                }
                shot("detail-finished-$size", base, dark, w, h) { openDetail("2") }
                shot("detail-torrent-$size", base, dark, w, h) { openDetail("3") }
                shot("detail-torrent-finished-$size", withFinishedTorrent, dark, w, h) { openDetail("4") }
                shot("detail-files-menu-$size", base, dark, w, h) {
                    openDetail("3")
                    onNodeWithTag("detail-files").performScrollTo().performClick()
                }
            }
        }
    }

    @Test
    fun captureBulk() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("bulk-bar-$size", detailSample(), dark, w, h) { tick("1", "2") }
                shot("bulk-remove-$size", detailSample(), dark, w, h) {
                    tick("1", "2")
                    onNodeWithTag("bar-remove").performClick()
                }
                shot("bulk-menu-finished-$size", detailSample(), dark, w, h) { rightClickRow("2") }
                shot("bulk-menu-mixed-$size", detailSample(), dark, w, h) {
                    tick("1", "2")
                    rightClickRow("1")
                }
                shot("bulk-menu-edge-$size", detailSample(), dark, w, h) {
                    onNodeWithTag("row-3").performMouseInput { rightClick(Offset(width - 2f, height - 2f)) }
                }
            }
        }
    }

    @Test
    fun captureQueue() {
        for ((theme, dark) in listOf("dark" to true, "light" to false)) {
            for ((w, h) in listOf(1280 to 800, 900 to 600)) {
                val size = "$theme-${w}x$h"
                shot("queue-list-$size", detailSample(), dark, w, h) { queueTwo() }
                shot("queue-menu-$size", detailSample(), dark, w, h) {
                    queueTwo()
                    rightClickRow("5")
                }
                shot("queue-detail-$size", detailSample(), dark, w, h) {
                    queueTwo()
                    openDetail("5")
                }
                shot("queue-new-download-hint-$size", detailSample(), dark, w, h) {
                    queueTwo()
                    onNodeWithTag("new-download").performClick()
                    onNodeWithTag("url-field").performTextInput("https://example.com/files/c.zip")
                }
            }
        }
    }

    /** At limit 2 with two rows running, these two wait as queue positions 1 and 2. */
    private fun ComposeUiTest.queueTwo() {
        for (name in listOf("archive.zip", "notes.pdf")) {
            onNodeWithTag("new-download").performClick()
            onNodeWithTag("url-field").performTextInput("https://example.com/files/$name")
            onNodeWithTag("download").performClick()
        }
    }

    private fun HomeState.withUsbColor(color: NetworkColor) =
        copy(networks = networks.map { if (it.id == "usb") it.copy(color = color) else it })

    private fun ComposeUiTest.rightClickRow(id: String) =
        onNodeWithTag("row-$id").performMouseInput { rightClick(Offset(width / 3f, height / 2f)) }

    private fun ComposeUiTest.tick(vararg ids: String) = ids.forEach { onNodeWithTag("select-$it").performClick() }

    private fun ComposeUiTest.openDetail(id: String) {
        onNodeWithTag("chevron-$id").performScrollTo().performClick()
    }

    private fun ComposeUiTest.openLimits() {
        openNetworks()
        onNodeWithTag("networks-limits-link").performClick()
    }

    private fun ComposeUiTest.openNetworks() = onNodeWithTag("network-chip").performClick()

    private fun ComposeUiTest.openSettings() {
        onRoot().performKeyInput {
            keyDown(Key.MetaLeft)
            pressKey(Key.Comma)
            keyUp(Key.MetaLeft)
        }
    }

    /** A store whose file is always unreadable, so the reset notice shows. */
    private class InMemorySettingsStoreAnswering(
        private val result: LoadResult,
    ) : SettingsStore {
        override fun load() = result

        override fun save(file: SettingsFile): SaveResult = SaveResult.Ok
    }
}
