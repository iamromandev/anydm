package dev.anydm.desktop

import dev.anydm.engine.AddPlaylistResult
import dev.anydm.engine.AddTorrentResult
import dev.anydm.engine.DownloadSnapshot
import dev.anydm.engine.Engine
import dev.anydm.engine.EngineEvent
import dev.anydm.engine.EngineState
import dev.anydm.engine.InspectResult
import dev.anydm.engine.SpeedWindow
import dev.anydm.engine.TorrentDetailResult
import dev.anydm.engine.core.CategoryId
import dev.anydm.engine.core.DownloadId
import dev.anydm.engine.core.DownloadKind
import dev.anydm.engine.core.ItemSelection
import dev.anydm.engine.core.MirrorId
import dev.anydm.engine.core.ProfileId
import dev.anydm.engine.core.ProxyId
import dev.anydm.engine.core.ScheduleRuleId
import dev.anydm.engine.files.Category
import dev.anydm.engine.files.CategoryResult
import dev.anydm.engine.files.NewCategory
import dev.anydm.engine.files.NewProfile
import dev.anydm.engine.files.Profile
import dev.anydm.engine.files.ProfileResult
import dev.anydm.engine.ftp.SshKeyLogin
import dev.anydm.engine.net.BondingState
import dev.anydm.engine.net.NetworkPolicy
import dev.anydm.engine.net.NetworkRules
import dev.anydm.engine.net.NewProxy
import dev.anydm.engine.net.NewScheduleRule
import dev.anydm.engine.net.PasswordChange
import dev.anydm.engine.net.ProxyChoice
import dev.anydm.engine.net.ProxyCommandResult
import dev.anydm.engine.net.ProxyEntry
import dev.anydm.engine.net.ScheduleRule
import dev.anydm.engine.os.HotkeyStatus
import dev.anydm.engine.os.LinkProbeResult
import dev.anydm.engine.os.OsSettings
import dev.anydm.engine.queue.CommandResult
import dev.anydm.engine.queue.RemoveFiles
import dev.anydm.engine.store.DayTraffic
import dev.anydm.engine.store.HistoryEntry
import dev.anydm.engine.store.ImportResult
import dev.anydm.engine.store.NewDownload
import dev.anydm.engine.store.ProxyRemoval
import dev.anydm.engine.store.RuntimeSettings
import dev.anydm.engine.store.Store
import dev.anydm.engine.store.TransferSettings
import dev.anydm.engine.tools.Tool
import dev.anydm.engine.tools.ToolStatus
import dev.anydm.engine.tools.ToolUpdate
import dev.anydm.engine.tools.YtDlpChannel
import dev.anydm.engine.torrent.TorrentInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the app reads from the engine and the commands it gives it; the tests stand in for it with a fake. Every command
 * throws IllegalStateException once the engine has stopped.
 */
interface EngineSource {
    val state: StateFlow<EngineState>
    val downloads: StateFlow<List<DownloadSnapshot>>
    val totalSpeed: StateFlow<SpeedWindow>
    val events: Flow<EngineEvent>

    suspend fun categories(): List<Category>

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

    suspend fun reprioritize(
        id: DownloadId,
        priority: Int,
    ): CommandResult

    suspend fun relink(
        id: DownloadId,
        mirrorId: MirrorId,
        url: String,
    ): CommandResult

    suspend fun stopSeeding(id: DownloadId): CommandResult

    suspend fun pauseAll(): CommandResult

    suspend fun resumeAll(): CommandResult

    suspend fun torrentDetail(id: DownloadId): TorrentDetailResult

    /** The kinds of download this engine can take; a part that couldn't be set up is missing. */
    val available: Set<DownloadKind>

    suspend fun profiles(): List<Profile>

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

    suspend fun addPlaylist(
        url: String,
        selection: ItemSelection,
        template: NewDownload,
    ): AddPlaylistResult

    /** Keeps an SFTP key login for [url]'s server; false when it only reached memory. */
    suspend fun saveKeyLogin(
        url: String,
        login: SshKeyLogin,
    ): Boolean

    suspend fun transferSettings(): TransferSettings

    suspend fun applyTransferSettings(next: TransferSettings): CommandResult

    suspend fun resetTransferSettings(): CommandResult

    suspend fun addCategory(new: NewCategory): CategoryResult

    suspend fun updateCategory(category: Category): CategoryResult

    suspend fun deleteCategory(id: CategoryId)

    suspend fun addProfile(new: NewProfile): ProfileResult

    suspend fun updateProfile(profile: Profile): ProfileResult

    suspend fun deleteProfile(id: ProfileId)

