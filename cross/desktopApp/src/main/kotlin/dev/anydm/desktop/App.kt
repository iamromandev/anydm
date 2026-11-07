package dev.anydm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.unit.dp
import dev.anydm.engine.EngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A request to open the Limits dialog on [page] (a network key, null for All downloads). */
private data class LimitsRequest(
    val id: Long,
    val page: String?,
)

/** Builds the dialog's model for [request] and takes the footer's Slow mode again once it closes (a save may have changed it). */
@Composable
private fun LimitsHost(
    source: EngineSource,
    request: LimitsRequest,
    unit: SpeedUnit,
    footer: FooterModel,
    networks: NetworksModel,
    keys: WindowKeys,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val model =
        remember(request.id) {
            val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            own to LimitsModel(source, unit, own, request.page)
        }
    DisposableEffect(model) { onDispose { model.first.cancel() } }
    val rows by networks.state.collectAsState()
    LimitsDialog(
        model.second,
        rows.rows,
        keys,
        onClose = {
            scope.launch { footer.refresh() }
            onClose()
        },
    )
}

/** Builds the Network Doctor's model for [request]; nothing is checked until its button is pressed. */
@Composable
private fun DoctorHost(
    source: EngineSource,
    request: Long,
    looks: StateFlow<NetworkLooks>,
    copy: (String) -> String?,
    keys: WindowKeys,
    onClose: () -> Unit,
) {
    val model =
        remember(request) {
            val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            own to DoctorModel(source, looks, own, copy)
        }
    DisposableEffect(model) { onDispose { model.first.cancel() } }
    DoctorDialog(model.second, keys, onClose)
}

/** Builds the pairing dialog's model for [request]; it is cleared and dropped when the dialog goes. */
@Composable
private fun PairHost(
    source: EngineSource,
    request: Long,
    files: FileActions,
    keys: WindowKeys,
    onClose: () -> Unit,
) {
    val model =
        remember(request) {
            val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            own to PairBrowserModel(source, own)
        }
    DisposableEffect(model) {
        onDispose {
            // The model's own close, revoke and cancel-pairing work is not stopped by the scope going (see PairBrowserModel).
            model.second.close()
            model.first.cancel()
        }
    }
    PairBrowserDialog(model.second, files, keys, onClose)
}

/** What the window's screens share: the list, footer and networks models, the networks' looks, and the landing page's form. */
private class AppModels(
    val scope: CoroutineScope,
    val home: HomeModel,
    val footer: FooterModel,
    val looks: NetworkLooksModel,
    val editor: NetworkEditor,
    val networks: NetworksModel,
    val landing: LandingSession,
    val binding: BindingNotice,
)

/**
 * The window's content: the landing page, the downloads list or a download's page in the theme the user chose, or what went
 * wrong when the engine could not start. Opening a row goes to its page ([navigator]), and the page's back control (or Esc)
 * returns one step towards the landing page. What [opens] hands over (links and `.torrent` paths from a paste, a drop, launch
 * arguments, File > Add) and the header button go to the landing page, its field filled with the first link; [visuals] keeps the names and colors of
 * networks and [onOpenLimits] is where a network's chevron and the "Speed & data limits" link lead.
 */
