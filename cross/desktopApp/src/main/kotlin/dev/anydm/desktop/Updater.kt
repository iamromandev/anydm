package dev.anydm.desktop

import dev.anydm.engine.http.HttpIoException
import dev.anydm.engine.http.HttpRequest
import dev.anydm.engine.http.HttpTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** What the update button shows. */
sealed interface UpdateState {
    data object Idle : UpdateState

    data object Checking : UpdateState

    /** A newer release with an installer for this system is out; [pageUrl] is its page on the website. */
    data class Available(
        val version: Version,
        val pageUrl: String,
    ) : UpdateState

    data class Downloading(
        val version: Version,
        /** 0 to 99; it is 100 only as [Ready]. */
        val percent: Int,
    ) : UpdateState

    /** [file] is downloaded and its SHA-256 matched the release's sums. */
    data class Ready(
        val version: Version,
        val file: Path,
    ) : UpdateState

    /** [version] could not be installed ([reason] says why, for people); [pageUrl] is where to get it by hand. Null version: none known. */
    data class Error(
        val version: Version?,
        val reason: String,
        val pageUrl: String?,
    ) : UpdateState
}

/** What a check the user asked for ("Check for Updates…") has to say; checks by the timer say nothing. */
sealed interface UpdateEvent {
    data object UpToDate : UpdateEvent

    data object CheckFailed : UpdateEvent
}

/** The answer to "Restart to update". */
sealed interface RestartResult {
    /** The installer was handed to the system and the app was asked to quit. */
    data object Started : RestartResult

    /** No verified installer is ready. */
    data object NotReady : RestartResult

    /** [activeDownloads] downloads would pause: ask the user, then call again with `confirmed = true`. */
    data class NeedsConfirmation(
        val activeDownloads: Int,
    ) : RestartResult

    /** The installer was not opened ([reason]); the state is [UpdateState.Error] and the app keeps running. */
    data class Failed(
        val reason: String,
    ) : RestartResult
}

private const val MAX_DOWNLOAD_BYTES = 1L shl 30
private const val BUFFER_BYTES = 16 * 1024
private val CHECK_INTERVAL = 4.hours

/**
 * Finds a newer anydm release, downloads its installer, verifies it and hands it to the system.
 *
 * Checks at [start] and every [interval] after (on [scope]'s clock, so tests drive it with virtual time). A check that fails does
 * not retry before the next tick. Only checks the user asks for ([checkForUpdates]) report an [UpdateEvent]. With automatic
 * updates on, a newer release is downloaded as soon as it is found; with them off, [download] starts it. A release whose
 * download or opening failed is not fetched again by a later check, only by [download]; a release a later check no longer lists
 * (withdrawn, or not newer) is taken off offer, and a check that fails changes nothing.
 *
 * The installer is only ever fetched from the anydm repository's GitHub release it was found on, over https, never more than
 * [maxDownloadBytes], into a new folder of its own (folders an earlier run left behind are swept at [start]). It reaches
 * [UpdateState.Ready] only when its SHA-256 equals the one the release's `SHA2-256SUMS` lists; any other outcome deletes it.
 * [restartToUpdate] checks the file once more before opening it, and only opens a file of the installer type of this system
 * ([installerExtension]).
 *
 * Authenticity is checksum-only, by decision: the `SHA2-256SUMS` and the installer come from the same release, so whoever
 * controls the release (a compromised account, token or CI job) can replace both, and the sums then match. They catch corruption,
 * truncation and damage by a CDN or a man in the middle, not a malicious release. An installer written by this program likely
 * carries no quarantine flag (macOS) or Mark-of-the-Web (Windows), so the system's own download checks do not run either: this
 * checksum is the only gate before the installer is opened. A follow-up, if wanted, is a detached Ed25519 signature over
 * `SHA2-256SUMS`: the owner generates the key pair, the private key is a GitHub secret of the release job, the public key is
 * embedded in the app.
 *
 * Not thread-safe: call it from the thread of [scope]'s dispatcher (the UI thread).
 */
