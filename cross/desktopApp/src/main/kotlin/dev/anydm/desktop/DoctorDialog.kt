package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.anydm.engine.net.CheckResult

/**
 * Network Doctor: says why a network may not add speed. Nothing is sent until Run check is pressed, and the dialog says that
 * the checks contact outside servers. Escape and Close shut it ([onClose]); a click outside does not.
 */
@Composable
fun DoctorDialog(
    model: DoctorModel,
    keys: WindowKeys,
    onClose: () -> Unit,
) {
    BlockWindowKeys(keys)
    val state by model.state.collectAsState()
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .focusRequester(focus)
            .focusTarget()
            .semantics { testTag = "doctor-dialog" }
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    onClose()
                    true
                } else {
                    false
                }
            }.pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth(0.92f)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .pointerInput(Unit) { detectTapGestures { } }
                .semantics { contentDescription = "Network Doctor" }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Txt("Why isn't a network adding speed?", Plexo.type.menu)
            Txt(DOCTOR_NOTICE, Plexo.type.hint, color = colors.textSecondary, maxLines = 6)
            when (val load = state.load) {
                DoctorLoad.Idle -> {
                    Unit
                }

                DoctorLoad.Running -> {
                    Txt("Checking the networks…", Plexo.type.hint, color = colors.textSecondary)
                }

                is DoctorLoad.Failed -> {
                    Txt(load.message, Plexo.type.hint, color = colors.danger, maxLines = 3)
                }

                is DoctorLoad.Done -> {
                    if (!load.report.online) {
                        Txt("This computer has no internet by its default route.", Plexo.type.hint, color = colors.danger, maxLines = 2)
                    }
                    if (state.rows.isEmpty()) Txt("No network to check.", Plexo.type.hint, color = colors.textSecondary)
                }
            }
            if (state.rows.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (row in state.rows) DoctorRowView(row)
                }
            }
            state.copyError?.let { Txt(it, Plexo.type.hint, color = colors.danger, maxLines = 3) }
            if (state.canCopy) Txt(DOCTOR_COPY_NOTICE, Plexo.type.hint, color = colors.textSecondary, maxLines = 2)
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (state.copied) {
                    Txt("Copied", Plexo.type.hint, Modifier.align(Alignment.CenterVertically), color = colors.textSecondary)
                }
                PButton("Copy report", model::copy, Modifier.semantics { testTag = "doctor-copy" }, enabled = state.canCopy)
                PButton(
                    "Run check",
                    model::run,
                    Modifier.semantics { testTag = "doctor-run" },
                    enabled = !state.running,
                )
                PButton("Close", onClose, Modifier.semantics { testTag = "doctor-close" }, kind = ButtonKind.PRIMARY)
            }
        }
    }
}

@Composable
private fun DoctorRowView(row: DoctorRow) {
    val colors = Plexo.colors
    val shape = RoundedCornerShape(9.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(0.5.dp, colors.border, shape)
            .padding(12.dp)
            .semantics { testTag = "doctor-row-${row.key}" },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Txt(row.name, Plexo.type.rowName)
        Txt(row.answer, Plexo.type.body, color = if (row.good) colors.text else colors.danger, maxLines = 4)
        for (finding in row.findings) {
            val mark =
                when (finding.result) {
                    CheckResult.PASS -> "OK"
                    CheckResult.WARN -> "Warning"
                    CheckResult.FAIL -> "Failed"
                    CheckResult.SKIPPED -> "Not checked"
                }
            Txt("$mark: ${finding.text}", Plexo.type.detail, color = colors.textTertiary, maxLines = 3)
        }
        for (fix in row.fixes) Txt("Fix: $fix", Plexo.type.hint, color = colors.textSecondary, maxLines = 5)
    }
}