@Composable
fun App(
    source: EngineSource,
    prefs: ViewPrefs,
    keys: WindowKeys = WindowKeys(),
    files: FileActions = remember { DesktopFileActions() },
    navigator: Navigator = remember { Navigator() },
    opens: PendingOpens? = null,
    pickers: Pickers = remember { DesktopPickers() },
    visuals: NetworkVisuals = remember { NetworkVisuals(null) },
    onOpenLimits: (String?) -> Unit = {},
    /** What the update button drives; none (an app without a version), and there is no button. */
    updates: UpdateControls? = null,
    /** Puts text on the system clipboard (Copy report); answers why it could not, else null. */
    copyText: (String) -> String? = ::copyToSystemClipboard,
) {
    val uiScope = rememberCoroutineScope()
    val updateSession = remember(updates, files) { updates?.let { UpdateSession(it, files, uiScope) } }
    // The results of manual checks come once, whatever screen is up.
    if (updateSession != null) LaunchedEffect(updateSession) { updateSession.collectEvents() }
    // The Limits dialog is open for this request (the page it opens on), if any; a new request is a new dialog.
    var limitsRequest by remember { mutableStateOf<LimitsRequest?>(null) }
    var requests by remember { mutableStateOf(0L) }
    // The Browsers (pairing) dialog is open; a new opening is a new model, so no code survives a close.
    var pairRequest by remember { mutableStateOf<Long?>(null) }
    // The Network Doctor is open for this request; a new opening is a new model, so an old result is never shown as new.
    var doctorRequest by remember { mutableStateOf<Long?>(null) }
    val doctor by rememberUpdatedState<() -> Unit> { doctorRequest = ++requests }
    val limits by rememberUpdatedState<(String?) -> Unit> { page ->
        onOpenLimits(page)
        limitsRequest = LimitsRequest(++requests, page)
    }
    val models =
        remember(source, visuals) {
            // The models read the engine's flows: their own scope, ended with the window.
            val own = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val looks = NetworkLooksModel(source, visuals, own)
            val editor = NetworkEditor(visuals, looks.looks)
            AppModels(
                own,
                HomeModel(source, own),
                FooterModel(source, prefs, own),
                looks,
                editor,
                NetworksModel(source, looks.looks, own, { doctor() }) { limits(it) },
                LandingSession(own) { prefill, scope -> NewDownloadModel(source, looks.looks, scope, prefill = prefill) },
                BindingNotice(source, own),
            )
        }
    DisposableEffect(models) { onDispose { models.scope.cancel() } }
    val footer by models.footer.state.collectAsState()
    val engine by source.state.collectAsState()
    val failed = engine as? EngineState.Failed
    val bindingShown by models.binding.visible.collectAsState()
    // Every way in with a link: the landing page, its field filled.
    val openLink: (List<String>) -> Unit =
        remember(models, navigator) {
            { inputs ->
                models.landing.open(inputs)
                navigator.landing()
            }
        }
    // Opens are taken only while the landing page can be shown; with the engine failed they wait.
    if (opens != null && failed == null) {
        LaunchedEffect(opens, models, openLink) { for (request in opens.requests) openLink(request) }
    }
    ReleaseKeysOnBlur(keys)
    val looks by models.looks.looks.collectAsState()
    PlexoTheme(footer.theme) {
        CompositionLocalProvider(LocalNetworkLooks provides looks) {
            Box(Modifier.fillMaxSize().background(Plexo.colors.bg)) {
                if (failed != null) {
                    EngineFailed(failed.error.message ?: failed.error::class.simpleName ?: "unknown error")
                } else {
                    val route by navigator.route.collectAsState()
                    when (val page = route) {
                        Route.Landing -> {
                            LandingScreen(
                                models.landing,
                                models.home,
                                source,
                                pickers,
                                models.editor,
                                keys,
                                navigator::downloads,
                                unit = footer.unit,
                                onLimits = { models.networks.openLimits(null) },
                            )
                        }

                        Route.Downloads -> {
                            HomeScreen(
                                source,
                                models.home,
                                models.footer,
                                openLink,
                                navigator::open,
                                navigator::back,
                                keys,
                                networks = models.networks to models.editor,
                                onBrowsers = { pairRequest = ++requests },
                                updates = updateSession,
                                onLimits = models.networks::openLimits,
                            )
                        }

                        is Route.Detail -> {
                            DetailRoute(
                                source,
                                files,
                                models.footer,
                                page.id,
                                navigator::back,
                                keys,
                                naming = models.looks.looks to models.editor,
                                onNewDownload = openLink,
                                onLimits = models.networks::openLimits,
                            )
                        }
                    }
                    pairRequest?.let { id -> PairHost(source, id, files, keys) { pairRequest = null } }
                    limitsRequest?.let { request ->
                        LimitsHost(source, request, footer.unit, models.footer, models.networks, keys) { limitsRequest = null }
                    }
                    doctorRequest?.let { id -> DoctorHost(source, id, models.looks.looks, copyText, keys) { doctorRequest = null } }
                    updateSession?.let { UpdateDialogs(it, keys) }
                    // Last, so it is on top of the other dialogs and its focus is what the user sees.
                    if (bindingShown) BindingDialog(keys, models.binding::dismiss)
                }
            }
        }
    }
}

/** The engine could not start: say so, with its reason, instead of an empty list that looks like nothing is wrong. */
@Composable
fun EngineFailed(reason: String) {
    val colors = Plexo.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Txt("anydm can't run", Plexo.type.title)
            Txt("The download engine could not start.", Plexo.type.hint, color = colors.textSecondary)
            Txt(reason, Plexo.type.detail, color = colors.danger, maxLines = 6)
        }
    }
}
