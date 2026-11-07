package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The page the window opens on: one field for a link, what it turned out to be, and Download. A start moves on to the downloads
 * list ([onDownloads]) and leaves a blank form (the clipboard is read only when the window opens). The paste shortcut fills the field from the clipboard when the field does not
 * have the focus (when it does, the field pastes by itself); a dropped `.torrent` fills it too.
 */
@Composable
fun LandingScreen(
    session: LandingSession,
    home: HomeModel,
    source: EngineSource,
    pickers: Pickers,
    editor: NetworkEditor,
    keys: WindowKeys,
    onDownloads: () -> Unit,
    unit: SpeedUnit = SpeedUnit.BYTES,
    onLimits: () -> Unit = {},
    /** The networks menu under the top bar's pill; none, and the pill does nothing. */
    networks: NetworksModel? = null,
) {
    val colors = Plexo.colors
    val scope = rememberCoroutineScope()
    val entry by session.model.collectAsState()
    val homeState by home.state.collectAsState()
    val count = homeState.groups.sumOf { it.rows.size }
    var fieldFocused by remember { mutableStateOf(false) }
    val focused by rememberUpdatedState(fieldFocused)
    DisposableEffect(keys, source, session) {
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            val command = if (IsMac) event.isMetaPressed else event.isCtrlPressed
            // In the field the paste is the field's own: filling it here as well would paste twice.
            if (event.type != KeyEventType.KeyDown || !command || event.key != Key.V || focused) return@handler false
            scope.launch {
                val link = clipboardLink(source.clipboard)
                if (link != null) session.open(listOf(link))
            }
            true
        }
        keys.handler = handler
        onDispose { if (keys.handler === handler) keys.handler = null }
    }
    Column(Modifier.fillMaxSize().background(colors.bg).dropTorrents(session::dropped)) {
        LandingBar(
            homeState.pill,
            unit,
            onNetworks = { networks?.toggle() },
            menu = { networks?.let { NetworksMenu(it, editor, unit, keys) } },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // One form at a time on show: a new model after a start shows a blank field.
            androidx.compose.runtime.key(entry.id) {
                val model = entry.model
                val state by model.state.collectAsState()
                // Also true when the page is shown again after it was left mid-start: the start finished meanwhile.
                LaunchedEffect(state.done) {
                    if (state.done) {
                        session.reset()
                        onDownloads()
                    }
                }
                Column(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Txt("What do you want to get?", Plexo.type.title)
                    NewDownloadForm(
                        model,
                        state,
                        pickers,
                        editor,
                        keys,
                        onFieldFocus = { fieldFocused = it },
                        unit = unit,
                        onLimits = onLimits,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PButton("Downloads ($count)", onDownloads, kind = ButtonKind.GHOST)
                        Box(Modifier.weight(1f))
                        PButton(state.startLabel, model::start, kind = ButtonKind.PRIMARY, enabled = state.canStart)
                    }
                }
            }
        }
    }
}

/** The landing page's top bar: the app's name on the left, the networks pill and its menu on the right. */
@Composable
private fun LandingBar(
    pill: NetworksPill,
    unit: SpeedUnit,
    onNetworks: () -> Unit,
    menu: @Composable () -> Unit,
) {
    TopBar(
        start = { Txt("anydm", Plexo.type.menu) },
        end = { NetworksControl(pill, unit, onNetworks, menu) },
    )
}
