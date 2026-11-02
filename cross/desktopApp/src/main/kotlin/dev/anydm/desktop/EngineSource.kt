package dev.anydm.desktop

import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.Engine
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.InspectResult
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.net.AllowanceStatus
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.os.PairedBrowsers
import dev.anydm.engine.os.PairingOffer
import dev.anydm.engine.os.PairingState
import dev.anydm.engine.os.RevokeResult
import dev.anydm.engine.os.systemClipboard
import dev.anydm.engine.platform.Clipboard
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.DayTraffic
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.TransferSettings
import dev.anydm.engine.torrent.TorrentInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * A pairing code to show, and when it stops working. The code is a secret: [toString] hides it. [grouped] is what the screen
 * shows and the copy button copies; what became of it is told by [state]. (The engine's own offer can't be built outside the
 * engine, so tests stand in with this.)
 */
class PairingCode(
    val grouped: String,
    val expiresAt: Instant,
    /** The engine's own offer behind this code, so a cancel can name it; null for a stand-in. */
    internal val offer: PairingOffer? = null,
    private val current: suspend () -> PairingState,
) {
    suspend fun state(): PairingState = current()

    override fun toString(): String = "PairingCode(code=***, expiresAt=$expiresAt)"
}

/**
 * What the app reads from the engine and the commands it gives it; the tests stand in for it with a fake. Every command
 * throws IllegalStateException once the engine has stopped.
 */
interface EngineSource {
    val state: StateFlow<EngineState>
    val downloads: StateFlow<List<DownloadSnapshot>>
    val totalSpeed: StateFlow<SpeedWindow>
    val events: Flow<EngineEvent>

    /** The kinds of download this engine can take; a part that couldn't be set up is missing. */
    val available: Set<DownloadKind>

    /** The networks the machine has and which of them bonding uses. */
    val networks: StateFlow<BondingState>

    /** Reads the text on the system clipboard. */
    val clipboard: Clipboard

    /** The bytes free for a new download in [folder], null when they can't be measured. */
    fun freeSpace(folder: String): Long?

    suspend fun start()

    suspend fun stop()

    suspend fun pause(id: DownloadId): CommandResult

    suspend fun resume(id: DownloadId): CommandResult

    suspend fun cancel(id: DownloadId): CommandResult

    suspend fun remove(
        id: DownloadId,
        files: RemoveFiles,
    ): CommandResult

    suspend fun retry(
        id: DownloadId,
        allowDuplicate: Boolean,
    ): CommandResult

    suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ): CommandResult

    suspend fun pauseAll(): CommandResult

    suspend fun resumeAll(): CommandResult

    suspend fun torrentDetail(id: DownloadId): TorrentDetailResult

    /** Sets the torrent's file priorities in torrent order (0 skips a file, 4 is Normal). */
    suspend fun setTorrentFiles(
        id: DownloadId,
        priorities: List<Int>,
    ): CommandResult

    suspend fun probeLink(raw: String): LinkProbeResult

    suspend fun inspectTorrent(source: String): InspectResult

    suspend fun add(new: NewDownload): CommandResult

    suspend fun addTorrent(
        source: String,
        files: Set<String>?,
        sequential: Boolean,
        template: NewDownload,
        info: TorrentInfo?,
    ): AddTorrentResult

    suspend fun transferSettings(): TransferSettings

    /** Starts a pairing: a new code, replacing any earlier one. Reads and writes files: call it off the UI thread. */
    suspend fun startPairing(): PairingCode

    /**
     * Ends the code [startPairing] made, if it is still waiting: the screen that showed it is gone, so it must not stay good.
     * With [only] given, that code is the one ended: a newer code made since is left alone.
     * Waits for the pairing lock, which a pairing in progress holds while it writes a file: call it off the UI thread.
     */
    suspend fun cancelPairing(only: PairingCode? = null)

    /** The browsers paired, or [PairedBrowsers.Unavailable] when they can't be read. Reads a file: call it off the UI thread. */
    suspend fun pairedBrowsers(): PairedBrowsers

    /** Revokes the browser [id]. Writes a file: call it off the UI thread. */
    suspend fun revokeBrowser(id: String): RevokeResult

    /**
     * Held while the transfer settings are read, changed and applied back: [applyTransferSettings] takes the whole record, so
     * two such changes at once would each write what the other did not see. Use [updateTransfer].
     */
    val transferLock: Mutex

    suspend fun applyTransferSettings(next: TransferSettings): CommandResult

    suspend fun resetTransferSettings(): CommandResult

    suspend fun networkRules(): NetworkRules

    /** Sets the policy of [network], null for the default; a null [policy] removes the entry. */
    suspend fun setNetworkPolicy(
        network: String?,
        policy: NetworkPolicy?,
    ): CommandResult

    /** Every network's usage against its allowance in its own period, by network key; a network with no allowance is missing. */
    suspend fun allowanceUsage(): Map<String, AllowanceStatus>

    /** Bytes per day [from]..[to] (`YYYY-MM-DD`, inclusive), by network and kind, as the engine has saved them. */
    suspend fun trafficByDay(
        from: String,
        to: String,
    ): List<DayTraffic>

    /**
     * Starts the data usage of [network] over: what it used in its current allowance period is forgotten, so a pause its
     * allowance caused lifts. Invalid, with nothing changed, for a blank key or `*` and for a network with no allowance.
     */
    suspend fun resetAllowanceUsage(network: String): CommandResult

    suspend fun scheduleRules(): List<ScheduleRule>

    suspend fun addScheduleRule(new: NewScheduleRule): ScheduleRule

    suspend fun updateScheduleRule(rule: ScheduleRule): Boolean

    suspend fun removeScheduleRule(id: ScheduleRuleId): Boolean

    suspend fun setNetworkEnabled(
        key: String,
        enabled: Boolean,
    ): CommandResult
}

