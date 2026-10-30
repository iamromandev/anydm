package dev.anydm.desktop

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** The diagnostics section (#609). */
@Composable
internal fun DiagnosticsPanel(
    model: DiagnosticsModel,
    pickers: Pickers,
) {
    val state by model.state.collectAsState()
    if (!state.loaded) {
        Text(state.message ?: "Reading the settings…", modifier = Modifier.testTag("diag-unavailable"))
        return
    }
    Toggle("Write detailed (debug) lines to the log", state.debugLogging, "diag-debug", model::setDebugLogging)
    Text("It takes effect at once. Detailed logs are bigger, so turn it off when you are done.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text(
        "Passwords, tokens, cookies and secrets in links are always removed from the log and from the bundle; that can't be switched off.",
        modifier = Modifier.testTag("diag-redaction"),
    )
    Text("The bundle is a zip of the logs, the versions of anydm and its tools, and a few settings.")
    OutlinedButton(
        onClick = { pickers.saveDiagnosticsFile("anydm-diagnostics.zip")?.let { model.export(it.toString()) } },
        enabled = !state.busy,
        modifier = Modifier.testTag("diag-export"),
    ) { Text("Export diagnostics…") }
    state.message?.let { Text(it, modifier = Modifier.testTag("diag-message")) }
}
