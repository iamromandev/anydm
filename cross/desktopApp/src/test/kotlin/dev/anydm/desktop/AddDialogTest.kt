package dev.anydm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import dev.anydm.engine.InspectResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.QUEUED
import dev.anydm.engine.core.DuplicateFound
import dev.anydm.engine.core.DuplicateReason
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.torrent.TorrentFile
import dev.anydm.engine.torrent.TorrentInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class AddDialogTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private lateinit var model: AppModel

    private val pickers =
        object : Pickers {
            var torrent: Path? = null

            override fun torrentFile() = torrent

            override fun folder(): Path? = null

            override fun keyFile(): Path? = null
        }

    @AfterTest
    fun close() = scope.cancel()

    private fun show(vararg snapshots: dev.anydm.engine.DownloadSnapshot) {
        source.downloads.value = snapshots.toList()
        model =
            AppModel(
                source,
                object : FirstRun {
                    override fun isDone() = true

                    override fun markDone() = Unit
                },
                scope,
                FakeFiles(),
            )
        model.start()
        rule.setContent { AnydmTheme(dark = false) { Shell(model, pickers) } }
    }

    private fun tag(name: String): SemanticsNodeInteraction = rule.onNodeWithTag(name)

    private fun click(name: String) = tag(name).performScrollTo().performClick()

    // Links are read after a short settle time; this waits until none is still being read.
    private fun awaitRead() =
        rule.waitUntil(5_000) {
            model.state.value.adding
                ?.state
                ?.value
                ?.rows
                ?.let { rows -> rows.isNotEmpty() && rows.none { it.status == RowStatus.CHECKING } } == true
        }

    private val command = if (IsMac) Key.MetaLeft else Key.CtrlLeft

    @Test
    fun `command-n opens it, esc closes it`() {
        show()
        rule.onRoot().performKeyInput { withKeyDown(command) { pressKey(Key.N) } }
        tag("add-dialog").assertIsDisplayed()
        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        tag("add-dialog").assertDoesNotExist()
    }

    @Test
    fun `pasted links each show what they are, and each shows its add`() {
        show()
        tag("add-open").performClick()
        tag("add-text").performTextInput("https://a.example/x.zip\nhello")
        awaitRead()
        tag("add-status-0").assertTextEquals("New")
        tag("add-status-1").assertTextEquals("Not a link")
        tag("add-submit").assertIsEnabled()
        click("add-submit")
        assertEquals(listOf("https://a.example/x.zip"), source.added.map { it.mirrors.single() })
        // Not everything was added, so it stays open with what came of each.
        tag("add-status-0").assertTextEquals("Added")
        tag("add-dialog").assertIsDisplayed()
    }

    @Test
    fun `it opens with links already in it`() {
        show()
        model.openAdd(listOf("https://a.example/x.zip", "https://a.example/y.zip"))
        rule.waitForIdle()
        awaitRead()
        tag("add-status-0").assertTextEquals("New")
        tag("add-status-1").assertTextEquals("New")
        tag("add-submit").assertTextEquals("Add 2")
    }

    @Test
    fun `a duplicate can be shown or added anyway`() {
        source.probes["https://a.example/x.zip"] =
            LinkProbeResult.Probed(
                "https://a.example/x.zip",
                DownloadKind.DIRECT,
                "x.zip",
                1,
                true,
                true,
                DuplicateFound(DownloadId(7), DuplicateReason.SAME_URL),
            )
        show(snapshot(7, QUEUED, name = "x.zip"))
        model.openAdd(listOf("https://a.example/x.zip"))
        rule.waitForIdle()
        awaitRead()
        tag("add-status-0").assertTextEquals("Duplicate")
        tag("add-submit").assertIsNotEnabled()
        click("add-anyway-0")
        tag("add-submit").assertIsEnabled()
        click("add-show-0")
        tag("add-dialog").assertDoesNotExist()
        assertEquals(setOf(DownloadId(7)), model.state.value.selection)
    }

    @Test
    fun `media choices reach the engine`() {
        show()
        model.openAdd(listOf("https://video.example/watch?v=1"))
        rule.waitForIdle()
        click("add-mode-media")
        tag("add-status-0").assertTextEquals("Media")
        click("add-preset-p1080")
        click("add-sponsor-mark")
        tag("add-sub-langs").performScrollTo().performTextInput("en")
        click("add-submit")
        val format = source.added.single().format!!
        assertEquals(1080, format.height)
        assertEquals(listOf("en"), format.subtitles?.languages)
    }

    @Test
    fun `a bad playlist range keeps add off and says why`() {
        show()
        model.openAdd(listOf("https://video.example/list"))
        rule.waitForIdle()
        click("add-mode-media")
        click("add-playlist")
        tag("add-range").performScrollTo().performTextInput("one to five")
        tag("add-error-range").performScrollTo().assertIsDisplayed()
        tag("add-submit").assertIsNotEnabled()
    }

    @Test
    fun `a torrent file lists its files to choose from`() {
        val info =
            TorrentInfo(
                "pack",
                "0123456789abcdef0123456789abcdef01234567",
                listOf(TorrentFile(0, "pack/a", 1), TorrentFile(1, "pack/b", 2)),
                byteArrayOf(1),
            )
        val file = Path.of("/tmp/pack.torrent")
        pickers.torrent = file
        source.inspects[file.toUri().toString()] = InspectResult.Inspected(info)
        show()
        tag("add-open").performClick()
        click("add-open-torrent")
        awaitRead()
        tag("add-status-0").assertTextEquals("New")
        tag("add-torrent").assertIsDisplayed()
        click("add-file-1")
        click("add-sequential")
        click("add-submit")
        val added = source.torrentsAdded.single()
        assertEquals(setOf("pack/a"), added.files)
        assertEquals(true, added.sequential)
    }

    @Test
    fun `an sftp link asks for a login and never shows the password`() {
        show()
        model.openAdd(listOf("sftp://host.example/f.iso"))
        rule.waitForIdle()
        tag("add-login").assertIsDisplayed()
        tag("add-user").performScrollTo().performTextInput("me")
        tag("add-password").performScrollTo().performTextInput("s3cret")
        tag("add-password").assertTextContains("••••••", substring = true)
        click("add-submit")
        assertEquals(
            "sftp://me:s3cret@host.example/f.iso",
            source.added
                .single()
                .mirrors
                .single(),
        )
        tag("add-dialog").assertDoesNotExist()
    }

    @Test
    fun `cancel closes it and adds nothing`() {
        show()
        model.openAdd(listOf("https://a.example/x.zip"))
        rule.waitForIdle()
        click("add-cancel")
        tag("add-dialog").assertDoesNotExist()
        assertNull(model.state.value.adding)
        assertEquals(emptyList(), source.added)
    }
}