/** Changes some of the transfer settings: reads them, applies [change] to them and applies the result, with nothing in between. */
suspend fun EngineSource.updateTransfer(change: (TransferSettings) -> TransferSettings): CommandResult =
    transferLock.withLock { applyTransferSettings(change(transferSettings())) }

/**
 * [onTransfer] hears the transfer settings after each change made through the source, for the parts built outside the engine.
 * [clipboard] is where pasted links are read from. [onStart] starts what the desktop runs beside the engine once it is running; [onStop] stops it.
 */
fun Engine.asSource(
    store: Store,
    kinds: Set<DownloadKind>,
    onTransfer: (TransferSettings) -> Unit = {},
    onStop: () -> Unit = {},
    onStart: () -> Unit = {},
    clipboard: Clipboard = systemClipboard(),
): EngineSource =
    object : EngineSource {
        private val engine = this@asSource
        override val available = kinds
        override val state = engine.state
        override val downloads = engine.downloads
        override val totalSpeed = engine.totalSpeed
        override val events = engine.events
        override val networks = engine.networks
        override val clipboard = clipboard
        override val transferLock = Mutex()

        override fun freeSpace(folder: String): Long? =
            runCatching {
                freeDiskSpace(
                    java.nio.file.Path
                        .of(folder),
                )
            }.getOrNull()

        override suspend fun start() {
            engine.start()
            onStart()
        }

        // A failed engine's stop() does nothing, so its store is closed here.
        override suspend fun stop() {
            onStop()
            engine.stop()
            if (engine.state.value is EngineState.Failed) store.close()
        }

        override suspend fun pause(id: DownloadId) = engine.pause(id)

        override suspend fun resume(id: DownloadId) = engine.resume(id)

        override suspend fun cancel(id: DownloadId) = engine.cancel(id)

        override suspend fun remove(
            id: DownloadId,
            files: RemoveFiles,
        ) = engine.remove(id, files)

        override suspend fun retry(
            id: DownloadId,
            allowDuplicate: Boolean,
        ) = engine.retry(id, allowDuplicate)

        override suspend fun relink(
            id: DownloadId,
            mirrorId: MirrorId,
            url: String,
        ) = engine.relink(id, mirrorId, url)

        override suspend fun pauseAll() = engine.pauseAll()

        override suspend fun resumeAll() = engine.resumeAll()

        override suspend fun torrentDetail(id: DownloadId) = engine.torrentDetail(id)

        override suspend fun setTorrentFiles(
            id: DownloadId,
            priorities: List<Int>,
        ) = engine.setTorrentFiles(id, priorities)

        override suspend fun probeLink(raw: String) = engine.probeLink(raw)

        override suspend fun inspectTorrent(source: String) = engine.inspectTorrent(source)

        override suspend fun add(new: NewDownload) = engine.add(new)

        override suspend fun addTorrent(
            source: String,
            files: Set<String>?,
            sequential: Boolean,
            template: NewDownload,
            info: TorrentInfo?,
        ) = engine.addTorrent(source, files, sequential, template, info)

        override suspend fun transferSettings() = engine.transferSettings()

        override suspend fun startPairing(): PairingCode =
            engine.startPairing().let { offer -> PairingCode(offer.grouped, offer.expiresAt, offer) { offer.state() } }

        override suspend fun cancelPairing(only: PairingCode?) = engine.cancelPairing(only?.offer)

        override suspend fun pairedBrowsers() = engine.pairedBrowsers()

        override suspend fun revokeBrowser(id: String) = engine.revokeBrowser(id)

        override suspend fun applyTransferSettings(next: TransferSettings) = engine.applyTransferSettings(next).also { told() }

        override suspend fun resetTransferSettings() = engine.resetTransferSettings().also { told() }

        override suspend fun networkRules() = engine.networkRules()

        override suspend fun setNetworkPolicy(
            network: String?,
            policy: NetworkPolicy?,
        ) = engine.setNetworkPolicy(network, policy)

        override suspend fun allowanceUsage() = engine.allowanceUsage()

        override suspend fun trafficByDay(
            from: String,
            to: String,
        ) = engine.trafficByDay(from, to)

        override suspend fun resetAllowanceUsage(network: String) = engine.resetAllowanceUsage(network)

        override suspend fun scheduleRules() = engine.scheduleRules()

        override suspend fun addScheduleRule(new: NewScheduleRule) = engine.addScheduleRule(new)

        override suspend fun updateScheduleRule(rule: ScheduleRule) = engine.updateScheduleRule(rule)

        override suspend fun removeScheduleRule(id: ScheduleRuleId) = engine.removeScheduleRule(id)

        override suspend fun setNetworkEnabled(
            key: String,
            enabled: Boolean,
        ) = engine.setNetworkEnabled(key, enabled)

        private suspend fun told() = onTransfer(engine.transferSettings())
    }
