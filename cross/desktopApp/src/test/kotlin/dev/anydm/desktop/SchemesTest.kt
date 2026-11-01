package dev.anydm.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.anydm.engine.EngineState
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.DownloadStatus.COMPLETED
import dev.anydm.engine.core.DownloadStatus.DOWNLOADING
import dev.anydm.engine.core.MirrorId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The restyled surfaces render in both schemes, with their tags in place. */
class SchemesTest {
    @get:Rule
    val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val source = FakeSource()
    private val firstRun =
        object : FirstRun {
            override fun isDone() = true

            override fun markDone() = Unit
        }

    @AfterTest
    fun close() = scope.cancel()

    private var dark by mutableStateOf(false)

    // Read inside the composition, so a check can tell which scheme is really in effect.
    private var seenDark = false
    private var seenSurface = Color.Unspecified

    private fun show(
        prepare: (AppModel) -> Unit = {},
        content: @androidx.compose.runtime.Composable (AppModel) -> Unit = { Shell(it) },
    ) {
        source.downloads.value =
            listOf(snapshot(1, DOWNLOADING, name = "a"), snapshot(2, COMPLETED, name = "b", kind = DownloadKind.TORRENT))
        val model = AppModel(source, firstRun, scope, FakeFiles())
        model.start()
        prepare(model)
        rule.setContent {
            AnydmTheme(dark = dark) {
                seenDark = LocalDark.current
                seenSurface = MaterialTheme.colorScheme.surface
                content(model)
            }
        }
    }

    /** Runs [check] in the light scheme, then switches the same composition to the dark one and runs it again. */
    private fun both(check: () -> Unit) {
        dark = false
        rule.waitForIdle()
        assertFalse(seenDark)
        val lightSurface = seenSurface
        check()
        dark = true
        rule.waitForIdle()
        assertTrue(seenDark)
        assertNotEquals(lightSurface, seenSurface)
        check()
    }

    @Test
    fun `the sidebar and status bar render in both schemes`() {
        show()
        both {
            listOf("nav-all", "nav-protocol-http", "nav-protocol-torrent", "nav-settings", "status-engine", "status-speed").forEach {
                rule.onNodeWithTag(it).assertIsDisplayed()
            }
            rule.onNodeWithTag("count-protocol-torrent", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun `settings render in both schemes`() {
        show(prepare = { it.navigate(Route.Settings) })
        both {
            rule.onNodeWithTag("settings").assertIsDisplayed()
            rule.onNodeWithTag("settings-section-files").assertIsDisplayed()
        }
    }

    @Test
    fun `the add dialog renders in both schemes`() {
        show(prepare = { it.openAdd() })
        both {
            rule.onNodeWithTag("add-dialog").assertIsDisplayed()
            rule.onNodeWithTag("add-text").assertIsDisplayed()
        }
    }

    @Test
    fun `the remove dialog renders in both schemes`() {
        show(content = { Dialogs(Dialog.ConfirmRemove(listOf(DownloadId(1))), it) })
        both {
            rule.onNodeWithTag("remove-dialog").assertIsDisplayed()
            rule.onNodeWithTag("remove-trash").assertIsDisplayed()
        }
    }

    @Test
    fun `the relink dialog renders in both schemes`() {
        show(content = { Dialogs(Dialog.Relink(DownloadId(1), MirrorId(5), "https://a.example/x"), it) })
        both {
            rule.onNodeWithTag("relink-dialog").assertIsDisplayed()
        }
    }

    @Test
    fun `banners render in both schemes`() {
        show()
        both {
            source.state.value = EngineState.Failed(IllegalStateException("db gone"))
            rule.waitForIdle()
            rule.onNodeWithTag("banner-engine").assertIsDisplayed()
            rule.onNodeWithTag("banner-dismiss").assertIsDisplayed()
        }
    }

    @Test
    fun `the empty and no-match placeholders render in both schemes`() {
        show(prepare = { it.setProtocol(Protocol.TOOL) })
        both {
            rule.onNodeWithTag("no-match").assertIsDisplayed()
        }
    }
}
