package dev.anydm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anydm.engine.net.BondingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinkInputReadTest {
    @Test
    fun `lines that are links are read, with the address an anydm link carries`() {
        val read =
            readLinkInput(
                "https://a.example/x.zip\n\n  magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567  \nanydm://add?url=https%3A%2F%2Fb.example%2Fy.zip",
            )
        assertIs<LinkInputRead.Links>(read)
        assertEquals(
            listOf(
                "https://a.example/x.zip",
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                "https://b.example/y.zip",
            ),
            read.links,
        )
    }

    @Test
    fun `one line that is not a link makes the whole input an error`() {
        assertIs<LinkInputRead.Invalid>(readLinkInput("https://a.example/x.zip\nhello"))
        assertIs<LinkInputRead.Invalid>(readLinkInput("hello"))
    }

    @Test
    fun `nothing typed is an error`() {
        assertIs<LinkInputRead.Invalid>(readLinkInput("  \n "))
    }
}

@OptIn(ExperimentalTestApi::class)
class LinkInputTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private lateinit var model: AppModel

    @AfterTest
    fun close() = scope.cancel()

    private fun show(
        width: Dp? = null,
        height: Dp = 800.dp,
    ) {
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
        rule.setContent {
            AnydmTheme(dark = false) {
                if (width == null) Shell(model) else Box(Modifier.width(width).height(height).clipToBounds()) { Shell(model) }
            }
        }
        rule.waitForIdle()
    }

    private fun tag(name: String) = rule.onNodeWithTag(name, useUnmergedTree = true)

    private fun addingText() =
        model.state.value.adding
            ?.state
            ?.value
            ?.text

    @Test
    fun `two links go to the add review and are added from there`() {
        show()
        tag("link-input").performTextInput("https://a.example/x.zip\nhttps://a.example/y.zip")
        tag("link-input-add").performClick()
        rule.waitForIdle()
        assertEquals("https://a.example/x.zip\nhttps://a.example/y.zip", addingText())
        tag("link-input-hint").assertExists()
        rule.waitUntil(5_000) {
            model.state.value.adding
                ?.state
                ?.value
                ?.canAdd == true
        }
        tag("add-submit").performClick()
        assertEquals(listOf("https://a.example/x.zip", "https://a.example/y.zip"), source.added.map { it.mirrors.single() })
    }

    @Test
    fun `text that is not a link shows an error, keeps the text and adds nothing`() {
        show()
        tag("link-input").performTextInput("hello")
        tag("link-input-add").performClick()
        rule.waitForIdle()
        tag("link-input-error").assertExists()
        tag("link-input").assertTextContains("hello")
        assertNull(model.state.value.adding)
        assertTrue(source.added.isEmpty())
    }

    @Test
    fun `nothing typed shows an error`() {
        show()
        tag("link-input-add").performClick()
        rule.waitForIdle()
        tag("link-input-error").assertExists()
        assertNull(model.state.value.adding)
    }

    @Test
    fun `a magnet is accepted`() {
        show()
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        tag("link-input").performTextInput(magnet)
        tag("link-input-add").performClick()
        rule.waitForIdle()
        tag("link-input-error").assertDoesNotExist()
        assertEquals(magnet, addingText())
    }

    @Test
    fun `enter adds and shift enter is a newline`() {
        show()
        tag("link-input").performTextInput("https://a.example/x.zip")
        tag("link-input").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        assertNull(model.state.value.adding)
        assertTrue(model.linkDraft.value.contains("\n"), "draft ${model.linkDraft.value}")
        tag("link-input-error").assertDoesNotExist()
        tag("link-input").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("https://a.example/x.zip", addingText())
        // The key coming back up must not send the cleared text again.
        tag("link-input-error").assertDoesNotExist()
    }

    @Test
    fun `the keypad enter adds too`() {
        show()
        tag("link-input").performTextInput("https://a.example/x.zip")
        tag("link-input").performKeyInput { pressKey(Key.NumPadEnter) }
        rule.waitForIdle()
        assertEquals("https://a.example/x.zip", addingText())
    }

    @Test
    fun `backspace in the input edits the text and does not remove the selected download`() {
        source.downloads.value = listOf(snapshot(1, dev.anydm.engine.core.DownloadStatus.QUEUED))
        show()
        tag("row-1").performClick()
        tag("link-input").performClick()
        tag("link-input").performTextInput("ab")
        tag("link-input").performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertEquals("a", model.linkDraft.value)
        assertNull(model.state.value.dialog)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `the typed text survives a visit to settings and clears only when sent`() {
        show()
        tag("link-input").performTextInput("https://a.example/x.zip")
        model.navigate(Route.Settings)
        rule.waitForIdle()
        model.navigate(Route.Downloads(Filter.All))
        rule.waitForIdle()
        assertEquals("https://a.example/x.zip", model.linkDraft.value)
        tag("link-input-hint").assertDoesNotExist()
        tag("link-input-add").performClick()
        rule.waitForIdle()
        assertEquals("", model.linkDraft.value)
        tag("link-input-hint").assertExists()
    }

    @Test
    fun `at the minimum window a two line input with an error and three budgeted networks leaves the list room`() {
        val mb = 1024L * 1024
        source.networks.value = BondingState(listOf("a", "b", "c").map { network(it, enabled = true, speed = 1.0 * mb) })
        source.usage = mapOf("a" to usage("a", 3 * mb, 2 * mb), "b" to usage("b", 3 * mb, 2 * mb), "c" to usage("c", 3 * mb, 2 * mb))
        source.downloads.value =
            listOf(snapshot(1, dev.anydm.engine.core.DownloadStatus.DOWNLOADING), snapshot(2, dev.anydm.engine.core.DownloadStatus.QUEUED))
        show(width = 900.dp, height = 640.dp)
        tag("row-1").performClick()
        tag("link-input").performTextInput("hello\nworld\nthird\nfourth")
        tag("link-input-add").performClick()
        rule.waitForIdle()
        tag("link-input-error").assertExists()
        val list = tag("list").getUnclippedBoundsInRoot()
        assertTrue(list.bottom - list.top >= 70.dp, "list only ${list.bottom - list.top}")
        val input = tag("link-input").getUnclippedBoundsInRoot()
        // Four lines typed, two shown.
        assertTrue(input.bottom - input.top < 80.dp, "input ${input.bottom - input.top}")
    }

    @Test
    fun `an error goes when the text is changed`() {
        show()
        tag("link-input").performTextInput("hello")
        tag("link-input-add").performClick()
        rule.waitForIdle()
        tag("link-input-error").assertExists()
        tag("link-input").performTextInput("!")
        rule.waitForIdle()
        tag("link-input-error").assertDoesNotExist()
    }
}
