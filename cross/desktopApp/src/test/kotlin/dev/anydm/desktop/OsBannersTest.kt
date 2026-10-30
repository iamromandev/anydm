package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.net.AfterQueueAction
import dev.anydm.engine.net.AfterQueueState
import dev.anydm.engine.os.ClipboardLink
import dev.anydm.engine.os.HotkeyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class OsBannersTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private lateinit var model: AppModel

    @AfterTest
    fun close() = scope.cancel()

    private fun show() {
        model =
            AppModel(
                source,
                object : FirstRun {
                    override fun isDone() = true

                    override fun markDone() = Unit
                },
                scope,
                FakeFiles(),
                io = Dispatchers.Unconfined,
                window = WindowModel(MemoryPrefs(), trayAvailable = true),
            )
        model.start()
        rule.setContent { AnydmTheme(dark = false) { Shell(model) } }
        rule.waitForIdle()
    }

    @Test
    fun `a hotkey that needs permission shows a notice that explains it, and can be dismissed`() {
        source.osSettings.value = source.osSettings.value.copy(globalHotkey = true)
        source.hotkeyStatus.value = HotkeyStatus.NeedsPermission
        show()
        rule.onNodeWithTag("banner-hotkey").assertExists()
        rule.onNodeWithTag("banner-hotkey-text").assertTextContains("Accessibility", substring = true)
        rule.onNodeWithTag("banner-hotkey-dismiss").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("banner-hotkey").assertDoesNotExist()
    }

    @Test
    fun `a hotkey that cannot work on this system says why`() {
        source.osSettings.value = source.osSettings.value.copy(globalHotkey = true)
        source.hotkeyStatus.value = HotkeyStatus.Unsupported("Wayland does not allow it")
        show()
        rule.onNodeWithTag("banner-hotkey-text").assertTextContains("Wayland", substring = true)
    }

    @Test
    fun `no notice while the hotkey is off or working`() {
        source.hotkeyStatus.value = HotkeyStatus.NeedsPermission
        show()
        rule.onNodeWithTag("banner-hotkey").assertDoesNotExist()
        source.osSettings.value = source.osSettings.value.copy(globalHotkey = true)
        source.hotkeyStatus.value = HotkeyStatus.Active
        rule.waitForIdle()
        rule.onNodeWithTag("banner-hotkey").assertDoesNotExist()
    }

    @Test
    fun `a copied link is offered and adding it opens the review`() {
        show()
        rule.onNodeWithTag("clipboard-offer").assertDoesNotExist()
        source.clipboardLinks.tryEmit(ClipboardLink("https://example.com/a.zip", DownloadKind.DIRECT))
        rule.waitForIdle()
        rule.onNodeWithTag("clipboard-text").assertTextContains("https://example.com/a.zip", substring = true)
        rule.onNodeWithTag("clipboard-add").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("clipboard-offer").assertDoesNotExist()
        assertNotNull(model.state.value.adding)
    }

    @Test
    fun `a copied link can be dismissed`() {
        show()
        source.clipboardLinks.tryEmit(ClipboardLink("https://example.com/a.zip", DownloadKind.DIRECT))
        rule.waitForIdle()
        rule.onNodeWithTag("clipboard-dismiss").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("clipboard-offer").assertDoesNotExist()
        assertEquals(null, model.state.value.adding)
    }

    @Test
    fun `the status bar shows the indicators`() {
        source.downloads.value = listOf(snapshot(1, DOWNLOADING))
        source.afterQueue.value = AfterQueueState(AfterQueueAction.SLEEP, null)
        source.bridge.value = BridgeStatus.Listening(18400)
        show()
        rule.onNodeWithTag("status-awake").assertTextContains("Keeping awake")
        rule.onNodeWithTag("status-after-queue").assertTextContains("After the queue: sleep")
        rule.onNodeWithTag("status-bridge").assertTextContains("port 18400", substring = true)
    }

    @Test
    fun `the after-queue indicator goes when the action is disarmed`() {
        source.afterQueue.value = AfterQueueState(AfterQueueAction.SLEEP, null)
        show()
        rule.onNodeWithTag("status-after-queue").assertExists()
        source.afterQueue.value = AfterQueueState.Idle
        rule.waitForIdle()
        rule.onNodeWithTag("status-after-queue").assertDoesNotExist()
    }

    @Test
    fun `keep-awake and after-queue indicators go when they do not apply`() {
        source.keepAwake.value = false
        source.downloads.value = listOf(snapshot(1, DOWNLOADING))
        show()
        rule.onNodeWithTag("status-awake").assertDoesNotExist()
        rule.onNodeWithTag("status-after-queue").assertDoesNotExist()
    }
}