    /** The profile a download that names none takes; null for none. */
    suspend fun defaultProfile(): ProfileId?

    /** False when there is no such profile. */
    suspend fun setDefaultProfile(id: ProfileId?): Boolean

    suspend fun history(limit: Int): List<HistoryEntry>

    suspend fun clearHistory()

    /** Days [from]..[to] as `YYYY-MM-DD`, inclusive. */
    suspend fun trafficByDay(
        from: String,
        to: String,
    ): List<DayTraffic>

    suspend fun exportSettings(): String

    suspend fun importSettings(text: String): ImportResult

    suspend fun proxies(): List<ProxyEntry>

    suspend fun addProxy(
        new: NewProxy,
        password: String?,
    ): ProxyCommandResult

    suspend fun updateProxy(
        proxy: ProxyEntry,
        password: PasswordChange,
    ): ProxyCommandResult

    suspend fun removeProxy(id: ProxyId): ProxyRemoval

    suspend fun proxyChoice(): ProxyChoice

    suspend fun setProxyChoice(choice: ProxyChoice): CommandResult

    suspend fun setDownloadProxy(
        id: DownloadId,
        choice: ProxyChoice,
    ): CommandResult

    suspend fun networkRules(): NetworkRules

    /** Sets the policy of [network], null for the default; a null [policy] removes the entry. */
    suspend fun setNetworkPolicy(
        network: String?,
        policy: NetworkPolicy?,
    ): CommandResult

    suspend fun scheduleRules(): List<ScheduleRule>

    suspend fun addScheduleRule(new: NewScheduleRule): ScheduleRule

    suspend fun updateScheduleRule(rule: ScheduleRule): Boolean

    suspend fun removeScheduleRule(id: ScheduleRuleId): Boolean

    /** The networks the machine has and which of them bonding uses. */
    val networks: StateFlow<BondingState>

    suspend fun setNetworkEnabled(
        key: String,
        enabled: Boolean,
    ): CommandResult

    /** The clipboard watch and the global hotkey, as the engine has them now. */
    val osSettings: StateFlow<OsSettings>

    /** Whether the global hotkey works; off until the OS integration runs it. */
    val hotkeyStatus: StateFlow<HotkeyStatus>

    suspend fun setClipboardWatch(on: Boolean): CommandResult

    suspend fun setGlobalHotkey(on: Boolean): CommandResult

    suspend fun runtimeSettings(): RuntimeSettings

    suspend fun setNotifications(on: Boolean): CommandResult

    suspend fun setBridgeOrigins(origins: Set<String>): CommandResult

    suspend fun setDebugLogging(on: Boolean): CommandResult

    suspend fun setYtDlpChannel(channel: YtDlpChannel): CommandResult

    suspend fun setSystemTools(on: Boolean): CommandResult

    suspend fun setToolFolder(
        tool: Tool,
        folder: String?,
    ): CommandResult

    suspend fun toolStatus(): List<ToolStatus>

    suspend fun updateTool(tool: Tool): ToolUpdate

    /** Writes a diagnostics bundle (logs, versions, settings with secrets removed) to [target]; null when it worked, else why not. */
    suspend fun exportDiagnostics(target: String): String?
}

/**
 * [onTransfer] hears the transfer settings after each change made through the source, for the parts built outside the engine.
 * [hotkey] is the hotkey service's status, and [diagnostics] writes the bundle; without them the hotkey reads Off and the export says it can't.
 */
