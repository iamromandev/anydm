package dev.anydm.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import dev.anydm.engine.store.RuntimeSettings
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
internal class DiagnosticsScreenTest : PanelScreenTest(Section.DIAGNOSTICS) {
    @Test
    fun `it shows debug logging and says redaction is always on`() {
        source.runtime = RuntimeSettings(debugLogging = true)
        show()
        rule.onNodeWithTag("diag-debug").assertIsOn()
        tag("diag-redaction").assertTextContains("always removed", substring = true)
    }

    @Test
    fun `debug logging reaches the engine`() {
        show()
        click("diag-debug")
        rule.waitForIdle()
        assertEquals(listOf("setDebugLogging true"), source.calls)
        rule.onNodeWithTag("diag-debug").assertIsOn()
    }

    @Test
    fun `export writes the bundle where the picker said`() {
        pickers.diagnostics = Path.of("/home/ann/anydm-diagnostics.zip")
        show()
        click("diag-export")
        rule.waitForIdle()
        assertEquals(listOf("/home/ann/anydm-diagnostics.zip"), source.bundles)
        tag("diag-message").assertTextContains("Saved the diagnostics bundle to /home/ann/anydm-diagnostics.zip.")
    }

    @Test
    fun `cancelling the picker writes nothing`() {
        show()
        click("diag-export")
        rule.waitForIdle()
        assertEquals(emptyList(), source.bundles)
    }

    @Test
    fun `a failed export says why`() {
        pickers.diagnostics = Path.of("/x.zip")
        source.diagnosticsError = "disk full"
        show()
        click("diag-export")
        rule.waitForIdle()
        tag("diag-message").assertTextContains("disk full", substring = true)
    }
}
