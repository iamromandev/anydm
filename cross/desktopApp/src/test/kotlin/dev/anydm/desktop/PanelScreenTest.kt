package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import java.nio.file.Path
import kotlin.test.AfterTest

/** The file choosers, answering what a test set. */
internal class TestPickers : Pickers {
    var diagnostics: Path? = null
    var chosenFolder: Path? = null

    override fun torrentFile(): Path? = null

    override fun folder(): Path? = chosenFolder

    override fun keyFile(): Path? = null

    override fun saveDiagnosticsFile(name: String): Path? = diagnostics
}

/** The full app shell on the settings screen with a [FakeSource], for the tests of one network, OS or tool section. */
@OptIn(ExperimentalTestApi::class)
internal abstract class PanelScreenTest(
    private val section: Section,
) {
    @get:Rule
    val rule = createComposeRule()

    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    protected val source = FakeSource()
    protected lateinit var model: AppModel
    protected val prefs = MemoryPrefs()
    protected var tray = true

    protected val pickers = TestPickers()
    protected val links = FakeLinkHandlers()

    @AfterTest
    fun close() = scope.cancel()

    protected fun show() {
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
                window = WindowModel(prefs, tray),
                linkHandlers = links,
            )
        model.start()
        model.navigate(Route.Settings)
        rule.setContent { AnydmTheme(dark = false) { Shell(model, pickers) } }
        rule.waitForIdle()
        click("settings-section-${section.name.lowercase()}")
        rule.waitForIdle()
    }

    protected fun tag(name: String): SemanticsNodeInteraction = rule.onNodeWithTag(name, useUnmergedTree = true)

    // The section's content scrolls; the section list doesn't.
    protected fun shown(name: String): SemanticsNodeInteraction =
        rule.onNodeWithTag(name).also { node -> runCatching { node.performScrollTo() } }

    protected fun click(name: String) = shown(name).performClick()

    protected fun type(
        name: String,
        text: String,
    ) = shown(name).apply {
        performTextClearance()
        performTextInput(text)
    }
}