fun Engine.asSource(
    store: Store,
    kinds: Set<DownloadKind>,
    onTransfer: (TransferSettings) -> Unit = {},
    hotkey: StateFlow<HotkeyStatus> = MutableStateFlow(HotkeyStatus.Off),
    diagnostics: suspend (String) -> String? = { "diagnostics aren't set up in this build" },
): EngineSource =
    object : EngineSource {
        private val engine = this@asSource
        override val available = kinds
        override val state = engine.state
        override val downloads = engine.downloads
        override val totalSpeed = engine.totalSpeed
        override val events = engine.events

        override suspend fun categories() = engine.categories()

        override suspend fun start() = engine.start()

        // A failed engine's stop() does nothing, so its store is closed here.
        override suspend fun stop() {
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

        override suspend fun reprioritize(
            id: DownloadId,
            priority: Int,
        ) = engine.reprioritize(id, priority)

        override suspend fun relink(
            id: DownloadId,
            mirrorId: MirrorId,
            url: String,
        ) = engine.relink(id, mirrorId, url)

        override suspend fun stopSeeding(id: DownloadId) = engine.stopSeeding(id)

        override suspend fun pauseAll() = engine.pauseAll()

        override suspend fun resumeAll() = engine.resumeAll()

        override suspend fun torrentDetail(id: DownloadId) = engine.torrentDetail(id)

        override suspend fun profiles() = engine.profiles()

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

        override suspend fun addPlaylist(
            url: String,
            selection: ItemSelection,
            template: NewDownload,
        ) = engine.addPlaylist(url, selection, template)

        override suspend fun saveKeyLogin(
            url: String,
            login: SshKeyLogin,
        ) = engine.saveKeyLogin(url, login)

        override suspend fun transferSettings() = engine.transferSettings()

        override suspend fun applyTransferSettings(next: TransferSettings) = engine.applyTransferSettings(next).also { told() }

        override suspend fun resetTransferSettings() = engine.resetTransferSettings().also { told() }

        override suspend fun addCategory(new: NewCategory) = engine.addCategory(new)

        override suspend fun updateCategory(category: Category) = engine.updateCategory(category)

        override suspend fun deleteCategory(id: CategoryId) = engine.deleteCategory(id)

        override suspend fun addProfile(new: NewProfile) = engine.addProfile(new)

        override suspend fun updateProfile(profile: Profile) = engine.updateProfile(profile)

        override suspend fun deleteProfile(id: ProfileId) {
            engine.deleteProfile(id)
        }

        override suspend fun defaultProfile() = store.settings.load().defaultProfileId

        override suspend fun setDefaultProfile(id: ProfileId?) = engine.setDefaultProfile(id)

        override suspend fun history(limit: Int) = engine.history(limit)

        override suspend fun clearHistory() = engine.clearHistory()

        override suspend fun trafficByDay(
            from: String,
            to: String,
        ) = engine.trafficByDay(from, to)

        override suspend fun exportSettings() = engine.exportSettings()

        // Part of an import may be in even when it throws.
        override suspend fun importSettings(text: String) =
            try {
                engine.importSettings(text)
            } finally {
                told()
            }

        override val networks = engine.networks
        override val osSettings = engine.osSettings
        override val hotkeyStatus = hotkey

        override suspend fun proxies() = engine.proxies()

        override suspend fun addProxy(
            new: NewProxy,
            password: String?,
        ) = engine.addProxy(new, password)

        override suspend fun updateProxy(
            proxy: ProxyEntry,
            password: PasswordChange,
        ) = engine.updateProxy(proxy, password)

        override suspend fun removeProxy(id: ProxyId) = engine.removeProxy(id)

        override suspend fun proxyChoice() = engine.proxyChoice()

        override suspend fun setProxyChoice(choice: ProxyChoice) = engine.setProxyChoice(choice)

        override suspend fun setDownloadProxy(
            id: DownloadId,
            choice: ProxyChoice,
        ) = engine.setDownloadProxy(id, choice)

        override suspend fun networkRules() = engine.networkRules()

        override suspend fun setNetworkPolicy(
            network: String?,
            policy: NetworkPolicy?,
        ) = engine.setNetworkPolicy(network, policy)

        override suspend fun scheduleRules() = engine.scheduleRules()

        override suspend fun addScheduleRule(new: NewScheduleRule) = engine.addScheduleRule(new)

        override suspend fun updateScheduleRule(rule: ScheduleRule) = engine.updateScheduleRule(rule)

        override suspend fun removeScheduleRule(id: ScheduleRuleId) = engine.removeScheduleRule(id)

        override suspend fun setNetworkEnabled(
            key: String,
            enabled: Boolean,
        ) = engine.setNetworkEnabled(key, enabled)

        override suspend fun setClipboardWatch(on: Boolean) = engine.setClipboardWatch(on)

        override suspend fun setGlobalHotkey(on: Boolean) = engine.setGlobalHotkey(on)

        override suspend fun runtimeSettings() = engine.runtimeSettings()

        override suspend fun setNotifications(on: Boolean) = engine.setNotifications(on)

        override suspend fun setBridgeOrigins(origins: Set<String>) = engine.setBridgeOrigins(origins)

        override suspend fun setDebugLogging(on: Boolean) = engine.setDebugLogging(on)

        override suspend fun setYtDlpChannel(channel: YtDlpChannel) = engine.setYtDlpChannel(channel)

        override suspend fun setSystemTools(on: Boolean) = engine.setSystemTools(on)

        override suspend fun setToolFolder(
            tool: Tool,
            folder: String?,
        ) = engine.setToolFolder(tool, folder)

        override suspend fun toolStatus() = engine.toolStatus()

        override suspend fun updateTool(tool: Tool) = engine.updateTool(tool)

        override suspend fun exportDiagnostics(target: String) = diagnostics(target)

        private suspend fun told() = onTransfer(engine.transferSettings())
    }
