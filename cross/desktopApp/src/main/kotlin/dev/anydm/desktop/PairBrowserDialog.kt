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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anydm.engine.os.PairedBrowser
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What Copy put on the clipboard, so it can be taken off again when the code is gone. Only what this dialog copied, and only
 * while the clipboard still holds it ([FileActions.clearCopied]).
 */
private class CopiedCode(
    private val files: FileActions,
) {
    private var text: String? = null

    /** Why the copy failed, or null. */
    fun copy(code: String): String? = files.copy(code).also { if (it == null) text = code }

    fun clear() {
        text?.let(files::clearCopied)
        text = null
    }
}

/**
 * "Browsers": pair a browser (a code to type into the extension, with a Copy button and a countdown) and the list of paired
 * browsers with Revoke. It holds the window's shortcuts back while shown and clears the code when it goes ([onClose] runs
 * after the model has): the code leaves the screen, the engine and, as far as this app can, the clipboard. A clipboard
 * history tool may have kept a copy of its own.
 */
@Composable
fun PairBrowserDialog(
    model: PairBrowserModel,
    files: FileActions,
    keys: WindowKeys,
    onClose: () -> Unit,
    clock: () -> Instant = { Clock.System.now() },
) {
    BlockWindowKeys(keys)
    val state by model.state.collectAsState()
    val colors = Plexo.colors
    val focus = remember { FocusRequester() }
    val copied = remember(files) { CopiedCode(files) }
    val close = {
        model.close()
        copied.clear()
        onClose()
    }
    LaunchedEffect(model) { model.load() }
    // Whatever way the dialog goes, no code stays behind.
    DisposableEffect(model, copied) {
        onDispose {
            model.close()
            copied.clear()
        }
    }
    // The code is off the screen (used, burned, run out, replaced or lost): what Copy put on the clipboard goes too.
    LaunchedEffect(state.code == null) { if (state.code == null) copied.clear() }
    LaunchedEffect(state.confirm) { if (state.confirm == null) runCatching { focus.requestFocus() } }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .focusRequester(focus)
            .focusTarget()
            .onPreviewKeyEvent {
                if (state.confirm == null && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                    close()
                    true
                } else {
                    false
                }
            }.pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(520.dp)
                .heightIn(max = 560.dp)
                .clip(shape)
                .background(colors.bgSecondary)
                .border(0.5.dp, colors.border, shape)
                .semantics { contentDescription = "Browsers dialog" },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("Browsers", Plexo.type.title, Modifier.weight(1f))
                PButton("Done", close)
            }
            HeaderRule()
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PairSection(state, model, copied)
                state.notice?.let {
                    Txt(
                        it,
                        Plexo.type.body,
                        Modifier.semantics { contentDescription = "Pairing note" },
                        color = colors.accent,
                    )
                }
                state.error?.let {
                    Txt(
                        it,
                        Plexo.type.hint,
                        Modifier.semantics { contentDescription = "Pairing problem" },
                        color = colors.danger,
                        maxLines = 4,
                    )
                }
                HeaderRule()
                BrowsersSection(state, model, clock)
            }
        }
        state.confirm?.let { row ->
            ConfirmDialog(
                title = "Revoke ${row.label}?",
                body = "That browser can no longer send downloads to anydm. It can be paired again with a new code.",
                confirmText = "Revoke",
                onConfirm = { model.confirmRevoke() },
                onCancel = model::dismissRevoke,
                destructive = true,
            )
        }
    }
}

@Composable
private fun PairSection(
    state: PairBrowserState,
    model: PairBrowserModel,
    copied: CopiedCode,
) {
    val colors = Plexo.colors
    var copyResult by remember(state.code?.grouped) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Txt("PAIR A BROWSER", Plexo.type.groupLabel, color = colors.textTertiary)
        Txt(
            "Open the anydm extension, choose Pair, type this code.",
            Plexo.type.hint,
            color = colors.textSecondary,
            maxLines = 2,
        )
        val code = state.code
        if (code != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(
                    code.grouped,
                    Plexo.type.speed.copy(fontSize = 26.sp, letterSpacing = 2.sp),
                    Modifier.semantics { contentDescription = "Pairing code ${code.grouped}" },
                )
                PButton("Copy", { copyResult = copied.copy(code.grouped)?.let { "Couldn't copy the code" } ?: "Copied" })
            }
            copyResult?.let {
                Txt(it, Plexo.type.hint, Modifier.semantics { contentDescription = "Copy result" }, color = colors.textSecondary)
            }
            Txt(
                "Expires in ${formatCountdown(code.secondsLeft)}",
                Plexo.type.detail,
                Modifier.semantics { contentDescription = "Code countdown" },
                color = colors.textTertiary,
            )
            if (code.throttled) Txt("Too many wrong tries, wait a minute", Plexo.type.hint, color = colors.danger)
        }
        when (state.ended) {
            CodeEnd.BURNED -> Txt("Code used up, generate a new one", Plexo.type.hint, color = colors.danger)
            CodeEnd.EXPIRED -> Txt("Code expired", Plexo.type.hint, color = colors.danger)
            null -> Unit
        }
        PButton(
            if (state.generating) {
                "Generating…"
            } else if (code != null || state.ended != null) {
                "New code"
            } else {
                "Generate code"
            },
            { model.generate() },
            kind = ButtonKind.PRIMARY,
            enabled = !state.generating,
            modifier = Modifier.semantics { contentDescription = "Generate code" },
        )
    }
}

@Composable
private fun BrowsersSection(
    state: PairBrowserState,
    model: PairBrowserModel,
    clock: () -> Instant,
) {
    val colors = Plexo.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt("PAIRED BROWSERS", Plexo.type.groupLabel, color = colors.textTertiary)
        when (val list = state.browsers) {
            BrowserList.Loading -> {
                Txt("Reading the paired browsers…", Plexo.type.hint, color = colors.textSecondary)
            }

            BrowserList.Unavailable -> {
                Txt("The paired browsers can't be read right now.", Plexo.type.hint, color = colors.danger, maxLines = 2)
                PButton("Retry", { model.load() })
            }

            is BrowserList.Listed -> {
                if (list.browsers.isEmpty()) {
                    Txt("No browser is paired.", Plexo.type.hint, color = colors.textSecondary)
                }
                val now = clock()
                for (row in list.browsers) BrowserRow(row, now, enabled = !state.revoking) { model.askRevoke(row.id) }
            }
        }
    }
}

@Composable
private fun BrowserRow(
    row: PairedBrowser,
    now: Instant,
    enabled: Boolean,
    onRevoke: () -> Unit,
) {
    val colors = Plexo.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(row.label, Plexo.type.rowName)
            Txt(
                "Paired ${formatAgo(now, row.createdAt)}, last used ${formatAgo(now, row.lastUsed)}",
                Plexo.type.detail,
                color = colors.textTertiary,
            )
        }
        PButton(
            "Revoke",
            onRevoke,
            kind = ButtonKind.DESTRUCTIVE,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "Revoke ${row.label}" },
        )
    }
}
