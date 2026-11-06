package dev.anydm.desktop

import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.core.DownloadStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the update button drives; [Updater] in the app ([UpdaterControls]), a fake in tests. */
interface UpdateControls {
    val state: StateFlow<UpdateState>
    val autoUpdate: StateFlow<Boolean>
    val events: Flow<UpdateEvent>

    fun check()

    fun download()

    fun setAutoUpdate(on: Boolean)

    suspend fun restart(confirmed: Boolean): RestartResult
}

/** [UpdateControls] on an [Updater]; call it from the thread the updater's scope runs on. */
class UpdaterControls(
    private val updater: Updater,
) : UpdateControls {
    override val state: StateFlow<UpdateState> get() = updater.state
    override val autoUpdate: StateFlow<Boolean> get() = updater.autoUpdate
    override val events: Flow<UpdateEvent> get() = updater.events

    override fun check() = updater.checkForUpdates()

    override fun download() = updater.download()

    override fun setAutoUpdate(on: Boolean) = updater.setAutoUpdate(on)

    override suspend fun restart(confirmed: Boolean) = updater.restartToUpdate(confirmed)
}

/** A one-shot dialog about updates. */
sealed interface UpdateNotice {
    data object UpToDate : UpdateNotice

    data object CheckFailed : UpdateNotice

    /** Restarting would pause [activeDownloads] downloads: ask first. */
    data class ConfirmRestart(
        val activeDownloads: Int,
    ) : UpdateNotice

    /** The release page could not be opened in the browser; [message] says why. */
    data class OpenFailed(
        val message: String,
    ) : UpdateNotice
}

/**
 * The update button's behavior, held at the app level so a dialog outlives the screen that asked for it: the [controls], the
 * dialog now open ([notice]) and the commands the button and the dialogs give. Everything runs on [scope], the UI's single thread
 * (the updater is not thread-safe). Opening the browser is the one thing that runs on [io] instead; it never touches the updater.
 */
class UpdateSession(
    val controls: UpdateControls,
    private val files: FileActions,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableNotice = MutableStateFlow<UpdateNotice?>(null)
    val notice: StateFlow<UpdateNotice?> = mutableNotice.asStateFlow()

    /** Turns the results of checks the user asked for into dialogs; runs until cancelled. Collect it once. */
    suspend fun collectEvents() {
        controls.events.collect {
            mutableNotice.value =
                when (it) {
                    UpdateEvent.UpToDate -> UpdateNotice.UpToDate
                    UpdateEvent.CheckFailed -> UpdateNotice.CheckFailed
                }
        }
    }

    /** "Restart to Update": goes ahead, or asks first when downloads are running. */
    fun restart() {
        scope.launch {
            val result = controls.restart(false)
            if (result is RestartResult.NeedsConfirmation) mutableNotice.value = UpdateNotice.ConfirmRestart(result.activeDownloads)
            // A failed launch is the updater's error state: the button shows it.
        }
    }

    /** The user agreed to pause the downloads. */
    fun confirmRestart() {
        mutableNotice.value = null
        scope.launch { controls.restart(true) }
    }

    fun dismiss() {
        mutableNotice.value = null
    }

    /** Opens [url] in the browser, off the UI thread; if that fails, the reason is shown as a notice. */
    fun openWebsite(url: String) {
        scope.launch {
            val problem = withContext(io) { files.openUrl(url) }
            if (problem != null) mutableNotice.value = UpdateNotice.OpenFailed(problem)
        }
    }
}

/** How many of [downloads] are transferring now: the ones a restart would pause. */
internal fun runningDownloads(downloads: List<DownloadSnapshot>): Int =
    downloads.count { it.download.status == DownloadStatus.DOWNLOADING || it.download.status == DownloadStatus.MUXING }