class Updater(
    private val scope: CoroutineScope,
    private val current: Version,
    private val releases: AppReleaseSource,
    private val transport: HttpTransport,
    private val files: UpdateFiles,
    private val opener: FileActions,
    private val prefs: ViewPrefs,
    /** How many downloads are running now: restarting would pause them. */
    private val activeDownloads: () -> Int,
    /**
     * Asks the app to quit; called once the installer has been opened. It must really end the app (Main.kt's closes the window,
     * stops the engine and exits): if it returns or throws, the updater stays in its launching state, where it ignores checks and
     * restarts so nothing touches the installer the system is running.
     */
    private val quit: () -> Unit,
    /** The installer file type of this system (`.dmg`, `.msi`, `.deb`): nothing else is opened. */
    private val installerExtension: String,
    private val interval: Duration = CHECK_INTERVAL,
    private val maxDownloadBytes: Long = MAX_DOWNLOAD_BYTES,
    private val repository: String = AppReleases.REPOSITORY,
) {
    init {
        require(installerExtension.startsWith(".")) { "installerExtension must start with a dot: $installerExtension" }
    }

    private val mutableState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    private val mutableAutoUpdate = MutableStateFlow(prefs.autoUpdate)

    /** Whether a newer release is downloaded as soon as it is found. */
    val autoUpdate: StateFlow<Boolean> = mutableAutoUpdate.asStateFlow()

    private val channel = Channel<UpdateEvent>(Channel.BUFFERED)

    /** One-shot results of checks the user asked for. */
    val events: Flow<UpdateEvent> = channel.receiveAsFlow()

    private class Work(
        val release: AppRelease,
        val file: Path,
        val directory: Path,
    )

    private class Refused(
        val reason: String,
    ) : Exception(reason)

    private var schedule: Job? = null
    private var downloading: Job? = null
    private var checking = false
    private var launching = false

    // The release a download would fetch: set while an install is possible, null when there is none to try.
    private var pending: AppRelease? = null

    // The verified installer and its folder while the state is Ready.
    private var ready: Work? = null

    // The version whose download or opening failed: not tried again on its own.
    private var failedVersion: Version? = null

    /**
     * Checks now and then every [interval] until [stop]. A second call does nothing.
     *
     * The sweep at the start of the schedule may also remove the folder of an older, still Ready download of a second running
     * instance, if it is a day old: that instance then finds its installer missing at restart and fails safe (it is not opened).
     */
    fun start() {
        if (schedule?.isActive == true) return
        schedule =
            scope.launch {
                sweepOldDownloads()
                while (true) {
                    check(manual = false)
                    delay(interval)
                }
            }
    }

    private suspend fun sweepOldDownloads() {
        try {
            files.sweepOld()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Housekeeping only: an update does not wait for it.
        }
    }

    /** Ends the schedule and a download in progress (its file is deleted). A verified installer stays: it may be running. */
    fun stop() {
        schedule?.cancel()
        downloading?.cancel()
    }

    /** "Check for Updates…": checks now and reports an [UpdateEvent] unless an update was found. Ignored during a check or a download. */
    fun checkForUpdates() {
        if (!claimCheck()) return
        scope.launch { runCheck(manual = true) }
    }

    /** Starts downloading the update that is available, or tries again after a failed download. */
    fun download() {
        startDownload()
    }

    /** Keeps the choice in the preferences; turning it on while an update is available starts the download. */
    fun setAutoUpdate(on: Boolean) {
        prefs.autoUpdate = on
        mutableAutoUpdate.value = on
        if (on && mutableState.value is UpdateState.Available) startDownload()
    }

    /**
     * "Restart to update": opens the verified installer with the system and asks the app to quit so it can run. With downloads
     * active it first answers [RestartResult.NeedsConfirmation]; call again with [confirmed] once the user agreed. The file is
     * checked against its SHA-256 again just before it is opened; a file that changed is deleted and not opened.
     */
    suspend fun restartToUpdate(confirmed: Boolean = false): RestartResult {
        val work = ready ?: return RestartResult.NotReady
        if (mutableState.value !is UpdateState.Ready || launching) return RestartResult.NotReady
        val active = activeDownloads()
        if (active > 0 && !confirmed) return RestartResult.NeedsConfirmation(active)
        launching = true
        var started = false
        try {
            val fileName = work.file.fileName.toString()
            if (!fileName.endsWith(installerExtension, ignoreCase = true)) {
                return fail(work, "The installer is not a $installerExtension file, so it was not opened.")
            }
            val actual = files.sha256(work.file)
            if (actual == null || !actual.equals(work.release.asset.sha256, ignoreCase = true)) {
                return fail(work, "The installer is missing or changed since it was checked, so it was not opened.")
            }
            val problem = opener.open(work.file)
            if (problem != null) return fail(work, "The installer could not be opened: $problem", problem)
            // Set first: a quit that throws must not clear `launching` after the installer was opened.
            started = true
            quit()
            return RestartResult.Started
        } finally {
            // After a start it stays set: the app is quitting, and nothing may touch the installer the system is running.
            if (!started) launching = false
        }
    }

    private suspend fun fail(
        work: Work,
        reason: String,
        shown: String = reason,
    ): RestartResult {
        ready = null
        // Kept, so download() can fetch the installer again after a failed open.
        pending = work.release
        failedVersion = work.release.version
        files.deleteAll(work.directory)
        mutableState.value = UpdateState.Error(work.release.version, reason, work.release.pageUrl)
        return RestartResult.Failed(shown)
    }

    private fun claimCheck(): Boolean {
        if (checking || launching || mutableState.value is UpdateState.Downloading) return false
        checking = true
        return true
    }

    private suspend fun check(manual: Boolean) {
        if (claimCheck()) runCheck(manual)
    }

    private suspend fun runCheck(manual: Boolean) {
        val before = mutableState.value
        var download = false
        try {
            if (before is UpdateState.Idle || before is UpdateState.Error) mutableState.value = UpdateState.Checking
            val result =
                try {
                    releases.latest()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ReleaseCheck.Failed
                }
            // The answer may be old: an installer may be opening meanwhile, and a failed one may have moved the state on.
            val now = mutableState.value
            if (launching || now is UpdateState.Downloading) return
            download = apply(result, if (now == UpdateState.Checking) before else now, manual)
        } finally {
            checking = false
            if (mutableState.value == UpdateState.Checking) mutableState.value = before
        }
        // Only now that the check is over: a download does not start during one.
        if (download) startDownload()
    }

    // Whether the release found is to be downloaded right away.
    private suspend fun apply(
        result: ReleaseCheck,
        before: UpdateState,
        manual: Boolean,
    ): Boolean {
        when (result) {
            ReleaseCheck.Failed -> {
                // Offline or rate limited says nothing about what was on offer.
                mutableState.value = before
                if (manual) channel.trySend(UpdateEvent.CheckFailed)
            }

            ReleaseCheck.None -> {
                nothingNewer(manual)
            }

            is ReleaseCheck.Found -> {
                return if (result.release.version <= current) {
                    nothingNewer(manual)
                    false
                } else {
                    found(result.release, before)
                }
            }

            is ReleaseCheck.CannotInstall -> {
                if (result.version <= current) {
                    nothingNewer(manual)
                } else {
                    pending = null
                    mutableState.value = UpdateState.Error(result.version, result.reason, result.pageUrl)
                    discardReady()
                }
            }
        }
        return false
    }

    // A check that worked and lists nothing newer: whatever was on offer was withdrawn, and an old error or check is over.
    private suspend fun nothingNewer(manual: Boolean) {
        pending = null
        failedVersion = null
        mutableState.value = UpdateState.Idle
        discardReady()
        if (manual) channel.trySend(UpdateEvent.UpToDate)
    }

    private suspend fun found(
        release: AppRelease,
        before: UpdateState,
    ): Boolean {
        val same =
            (before as? UpdateState.Available)?.version == release.version || (before as? UpdateState.Ready)?.version == release.version
        val triedAndFailed = before is UpdateState.Error && failedVersion == release.version
        if (same || triedAndFailed) {
            // Nothing new: a failed download is not started again on its own, a verified one is not fetched twice.
            pending = release
            mutableState.value = before
            // Automatic updates switched on during this check: the offer found again is downloaded now (never a Ready one).
            return same && before is UpdateState.Available && mutableAutoUpdate.value
        }
        failedVersion = null
        pending = release
        mutableState.value = UpdateState.Available(release.version, release.pageUrl)
        discardReady()
        return mutableAutoUpdate.value
    }

    // The state is already set when this runs: the deletion suspends, and nobody may see an installer on offer that is going.
    private suspend fun discardReady() {
        val old = ready ?: return
        ready = null
        files.deleteAll(old.directory)
    }

    private fun startDownload() {
        if (checking) return
        val release = pending ?: return
        val now = mutableState.value
        if (now !is UpdateState.Available && now !is UpdateState.Error) return
        failedVersion = null
        mutableState.value = UpdateState.Downloading(release.version, 0)
        downloading = scope.launch { fetch(release) }
    }

    private suspend fun fetch(release: AppRelease) {
        val version = release.version
        var sink: UpdateSink? = null
        var folder: Path? = null
        try {
            val asset = release.asset
            if (!AppReleases.isPlainAssetName(asset.name) || asset.url != AppReleases.assetUrl(repository, release.tag, asset.name) ||
                !isGithubHttps(asset.url)
            ) {
                throw Refused("The installer is not on this release of anydm, so it was not downloaded.")
            }
            if (asset.size != null && asset.size > maxDownloadBytes) throw Refused("The installer is larger than anydm will download.")
            val response = transport.open(HttpRequest(asset.url))
            try {
                if (response.status != 200) throw Refused("The download failed (HTTP ${response.status}).")
                if (!isGithubHttps(
                        response.finalUrl,
                    )
                ) {
                    throw Refused("The download was sent somewhere that is not GitHub, so it was stopped.")
                }
                val announced = response.headers["Content-Length"]?.trim()?.toLongOrNull()
                if (announced != null && announced > maxDownloadBytes) throw Refused("The installer is larger than anydm will download.")
                val expected = announced ?: asset.size
                val made = files.createDirectory()
                folder = made
                val name = sanitizedInstallerName(asset.name)
                val target = files.create(made, name)
                sink = target
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_BYTES)
                var total = 0L
                var shown = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = response.body.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxDownloadBytes) throw Refused("The installer is larger than anydm will download.")
                    digest.update(buffer, 0, n)
                    target.write(buffer, n)
                    val percent = if (expected != null && expected > 0) (total * 100 / expected).toInt().coerceIn(0, 99) else 0
                    if (percent != shown) {
                        shown = percent
                        mutableState.value = UpdateState.Downloading(version, percent)
                    }
                }
                target.close()
                sink = null
                if (asset.size != null && total != asset.size) throw Refused("The download was incomplete.")
                if (!digest.digest().toHex().equals(asset.sha256, ignoreCase = true)) {
                    throw Refused("The download did not match its checksum, so it was deleted.")
                }
                ready = Work(release, made.resolve(name), made)
                folder = null
                pending = release
                mutableState.value = UpdateState.Ready(version, made.resolve(name))
            } finally {
                response.body.close()
            }
        } catch (e: CancellationException) {
            cleanUp(sink, folder)
            if (mutableState.value is UpdateState.Downloading) mutableState.value = UpdateState.Available(version, release.pageUrl)
            throw e
        } catch (e: Refused) {
            failed(release, sink, folder, e.reason)
        } catch (e: HttpIoException) {
            failed(release, sink, folder, "The download failed: ${e.message}")
        } catch (e: IOException) {
            failed(release, sink, folder, "The download could not be saved: ${e.message ?: e::class.simpleName}")
        } catch (e: Exception) {
            // Whatever else goes wrong: the state must not stay at Downloading, and the failure must not end the scope.
            failed(release, sink, folder, "The download failed unexpectedly: ${e.message ?: e::class.simpleName}")
        }
    }

    private suspend fun failed(
        release: AppRelease,
        sink: UpdateSink?,
        folder: Path?,
        reason: String,
    ) {
        cleanUp(sink, folder)
        failedVersion = release.version
        mutableState.value = UpdateState.Error(release.version, reason, release.pageUrl)
    }

    // Also while cancelled: a half-written or rejected file must not stay behind.
    private suspend fun cleanUp(
        sink: UpdateSink?,
        folder: Path?,
    ) {
        withContext(NonCancellable) {
            runCatching { sink?.close() }
            folder?.let { files.deleteAll(it) }
        }
    }
}

/**
 * [name] as a file name that stays inside a folder: any path part before the last `/` or `\` is dropped, anything but ASCII
 * letters, digits and `._-+~` becomes `_`, leading dots go, and it is cut to 100 characters keeping its extension. An empty
 * result is `anydm-update`.
 */
internal fun sanitizedInstallerName(name: String): String {
    val last = name.substringAfterLast('/').substringAfterLast('\\')
    val plain =
        last
            .map { if ((it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it in "._-+~") it else '_' }
            .joinToString("")
            .trimStart('.')
    if (plain.isEmpty()) return "anydm-update"
    if (plain.length <= MAX_NAME) return plain
    val extension =
        plain
            .substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= 10 }
            ?.let { ".$it" }
            .orEmpty()
    return plain.take(MAX_NAME - extension.length) + extension
}

private const val MAX_NAME = 100
